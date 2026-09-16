package com.coltcore.core.modules;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The fastText-style advertising classifier (layer 2 of the anti-ad pipeline).
 *
 * <h2>What this model is</h2>
 * Trained offline by {@code antiad/train_antiad.py} on Common Crawl 300d
 * subword vectors (reduced to 100d by PCA) plus the server's seed corpus and
 * real chat log. Architecture mirrors fastText: word embeddings plus hashed
 * character 3-6-gram subword embeddings, mean-pooled, into a two-way softmax
 * (advertising / clean). The full 5.8 GB crawl vector file is training-time
 * only; what ships is the 3.6 MB quantized classifier below.
 *
 * <h2>Binary format (FTA1, big-endian)</h2>
 * <pre>
 *   "FTA1"                      magic
 *   int dim, minn, maxn, bucket, vocabSize, labelCount
 *   vocabSize x writeUTF        vocabulary, frequency order
 *   labelCount x writeUTF       labels (index 0 = advertising)
 *   float inputScale            dequantization scale for the input matrix
 *   (vocabSize+bucket) x dim    int8 input matrix rows
 *   float outputScale
 *   labelCount x dim            int8 output matrix rows
 * </pre>
 *
 * <h2>Hash contract</h2>
 * The subword hash is FNV-1a 32-bit unsigned over the UTF-8 bytes of the
 * n-gram including fastText's {@code <}/{@code>} word boundary markers, and
 * the row index is {@code vocabSize + hash % bucket}. The Python trainer and
 * this loader must stay byte-identical or the model silently sees garbage.
 * {@link #main} runs the golden-vector parity check against
 * {@code antiad/vectors-check.tsv} to prove they do.
 *
 * <h2>Failure behaviour</h2>
 * Any load or prediction problem returns {@code -1}, which the pipeline reads
 * as "model unavailable": the message passes and a staff alert is raised. The
 * classifier is never allowed to be the reason a player is punished.
 */
public final class FastTextModel {

    private static final int MAGIC = 0x46544131;             // "FTA1"
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9']+");

    private final int dim;
    private final int minn;
    private final int maxn;
    private final int bucket;
    private final String[] vocab;
    private final String[] labels;
    private final byte[] inputMatrix;                        // int8 rows
    private final float inputScale;
    private final byte[] outputMatrix;
    private final float outputScale;

    private FastTextModel(int dim, int minn, int maxn, int bucket, String[] vocab,
                          String[] labels, byte[] inputMatrix, float inputScale,
                          byte[] outputMatrix, float outputScale) {
        this.dim = dim;
        this.minn = minn;
        this.maxn = maxn;
        this.bucket = bucket;
        this.vocab = vocab;
        this.labels = labels;
        this.inputMatrix = inputMatrix;
        this.inputScale = inputScale;
        this.outputMatrix = outputMatrix;
        this.outputScale = outputScale;
    }

    /** Loads {@code /antiad.ft.bin} from the plugin jar; null on any mismatch. */
    public static FastTextModel loadResource(String resource) {
        try (InputStream raw = FastTextModel.class.getResourceAsStream(resource)) {
            if (raw == null) return null;
            return read(new DataInputStream(raw));
        } catch (Throwable t) {
            return null;
        }
    }

    private static FastTextModel read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) return null;
        int dim = in.readInt();
        int minn = in.readInt();
        int maxn = in.readInt();
        int bucket = in.readInt();
        int vocabSize = in.readInt();
        int labelCount = in.readInt();
        if (dim <= 0 || dim > 512 || minn < 1 || maxn > 10 || minn > maxn
                || bucket <= 0 || bucket > 10_000_000
                || vocabSize < 0 || vocabSize > 1_000_000
                || labelCount <= 0 || labelCount > 64) return null;
        String[] vocab = new String[vocabSize];
        for (int i = 0; i < vocabSize; i++) vocab[i] = readUtf(in);
        String[] labels = new String[labelCount];
        for (int i = 0; i < labelCount; i++) labels[i] = readUtf(in);

        int inputRows = vocabSize + bucket;
        float inputScale = in.readFloat();
        byte[] input = new byte[inputRows * dim];
        in.readFully(input);
        float outputScale = in.readFloat();
        byte[] output = new byte[labelCount * dim];
        in.readFully(output);
        return new FastTextModel(dim, minn, maxn, bucket, vocab, labels,
                input, inputScale, output, outputScale);
    }

    /** DataInputStream.readUTF without the synchronized overhead. */
    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * p(advertising) for the text, or -1 when the model cannot answer.
     * Tokenization and hashing mirror the Python trainer exactly.
     */
    public double advertisingProbability(String text) {
        if (text == null) return -1.0D;
        String lower = text.toLowerCase(Locale.ROOT);

        // Feature rows: the word row when known, plus every hashed subword.
        List<Integer> rows = new ArrayList<>(64);
        java.util.regex.Matcher m = TOKEN.matcher(lower);
        while (m.find()) {
            String word = m.group();
            int wi = indexOfWord(word);
            if (wi >= 0) rows.add(wi);
            String padded = "<" + word + ">";
            addHashed(rows, padded);
            for (int n = this.minn; n <= this.maxn; n++) {
                for (int i = 0; i + n <= padded.length(); i++) {
                    addHashed(rows, padded.substring(i, i + n));
                }
            }
        }
        if (rows.isEmpty()) return 0.0D;

        // Mean-pool the input rows, then the output matrix gives the logits.
        float[] hidden = new float[this.dim];
        for (int row : rows) {
            int base = row * this.dim;
            for (int d = 0; d < this.dim; d++) hidden[d] += this.inputMatrix[base + d];
        }
        float inv = 1.0F / rows.size();
        float[] logits = new float[this.labels.length];
        for (int l = 0; l < this.labels.length; l++) {
            float dot = 0.0F;
            int base = l * this.dim;
            for (int d = 0; d < this.dim; d++) {
                dot += this.outputMatrix[base + d] * (hidden[d] * inv);
            }
            logits[l] = dot * this.outputScale * this.inputScale;
        }
        // Softmax over labels; advertising is found by name, not fixed index.
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) if (v > max) max = v;
        double sum = 0.0D;
        double[] exp = new double[logits.length];
        for (int l = 0; l < logits.length; l++) {
            exp[l] = Math.exp(logits[l] - max);
            sum += exp[l];
        }
        for (int l = 0; l < this.labels.length; l++) {
            if ("advertising".equals(this.labels[l])) return exp[l] / sum;
        }
        return -1.0D;
    }

    private int indexOfWord(String word) {
        // Vocab is small (a few thousand). Linear scan is fine at chat rate and
        // avoids building a map per call; most scans miss, so keep it tight.
        for (int i = 0; i < this.vocab.length; i++) {
            if (this.vocab[i].equals(word)) return i;
        }
        return -1;
    }

    private void addHashed(List<Integer> rows, String gram) {
        int h = 0x811C9DC5;                                  // 2166136261 unsigned
        for (byte b : gram.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFF);
            h *= 16777619;
        }
        rows.add(this.vocab.length
                + (int) (Integer.toUnsignedLong(h) % this.bucket));
    }

    public String[] labels() {
        return this.labels.clone();
    }

    /**
     * Smoke test + Python-parity check. Run against the workspace copy of the
     * model and the golden vectors:
     * <pre>
     *   java -cp target/classes com.coltcore.core.modules.FastTextModel \
     *        ../antiad/antiad.ft.bin ../antiad/vectors-check.tsv
     * </pre>
     */
    public static void main(String[] args) throws Exception {
        FastTextModel model;
        try (InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(args[0]))) {
            model = read(new DataInputStream(in));
        }
        if (model == null) {
            System.out.println("FAIL: could not parse " + args[0]);
            System.exit(1);
        }
        System.out.println("loaded: dim=" + model.dim + " vocab=" + model.vocab.length
                + " bucket=" + model.bucket + " labels=" + String.join(",", model.labels));
        System.out.printf("join play.hypixel.net now -> %.3f%n",
                model.advertisingProbability("join play.hypixel.net now"));
        System.out.printf("reddit.com is a bad website lol -> %.3f%n",
                model.advertisingProbability("reddit.com is a bad website lol"));
        System.out.printf("come to p l a y dot coltcore dot net -> %.3f%n",
                model.advertisingProbability("come to p l a y dot coltcore dot net"));

        int checked = 0;
        double worst = 0.0D;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                java.nio.file.Files.newInputStream(java.nio.file.Path.of(args[1])),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                double expected = Double.parseDouble(line.substring(0, tab));
                String text = line.substring(tab + 1);
                double got = model.advertisingProbability(text);
                double delta = Math.abs(expected - got);
                worst = Math.max(worst, delta);
                checked++;
                if (delta > 1e-3) {
                    System.out.printf("PARITY FAIL: expected %.6f got %.6f for %s%n",
                            expected, got, text);
                }
            }
        }
        System.out.printf("parity: %d checked, worst delta %.6f -> %s%n",
                checked, worst, worst <= 1e-3 ? "PASS" : "FAIL");
        if (worst > 1e-3) System.exit(1);
    }
}
