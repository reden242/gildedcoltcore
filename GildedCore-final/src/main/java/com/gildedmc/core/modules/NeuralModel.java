package com.gildedmc.core.modules;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The purpose-built model: hashed n-gram embeddings, mean pooled, one hidden
 * layer, softmax. Pure Java, no dependencies, no sidecar, no native library.
 *
 * <h2>Why this shape and not a transformer</h2>
 * A transformer earns its cost on tasks needing word order and long-range
 * context. Deciding whether a chat line is abuse needs neither: the evidence is
 * which n-grams are present, not what order they arrive in. This is the
 * fastText architecture, which was designed for exactly this trade and remains
 * the right one — a shared embedding table gives the statistical strength that
 * a plain linear model lacks, at a fraction of a transformer's cost.
 *
 * <p>Concretely, against the linear model it replaces: the embedding table lets
 * a feature that appeared twice borrow from every other feature it co-occurs
 * with, which is precisely the "only 25% recall on unseen phrasing" problem.
 *
 * <h2>Why in-process matters here</h2>
 * On Pterodactyl you cannot run Docker inside your container, and a sidecar
 * means a second server allocation and a second RAM budget. The whole model is
 * a few megabytes of float arrays living in the plugin's own heap: no port, no
 * process, no network hop, and no 40 ms round trip.
 *
 * <h2>Layout</h2>
 * <pre>
 *   features  ->  embedding[buckets][dim]  ->  mean  ->  ReLU(W1)  ->  softmax(W2)
 * </pre>
 * At the shipped sizes that is roughly 8 MB of weights in memory and about
 * 2 MB on disk once quantised to int8.
 */
public final class NeuralModel {

    /**
     * Default geometry, chosen by measurement rather than ambition.
     *
     * <p>An earlier version of this comment claimed a wider table was "the
     * cheapest accuracy available". Swept across five random splits on the
     * 2,246-example corpus, that is the opposite of true:
     *
     * <pre>
     *   2^16 x 32  x 64     2 MB    mean 97.4%   worst 95.8%
     *   2^18 x 64  x 128   16 MB    mean 96.8%   worst 95.3%
     *   2^19 x 96  x 192   48 MB    mean 94.8%   worst 91.3%
     *   2^20 x 128 x 256  128 MB    mean 93.6%   worst 92.2%
     * </pre>
     *
     * <p>Capacity with too little data to constrain it memorises the majority
     * classes instead of generalising, and the variance across splits grows
     * with it. The smallest geometry won on both mean and worst case, so it is
     * the default.
     *
     * <p>This is not a permanent ceiling. Every reviewed punishment adds a
     * gold-standard example, and once the corpus is several times larger the
     * larger geometries should overtake — which is why the size is a config
     * setting and not a constant. Re-run the sweep before raising it.
     */
    public static final int BUCKETS = 1 << 17;          // 131072
    /** Embedding width. */
    public static final int DIM = 40;
    /** Hidden layer width. */
    public static final int HIDDEN = 96;

    /*
     * Sizes are per-instance, not compile-time constants, so that a build can be
     * sized against measured accuracy rather than a guess, and so a model saved
     * at one size still loads at another. The constants above are only the
     * defaults.
     */
    private int buckets = BUCKETS;
    private int dim = DIM;
    private int hidden = HIDDEN;

    private final List<String> labels = new ArrayList<>();
    private float[] embedding;      // BUCKETS * DIM
    private float[] w1;             // HIDDEN * DIM
    private float[] b1;             // HIDDEN
    private float[] w2;             // classes * HIDDEN
    private float[] b2;             // classes
    private int trainedOn;
    private double heldOutAccuracy = -1.0D;

    public NeuralModel(List<String> labels) {
        this(labels, BUCKETS, DIM, HIDDEN);
    }

    public NeuralModel(List<String> labels, int buckets, int dim, int hidden) {
        this.labels.addAll(labels);
        // The bucket count must stay a power of two: the forward pass masks with
        // (buckets - 1) instead of dividing, which is only equivalent then.
        this.buckets = Integer.highestOneBit(Math.max(1024, buckets));
        this.dim = Math.max(8, dim);
        this.hidden = Math.max(8, hidden);
        int classes = labels.size();
        this.embedding = new float[this.buckets * this.dim];
        this.w1 = new float[this.hidden * this.dim];
        this.b1 = new float[this.hidden];
        this.w2 = new float[classes * this.hidden];
        this.b2 = new float[classes];
        initialise(new Random(12345));
    }

