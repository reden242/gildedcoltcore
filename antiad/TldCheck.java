import com.coltcore.core.modules.FastTextModel;

import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Manual check: obscure-TLD advertising vs obscure-TLD benign mention. */
public final class TldCheck {

    public static void main(String[] args) throws Exception {
        FastTextModel model;
        try (InputStream in = Files.newInputStream(Path.of(args[0]))) {
            java.lang.reflect.Method read = FastTextModel.class
                    .getDeclaredMethod("read", DataInputStream.class);
            read.setAccessible(true);
            model = (FastTextModel) read.invoke(null, new DataInputStream(in));
        }
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
        for (String[] sample : samples) {
            System.out.printf("%-16s %.3f  %s%n", sample[0],
                    model.advertisingProbability(sample[1]), sample[1]);
        }
    }
}
