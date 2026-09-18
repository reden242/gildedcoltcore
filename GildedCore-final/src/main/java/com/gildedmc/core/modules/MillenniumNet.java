package com.gildedmc.core.modules;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small recurrent text classifier built on the Millennium 5 design.
 *
 * <p>Millennium 5 classifies rotation sequences, and its architecture is a good
 * one: stacked bidirectional LSTM, layer normalisation, attention pooling,
 * AdamW, label smoothing, gradient clipping. What does not transfer is its
 * input contract. There, one timestep is one rotation sample carrying two
 * channels - yaw and pitch - and the preprocessors are built around movement:
 * {@code tanh(yaw / 100)}, {@code hypot(yaw, pitch)} magnitudes, windowed
 * entropy and kurtosis. A chat message is not two numbers per step, so this is
 * the same engine redesigned around text, where one timestep is one word token.
 *
 * <pre>
 *   tokens -&gt; embeddings (word row + mean of char 4-gram rows)
 *          -&gt; L stacked bidirectional LSTM layers
 *          -&gt; layer norm per timestep
 *          -&gt; attention pooling over timesteps
 *          -&gt; linear head -&gt; sigmoid -&gt; p(advertising)
 * </pre>
 *
 * <p>Two choices make it work on chat rather than on movement:
 *
 * <ul>
 *   <li><b>Subword rows.</b> A token's vector is its word row plus the mean of
 *       the hashed character 4-grams inside it, so a host on a TLD the model
 *       never saw still produces a sensible vector from how it is spelled.
 *       Same trick the fastText model used, and the reason the 1,438-TLD
 *       coverage generalises.</li>
 *   <li><b>No padding, no mask.</b> A message is however many tokens it is,
 *       truncated to the most recent {@code maxTokens}. Attention runs over
 *       real timesteps only, so there is no mask anywhere to get wrong, and
 *       one set of weights serves both a single line and a six-line context
 *       window - which is exactly what the two callers need.</li>
 * </ul>
 *
 * <p>A little under 0.7M parameters, no threads parked, no HTTP, no external
 * process. The trainer is a plain {@code main} in {@code antiad/TrainMillennium.java},
 * and it ships a finite-difference {@code gradcheck} mode, because hand-written
 * BPTT that merely looks right is the classic way to ship a model that quietly
 * learns nothing.
 *
 * <p>Not thread-safe during training. {@link #probability} is synchronised so
 * the plugin can call it from async chat threads.
 */
public final class MillenniumNet {

    /** File magic "M5NT" and format revision, big-endian. */
    public static final int MAGIC = 0x4D354E54;
    public static final int FORMAT = 1;

    private static final Pattern TOKEN = Pattern.compile("[a-z0-9']+");
    private static final float LN_EPS = 1e-5f;
    private static final float ADAM_B1 = 0.9f;
    private static final float ADAM_B2 = 0.999f;
    private static final float ADAM_EPS = 1e-8f;

    /** Gate block offsets inside the 4H gate vector. */
    private static final int G_IN = 0;
    private static final int G_FORGET = 1;
    private static final int G_CELL = 2;
    private static final int G_OUT = 3;

    // ---- shape ----------------------------------------------------------
    public final int vocabSize;   // includes row 0, the unknown-word row
    public final int bucket;      // hashed character n-gram rows
    public final int embDim;
    public final int hidden;
    public final int layers;
    public final int gramLen;

    // ---- hyperparameters ------------------------------------------------
    public float learningRate = 2.0e-3f;
    public float weightDecay = 1.0e-4f;
    public float gradientClip = 5.0f;
    public float labelSmoothing = 0.05f;
    /** Probability of dropping a whole token, the only regulariser we need. */
    public float tokenDropout = 0.10f;
    /**
     * Logit divisor applied at inference. Training always runs at 1.0 -
     * backward assumes {@code p == sigmoid(logit)} - and the trainer sweeps
     * this afterwards so the 0.40-0.85 band, the only thing that moves a
     * message from L2 to L3, keeps meaningful mass instead of collapsing to
     * 0.0001 / 0.9999.
     */
    public float temperature = 1.0f;
    public long step;

    // ---- parameters -----------------------------------------------------
    private final P embW;        // vocabSize x embDim
    private final P embS;        // bucket x embDim
    private final P[] wGate;     // [layer * 2 + dir] : 4H x in
    private final P[] uGate;     // [layer * 2 + dir] : 4H x H
    private final P[] bGate;     // [layer * 2 + dir] : 4H
    private final P[] lnG;       // [layer] : 2H
    private final P[] lnB;       // [layer] : 2H
    private final P attnV;       // 2H
    private final P headV;       // 2H
    private final P headB;       // 1
    private final List<P> params = new ArrayList<>();
    private final List<String> paramNames = new ArrayList<>();

    private Map<String, Integer> vocab = new HashMap<>();
    private List<String> vocabWords = new ArrayList<>();

    // ---- per-sample caches, rebuilt on every forward ---------------------
    private int t;
    private float[][] layerIn;    // [layer] : t x in, flat
    private float[][] layerOut;   // [layer] : t x 2H, flat
    private float[][] lstmH;      // [layer * 2 + dir] : t x H
    private float[][] lstmC;      // [layer * 2 + dir] : t x H
    private float[][] lstmZ;      // [layer * 2 + dir] : t x 4H
    private float[][] lnMu;       // [layer] : t
    private float[][] lnInv;      // [layer] : t
    private float[] attn;         // t
    private float[] ctx;          // 2H

    public MillenniumNet(int vocabSize, int bucket, int embDim, int hidden, int layers) {
        this(vocabSize, bucket, embDim, hidden, layers, 4);
    }

    public MillenniumNet(int vocabSize, int bucket, int embDim, int hidden, int layers,
                         int gramLen) {
        this.vocabSize = vocabSize;
        this.bucket = bucket;
        this.embDim = embDim;
        this.hidden = hidden;
        this.layers = layers;
        this.gramLen = gramLen;

        this.embW = param("embW", vocabSize * embDim);
        this.embS = param("embS", bucket * embDim);
        this.wGate = new P[layers * 2];
        this.uGate = new P[layers * 2];
        this.bGate = new P[layers * 2];
        this.lnG = new P[layers];
        this.lnB = new P[layers];
        for (int li = 0; li < layers; li++) {
            int in = li == 0 ? embDim : hidden * 2;
            for (int d = 0; d < 2; d++) {
                String dir = d == 0 ? "f" : "b";
                this.wGate[li * 2 + d] = param("wGate[" + li + dir + "]", 4 * hidden * in);
                this.uGate[li * 2 + d] = param("uGate[" + li + dir + "]", 4 * hidden * hidden);
                this.bGate[li * 2 + d] = param("bGate[" + li + dir + "]", 4 * hidden);
            }
            this.lnG[li] = param("lnG[" + li + "]", hidden * 2);
            this.lnB[li] = param("lnB[" + li + "]", hidden * 2);
        }
        this.attnV = param("attnV", hidden * 2);
        this.headV = param("headV", hidden * 2);
        this.headB = param("headB", 1);
    }

    private P param(String name, int n) {
        P p = new P(n);
        this.params.add(p);
        this.paramNames.add(name);
        return p;
    }

    /** Number of parameter groups, in serialisation order. */
    public int groupCount() {
        return this.params.size();
    }

    public String groupName(int group) {
        return this.paramNames.get(group);
    }

    /** Live value array for a group. Mutating it mutates the model. */
    public float[] values(int group) {
        return this.params.get(group).v;
    }

    /** Live gradient accumulator for a group. */
    public float[] grads(int group) {
        return this.params.get(group).g;
    }

    /** Clears AdamW momentum and variance, e.g. before a fine-tune. */
    public void resetOptimiserState() {
        for (P p : this.params) {
            Arrays.fill(p.m, 0f);
            Arrays.fill(p.s, 0f);
        }
        this.step = 0L;
    }

    /** Xavier-uniform weights, forget-gate bias 1, layer norms at identity. */
    public void init(long seed) {
        Random rng = new Random(seed);
        this.embW.fill(rng, 1.0f);
        this.embS.fill(rng, 1.0f);
        for (int li = 0; li < this.layers; li++) {
            int in = li == 0 ? this.embDim : this.hidden * 2;
            for (int d = 0; d < 2; d++) {
                this.wGate[li * 2 + d].fillXavier(rng, in);
                this.uGate[li * 2 + d].fillXavier(rng, this.hidden);
                float[] b = this.bGate[li * 2 + d].v;
                Arrays.fill(b, 0f);
                for (int k = 0; k < this.hidden; k++) b[G_FORGET * this.hidden + k] = 1.0f;
            }
            Arrays.fill(this.lnG[li].v, 1.0f);
            Arrays.fill(this.lnB[li].v, 0f);
        }
        this.attnV.fill(rng, 0.1f);
        this.headV.fill(rng, 0.1f);
    }

    public int parameters() {
        int n = 0;
        for (P p : this.params) n += p.v.length;
        return n;
    }

    /** Serialised size in bytes including header, vocabulary and parameters. */
    public int serialisedBytes() {
        int n = 4 * 8 + 4 * 6 + 8 + 4 + 4 + 4 * this.parameters();
        for (String w : this.vocabWords) {
            n += 2 + w.getBytes(StandardCharsets.UTF_8).length;
        }
        return n;
    }

    /* ------------------------------------------------------------------ */
    /*  Text to tensor                                                     */
    /* ------------------------------------------------------------------ */

    /** Lowercased word tokens, the same character class the trainer uses. */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }

    /** FNV-1a, 32-bit unsigned over UTF-8 bytes. */
    public static int hash32(String s) {
        int h = 0x811C9DC5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xFF);
            h *= 16777619;
        }
        return h;
    }

    /** Hashed character n-gram rows for one token, with boundary markers. */
    public int[] gramRows(String token) {
        String padded = "<" + token + ">";
        int n = this.gramLen;
        int count = padded.length() - n + 1;
        if (count < 1) {
            return new int[]{(int) (Integer.toUnsignedLong(hash32(padded)) % this.bucket)};
        }
        int[] rows = new int[count];
        for (int i = 0; i < count; i++) {
            rows[i] = (int) (Integer.toUnsignedLong(hash32(padded.substring(i, i + n)))
                    % this.bucket);
        }
        return rows;
    }

    /** Installs the trained vocabulary. Row 0 is reserved for unknown words. */
    public void setVocab(List<String> words) {
        Map<String, Integer> map = new HashMap<>(Math.max(16, words.size() * 2));
        for (int i = 0; i < words.size(); i++) map.put(words.get(i), i + 1);
        this.vocab = map;
        this.vocabWords = new ArrayList<>(words);
    }

    /** The vocabulary in row order, excluding the unknown-word row 0. */
    public List<String> vocabWords() {
        return this.vocabWords;
    }

    public int wordRow(String token) {
        Integer id = this.vocab.get(token);
        return id == null ? 0 : id;
    }

    /** One encoded message: an embedding row per token, plus its gram rows. */
    public Sample sample(String text, int maxTokens) {
        List<String> tokens = tokenize(text);
        int from = Math.max(0, tokens.size() - maxTokens);
        int n = tokens.size() - from;
        Sample s = new Sample(n, tokens.subList(from, tokens.size()));
        for (int i = 0; i < n; i++) {
            String tok = s.tokens.get(i);
            s.ids[i] = wordRow(tok);
            s.grams[i] = gramRows(tok);
        }
        return s;
    }

    /** An encoded message ready for {@link #forward}. */
    public static final class Sample {
        public final int[] ids;
        public final int[][] grams;
        public final List<String> tokens;

        Sample(int n, List<String> tokens) {
            this.ids = new int[n];
            this.grams = new int[n][];
            this.tokens = new ArrayList<>(tokens);
        }

        public int length() {
            return this.ids.length;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Forward                                                            */
    /* ------------------------------------------------------------------ */

    /** Runs one sample forward and caches what backward needs. */
    public float forward(Sample s, boolean train) {
        int H = this.hidden;
        int w = H * 2;
        this.t = s.length();
        // No [a-z0-9']+ tokens at all: pure punctuation, an emoji run, or a
        // script the tokenizer does not cover. There is no evidence here to
        // weigh, and 0.5 was not a neutral answer - it landed inside the
        // 0.40-0.85 band, so these messages were escalated to L3 and could be
        // flagged by rule 2 on the strength of the surrounding lines alone,
        // having said nothing themselves. Return the clear end, the same
        // fail-open direction the rest of this layer takes.
        if (this.t == 0) return 0f;
        allocate();

        // ---- embeddings: word row + mean of gram rows --------------------
        float[] emb = this.layerIn[0];
        boolean[] kept = this.kept;
        for (int i = 0; i < this.t; i++) {
            int id = Math.min(s.ids[i], this.vocabSize - 1);
            kept[i] = !(train && this.tokenDropout > 0f
                    && RNG.get().nextFloat() < this.tokenDropout);
            int xb = i * this.embDim;
            Arrays.fill(emb, xb, xb + this.embDim, 0f);
            if (!kept[i]) continue;
            int wbase = id * this.embDim;
            for (int k = 0; k < this.embDim; k++) emb[xb + k] += this.embW.v[wbase + k];
            int[] grams = s.grams[i];
            if (grams.length > 0) {
                float inv = 1f / grams.length;
                for (int g : grams) {
                    int sbase = g * this.embDim;
                    for (int k = 0; k < this.embDim; k++) {
                        emb[xb + k] += this.embS.v[sbase + k] * inv;
                    }
                }
            }
        }

        // ---- stacked bidirectional LSTM + layer norm ---------------------
        for (int li = 0; li < this.layers; li++) {
            int in = li == 0 ? this.embDim : w;
            // layer li reads the previous layer's layer-normed output; layer 0
            // reads the embeddings filled above
            if (li > 0) this.layerIn[li] = this.layerOut[li - 1];
            runLstm(li, 0, in);
            runLstm(li, 1, in);
            float[] mu = this.lnMu[li];
            float[] inv = this.lnInv[li];
            float[] gamma = this.lnG[li].v;
            float[] beta = this.lnB[li].v;
            float[] hF = this.lstmH[li * 2];
            float[] hB = this.lstmH[li * 2 + 1];
            float[] out = this.layerOut[li];
            for (int i = 0; i < this.t; i++) {
                int ob = i * w;
                System.arraycopy(hF, i * H, out, ob, H);
                System.arraycopy(hB, i * H, out, ob + H, H);
                float mean = 0f;
                for (int k = 0; k < w; k++) mean += out[ob + k];
                mean /= w;
                float var = 0f;
                for (int k = 0; k < w; k++) {
                    float dv = out[ob + k] - mean;
                    var += dv * dv;
                }
                var /= w;
                float invs = (float) (1.0 / Math.sqrt(var + LN_EPS));
                mu[i] = mean;
                inv[i] = invs;
                for (int k = 0; k < w; k++) {
                    out[ob + k] = gamma[k] * (out[ob + k] - mean) * invs + beta[k];
                }
            }
        }

        // ---- attention pooling -------------------------------------------
        float[] last = this.layerOut[this.layers - 1];
        this.attn = new float[this.t];
        this.ctx = new float[w];
        float best = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < this.t; i++) {
            float sc = 0f;
            int ob = i * w;
            for (int k = 0; k < w; k++) sc += this.attnV.v[k] * last[ob + k];
            this.attn[i] = sc;
            if (sc > best) best = sc;
        }
        float sum = 0f;
        for (int i = 0; i < this.t; i++) {
            this.attn[i] = (float) Math.exp(this.attn[i] - best);
            sum += this.attn[i];
        }
        for (int i = 0; i < this.t; i++) this.attn[i] /= sum;
        for (int i = 0; i < this.t; i++) {
            float a = this.attn[i];
            int ob = i * w;
            for (int k = 0; k < w; k++) this.ctx[k] += a * last[ob + k];
        }

        // ---- head ---------------------------------------------------------
        return sigmoid(logit() / this.temperature);
    }

    private float logit() {
        float z = this.headB.v[0];
        for (int k = 0; k < this.ctx.length; k++) z += this.headV.v[k] * this.ctx[k];
        return z;
    }

    /** Inference on raw text. Safe to call from several threads. */
    public float probability(String text, int maxTokens) {
        Sample s = sample(text, maxTokens);
        synchronized (this) {
            return forward(s, false);
        }
    }

    /** One LSTM direction of one layer over the cached layer input. */
    private void runLstm(int li, int d, int in) {
        int idx = li * 2 + d;
        int H = this.hidden;
        float[] W = this.wGate[idx].v;
        float[] U = this.uGate[idx].v;
        float[] b = this.bGate[idx].v;
        float[] hh = this.lstmH[idx];
        float[] cc = this.lstmC[idx];
        float[] zz = this.lstmZ[idx];
        float[] src = this.layerIn[li];
        float[] prevH = new float[H];
        float[] prevC = new float[H];
        for (int n = 0; n < this.t; n++) {
            int i = d == 0 ? n : this.t - 1 - n;
            int xb = i * in;
            int zb = i * 4 * H;
            for (int g = 0; g < 4 * H; g++) {
                float acc = b[g];
                int wb = g * in;
                for (int k = 0; k < in; k++) acc += W[wb + k] * src[xb + k];
                int ub = g * H;
                for (int k = 0; k < H; k++) acc += U[ub + k] * prevH[k];
                zz[zb + g] = acc;
            }
            int hb = i * H;
            for (int k = 0; k < H; k++) {
                float ig = sigmoid(zz[zb + G_IN * H + k]);
                float fg = sigmoid(zz[zb + G_FORGET * H + k]);
                float gg = tanh(zz[zb + G_CELL * H + k]);
                float og = sigmoid(zz[zb + G_OUT * H + k]);
                float c = fg * prevC[k] + ig * gg;
                cc[hb + k] = c;
                hh[hb + k] = og * tanh(c);
            }
            System.arraycopy(hh, hb, prevH, 0, H);
            System.arraycopy(cc, hb, prevC, 0, H);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Backward                                                           */
    /* ------------------------------------------------------------------ */

    /** Accumulates gradients for one sample. {@code p} is the forward output. */
    public void backward(Sample s, float p, float y) {
        if (this.t == 0) return;
        int H = this.hidden;
        int w = H * 2;
        float target = y * (1f - this.labelSmoothing) + 0.5f * this.labelSmoothing;
        float dLogit = p - target;

        // ---- head ---------------------------------------------------------
        for (int k = 0; k < w; k++) this.headV.g[k] += dLogit * this.ctx[k];
        this.headB.g[0] += dLogit;

        // ---- context ------------------------------------------------------
        float[] dCtx = new float[w];
        for (int k = 0; k < w; k++) dCtx[k] = dLogit * this.headV.v[k];

        // ---- attention pooling --------------------------------------------
        float[] last = this.layerOut[this.layers - 1];
        float[] dY = new float[this.t * w];
        float weighted = 0f;
        float[] da = new float[this.t];
        for (int i = 0; i < this.t; i++) {
            float acc = 0f;
            int ob = i * w;
            for (int k = 0; k < w; k++) acc += last[ob + k] * dCtx[k];
            da[i] = acc;
            weighted += this.attn[i] * acc;
        }
        for (int i = 0; i < this.t; i++) {
            float ds = this.attn[i] * (da[i] - weighted);
            int ob = i * w;
            for (int k = 0; k < w; k++) {
                this.attnV.g[k] += ds * last[ob + k];
                // two paths into the same timestep: the attention weight, and
                // the direct share of the pooled vector
                dY[ob + k] += ds * this.attnV.v[k] + this.attn[i] * dCtx[k];
            }
        }

        // ---- layers, last to first ----------------------------------------
        for (int li = this.layers - 1; li >= 0; li--) {
            int in = li == 0 ? this.embDim : w;
            float[] dConcat = new float[this.t * w];
            float[] gamma = this.lnG[li].v;
            for (int i = 0; i < this.t; i++) {
                float mean = this.lnMu[li][i];
                float inv = this.lnInv[li][i];
                int ob = i * w;
                float[] xhat = new float[w];
                for (int k = 0; k < w; k++) {
                    xhat[k] = (rawConcat(li, i, k) - mean) * inv;
                }
                float m1 = 0f;
                float m2 = 0f;
                for (int k = 0; k < w; k++) {
                    float dy = dY[ob + k];
                    this.lnG[li].g[k] += dy * xhat[k];
                    this.lnB[li].g[k] += dy;
                    float dxhat = dy * gamma[k];
                    m1 += dxhat;
                    m2 += dxhat * xhat[k];
                }
                m1 /= w;
                m2 /= w;
                for (int k = 0; k < w; k++) {
                    float dxhat = dY[ob + k] * gamma[k];
                    dConcat[ob + k] = inv * (dxhat - m1 - xhat[k] * m2);
                }
            }
            float[] dhF = new float[this.t * H];
            float[] dhB = new float[this.t * H];
            for (int i = 0; i < this.t; i++) {
                System.arraycopy(dConcat, i * w, dhF, i * H, H);
                System.arraycopy(dConcat, i * w + H, dhB, i * H, H);
            }
            float[] dX = new float[this.t * in];
            lstmBackward(li, 0, in, dhF, dX);
            lstmBackward(li, 1, in, dhB, dX);
            if (li == 0) {
                // gradient into the embedding layer
                for (int i = 0; i < this.t; i++) {
                    if (!this.kept[i]) continue;
                    int id = Math.min(s.ids[i], this.vocabSize - 1);
                    int[] grams = s.grams[i];
                    float invg = grams.length > 0 ? 1f / grams.length : 0f;
                    int xb = i * this.embDim;
                    int wbase = id * this.embDim;
                    for (int k = 0; k < this.embDim; k++) {
                        float dv = dX[xb + k];
                        this.embW.g[wbase + k] += dv;
                        for (int g : grams) this.embS.g[g * this.embDim + k] += dv * invg;
                    }
                }
            } else {
                dY = dX;
            }
        }
    }

    /** The concatenated pre-layer-norm LSTM output of layer li at step i. */
    private float rawConcat(int li, int i, int k) {
        return k < this.hidden
                ? this.lstmH[li * 2][i * this.hidden + k]
                : this.lstmH[li * 2 + 1][i * this.hidden + (k - this.hidden)];
    }

    /** Backprop through one LSTM direction, adding into {@code dX}. */
    private void lstmBackward(int li, int d, int in, float[] dhIn, float[] dX) {
        int H = this.hidden;
        int idx = li * 2 + d;
        float[] W = this.wGate[idx].v;
        float[] U = this.uGate[idx].v;
        float[] hh = this.lstmH[idx];
        float[] cc = this.lstmC[idx];
        float[] zz = this.lstmZ[idx];
        float[] src = this.layerIn[li];
        float[] dz = new float[4 * H];
        float[] dhPrev = new float[H];
        float[] dcNext = new float[H];
        float[] cPrev = new float[H];
        float[] hPrev = new float[H];
        for (int n = 0; n < this.t; n++) {
            int i = d == 0 ? this.t - 1 - n : n;
            int prev = d == 0 ? i - 1 : i + 1;
            Arrays.fill(cPrev, 0f);
            Arrays.fill(hPrev, 0f);
            if (prev >= 0 && prev < this.t) {
                System.arraycopy(cc, prev * H, cPrev, 0, H);
                System.arraycopy(hh, prev * H, hPrev, 0, H);
            }
            int zb = i * 4 * H;
            int hb = i * H;
            for (int k = 0; k < H; k++) {
                float ig = sigmoid(zz[zb + G_IN * H + k]);
                float fg = sigmoid(zz[zb + G_FORGET * H + k]);
                float gg = tanh(zz[zb + G_CELL * H + k]);
                float og = sigmoid(zz[zb + G_OUT * H + k]);
                float tc = tanh(cc[hb + k]);
                float dh = dhIn[hb + k] + dhPrev[k];
                float dOut = dh * tc;
                float dc = dh * og * (1f - tc * tc) + dcNext[k];
                float dIn = dc * gg;
                float dForget = dc * cPrev[k];
                float dCell = dc * ig;
                dz[G_IN * H + k] = dIn * ig * (1f - ig);
                dz[G_FORGET * H + k] = dForget * fg * (1f - fg);
                dz[G_CELL * H + k] = dCell * (1f - gg * gg);
                dz[G_OUT * H + k] = dOut * og * (1f - og);
                dcNext[k] = dc * fg;
            }
            Arrays.fill(dhPrev, 0f);
            int xb = i * in;
            for (int g = 0; g < 4 * H; g++) {
                float gv = dz[g];
                this.bGate[idx].g[g] += gv;
                int wb = g * in;
                int ub = g * H;
                for (int k = 0; k < in; k++) this.wGate[idx].g[wb + k] += gv * src[xb + k];
                for (int k = 0; k < H; k++) {
                    this.uGate[idx].g[ub + k] += gv * hPrev[k];
                    dhPrev[k] += U[ub + k] * gv;
                }
                for (int k = 0; k < in; k++) dX[xb + k] += W[wb + k] * gv;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Optimiser                                                          */
    /* ------------------------------------------------------------------ */

    public void zeroGrads() {
        for (P p : this.params) Arrays.fill(p.g, 0f);
    }

    /** Global-norm gradient clipping, then one AdamW update. */
    public void step(float lr) {
        double sq = 0.0;
        for (P p : this.params) {
            for (float g : p.g) sq += (double) g * g;
        }
        double norm = Math.sqrt(sq);
        float scale = 1f;
        if (this.gradientClip > 0f && norm > this.gradientClip) {
            scale = (float) (this.gradientClip / (norm + 1e-6));
        }
        this.step++;
        float b1t = 1f - (float) Math.pow(ADAM_B1, this.step);
        float b2t = 1f - (float) Math.pow(ADAM_B2, this.step);
        for (P p : this.params) {
            for (int i = 0; i < p.v.length; i++) {
                float g = p.g[i] * scale;
                p.m[i] = ADAM_B1 * p.m[i] + (1f - ADAM_B1) * g;
                p.s[i] = ADAM_B2 * p.s[i] + (1f - ADAM_B2) * g * g;
                float mh = p.m[i] / b1t;
                float sh = p.s[i] / b2t;
                p.v[i] -= lr * (mh / ((float) Math.sqrt(sh) + ADAM_EPS)
                        + this.weightDecay * p.v[i]);
            }
        }
    }

    /** Forward, backward and one update for a single labelled sample. */
    public float trainSample(Sample s, float y, float lr) {
        zeroGrads();
        float p = forward(s, true);
        backward(s, p, y);
        step(lr);
        return p;
    }

    private boolean[] kept = new boolean[0];

    /* ------------------------------------------------------------------ */
    /*  Caches                                                             */
    /* ------------------------------------------------------------------ */

    private void allocate() {
        int H = this.hidden;
        int w = H * 2;
        this.kept = new boolean[this.t];
        this.layerIn = new float[this.layers][];
        this.layerOut = new float[this.layers][];
        this.lnMu = new float[this.layers][];
        this.lnInv = new float[this.layers][];
        this.lstmH = new float[this.layers * 2][];
        this.lstmC = new float[this.layers * 2][];
        this.lstmZ = new float[this.layers * 2][];
        for (int li = 0; li < this.layers; li++) {
            int in = li == 0 ? this.embDim : w;
            this.layerIn[li] = new float[this.t * in];
            this.layerOut[li] = new float[this.t * w];
            this.lnMu[li] = new float[this.t];
            this.lnInv[li] = new float[this.t];
            for (int d = 0; d < 2; d++) {
                this.lstmH[li * 2 + d] = new float[this.t * H];
                this.lstmC[li * 2 + d] = new float[this.t * H];
                this.lstmZ[li * 2 + d] = new float[this.t * 4 * H];
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Persistence                                                        */
    /* ------------------------------------------------------------------ */

    public void save(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(FORMAT);
        out.writeInt(this.vocabSize);
        out.writeInt(this.bucket);
        out.writeInt(this.embDim);
        out.writeInt(this.hidden);
        out.writeInt(this.layers);
        out.writeInt(this.gramLen);
        out.writeFloat(this.learningRate);
        out.writeFloat(this.weightDecay);
        out.writeFloat(this.gradientClip);
        out.writeFloat(this.labelSmoothing);
        out.writeFloat(this.tokenDropout);
        out.writeFloat(0f);
        out.writeLong(this.step);
        out.writeFloat(this.temperature);
        out.writeInt(this.vocabWords.size());
        for (String w : this.vocabWords) out.writeUTF(w);
        for (P p : this.params) {
            for (float f : p.v) out.writeFloat(f);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Loading from the jar                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Loads a model embedded as a plugin resource, or null if it is missing,
     * truncated or built for a different architecture.
     *
     * <p>A null return is the fail-open path everywhere it is used: the caller
     * disables that layer and the message passes. It is never an exception,
     * because a broken model file must not stop the server from starting.
     *
     * <p>The header is read twice - once to size the instance, once to fill it -
     * which is why the resource is pulled into a byte array first. The model is
     * under 2 MB, so holding it whole costs nothing and avoids leaving a
     * partially-read stream behind on a malformed file.
     */
    public static MillenniumNet loadResource(String resource) {
        byte[] raw;
        try {
            raw = readResource(resource);
        } catch (IOException ex) {
            return null;
        }
        if (raw == null) return null;
        int vocabSize;
        int bucket;
        int embDim;
        int hidden;
        int layers;
        int gramLen;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (in.readInt() != MAGIC) return null;
            if (in.readInt() != FORMAT) return null;
            vocabSize = in.readInt();
            bucket = in.readInt();
            embDim = in.readInt();
            hidden = in.readInt();
            layers = in.readInt();
            gramLen = in.readInt();
        } catch (IOException ex) {
            return null;
        }
        if (vocabSize < 1 || bucket < 1 || embDim < 1 || hidden < 1 || layers < 1
                || gramLen < 1) {
            return null;
        }
        MillenniumNet net = new MillenniumNet(vocabSize, bucket, embDim, hidden, layers,
                gramLen);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            net.load(in);
        } catch (IOException ex) {
            return null;
        }
        return net;
    }

    private static byte[] readResource(String resource) throws IOException {
        try (InputStream in = MillenniumNet.class.getResourceAsStream(resource)) {
            if (in == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 21);
            byte[] chunk = new byte[1 << 16];
            int read;
            while ((read = in.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        }
    }

    public void load(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("not a MillenniumNet file");
        if (in.readInt() != FORMAT) throw new IOException("unsupported format revision");
        if (in.readInt() != this.vocabSize || in.readInt() != this.bucket
                || in.readInt() != this.embDim || in.readInt() != this.hidden
                || in.readInt() != this.layers || in.readInt() != this.gramLen) {
            throw new IOException("architecture mismatch");
        }
        this.learningRate = in.readFloat();
        this.weightDecay = in.readFloat();
        this.gradientClip = in.readFloat();
        this.labelSmoothing = in.readFloat();
        this.tokenDropout = in.readFloat();
        in.readFloat();
        this.step = in.readLong();
        this.temperature = in.readFloat();
        int wordCount = in.readInt();
        if (wordCount < 0 || wordCount > 1_000_000) throw new IOException("bad vocab size");
        List<String> words = new ArrayList<>(wordCount);
        for (int i = 0; i < wordCount; i++) words.add(in.readUTF());
        setVocab(words);
        for (P p : this.params) {
            for (int i = 0; i < p.v.length; i++) p.v[i] = in.readFloat();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Bits                                                               */
    /* ------------------------------------------------------------------ */

    private static final ThreadLocal<Random> RNG = ThreadLocal.withInitial(() -> new Random(7L));

    private static float sigmoid(float x) {
        return (float) (1.0 / (1.0 + Math.exp(-x)));
    }

    private static float tanh(float x) {
        return (float) Math.tanh(x);
    }

    private static final class P {
        final float[] v;
        final float[] g;
        final float[] m;
        final float[] s;

        P(int n) {
            this.v = new float[n];
            this.g = new float[n];
            this.m = new float[n];
            this.s = new float[n];
        }

        void fill(Random rng, float scale) {
            for (int i = 0; i < this.v.length; i++) {
                this.v[i] = (rng.nextFloat() * 2f - 1f) * scale;
            }
        }

        void fillXavier(Random rng, int fanIn) {
            float limit = (float) Math.sqrt(6.0 / (fanIn + this.v.length / 4.0));
            for (int i = 0; i < this.v.length; i++) {
                this.v[i] = (rng.nextFloat() * 2f - 1f) * limit;
            }
        }
    }
}