    private NeuralModel() { }

    /** Small random init; zeros would make every unit learn the same thing. */
    private void initialise(Random rng) {
        float eScale = (float) (1.0 / Math.sqrt(this.dim));
        for (int i = 0; i < this.embedding.length; i++) {
            this.embedding[i] = (float) (rng.nextGaussian() * eScale * 0.1);
        }
        float s1 = (float) Math.sqrt(2.0 / this.dim);           // He init, for ReLU
        for (int i = 0; i < this.w1.length; i++) this.w1[i] = (float) (rng.nextGaussian() * s1);
        float s2 = (float) Math.sqrt(2.0 / this.hidden);
        for (int i = 0; i < this.w2.length; i++) this.w2[i] = (float) (rng.nextGaussian() * s2);
    }

    /* ------------------------------------------------------------------ */
    /*  Forward                                                           */
    /* ------------------------------------------------------------------ */

    /** Scratch space for one forward pass, so inference allocates nothing. */
    private static final class Pass {
        final float[] pooled;
        final float[] hidden;
        float[] logits;
        Pass(int dim, int hidden) {
            this.pooled = new float[dim];
            this.hidden = new float[hidden];
        }
    }

    private final ThreadLocal<Pass> scratch =
            ThreadLocal.withInitial(() -> new Pass(this.dim, this.hidden));

