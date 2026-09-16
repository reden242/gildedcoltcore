package com.gildedmc.core.modules;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Multinomial logistic regression. Pure Java, CPU only, no dependencies.
 *
 * <h2>Why this local model</h2>
 * Classifying a chat line is not a reasoning problem, it is a pattern problem,
 * and pattern problems are what linear models over character n-grams have
 * always been good at. The practical difference is four orders of magnitude:
 * this scores a message in microseconds inside the server process.
 *
 * <p>It is also auditable in a way a model is not: every decision is a dot
 * product over features you can print, there is no prompt to inject, and it
 * cannot be talked out of its answer.
 *
 * <h2>Training</h2>
 * Plain SGD on cross-entropy with L2, a few epochs over the stored examples.
 * Weights are a flat {@code float[classes * DIMENSION]}, which for four classes
 * at 2^17 columns is 2 MB — small enough to keep resident and to write to disk
 * in one go.
 */
public final class LinearModel {

    private final List<String> labels = new ArrayList<>();
    private float[] weights;
    private float[] bias;
    private int dimension;
    private int trainedOn;
    private double heldOutAccuracy = -1.0D;

    public LinearModel(List<String> labels, int dimension) {
        this.labels.addAll(labels);
        this.dimension = dimension;
        this.weights = new float[labels.size() * dimension];
        this.bias = new float[labels.size()];
    }

    private LinearModel() { }

    /* ------------------------------------------------------------------ */
    /*  Inference                                                         */
    /* ------------------------------------------------------------------ */

    /** One classification: the winning label and the full distribution. */
    public record Prediction(String label, double confidence, Map<String, Double> scores) { }

    /**
     * Scores one message. This is the hot path — it runs on every chat line, so
     * it allocates only the small result arrays and touches one weight row per
     * class per feature.
     */
    public Prediction predict(TextFeatures.Vector v) {
        int classes = this.labels.size();
        double[] score = new double[classes];
        for (int c = 0; c < classes; c++) {
            int base = c * this.dimension;
            double sum = this.bias[c];
            for (int i = 0; i < v.indices().length; i++) {
                sum += this.weights[base + v.indices()[i]] * v.values()[i];
            }
            score[c] = sum;
        }
        // Softmax, shifted for numerical stability.
        double max = Double.NEGATIVE_INFINITY;
        for (double s : score) max = Math.max(max, s);
        double total = 0.0D;
        for (int c = 0; c < classes; c++) {
            score[c] = Math.exp(score[c] - max);
            total += score[c];
        }
        Map<String, Double> out = new LinkedHashMap<>();
        int best = 0;
        for (int c = 0; c < classes; c++) {
            score[c] /= total;
            out.put(this.labels.get(c), score[c]);
            if (score[c] > score[best]) best = c;
        }
        return new Prediction(this.labels.get(best), score[best], out);
    }

    public Prediction predict(String text) {
        return predict(TextFeatures.extract(text));
    }

    /* ------------------------------------------------------------------ */
    /*  Training                                                          */
    /* ------------------------------------------------------------------ */

    /** One labelled example, already vectorised. */
    public record Example(TextFeatures.Vector vector, int label) { }

    /**
     * Fits the model, holding back a slice to measure on.
     *
     * <p>The held-out split is deterministic — it comes from the example's own
     * hash, not from a shuffle — so retraining on the same data reports the
     * same accuracy rather than a number that wanders every time you look.
     *
     * @return held-out accuracy, or -1 when there was too little data
     */
    public double train(List<Example> examples, int epochs, double learningRate,
                        double l2, double holdOutFraction) {
        if (examples.size() < this.labels.size() * 2) return -1.0D;

        List<Example> train = new ArrayList<>(examples.size());
        List<Example> test = new ArrayList<>();
        int holdEvery = holdOutFraction <= 0 ? 0 : (int) Math.round(1.0D / holdOutFraction);
        int i = 0;
        for (Example e : examples) {
            if (holdEvery > 0 && i % holdEvery == 0) test.add(e); else train.add(e);
            i++;
        }
        if (train.isEmpty()) { train = examples; test = Collections.emptyList(); }

        Arrays.fill(this.weights, 0.0F);
        Arrays.fill(this.bias, 0.0F);

        int classes = this.labels.size();
        double[] probability = new double[classes];
        Random rng = new Random(1234);                 // fixed: reproducible fits
        for (int epoch = 0; epoch < epochs; epoch++) {
            Collections.shuffle(train, rng);
            double lr = learningRate / (1.0D + epoch);
            for (Example e : train) {
                // Forward.
                double max = Double.NEGATIVE_INFINITY;
                for (int c = 0; c < classes; c++) {
                    int base = c * this.dimension;
                    double sum = this.bias[c];
                    for (int k = 0; k < e.vector().indices().length; k++) {
                        sum += this.weights[base + e.vector().indices()[k]] * e.vector().values()[k];
                    }
                    probability[c] = sum;
                    max = Math.max(max, sum);
                }
                double total = 0.0D;
                for (int c = 0; c < classes; c++) {
                    probability[c] = Math.exp(probability[c] - max);
                    total += probability[c];
                }
                // Backward: gradient of cross-entropy is (p - y).
                for (int c = 0; c < classes; c++) {
                    double p = probability[c] / total;
                    double error = p - (c == e.label() ? 1.0D : 0.0D);
                    if (error == 0.0D) continue;
                    int base = c * this.dimension;
                    double step = lr * error;
                    for (int k = 0; k < e.vector().indices().length; k++) {
                        int column = base + e.vector().indices()[k];
                        float w = this.weights[column];
                        this.weights[column] = (float)
                                (w - step * e.vector().values()[k] - lr * l2 * w);
                    }
                    this.bias[c] = (float) (this.bias[c] - step);
                }
            }
        }
        this.trainedOn = train.size();
        this.heldOutAccuracy = test.isEmpty() ? -1.0D : accuracy(test);
        return this.heldOutAccuracy;
    }

