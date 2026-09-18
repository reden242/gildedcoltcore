package com.coltcore.core.modules;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads chat lines on stdin and prints the ones the shipped L1 regex would flag,
 * one per line, so that a corpus builder never mines an advert as a negative.
 *
 * <p>Deliberately calls the real package-private {@link ChatGuardModule#advertHit}
 * on a real instance rather than re-implementing the pattern, so the filter is
 * the method that ships. It is treated as a coarse first pass and not as the
 * definition of an advert - an agent is fixing the IPv4 holes right now - so the
 * corpus builder applies its own dotted-host, IPv4, host:port and invite rules
 * on top and keeps an allowlist for the things a dot-regex over-triggers on.
 *
 * <pre>
 *   java -cp "&lt;classes&gt;;antiad;antiad/filter;&lt;deps&gt;" \
 *        com.coltcore.core.modules.ChatMineFilter &lt; chat_corpus.txt &gt; flagged.txt
 * </pre>
 */
public final class ChatMineFilter {

    public static void main(String[] args) throws Exception {
        ChatGuardModule module = new ChatGuardModule(null);
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                String hit;
                try {
                    hit = module.advertHit(line);
                } catch (RuntimeException e) {
                    System.out.println("!" + e.getClass().getSimpleName() + "!" + line);
                    continue;
                }
                if (hit != null) {
                    System.out.println(line);
                }
            }
        }
    }

    private ChatMineFilter() {
    }
}
