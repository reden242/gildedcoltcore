import com.coltcore.core.modules.MillenniumNet;

/**
 * Prints the raw model probability for inputs the tokenizer reduces to zero
 * tokens, plus a couple of ordinary controls.
 *
 * <p>Run it against the frozen pre-change classes and the built ones and diff:
 * the only rows that may move are the zero-token ones. Any ordinary line that
 * changes is a regression, because the {@code t == 0} branch cannot be reached
 * when at least one token was produced.
 *
 * <pre>
 *   java -cp "&lt;classes&gt;;antiad;&lt;deps&gt;" ZeroTokenCheck /antiad.m5.bin
 * </pre>
 */
public final class ZeroTokenCheck {

    public static void main(String[] args) {
        String resource = args.length > 0 ? args[0] : "/antiad.m5.bin";
        MillenniumNet model = MillenniumNet.loadResource(resource);
        if (model == null) {
            System.err.println("could not load " + resource);
            System.exit(1);
        }
        String[] inputs = {
            "", ".", "!!!", "...", "?????", "@#$%^&*()", "-_-", ":)",
            "!", "😀😀😀", "你好加入我的服务器", "ｊｏｉｎ", "𝐩𝐨𝐢𝐧",
            "'''", "'",
            "gg wp", "join play.coolpvp.xyz for free ranks", "the",
        };
        for (String s : inputs) {
            System.out.printf("%-34s %.6f%n", "\"" + s + "\"", model.probability(s, 24));
        }
    }
}
