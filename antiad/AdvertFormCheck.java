package com.coltcore.core.modules;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads lines on stdin and prints {@code <form>\t<score>} for each, where
 * {@code form} is {@link ChatGuardModule#advertForm(String)} and {@code score}
 * is the shipped model's probability of that form at the pipeline's 24-token
 * window.
 *
 * <p>Deliberately calls the real package-private {@code advertForm} rather than
 * a copy, so this measures the method that ships. Run the same corpus through
 * two classpaths - the frozen pre-change classes and the built ones - and diff
 * the two outputs: that is the regression test for a change to Stage 0, which
 * every other layer sits behind.
 *
 * <pre>
 *   java -cp "&lt;classes&gt;;antiad;&lt;deps&gt;" com.coltcore.core.modules.AdvertFormCheck /antiad.m5.bin &lt; in &gt; out
 * </pre>
 */
public final class AdvertFormCheck {

    public static void main(String[] args) throws Exception {
        String resource = args.length > 0 ? args[0] : "/antiad.m5.bin";
        MillenniumNet model = MillenniumNet.loadResource(resource);
        if (model == null) {
            System.err.println("could not load " + resource);
            System.exit(1);
        }
        long lines = 0;
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                String form = ChatGuardModule.advertForm(line);
                float score = model.probability(form, 24);
                System.out.println(form + "\t" + score);
                lines++;
            }
        }
        System.err.println("scored " + lines + " lines");
    }
}
