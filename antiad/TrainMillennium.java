import com.coltcore.core.modules.MillenniumNet;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Headless trainer for {@link MillenniumNet}, the text-native rebuild of the
 * Millennium 5 engine.
 *
 * <pre>
 *   java TrainMillennium gradcheck
 *   java TrainMillennium train [out.bin]
 * </pre>
 *
 * <p>Reads the same corpora the fastText model was built from - the seed
 * corpus, the generated TLD coverage corpus and the real chat log - and writes
 * a single model file plus a parity fixture. It shares {@code MillenniumNet}
 * with the plugin, so tokenisation, hashing and the forward pass cannot drift
 * between training and serving; the parity fixture proves the saved-then-
 * reloaded model still agrees with the in-memory one.
 *
 * <p>Training runs over a pool of worker nets with identical weights. Each
 * worker accumulates gradients for its slice of a batch, the master sums them
 * and takes one AdamW step, then broadcasts the new weights back. Same maths
 * as sequential mini-batch SGD, several times faster.
 */
public final class TrainMillennium {

    // ---- shape ----------------------------------------------------------
    private static final int EMB_DIM = 48;
    private static final int HIDDEN = 48;
    private static final int LAYERS = 2;
    private static final int BUCKET = 4096;
    private static final int GRAM_LEN = 4;
    private static final int MIN_COUNT = 3;

    /** Longest sequence L2 sees: a single message. */
    private static final int MAX_TOKENS_L2 = 24;
    /** Longest sequence L3 sees: a message plus its context window. */
    private static final int MAX_TOKENS_L3 = 64;

    // ---- training -------------------------------------------------------
    private static final int EPOCHS = 6;
    private static final int BATCH = 64;
    private static final float LR = 3.0e-3f;
    private static final float VAL_FRACTION = 0.10f;
    private static final long SEED = 42L;
    private static final int SIZE_BUDGET_BYTES = 5 * 1024 * 1024;

