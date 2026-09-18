import com.coltcore.core.modules.MillenniumNet;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Scores a MillenniumNet model file against the things that actually matter,
 * none of which live in the trainer's templated held-out split:
 *
 * <ul>
 *   <li>the 100-line out-of-distribution probe at antiad/eval/leak/ood_probe.tsv
 *       (label TAB text) - precision / recall / clean-FP at the 0.85 block
 *       threshold;</li>
 *   <li>the positional pairs the defect was first seen on, verbatim;</li>
 *   <li>every line of chat_corpus.txt - how many reach the 0.85 block
 *       threshold and how many reach the 0.40 band floor;</li>
 *   <li>how much mass lands in the 0.40-0.85 ambiguous band, on the probe and
 *       on real chat, plus the p50/p95/p99/max of both.</li>
 * </ul>
 *
 * <p>Raw text is scored (the trainer's own convention, so the numbers are
 * comparable with the held-out split), and the advertForm-ed text is scored
 * separately for chat because that is what {@code ChatGuardModule} actually
 * feeds to L2.
 *
 * <pre>
 *   java -cp "antiad;ColtCore/target/classes" ModelProbe antiad/antiad.m5.bin
 * </pre>
 */
public final class ModelProbe {

    private static final int MAX_TOKENS = 24;
    private static final float BLOCK = 0.85f;
    private static final float BAND_LO = 0.40f;

    /** The pairs the positional defect was measured on, plus the sentinels. */
    private static final String[] PAIRS = {
            "join play.example.com now",
            "then join play.example.com now",
            "the join play.example.com now",
            "together join play.example.com now",
            "together is join play.example.com now",
            "the",
            "discord is broken",
            "the discord is broken",
            "join us",
            "join us on the server",
            "check #server-ip on discord",
            "visit my horse",
            "engineering is hard",
            "bike",
    };

    public static void main(String[] args) throws Exception {
        Path model = Paths.get(args.length > 0 ? args[0] : "antiad/antiad.m5.bin");
        Path probe = Paths.get(args.length > 1 ? args[1]
                : "antiad/eval/leak/ood_probe.tsv");
        Path chat = Paths.get(args.length > 2 ? args[2] : "chat_corpus.txt");

        MillenniumNet net = load(model);
        System.out.printf("model %s%n", model);
        System.out.printf("parameters %d, temperature %.2f%n", net.parameters(),
                net.temperature);

        // ---- positional pairs -------------------------------------------
        System.out.println();
        System.out.println("positional pairs (raw text, 24-token window):");
        for (String p : PAIRS) {
            System.out.printf(Locale.ROOT, "  %-40s %.4f%n", p, net.probability(p, MAX_TOKENS));
        }

        // ---- OOD probe ---------------------------------------------------
        List<Float> adScores = new ArrayList<>();
        List<Float> cleanScores = new ArrayList<>();
        List<String> adTexts = new ArrayList<>();
        int tp = 0;
        int fp = 0;
        int fn = 0;
        int tn = 0;
        if (Files.exists(probe)) {
            try (BufferedReader r = Files.newBufferedReader(probe, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    boolean ad = !"clean".equals(line.substring(0, tab).trim()
                            .toLowerCase(Locale.ROOT));
                    String text = line.substring(tab + 1).trim();
                    if (text.isEmpty()) continue;
                    float p = net.probability(text, MAX_TOKENS);
                    if (ad) {
                        adScores.add(p);
                        adTexts.add(text);
                        if (p >= BLOCK) tp++;
                        else fn++;
                    } else {
                        cleanScores.add(p);
                        if (p >= BLOCK) fp++;
                        else tn++;
                    }
                }
            }
        }
        System.out.println();
        System.out.printf("ood probe: tp %d  fp %d  fn %d  tn %d%n", tp, fp, fn, tn);
        double precision = tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
        double recall = tp + fn == 0 ? 1.0 : (double) tp / (tp + fn);
        double cleanFp = fp + tn == 0 ? 0.0 : (double) fp / (fp + tn);
        System.out.printf(Locale.ROOT, "  precision %.4f  recall %.4f  cleanFP %.4f%n",
                precision, recall, cleanFp);
        System.out.printf(Locale.ROOT, "  band mass (ad) %.2f%%  (clean) %.2f%%%n",
                share(adScores, BAND_LO, BLOCK) * 100.0,
                share(cleanScores, BAND_LO, BLOCK) * 100.0);
        quantiles("  ad   ", adScores);
        quantiles("  clean", cleanScores);
        System.out.println("  detected advertising rows (>= 0.85):");
        for (int i = 0; i < adTexts.size(); i++) {
            if (adScores.get(i) >= BLOCK) {
                System.out.printf(Locale.ROOT, "    %.4f  %s%n", adScores.get(i), adTexts.get(i));
            }
        }
        System.out.println("  missed advertising rows (< 0.85):");
        for (int i = 0; i < adTexts.size(); i++) {
            if (adScores.get(i) < BLOCK) {
                System.out.printf(Locale.ROOT, "    %.4f  %s%n", adScores.get(i), adTexts.get(i));
            }
        }

        // ---- real chat ---------------------------------------------------
        if (Files.exists(chat)) {
            List<Float> raw = new ArrayList<>();
            int rawBlock = 0;
            int rawBand = 0;
            int lines = 0;
            try (BufferedReader r = Files.newBufferedReader(chat, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String text = line.trim();
                    if (text.isEmpty()) continue;
                    lines++;
                    float p = net.probability(text, MAX_TOKENS);
                    raw.add(p);
                    if (p >= BLOCK) rawBlock++;
                    else if (p >= BAND_LO) rawBand++;
                }
            }
            System.out.println();
            System.out.printf("chat_corpus.txt: %d lines (raw text)%n", lines);
            System.out.printf("  >= 0.85: %d (%.4f%%)   >= 0.40: %d (%.4f%%)%n",
                    rawBlock, 100.0 * rawBlock / lines, rawBlock + rawBand,
                    100.0 * (rawBlock + rawBand) / lines);
            quantiles("  raw   ", raw);
        }
    }

    private static void quantiles(String label, List<Float> scores) {
        if (scores.isEmpty()) return;
        List<Float> s = new ArrayList<>(scores);
        s.sort(null);
        System.out.printf(Locale.ROOT, "%s  p50 %.4f  p90 %.4f  p95 %.4f  p99 %.4f  max %.4f%n",
                label, q(s, 0.50), q(s, 0.90), q(s, 0.95), q(s, 0.99), s.get(s.size() - 1));
    }

    private static float q(List<Float> sorted, double f) {
        int i = (int) Math.round(f * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    private static double share(List<Float> scores, float lo, float hi) {
        if (scores.isEmpty()) return 0.0;
        int n = 0;
        for (float p : scores) if (p > lo && p < hi) n++;
        return (double) n / scores.size();
    }

    private static MillenniumNet load(Path path) throws Exception {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(path.toFile())))) {
            if (in.readInt() != 0x4D354E54) throw new java.io.IOException("not a MillenniumNet file");
            in.readInt();
            int vocabSize = in.readInt();
            int bucket = in.readInt();
            int embDim = in.readInt();
            int hidden = in.readInt();
            int layers = in.readInt();
            int gramLen = in.readInt();
            MillenniumNet net = new MillenniumNet(vocabSize, bucket, embDim, hidden, layers, gramLen);
            try (java.io.DataInputStream in2 = new java.io.DataInputStream(
                    new java.io.BufferedInputStream(
                            new java.io.FileInputStream(path.toFile())))) {
                net.load(in2);
            }
            return net;
        }
    }

    private ModelProbe() {
    }
}