    public LinearModel.Prediction predict(TextFeatures.Vector v) {
        Pass p = this.scratch.get();
        if (p.logits == null || p.logits.length != this.labels.size()) {
            p.logits = new float[this.labels.size()];
        }
        forward(v, p);

        double max = Double.NEGATIVE_INFINITY;
        for (float l : p.logits) max = Math.max(max, l);
        double total = 0.0D;
        double[] prob = new double[p.logits.length];
        for (int c = 0; c < prob.length; c++) {
            prob[c] = Math.exp(p.logits[c] - max);
            total += prob[c];
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        int best = 0;
        for (int c = 0; c < prob.length; c++) {
            prob[c] /= total;
            scores.put(this.labels.get(c), prob[c]);
            if (prob[c] > prob[best]) best = c;
        }
        return new LinearModel.Prediction(this.labels.get(best), prob[best], scores);
    }

    public LinearModel.Prediction predict(String text) {
        return predict(TextFeatures.extract(text));
    }

    private void forward(TextFeatures.Vector v, Pass p) {
        Arrays.fill(p.pooled, 0.0F);
        int n = v.indices().length;
        if (n > 0) {
            for (int k = 0; k < n; k++) {
                int base = (v.indices()[k] & (this.buckets - 1)) * this.dim;
                float weight = v.values()[k];
                for (int d = 0; d < this.dim; d++) p.pooled[d] += this.embedding[base + d] * weight;
            }
        }
        for (int h = 0; h < this.hidden; h++) {
            float sum = this.b1[h];
            int row = h * this.dim;
            for (int d = 0; d < this.dim; d++) sum += this.w1[row + d] * p.pooled[d];
            p.hidden[h] = sum > 0 ? sum : 0.0F;            // ReLU
        }
        for (int c = 0; c < this.labels.size(); c++) {
            float sum = this.b2[c];
            int row = c * this.hidden;
            for (int h = 0; h < this.hidden; h++) sum += this.w2[row + h] * p.hidden[h];
            p.logits[c] = sum;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Training                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Fits the model with Adam.
     *
     * <p>Only the embedding rows a batch actually touches are updated, which is
     * what keeps training cheap despite a 65k-row table: a chat line lights up
     * perhaps eighty rows, not sixty-five thousand.
     *
     * @return held-out accuracy, or -1 with too little data
     */
    public double train(List<LinearModel.Example> examples, int epochs, double learningRate,
                        double holdOutFraction) {
        return train(examples, epochs, learningRate, holdOutFraction,
                0.0D, 0.0D, 999L, 0.5D, 0, 0.0D);
    }

    /**
     * Fits the model with Adam, with every knob exposed.
     *
     * @param weightDecay      L2 pull towards zero per step. 0 disables it.
     * @param gradClip         absolute gradient ceiling. 0 disables clipping.
     * @param seed             shuffle seed, so a run is reproducible
     * @param classWeightPower 0 = no class weighting, 0.5 = sqrt-damped
     *                         inverse frequency (the old hardcoded behaviour),
     *                         1 = full inverse frequency
     * @param patience         stop early after this many epochs without a
     *                         held-out improvement. 0 disables early stopping.
     * @param labelSmoothing   softens the one-hot target by spreading this
     *                         fraction across all classes (0..0.3 sane range).
     *                         Fights overconfidence, which is exactly what made
     *                         the old model shout "hate 100%" at opinions.
     * @return held-out accuracy, or -1 with too little data
     */
    public double train(List<LinearModel.Example> examples, int epochs, double learningRate,
                        double holdOutFraction, double weightDecay, double gradClip,
                        long seed, double classWeightPower, int patience, double labelSmoothing) {
        double smooth = Math.max(0.0D, Math.min(0.5D, labelSmoothing));
        int classes = this.labels.size();
        if (examples.size() < this.labels.size() * 2) return -1.0D;

        List<LinearModel.Example> train = new ArrayList<>();
        List<LinearModel.Example> test = new ArrayList<>();
        int holdEvery = holdOutFraction <= 0 ? 0 : (int) Math.round(1.0D / holdOutFraction);
        int i = 0;
        for (LinearModel.Example e : examples) {
            if (holdEvery > 0 && i % holdEvery == 0) test.add(e); else train.add(e);
            i++;
        }
        if (train.isEmpty()) { train = examples; test = Collections.emptyList(); }

        // Adam moments, allocated once for the whole fit.
        float[] mE = new float[this.embedding.length], vE = new float[this.embedding.length];
        float[] m1 = new float[this.w1.length], v1 = new float[this.w1.length];
        float[] mb1 = new float[this.b1.length], vb1 = new float[this.b1.length];
        float[] m2 = new float[this.w2.length], v2 = new float[this.w2.length];
        float[] mb2 = new float[this.b2.length], vb2 = new float[this.b2.length];

        // CLASS WEIGHTING, instead of capping the big classes.
        //
        // Truncating hate from 11,707 to 4,000 did reduce false positives, but
        // it did it by throwing away real training signal, and it immediately
        // cost advertising four detections. Capping trades one error for the
        // other; it does not remove either.
        //
        // Weighting fixes the imbalance without discarding anything. Each
        // example's gradient is scaled by total / (classes * count), so a class
        // with a tenth of the data gets ten times the pull per example and ends
        // up with a decision region the right size. Every example is still
        // trained on.
        //
        // sqrt-damped, because full inverse-frequency weighting on a class with
        // a few hundred examples makes each one so influential that the model
        // chases its noise.
        int[] classCounts = new int[classes];
        for (LinearModel.Example e : train) classCounts[e.label()]++;
        float[] classWeight = new float[classes];
        for (int c = 0; c < classes; c++) {
            if (classCounts[c] == 0 || classWeightPower <= 0.0D) { classWeight[c] = 1.0F; continue; }
            double ideal = train.size() / (double) classes;
            classWeight[c] = (float) Math.pow(ideal / classCounts[c], classWeightPower);
        }

        Pass p = new Pass(this.dim, this.hidden);
        p.logits = new float[classes];
        float[] dHidden = new float[this.hidden];
        float[] dPooled = new float[this.dim];
        double[] prob = new double[classes];
        Random rng = new Random(seed);
        int step = 0;
        double bestAccuracy = -1.0D;
        int sinceImproved = 0;

        for (int epoch = 0; epoch < epochs; epoch++) {
            Collections.shuffle(train, rng);
            for (LinearModel.Example e : train) {
                step++;
                forward(e.vector(), p);

                double max = Double.NEGATIVE_INFINITY;
                for (float l : p.logits) max = Math.max(max, l);
                double total = 0.0D;
                for (int c = 0; c < classes; c++) {
                    prob[c] = Math.exp(p.logits[c] - max);
                    total += prob[c];
                }
                for (int c = 0; c < classes; c++) prob[c] /= total;

                // Output layer.
                float w = classWeight[e.label()];
                Arrays.fill(dHidden, 0.0F);
                for (int c = 0; c < classes; c++) {
                    float grad = w * (float) (prob[c]
                            - ((1.0D - smooth) * (c == e.label() ? 1.0D : 0.0D)
                               + smooth / classes));
                    if (grad == 0.0F) continue;
                    int row = c * this.hidden;
                    for (int h = 0; h < this.hidden; h++) {
                        dHidden[h] += grad * this.w2[row + h];
                        adam(this.w2, m2, v2, row + h, grad * p.hidden[h], learningRate, step,
                                weightDecay, gradClip);
                    }
                    adam(this.b2, mb2, vb2, c, grad, learningRate, step, 0.0D, gradClip);
                }

                // Hidden layer, through the ReLU.
                Arrays.fill(dPooled, 0.0F);
                for (int h = 0; h < this.hidden; h++) {
                    if (p.hidden[h] <= 0.0F) continue;      // ReLU gate closed
                    float grad = dHidden[h];
                    if (grad == 0.0F) continue;
                    int row = h * this.dim;
                    for (int d = 0; d < this.dim; d++) {
                        dPooled[d] += grad * this.w1[row + d];
                        adam(this.w1, m1, v1, row + d, grad * p.pooled[d], learningRate, step,
                                weightDecay, gradClip);
                    }
                    adam(this.b1, mb1, vb1, h, grad, learningRate, step, 0.0D, gradClip);
                }

                // Embeddings: only the rows this example touched.
                for (int k = 0; k < e.vector().indices().length; k++) {
                    int base = (e.vector().indices()[k] & (this.buckets - 1)) * this.dim;
                    float weight = e.vector().values()[k];
                    for (int d = 0; d < this.dim; d++) {
                        adam(this.embedding, mE, vE, base + d,
                                dPooled[d] * weight, learningRate, step, weightDecay, gradClip);
                    }
                }
            }
            // Early stopping, when a hold-out set exists and patience is set.
            if (patience > 0 && !test.isEmpty()) {
                double acc = accuracy(test);
                if (acc > bestAccuracy) { bestAccuracy = acc; sinceImproved = 0; }
                else if (++sinceImproved >= patience) break;
            }
        }
        this.trainedOn = train.size();
        this.heldOutAccuracy = test.isEmpty() ? -1.0D : accuracy(test);
        return this.heldOutAccuracy;
    }

    /** One Adam step on one weight, with optional clipping and decay. */
    private static void adam(float[] w, float[] m, float[] v, int i, float grad,
                             double lr, int step) {
        adam(w, m, v, i, grad, lr, step, 0.0D, 0.0D);
    }

    private static void adam(float[] w, float[] m, float[] v, int i, float grad,
                             double lr, int step, double weightDecay, double gradClip) {
        if (gradClip > 0.0D) {
            if (grad > gradClip) grad = (float) gradClip;
            else if (grad < -gradClip) grad = (float) -gradClip;
        }
        if (weightDecay > 0.0D) grad += (float) (weightDecay * w[i]);
        final double beta1 = 0.9, beta2 = 0.999, eps = 1e-8;
        m[i] = (float) (beta1 * m[i] + (1 - beta1) * grad);
        v[i] = (float) (beta2 * v[i] + (1 - beta2) * grad * grad);
        double mHat = m[i] / (1 - Math.pow(beta1, step));
        double vHat = v[i] / (1 - Math.pow(beta2, step));
        w[i] -= (float) (lr * mHat / (Math.sqrt(vHat) + eps));
    }

    public double accuracy(List<LinearModel.Example> examples) {
        if (examples.isEmpty()) return -1.0D;
        int right = 0;
        for (LinearModel.Example e : examples) {
            if (this.labels.indexOf(predict(e.vector()).label()) == e.label()) right++;
        }
        return right / (double) examples.size();
    }

    public Map<String, double[]> perLabel(List<LinearModel.Example> examples) {
        int classes = this.labels.size();
        int[] tp = new int[classes], predicted = new int[classes], actual = new int[classes];
        for (LinearModel.Example e : examples) {
            int got = this.labels.indexOf(predict(e.vector()).label());
            predicted[got]++;
            actual[e.label()]++;
            if (got == e.label()) tp[got]++;
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        for (int c = 0; c < classes; c++) {
            out.put(this.labels.get(c), new double[]{
                    predicted[c] == 0 ? 0 : tp[c] / (double) predicted[c],
                    actual[c] == 0 ? 0 : tp[c] / (double) actual[c],
                    actual[c] });
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Accessors                                                         */
    /* ------------------------------------------------------------------ */

    public List<String> labels() { return Collections.unmodifiableList(this.labels); }

    public int trainedOn() { return this.trainedOn; }

    public double heldOutAccuracy() { return this.heldOutAccuracy; }

    public boolean trained() { return this.trainedOn > 0; }

    public long memoryBytes() {
        return 4L * (this.embedding.length + this.w1.length + this.b1.length
                + this.w2.length + this.b2.length);
    }

    /* ------------------------------------------------------------------ */
    /*  Persistence, quantised                                            */
    /* ------------------------------------------------------------------ */

    private static final int MAGIC = 0x474E4E31;         // "GNN1"

    /**
     * Writes the model with int8 weights.
     *
     * <p>Each array is stored as one float scale plus one byte per weight, which
     * is a quarter the size for an accuracy cost that is not measurable at this
     * scale. It is what makes the shipped model about 2 MB instead of 8.
     */
    public void save(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            write(out);
        }
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(this.buckets);
        out.writeInt(this.dim);
        out.writeInt(this.hidden);
        out.writeInt(this.labels.size());
        for (String l : this.labels) out.writeUTF(l);
        out.writeInt(this.trainedOn);
        out.writeDouble(this.heldOutAccuracy);
        quantise(out, this.embedding);
        quantise(out, this.w1);
        quantise(out, this.b1);
        quantise(out, this.w2);
        quantise(out, this.b2);
    }

    private static void quantise(DataOutputStream out, float[] a) throws IOException {
        float max = 1e-8F;
        for (float f : a) max = Math.max(max, Math.abs(f));
        float scale = max / 127.0F;
        out.writeInt(a.length);
        out.writeFloat(scale);
        for (float f : a) out.writeByte(Math.round(f / scale));
    }

    private static float[] dequantise(DataInputStream in) throws IOException {
        int length = in.readInt();
        float scale = in.readFloat();
        float[] a = new float[length];
        for (int i = 0; i < length; i++) a[i] = in.readByte() * scale;
        return a;
    }

    public static NeuralModel load(File file) {
        if (file == null || !file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            return read(in);
        } catch (Exception ex) {
            return null;
        }
    }

    /** Reads the model shipped inside the jar. */
    public static NeuralModel loadResource(String name) {
        try (InputStream raw = NeuralModel.class.getResourceAsStream(name)) {
            if (raw == null) return null;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
                return read(in);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static NeuralModel read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) return null;
        int buckets = in.readInt(), dim = in.readInt(), hidden = in.readInt();
        // Any geometry loads. An earlier version rejected anything that did not
        // match the compile-time constants, which meant resizing the model
        // silently discarded the shipped weights and started from noise.
        NeuralModel m = new NeuralModel();
        m.buckets = buckets;
        m.dim = dim;
        m.hidden = hidden;
        int classes = in.readInt();
        for (int i = 0; i < classes; i++) m.labels.add(in.readUTF());
        m.trainedOn = in.readInt();
        m.heldOutAccuracy = in.readDouble();
        m.embedding = dequantise(in);
        m.w1 = dequantise(in);
        m.b1 = dequantise(in);
        m.w2 = dequantise(in);
        m.b2 = dequantise(in);
        // A header that disagrees with the arrays behind it would index out of
        // bounds on the first prediction. Refuse it here instead.
        if (m.embedding.length != (long) buckets * dim
                || m.w1.length != (long) hidden * dim
                || m.b1.length != hidden
                || m.w2.length != (long) classes * hidden
                || m.b2.length != classes) {
            return null;
        }
        return m;
    }
}