    // ---- metric gates ---------------------------------------------------
    private static final double GATE_PRECISION = 0.97;
    private static final double GATE_RECALL = 0.90;
    private static final double GATE_CLEAN_FP = 0.10;
    private static final double BAND_LO = 0.40;
    private static final double BAND_HI = 0.85;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "train";
        if ("gradcheck".equals(mode)) {
            gradcheck();
            return;
        }
        if ("eval".equals(mode)) {
            eval(Paths.get(args.length > 1 ? args[1] : "antiad/antiad.m5.bin"));
            return;
        }
        if (!"train".equals(mode)) {
            System.err.println("usage: TrainMillennium [gradcheck|train|eval] [out.bin]");
            System.exit(2);
            return;
        }
        Path out = Paths.get(args.length > 1 ? args[1] : "antiad/antiad.m5.bin");
        train(out);
    }

    /* ------------------------------------------------------------------ */
    /*  Decision-threshold calibration                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Answers the two questions the pipeline's thresholds depend on, using the
     * held-out split rather than a hand-picked example or two.
     *
     * <p>First: where do the two classes actually sit? The temperature sweep
     * optimises for band mass, which says nothing about whether a block
     * threshold of 0.85 is above or below where real advertising lands. A
     * temperature that parks every positive at 0.81 would silently disable L2 —
     * it would never block, and it would hand everything to L3.
     *
     * <p>Second: does the context window change anything? L3 only earns its
     * cost if appending five prior messages moves the score. If it does not,
     * L3 is L2 with a bigger token budget and should be removed rather than
     * shipped.
     */
    private static void eval(Path model) throws Exception {
        int vocabSize;
        int bucket;
        int embDim;
        int hidden;
        int layers;
        int gramLen;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(model.toFile())))) {
            if (in.readInt() != 0x4D354E54) throw new IOException("not a MillenniumNet file");
            in.readInt();
            vocabSize = in.readInt();
            bucket = in.readInt();
            embDim = in.readInt();
            hidden = in.readInt();
            layers = in.readInt();
            gramLen = in.readInt();
        }
        MillenniumNet net = new MillenniumNet(vocabSize, bucket, embDim, hidden, layers, gramLen);
        float savedTemperature;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(model.toFile())))) {
            net.load(in);
            savedTemperature = net.temperature;
        }
        System.out.printf("model: %d vocab, %d bucket, %d emb, %d hidden, %d layers%n",
                vocabSize, bucket, embDim, hidden, layers);
        System.out.printf("trained temperature %.2f, %d parameters%n",
                savedTemperature, net.parameters());

        List<String> valTexts = new ArrayList<>();
        List<Float> valLabels = new ArrayList<>();
        validationSplit(valTexts, valLabels);
        System.out.printf("validation: %d samples (%d advertising, %d clean)%n%n",
                valTexts.size(), countPositive(valLabels),
                valLabels.size() - countPositive(valLabels));

        for (float temp : new float[]{1f, 1.5f, 2f, 2.5f, 3f, 4f}) {
            net.temperature = temp;
            List<Float> posScores = new ArrayList<>();
            List<Float> negScores = new ArrayList<>();
            for (int i = 0; i < valTexts.size(); i++) {
                float p = net.probability(valTexts.get(i), MAX_TOKENS_L2);
                if (valLabels.get(i) > 0.5f) posScores.add(p);
                else negScores.add(p);
            }
            System.out.printf("T=%.1f%n", temp);
            printQuantiles("  advertising", posScores);
            printQuantiles("  clean      ", negScores);
            System.out.printf("  share >= 0.85: ad %.3f%%  clean %.3f%%%n",
                    shareAtLeast(posScores, 0.85f) * 100.0,
                    shareAtLeast(negScores, 0.85f) * 100.0);
            System.out.printf("  share <= 0.40: ad %.3f%%  clean %.3f%%%n",
                    shareAtMost(posScores, 0.40f) * 100.0,
                    shareAtMost(negScores, 0.40f) * 100.0);
            System.out.printf("  share in 0.40-0.85: ad %.3f%%  clean %.3f%%%n%n",
                    shareBetween(posScores, 0.40f, 0.85f) * 100.0,
                    shareBetween(negScores, 0.40f, 0.85f) * 100.0);
        }

        net.temperature = savedTemperature;

        // ---- threshold sweep: what block/clear pair actually buys ----
        List<Float> posScores = new ArrayList<>();
        List<Float> negScores = new ArrayList<>();
        for (int i = 0; i < valTexts.size(); i++) {
            float p = net.probability(valTexts.get(i), MAX_TOKENS_L2);
            if (valLabels.get(i) > 0.5f) posScores.add(p);
            else negScores.add(p);
        }
        System.out.println("threshold sweep at the trained temperature:");
        System.out.println("  block  clear   precision   recall   cleanFP   bandMass");
        for (float block : new float[]{0.60f, 0.70f, 0.80f, 0.85f, 0.90f, 0.95f}) {
            for (float clear : new float[]{0.20f, 0.30f, 0.40f}) {
                if (clear >= block) continue;
                int tp = 0;
                int fn = 0;
                int fp = 0;
                int tn = 0;
                int band = 0;
                for (float p : posScores) {
                    if (p >= block) tp++;
                    else if (p <= clear) fn++;
                    else band++;
                }
                for (float p : negScores) {
                    if (p >= block) fp++;
                    else if (p <= clear) tn++;
                    else band++;
                }
                double precision = tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
                double recall = tp + fn == 0 ? 1.0 : (double) tp / (tp + fn);
                double cleanFp = fp + tn == 0 ? 0.0 : (double) fp / (fp + tn);
                double bandMass = (double) band / (posScores.size() + negScores.size());
                System.out.printf(Locale.ROOT, "  %.2f   %.2f    %.4f      %.4f   %.4f    %.3f%%%n",
                        block, clear, precision, recall, cleanFp, bandMass * 100.0);
            }
        }

        // ---- does the context window move anything? ----
        // L2 scores the message alone; L3 has the same weights, so the only
        // thing L3 can add is the conversation. Three rules, each aimed at a
        // measured failure of the naive "concatenate context + message" design
        // it replaces:
        //
        //   confident message            -> flag (L2 already does this)
        //   borderline message, ad-heavy -> flag; a borderline message among
        //       context                       clean lines is just unusual wording
        //   clean-looking message        -> flag only if it measurably raises the
        //       score of what precedes it     context's own advertising score
        //
        // The third rule is the interesting one. Requiring the score to RISE
        // (rather than be high) is what keeps an innocent "ok" inside five
        // advertising lines from being flagged: that context already scores
        // high and the "ok" does not raise it. A pitch split across messages -
        // "join my server at" / "play.coolpvp.xyz" - does raise it, which is the
        // case L2 structurally cannot see.
        System.out.println();
        System.out.println("context experiment (shipped L3 rules):");
        for (String[] pair : CONTEXT_CASES) {
            String context = pair[0];
            String message = pair[1];
            float alone = net.probability(message, MAX_TOKENS_L2);
            List<Float> ctx = new ArrayList<>();
            StringBuilder joined = new StringBuilder();
            for (String line : context.split("\\s*;\\s*")) {
                ctx.add(net.probability(line, MAX_TOKENS_L2));
                if (joined.length() > 0) joined.append(' ');
                joined.append(line);
            }
            int ad = 0;
            for (float p : ctx) if (p >= BAND_HI) ad++;
            double adShare = ctx.isEmpty() ? 0.0 : (double) ad / ctx.size();
            float contextOnly = net.probability(joined.toString(), MAX_TOKENS_L3);
            float withMessage = net.probability(joined + " " + message, MAX_TOKENS_L3);
            float lift = withMessage - contextOnly;

            String rule;
            boolean flag;
            if (alone >= BAND_HI) {
                rule = "confident";
                flag = true;
            } else if (alone >= BAND_LO) {
                rule = "band + ad-context";
                flag = adShare >= 0.5;
            } else {
                rule = "clean + lift";
                // Mirrors AntiAdPipeline.CONTEXT_LIFT / CONTEXT_LIFT_FLOOR.
                // Keep the two in step or this stops reproducing the shipped
                // rules and starts measuring a filter nobody runs.
                flag = lift >= 0.35f && withMessage >= 0.85f;
            }
            System.out.printf(Locale.ROOT,
                    "  %-4s  %-20s  msg %.3f  adCtx %.2f  ctxOnly %.3f  with %.3f  "
                            + "lift %+.3f  [%s]%n",
                    flag ? "FLAG" : "pass", rule, alone, adShare, contextOnly,
                    withMessage, lift, message);
        }
    }

    private static List<Float> scoresOf(MillenniumNet net, List<String> texts,
                                       List<Float> labels, boolean positive, int maxTokens) {
        List<Float> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            if ((labels.get(i) > 0.5f) == positive) {
                out.add(net.probability(texts.get(i), maxTokens));
            }
        }
        return out;
    }

    private static void printQuantiles(String label, List<Float> scores) {
        if (scores.isEmpty()) return;
        List<Float> sorted = new ArrayList<>(scores);
        Collections.sort(sorted);
        System.out.printf(Locale.ROOT,
                "%s  p01 %.3f  p05 %.3f  p25 %.3f  p50 %.3f  p75 %.3f  p95 %.3f  max %.3f%n",
                label,
                quantile(sorted, 0.01), quantile(sorted, 0.05), quantile(sorted, 0.25),
                quantile(sorted, 0.50), quantile(sorted, 0.75), quantile(sorted, 0.95),
                quantile(sorted, 0.99));
    }

    private static float quantile(List<Float> sorted, double q) {
        int index = (int) Math.round(q * (sorted.size() - 1));
        index = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(index);
    }

    private static double shareAtLeast(List<Float> scores, float bound) {
        if (scores.isEmpty()) return 0.0;
        int n = 0;
        for (float p : scores) if (p >= bound) n++;
        return (double) n / scores.size();
    }

    private static double shareAtMost(List<Float> scores, float bound) {
        if (scores.isEmpty()) return 0.0;
        int n = 0;
        for (float p : scores) if (p <= bound) n++;
        return (double) n / scores.size();
    }

    private static double shareBetween(List<Float> scores, float lo, float hi) {
        if (scores.isEmpty()) return 0.0;
        int n = 0;
        for (float p : scores) if (p > lo && p < hi) n++;
        return (double) n / scores.size();
    }

    /**
     * Context window experiments: {five prior messages separated by ';', the
     * message under test}. The first four cover the two ways a context window
     * can go wrong - an innocent reply inside advertising chatter, and a real
     * pitch buried in ordinary chatter.
     */
    private static final String[][] CONTEXT_CASES = {
            {"gg wp that was a good fight; somebody help me find the nether fortress; "
                    + "anyone got spare iron lol; nice base btw; brb dinner",
                    "wanna join my smp? ip in bio"},
            {"join coolpvp.zip; come play minescape.museum; free ranks at funserver.nra; "
                    + "play now at coolpvp.zip; ip in bio",
                    "lol no thanks"},
            {"join coolpvp.zip; come play minescape.museum; free ranks at funserver.nra; "
                    + "play now at coolpvp.zip; ip in bio",
                    "ok"},
            {"gg wp that was a good fight; somebody help me find the nether fortress; "
                    + "anyone got spare iron lol; nice base btw; brb dinner",
                    "reddit.com is a bad website lol"},
            {"join coolpvp.zip; come play minescape.museum; free ranks at funserver.nra; "
                    + "play now at coolpvp.zip; ip in bio",
                    "anyone wanna buy my diamonds"},
            // A pitch split across messages: every fragment is innocuous alone,
            // and only the rise in score gives it away.
            {"join my server at; it has free ranks and; come play at; the ip is; go to",
                    "play.coolpvp.xyz"},
    };

    /**
     * Rebuilds the validation split exactly as {@link #train} does, so the
     * calibration numbers describe the same held-out data the gates reported.
     */
    private static void validationSplit(List<String> texts, List<Float> labels) throws IOException {
        List<String> all = new ArrayList<>();
        List<Float> allLabels = new ArrayList<>();
        loadTsv(Paths.get("antiad/seed_corpus.tsv"), all, allLabels);
        loadTsv(Paths.get("antiad/tld_corpus.tsv"), all, allLabels);
        loadClean(Paths.get("chat_corpus.txt"), all, allLabels);
        List<Integer> pos = new ArrayList<>();
        List<Integer> neg = new ArrayList<>();
        for (int i = 0; i < allLabels.size(); i++) {
            if (allLabels.get(i) > 0.5f) pos.add(i);
            else neg.add(i);
        }
        Collections.shuffle(pos, new Random(SEED));
        Collections.shuffle(neg, new Random(SEED + 1));
        int posVal = (int) (pos.size() * VAL_FRACTION);
        int negVal = (int) (neg.size() * VAL_FRACTION);
        List<Integer> valIdx = new ArrayList<>(pos.subList(0, posVal));
        valIdx.addAll(neg.subList(0, negVal));
        Collections.sort(valIdx);
        for (int i : valIdx) {
            texts.add(all.get(i));
            labels.add(allLabels.get(i));
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Gradient check                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Finite-difference check of every parameter group. The whole point of
     * hand-written BPTT is that it is easy to get subtly wrong and hard to
     * notice, so this runs before any training.
     */
    private static void gradcheck() {
        MillenniumNet net = new MillenniumNet(40, 64, 8, 6, 2);
        List<String> vocab = new ArrayList<>();
        for (int i = 0; i < 39; i++) vocab.add("w" + i);
        net.setVocab(vocab);
        net.init(SEED);
        net.tokenDropout = 0f;

        String[] texts = {
                "join play example gg com now",
                "w0 w3 w9 w12 w30 w7",
        };
        float[] labels = {1f, 0f};
        float eps = 2e-3f;

        net.zeroGrads();
        double loss = 0.0;
        for (int i = 0; i < texts.length; i++) {
            MillenniumNet.Sample s = net.sample(texts[i], MAX_TOKENS_L2);
            float p = net.forward(s, false);
            loss += bce(p, labels[i]);
            net.backward(s, p, labels[i]);
        }

        double worstAbs = 0.0;
        double worstExcess = 0.0;
        String worstWhere = "-";
        String worstExcessWhere = "-";
        int checked = 0;
        // atol covers the finite-difference noise floor (a float-precision
        // forward pass with eps=2e-3 puts it near 1e-4); rtol covers everything
        // above it. Comparing pure relative error is wrong here, because a
        // gradient sitting at the floor has a meaningless ratio.
        final double atol = 1.0e-4;
        final double rtol = 2.0e-2;
        System.out.println("  per-group max |analytic grad|:");
        for (int g = 0; g < net.groupCount(); g++) {
            float[] gg = net.grads(g);
            double mx = 0.0;
            for (float f : gg) mx = Math.max(mx, Math.abs(f));
            System.out.printf("    %-14s n=%-6d max|g|=%.3e%n",
                    net.groupName(g), gg.length, mx);
        }
        for (int g = 0; g < net.groupCount(); g++) {
            float[] v = net.values(g);
            float[] grad = net.grads(g);
            for (int i = 0; i < v.length; i++) {
                float orig = v[i];
                v[i] = orig + eps;
                double plus = totalLoss(net, texts, labels);
                v[i] = orig - eps;
                double minus = totalLoss(net, texts, labels);
                v[i] = orig;
                double numeric = (plus - minus) / (2.0 * eps);
                double analytic = grad[i];
                double abs = Math.abs(numeric - analytic);
                if (abs > worstAbs) {
                    worstAbs = abs;
                    worstWhere = net.groupName(g) + "[" + i + "]";
                }
                double allowed = atol + rtol * Math.max(Math.abs(numeric),
                        Math.abs(analytic));
                double excess = abs / allowed;
                if (excess > worstExcess) {
                    worstExcess = excess;
                    worstExcessWhere = net.groupName(g) + "[" + i + "]"
                            + String.format(Locale.ROOT, " (num %.3e ana %.3e)",
                            numeric, analytic);
                }
                checked++;
            }
        }
        System.out.printf("gradcheck: %d parameters, loss %.6f%n", checked, loss);
        System.out.printf("  worst abs delta %.3e at %s%n", worstAbs, worstWhere);
        System.out.printf("  worst tolerance ratio %.3f of 1.0 at %s%n",
                worstExcess, worstExcessWhere);
        if (worstExcess > 1.0) {
            System.out.println("GRADCHECK FAIL");
            System.exit(1);
        }
        System.out.println("GRADCHECK PASS");
    }

    private static double totalLoss(MillenniumNet net, String[] texts, float[] labels) {
        double loss = 0.0;
        for (int i = 0; i < texts.length; i++) {
            float p = net.forward(net.sample(texts[i], MAX_TOKENS_L2), false);
            loss += bce(p, labels[i]);
        }
        return loss;
    }

    private static double bce(float p, float y) {
        float target = y * (1f - 0.05f) + 0.5f * 0.05f;
        double pc = Math.min(1.0 - 1e-7, Math.max(1e-7, p));
        return -(target * Math.log(pc) + (1.0 - target) * Math.log(1.0 - pc));
    }

    /* ------------------------------------------------------------------ */
    /*  Training                                                           */
    /* ------------------------------------------------------------------ */

    private static void train(Path out) throws Exception {
        List<String> texts = new ArrayList<>();
        List<Float> labels = new ArrayList<>();
        loadTsv(Paths.get("antiad/seed_corpus.tsv"), texts, labels);
        loadTsv(Paths.get("antiad/tld_corpus.tsv"), texts, labels);
        loadClean(Paths.get("chat_corpus.txt"), texts, labels);
        System.out.printf("corpus: %d samples (%d advertising, %d clean)%n",
                texts.size(), countPositive(labels), labels.size() - countPositive(labels));
        if (texts.isEmpty()) {
            System.err.println("no corpus found - run DumpCorpus and tld_corpus.py first");
            System.exit(2);
        }

        // Deterministic split, stratified so both halves keep the class balance.
        List<Integer> pos = new ArrayList<>();
        List<Integer> neg = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i) > 0.5f) pos.add(i);
            else neg.add(i);
        }
        Collections.shuffle(pos, new Random(SEED));
        Collections.shuffle(neg, new Random(SEED + 1));
        int posVal = (int) (pos.size() * VAL_FRACTION);
        int negVal = (int) (neg.size() * VAL_FRACTION);
        List<Integer> valIdx = new ArrayList<>(pos.subList(0, posVal));
        valIdx.addAll(neg.subList(0, negVal));
        List<Integer> trainIdx = new ArrayList<>(pos.subList(posVal, pos.size()));
        trainIdx.addAll(neg.subList(negVal, neg.size()));
        Collections.sort(valIdx);
        Collections.sort(trainIdx);

        List<String> trainTexts = new ArrayList<>(trainIdx.size());
        List<Float> trainLabels = new ArrayList<>(trainIdx.size());
        for (int i : trainIdx) {
            trainTexts.add(texts.get(i));
            trainLabels.add(labels.get(i));
        }
        List<String> valTexts = new ArrayList<>(valIdx.size());
        List<Float> valLabels = new ArrayList<>(valIdx.size());
        for (int i : valIdx) {
            valTexts.add(texts.get(i));
            valLabels.add(labels.get(i));
        }
        System.out.printf("split: %d train / %d validation%n", trainTexts.size(), valTexts.size());

        List<String> vocab = buildVocab(trainTexts, MIN_COUNT);
        int vocabSize = vocab.size() + 1;
        System.out.printf("vocab: %d words (minCount %d)%n", vocab.size(), MIN_COUNT);

        MillenniumNet master = new MillenniumNet(vocabSize, BUCKET, EMB_DIM, HIDDEN, LAYERS,
                GRAM_LEN);
        master.setVocab(vocab);
        master.init(SEED);
        System.out.printf("model: %d parameters, %.2f MB serialised%n",
                master.parameters(), master.serialisedBytes() / 1048576.0);
        if (master.serialisedBytes() > SIZE_BUDGET_BYTES) {
            System.err.println("model exceeds the 5 MB budget - shrink EMB_DIM or BUCKET");
            System.exit(1);
        }

        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        List<MillenniumNet> pool = new ArrayList<>();
        for (int i = 0; i < workers; i++) {
            MillenniumNet w = new MillenniumNet(vocabSize, BUCKET, EMB_DIM, HIDDEN, LAYERS,
                    GRAM_LEN);
            w.setVocab(vocab);
            copyWeights(master, w);
            w.tokenDropout = master.tokenDropout;
            pool.add(w);
        }
        ExecutorService exec = Executors.newFixedThreadPool(workers);
        System.out.printf("threads: %d%n", workers);

        int n = trainTexts.size();
        int[] shuffled = new int[n];
        for (int i = 0; i < n; i++) shuffled[i] = i;
        Random rng = new Random(SEED);
        long started = System.currentTimeMillis();
        int stepsPerEpoch = (n + BATCH - 1) / BATCH;

        for (int epoch = 1; epoch <= EPOCHS; epoch++) {
            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = shuffled[i];
                shuffled[i] = shuffled[j];
                shuffled[j] = tmp;
            }
            float lr = LR * (0.1f + 0.9f * (1f - (epoch - 1f) / EPOCHS));
            double epochLoss = 0.0;
            int steps = 0;
            for (int start = 0; start < n; start += BATCH) {
                int end = Math.min(n, start + BATCH);
                int chunks = Math.min(workers, end - start);
                int per = (end - start + chunks - 1) / chunks;
                List<Callable<Double>> jobs = new ArrayList<>(chunks);
                List<MillenniumNet> used = new ArrayList<>(chunks);
                for (int c = 0; c < chunks; c++) {
                    int from = start + c * per;
                    int to = Math.min(end, from + per);
                    if (from >= to) continue;
                    MillenniumNet w = pool.get(c);
                    used.add(w);
                    jobs.add(() -> {
                        w.zeroGrads();
                        double sum = 0.0;
                        for (int q = from; q < to; q++) {
                            int idx = shuffled[q];
                            MillenniumNet.Sample s =
                                    w.sample(trainTexts.get(idx), MAX_TOKENS_L2);
                            float y = trainLabels.get(idx);
                            float p = w.forward(s, true);
                            w.backward(s, p, y);
                            sum += bce(p, y);
                        }
                        return sum;
                    });
                }
                List<Future<Double>> results = exec.invokeAll(jobs);
                master.zeroGrads();
                for (int c = 0; c < jobs.size(); c++) {
                    epochLoss += results.get(c).get();
                    MillenniumNet w = used.get(c);
                    for (int g = 0; g < master.groupCount(); g++) {
                        float[] src = w.grads(g);
                        float[] dst = master.grads(g);
                        for (int i = 0; i < dst.length; i++) dst[i] += src[i];
                    }
                }
                master.step(lr);
                for (MillenniumNet w : pool) copyWeights(master, w);
                steps++;
                if (steps % 50 == 0 || steps == stepsPerEpoch) {
                    System.out.printf("  epoch %d  step %d/%d  loss %.5f  %.0fs%n",
                            epoch, steps, stepsPerEpoch, epochLoss / Math.max(1, steps * BATCH),
                            (System.currentTimeMillis() - started) / 1000.0);
                }
            }
            Metrics m = evaluate(master, valTexts, valLabels, MAX_TOKENS_L2);
            System.out.printf("epoch %d done  loss %.5f  P %.4f  R %.4f  cleanFP %.4f  band %.3f%%%n",
                    epoch, epochLoss / Math.max(1, n), m.precision(), m.recall(),
                    m.cleanFalseFlagRate(), m.bandMass * 100.0);
        }
        exec.shutdown();

        // ---- temperature sweep -----------------------------------------
        // Training always divides by T=1, so T=1 is the model's own
        // calibration; raising it only ever compresses the logits. The sweep is
        // kept as evidence rather than as a search: it reports how the block
        // threshold behaves across temperatures, and the shipped value is the
        // model's own. An earlier revision selected the temperature that
        // maximised ambiguous-band mass, which drove every positive into
        // 0.788-0.822 - inside the 0.40-0.85 band - so L2 could never block
        // anything and every message fell through to L3.
        for (float temp : new float[]{1f, 1.5f, 2f, 2.5f, 3f, 4f}) {
            master.temperature = temp;
            Metrics m = evaluate(master, valTexts, valLabels, MAX_TOKENS_L2);
            System.out.printf("  T=%.1f  P %.4f  R %.4f  cleanFP %.4f  band %.3f%%%n",
                    temp, m.precision(), m.recall(), m.cleanFalseFlagRate(), m.bandMass * 100.0);
        }

        float bestT = 1f;
        master.temperature = bestT;
        Metrics fin = evaluate(master, valTexts, valLabels, MAX_TOKENS_L2);
        System.out.printf("chosen temperature %.1f (the trained calibration)%n", bestT);
        System.out.printf("final: P %.4f  R %.4f  cleanFP %.4f  band %.3f%%%n",
                fin.precision(), fin.recall(), fin.cleanFalseFlagRate(), fin.bandMass * 100.0);

        boolean ok = true;
        if (fin.precision() < GATE_PRECISION) {
            System.err.printf("GATE FAIL precision %.4f < %.2f%n", fin.precision(), GATE_PRECISION);
            ok = false;
        }
        if (fin.recall() < GATE_RECALL) {
            System.err.printf("GATE FAIL recall %.4f < %.2f%n", fin.recall(), GATE_RECALL);
            ok = false;
        }
        if (fin.cleanFalseFlagRate() > GATE_CLEAN_FP) {
            System.err.printf("GATE FAIL clean false-flag %.4f > %.2f%n",
                    fin.cleanFalseFlagRate(), GATE_CLEAN_FP);
            ok = false;
        }
        // The block threshold has to be above where clean text actually lands.
        // This is the check that would have caught the temperature mistake: at
        // T=2.5 not one advertising sample cleared 0.85, so the gate is "do the
        // positives reach the block threshold at all".
        double positivesAtBlock = shareAtLeast(scoresOf(master, valTexts, valLabels, true,
                MAX_TOKENS_L2), 0.85f);
        if (positivesAtBlock < GATE_RECALL) {
            System.err.printf("GATE FAIL only %.3f%% of advertising reaches the 0.85 block "
                    + "threshold - L2 would never block%n", positivesAtBlock * 100.0);
            ok = false;
        }
        System.out.printf("calibration: %.3f%% of advertising at or above the 0.85 block "
                + "threshold, %.3f%% of clean at or above it%n",
                positivesAtBlock * 100.0,
                shareAtLeast(scoresOf(master, valTexts, valLabels, false, MAX_TOKENS_L2),
                        0.85f) * 100.0);

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(out.toFile())))) {
            master.save(dos);
        }
        long size = Files.size(out);
        System.out.printf("wrote %s (%.2f MB)%n", out, size / 1048576.0);
        if (size > SIZE_BUDGET_BYTES) {
            System.err.println("GATE FAIL model over the 5 MB budget");
            ok = false;
        }

        writeParityFixture(master, Paths.get("antiad/m5-vectors-check.tsv"));
        writeSpotCheck(master);

        if (!ok) {
            System.out.println("TRAINING FAILED ITS GATES");
            System.exit(1);
        }
        System.out.println("TRAINING OK");
    }

    private static void copyWeights(MillenniumNet from, MillenniumNet to) {
        for (int g = 0; g < from.groupCount(); g++) {
            float[] src = from.values(g);
            float[] dst = to.values(g);
            System.arraycopy(src, 0, dst, 0, src.length);
        }
        to.temperature = from.temperature;
    }

    /* ------------------------------------------------------------------ */
    /*  Metrics                                                            */
    /* ------------------------------------------------------------------ */

    private static final class Metrics {
        double tp;
        double fp;
        double fn;
        double tn;
        double bandMass;

        double precision() {
            return this.tp + this.fp == 0 ? 1.0 : this.tp / (this.tp + this.fp);
        }

        double recall() {
            return this.tp + this.fn == 0 ? 1.0 : this.tp / (this.tp + this.fn);
        }

        double cleanFalseFlagRate() {
            return this.fp + this.tn == 0 ? 0.0 : this.fp / (this.fp + this.tn);
        }
    }

    private static Metrics evaluate(MillenniumNet net, List<String> texts, List<Float> labels,
                                    int maxTokens) {
        Metrics m = new Metrics();
        int band = 0;
        for (int i = 0; i < texts.size(); i++) {
            float p = net.probability(texts.get(i), maxTokens);
            boolean ad = labels.get(i) > 0.5f;
            boolean flagged = p >= 0.5f;
            if (ad && flagged) m.tp++;
            else if (ad) m.fn++;
            else if (flagged) m.fp++;
            else m.tn++;
            if (p > BAND_LO && p < BAND_HI) band++;
        }
        m.bandMass = texts.isEmpty() ? 0.0 : (double) band / texts.size();
        return m;
    }

    /* ------------------------------------------------------------------ */
    /*  Outputs                                                            */
    /* ------------------------------------------------------------------ */

    private static final String[] SPOT = {
            "join coolpvp.zip",
            "come play minescape.museum",
            "free ranks at funserver.nra",
            "the docs at papermc.museum explain it",
            "is github.pnc down for anyone else",
            "gg wp that was a good fight",
            "check out my anarchy server at play.coolpvp.xyz",
            "reddit.com is a bad website lol",
            "somebody help me find the nether fortress",
            "wanna join my smp? ip in bio",
    };

    private static void writeParityFixture(MillenniumNet net, Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String t : SPOT) {
            sb.append(String.format(Locale.ROOT, "%.6f\t%s%n",
                    net.probability(t, MAX_TOKENS_L2), t));
        }
        Files.write(path, sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.printf("wrote %s (%d vectors)%n", path, SPOT.length);
    }

    private static void writeSpotCheck(MillenniumNet net) {
        System.out.println("spot check (L2 sequence / L3 sequence):");
        for (String t : SPOT) {
            System.out.printf("  %.3f / %.3f  %s%n",
                    net.probability(t, MAX_TOKENS_L2),
                    net.probability(t, MAX_TOKENS_L3), t);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Corpus                                                             */
    /* ------------------------------------------------------------------ */

    private static int countPositive(List<Float> labels) {
        int n = 0;
        for (float f : labels) if (f > 0.5f) n++;
        return n;
    }

    private static List<String> buildVocab(List<String> texts, int minCount) {
        Map<String, Integer> counts = new HashMap<>();
        for (String t : texts) {
            for (String tok : MillenniumNet.tokenize(t)) {
                counts.merge(tok, 1, Integer::sum);
            }
        }
        List<String> words = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() >= minCount) words.add(e.getKey());
        }
        Collections.sort(words);
        return words;
    }

    /** Reads {@code label<TAB>text}. Any label other than "clean" is positive. */
    private static void loadTsv(Path path, List<String> texts, List<Float> labels)
            throws IOException {
        if (!Files.exists(path)) {
            System.out.printf("skip %s (absent)%n", path);
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String label = line.substring(0, tab).trim().toLowerCase(Locale.ROOT);
                String text = line.substring(tab + 1).trim();
                if (text.isEmpty()) continue;
                texts.add(text);
                labels.add("clean".equals(label) ? 0f : 1f);
            }
        }
    }

    /** Reads a plain text file where every non-empty line is clean chat. */
    private static void loadClean(Path path, List<String> texts, List<Float> labels)
            throws IOException {
        if (!Files.exists(path)) {
            System.out.printf("skip %s (absent)%n", path);
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty()) continue;
                texts.add(text);
                labels.add(0f);
            }
        }
    }

    private TrainMillennium() {
    }
}
