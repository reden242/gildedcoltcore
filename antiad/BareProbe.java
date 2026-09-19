package com.coltcore.core.modules;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * §9.3 gate for the F1 leet patch. Calls the shipped
 * {@link ChatGuardModule#advertForm}, {@link TextFeatures#unleet} and the
 * 3-arg {@code bareAddress} — the same union {@code advertHit} performs —
 * minus the typed path (unchanged by design) and {@code allowed()} (config
 * data, not logic). Fails the process on any miss.
 */
public final class BareProbe {

    record Row(String input, String expectContains) { }

    public static void main(String[] args) throws Exception {
        Set<String> common = new HashSet<>();
        List<String> words = Files.readAllLines(
                Paths.get("ColtCore/src/main/resources/commonwords.txt"),
                StandardCharsets.UTF_8);
        for (String w : words) {
            w = w.trim().toLowerCase(java.util.Locale.ROOT);
            if (!w.isEmpty()) common.add(w);
        }

        List<Row> mustFire = List.of(
                new Row("pl4yg1ld3dmcpr0", "playgildedmc.pro"),
                // Uppercase folds to a digit-bearing label that the PRIMARY bare
                // path already accepts via HOSTLIKE-before-digits (pre-existing
                // ordering, untouched by F1): either evidence string blocks.
                new Row("PL4YG1LD3DMCPRO", ".pro"),
                new Row("pl4y g1ld3dmc pr0", "gildedmc.pro"),
                new Row("ｐｌ４ｙｇ１ｌｄ３ｄｍｃｐｒ０", "playgildedmc.pro"));

        List<String> mustStayClean = List.of(
                "lvl100pro",
                "i have 51 diamonds and 79 gold",
                "gg that was a good game",
                "playtime like tpa",
                "d3f3nd the b4se",
                "m1n3cr4ft is fun",
                "st4ff only",
                "pr1s0n",
                "b4dw4rs",
                "n3tw0rk1ng");

        int failures = 0;
        for (Row row : mustFire) {
            String hit = unionHit(row.input, common);
            boolean ok = hit != null && hit.contains(row.expectContains);
            System.out.println((ok ? "FIRE " : "MISS ") + row.input + " -> " + hit);
            if (!ok) failures++;
        }
        for (String input : mustStayClean) {
            String hit = unionHit(input, common);
            boolean ok = hit == null;
            System.out.println((ok ? "CLEAN" : "FALSE-POSITIVE ") + " " + input
                    + (hit == null ? "" : " -> " + hit));
            if (!ok) failures++;
        }
        System.out.println(failures == 0 ? "BAREPROBE-OK" : "BAREPROBE-FAIL " + failures);
        if (failures != 0) System.exit(1);
    }

    /** Mirrors advertHit's union minus typed/allowed: primary bare, then leet sibling. */
    static String unionHit(String raw, Set<String> common) {
        String s = ChatGuardModule.advertForm(raw);
        String bare = ChatGuardModule.bareAddress(s, common, false);
        if (bare != null) return bare;
        String leet = TextFeatures.unleet(s);
        if (!leet.equals(s)) {
            return ChatGuardModule.bareAddress(leet, common, true);
        }
        return null;
    }
}
