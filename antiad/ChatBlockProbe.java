import com.coltcore.core.modules.MillenniumNet;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Scores every chat line raw (what the L3 scan-all path sees) and prints every
 *  row at or above the 0.40 band floor, so the hits can be named and judged.
 *
 * <pre>
 *   java -cp "antiad;ColtCore/target/classes" ChatBlockProbe antiad/antiad.m5.bin chat_corpus.txt
 * </pre>
 */
public final class ChatBlockProbe {

    private static final int MAX_TOKENS = 24;
    private static final float BLOCK = 0.85f;
    private static final float BAND_LO = 0.40f;

    public static void main(String[] args) throws Exception {
        Path model = Paths.get(args[0]);
        Path chat = Paths.get(args.length > 1 ? args[1] : "chat_corpus.txt");
        MillenniumNet net = load(model);
        int lines = 0;
        int block = 0;
        int band = 0;
        try (BufferedReader r = Files.newBufferedReader(chat, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                String text = line.trim();
                if (text.isEmpty()) continue;
                lines++;
                float p = net.probability(text, MAX_TOKENS);
                if (p >= BLOCK) {
                    block++;
                    System.out.printf(Locale.ROOT, "BLOCK  %.4f  %s%n", p, text);
                } else if (p >= BAND_LO) {
                    band++;
                    System.out.printf(Locale.ROOT, "band   %.4f  %s%n", p, text);
                }
            }
        }
        System.out.printf(Locale.ROOT, "%d lines  block %d (%.4f%%)  band %d (%.4f%%)%n",
                lines, block, 100.0 * block / lines, band, 100.0 * band / lines);
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

    private ChatBlockProbe() {
    }
}