    /** Share of held-out examples the model gets right. */
    public double accuracy(List<Example> examples) {
        if (examples.isEmpty()) return -1.0D;
        int right = 0;
        for (Example e : examples) {
            if (this.labels.indexOf(predict(e.vector()).label()) == e.label()) right++;
        }
        return right / (double) examples.size();
    }

    /** Per-label precision and recall on a set, for the status report. */
    public Map<String, double[]> perLabel(List<Example> examples) {
        int classes = this.labels.size();
        int[] truePositive = new int[classes];
        int[] predicted = new int[classes];
        int[] actual = new int[classes];
        for (Example e : examples) {
            int got = this.labels.indexOf(predict(e.vector()).label());
            predicted[got]++;
            actual[e.label()]++;
            if (got == e.label()) truePositive[got]++;
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        for (int c = 0; c < classes; c++) {
            double precision = predicted[c] == 0 ? 0 : truePositive[c] / (double) predicted[c];
            double recall = actual[c] == 0 ? 0 : truePositive[c] / (double) actual[c];
            out.put(this.labels.get(c), new double[]{ precision, recall, actual[c] });
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /*  Accessors                                                         */
    /* ------------------------------------------------------------------ */

    public List<String> labels() { return Collections.unmodifiableList(this.labels); }

    public int labelIndex(String label) { return this.labels.indexOf(TextFeatures.norm(label)); }

    public int trainedOn() { return this.trainedOn; }

    public double heldOutAccuracy() { return this.heldOutAccuracy; }

    public boolean trained() { return this.trainedOn > 0; }

    public long memoryBytes() { return (long) this.weights.length * 4L + this.bias.length * 4L; }

    /* ------------------------------------------------------------------ */
    /*  Persistence                                                       */
    /* ------------------------------------------------------------------ */

    private static final int MAGIC = 0x47434D31;        // "GCM1"

    public void save(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(MAGIC);
            out.writeInt(this.dimension);
            out.writeInt(this.labels.size());
            for (String l : this.labels) out.writeUTF(l);
            out.writeInt(this.trainedOn);
            out.writeDouble(this.heldOutAccuracy);
            for (float b : this.bias) out.writeFloat(b);
            // Sparse: most columns are never touched, so only non-zero weights
            // are written. A trained model is typically a few hundred KB.
            int nonZero = 0;
            for (float w : this.weights) if (w != 0.0F) nonZero++;
            out.writeInt(nonZero);
            for (int i = 0; i < this.weights.length; i++) {
                if (this.weights[i] == 0.0F) continue;
                out.writeInt(i);
                out.writeFloat(this.weights[i]);
            }
        }
    }

    /** Returns null when the file is absent or was written by another build. */
    public static LinearModel load(File file) {
        if (file == null || !file.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) return null;
            LinearModel m = new LinearModel();
            m.dimension = in.readInt();
            int classes = in.readInt();
            for (int i = 0; i < classes; i++) m.labels.add(in.readUTF());
            m.trainedOn = in.readInt();
            m.heldOutAccuracy = in.readDouble();
            m.bias = new float[classes];
            for (int i = 0; i < classes; i++) m.bias[i] = in.readFloat();
            m.weights = new float[classes * m.dimension];
            int nonZero = in.readInt();
            for (int i = 0; i < nonZero; i++) {
                int index = in.readInt();
                float value = in.readFloat();
                if (index >= 0 && index < m.weights.length) m.weights[index] = value;
            }
            return m;
        } catch (Exception ex) {
            return null;
        }
    }
}
