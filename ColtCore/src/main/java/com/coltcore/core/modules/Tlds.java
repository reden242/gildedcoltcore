package com.coltcore.core.modules;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The shipped TLD list, for addresses written without dots.
 *
 * <h2>Why this exists and where it is used</h2>
 * An address with a dot needs no list — the dot is the evidence, and demanding a
 * known TLD there would be an allowlist, which is a bypass by construction: a
 * host on any TLD outside the list is skipped entirely, which is how
 * {@code bloodmoon.bot.nu} once sailed through. The dotted matcher therefore
 * still accepts any 2–24 letter suffix.
 *
 * <p>This list is for the case with no dot to anchor on: {@code play coltcore
 * pro} on three lines of a sign, or {@code playcoltcorepro} run together. There
 * the last token has to be recognised as a TLD, because nothing else marks it.
 *
 * <h2>Why the list alone is not enough</h2>
 * 229 of the 1,424 TLDs are ordinary English words — {@code online}, {@code
 * store}, {@code pro}, {@code live}, {@code best}, {@code top}, {@code chat},
 * {@code game}. In text with the separators removed there is nothing to tell
 * "best pro player online" from a hostname, which is exactly what happened when
 * the full list was tried on its own.
 *
 * <p>So the list answers only "could this token be a TLD". Whether the tokens in
 * front of it are a hostname is decided separately, by
 * {@link ChatGuardModule#bareAddress}, using the common-word list and the
 * surrounding invitation.
 *
 * <h2>Second-level suffixes</h2>
 * {@code co.uk} and {@code com.br} are carried as well, so {@code play coltcore
 * co uk} is read as one address rather than as a label followed by two
 * unrelated words.
 */
public final class Tlds {

    private Tlds() { }

    private static volatile Set<String> single = Collections.emptySet();
    private static volatile Set<String> second = Collections.emptySet();
    private static volatile boolean loaded;

    /** Loads {@code /tlds.txt}. Safe to call repeatedly; only the first reads. */
    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Set<String> ones = new HashSet<>(2048);
        Set<String> twos = new HashSet<>(1024);
        try (InputStream in = Tlds.class.getResourceAsStream("/tlds.txt")) {
            if (in == null) return;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim().toLowerCase(Locale.ROOT);
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    if (line.indexOf('.') >= 0) twos.add(line); else ones.add(line);
                }
            }
        } catch (Exception ignored) {
            // Shipping without the resource degrades the dotless matcher to
            // nothing. It does not break the dotted one, which is the main path.
        }
        single = ones;
        second = twos;
    }

    /** Test seam: load from an explicit stream instead of the classpath. */
    public static synchronized void loadFrom(InputStream in) throws Exception {
        Set<String> ones = new HashSet<>(2048);
        Set<String> twos = new HashSet<>(1024);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.ROOT);
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.indexOf('.') >= 0) twos.add(line); else ones.add(line);
            }
        }
        single = ones;
        second = twos;
        loaded = true;
    }

    public static boolean isTld(String token) {
        if (token == null || token.isEmpty()) return false;
        load();
        return single.contains(token.toLowerCase(Locale.ROOT));
    }

    /** Whether {@code a.b} is a known second-level suffix, such as {@code co.uk}. */
    public static boolean isSecondLevel(String a, String b) {
        if (a == null || b == null) return false;
        load();
        return second.contains(a.toLowerCase(Locale.ROOT) + "." + b.toLowerCase(Locale.ROOT));
    }

    public static int size() {
        load();
        return single.size() + second.size();
    }
}
