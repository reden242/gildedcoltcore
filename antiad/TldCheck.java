import com.coltcore.core.modules.MillenniumNet;

/**
 * Manual smoke check: obscure-TLD advertising vs obscure-TLD benign mention.
 *
 * <p>Run against the compiled ColtCore classes plus the resource directory so
 * the loader finds the model the same way the plugin does:
 *
 * <pre>
 *   mvn -f ColtCore/pom.xml compile
 *   java -cp "ColtCore/target/classes;antiad" TldCheck /antiad.m5.bin
 * </pre>
 *
 * <p>This is a sanity check on the shipped binary, not a gate. The measured
 * numbers behind the thresholds live in {@code TrainMillennium eval}, which
 * runs the held-out split; these twelve lines only confirm that the file the
 * plugin actually loads behaves like the file that was trained.
 */
public final class TldCheck {

    public static void main(String[] args) {
        String resource = args.length > 0 ? args[0] : "/antiad.m5.bin";
        MillenniumNet model = MillenniumNet.loadResource(resource);
        if (model == null) {
            System.err.println("could not load " + resource
                    + " - is the resource directory on the classpath?");
            System.exit(1);
        }
        System.out.printf("loaded %s: %d parameters, %.2f MB, %d words, temperature %.2f%n",
                resource, model.parameters(), model.serialisedBytes() / 1048576.0D,
                model.vocabWords().size(), model.temperature);

        String[][] samples = {
            {"AD rare tld", "join coolpvp.zip"},
            {"AD rare tld", "come play minescape.museum"},
            {"AD rare tld", "server ip is dragonmc.abbott"},
            {"AD rare tld", "free ranks at funserver.nra"},
            {"AD obfuscated", "join anarchy dot museum"},
            {"AD obfuscated", "server ip is d r a g o n m c . m u s e u m"},
            {"CLEAN rare tld", "the docs at papermc.museum explain it"},
            {"CLEAN rare tld", "reddit.zw is a bad website lol"},
            {"CLEAN rare tld", "is github.pnc down for anyone else"},
            {"CLEAN plain", "anyone want to trade diamonds"},
            {"CLEAN plain", "gg wp that was a good fight"},
            {"CLEAN mention", "i saw a thread on stackoverflow.abbott"},
        };

        // Same 24-token window the pipeline scores with, so what is printed here
        // is what layer 2 decides on.
        for (String[] sample : samples) {
            System.out.printf("%-16s %.3f  %s%n", sample[0],
                    model.probability(sample[1], 24), sample[1]);
        }
        System.out.println("expect: AD lines at or above 0.85, CLEAN lines below 0.40.");
    }
}
