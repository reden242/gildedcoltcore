package com.coltcore.core.modules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the output of the console {@code ldupeip} command.
 *
 * <p>The important thing about that output is that account <b>status is encoded
 * in colour, not in text</b>:
 *
 * <pre>
 *   key · [online] [offline] [banned] [IP-banned]
 *   mtyri, 305
 * </pre>
 *
 * <p>Strip the colours and you get "mtyri, 305", which says nothing about who is
 * banned — so handing the raw stripped line to a model would invite it to guess.
 * Instead this reads the legend line to learn which colour means what, then
 * applies that map to each account. The legend is parsed rather than hardcoded,
 * so a colour scheme change in the source plugin does not silently break it.
 *
 * <p>Handles both section-sign codes and raw ANSI, since which one reaches a log
 * handler depends on the console appender.
 */
public final class DupeIpParser {

    private DupeIpParser() { }

    private static final Pattern SECTION = Pattern.compile("(?:§|&)([0-9a-fk-orA-FK-OR])");
    private static final Pattern ANSI = Pattern.compile("\\[([0-9;]*)m");
    /** Strips the "[18:38:51 INFO]: " prefix a log line may carry. */
    private static final Pattern LOG_PREFIX =
            Pattern.compile("^\\s*(?:\\u001B\\[[0-9;]*m)*\\[[0-9:]{5,8}\\s+[A-Z]+\\]:\\s*");
    private static final Pattern LEGEND_ENTRY = Pattern.compile("\\[([^\\]]{2,20})\\]");
    private static final Pattern NAME_OK = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** Colour token immediately preceding a piece of text, section or ANSI. */
    private record Span(String colour, String text) { }

    /**
     * @param lines raw captured console lines, colour codes INTACT
     * @return legend, per-account statuses, and the evasion-relevant subset
     */
    public static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> legend = new LinkedHashMap<>();
        List<Map<String, Object>> accounts = new ArrayList<>();
        List<String> flagged = new ArrayList<>();
        List<String> unparsed = new ArrayList<>();

        for (String raw : lines) {
            if (raw == null || raw.isBlank()) continue;
            String line = LOG_PREFIX.matcher(raw).replaceFirst("");
            if (plain(line).isBlank()) continue;

            if (looksLikeLegend(line)) {
                legend.putAll(readLegend(line));
                continue;
            }
            List<Span> spans = spans(line);
            boolean any = false;
            for (Span s : spans) {
                for (String piece : s.text().split(",")) {
                    String name = piece.trim();
                    if (!NAME_OK.matcher(name).matches()) continue;
                    String status = legend.getOrDefault(s.colour(), null);
                    Map<String, Object> acc = new LinkedHashMap<>();
                    acc.put("name", name);
                    acc.put("status", status == null ? "unknown" : status);
                    if (status == null) acc.put("colour", s.colour());
                    accounts.add(acc);
                    any = true;
                    if (status != null) {
                        String low = status.toLowerCase(Locale.ROOT);
                        if (low.contains("ban")) flagged.add(name);
                    }
                }
            }
            if (!any) unparsed.add(plain(line));
        }

        out.put("legend", legend);
        out.put("accounts", accounts);
        out.put("banned_accounts_on_this_address", flagged);
        out.put("account_count", accounts.size());

        if (legend.isEmpty() && !accounts.isEmpty()) {
            out.put("warning", "No legend line was captured, so colours could not be mapped to "
                    + "statuses. Every account is reported as unknown. Do NOT infer bans from "
                    + "this output.");
        } else if (accounts.isEmpty()) {
            out.put("warning", "No accounts were parsed. Treat as NO DATA, not as 'no alts'.");
        } else if (flagged.isEmpty()) {
            out.put("note", "No banned account shares this address in the captured output. "
                    + "That is not proof the player is not evading.");
        } else {
            out.put("note", "A banned account shares this address. Shared addresses also occur "
                    + "in households and on shared connections - corroborate before acting.");
        }
        if (!unparsed.isEmpty()) out.put("unparsed_lines", unparsed);
        return out;
    }

    /** The legend row: several bracketed labels, no commas between names. */
    static boolean looksLikeLegend(String line) {
        String p = plain(line).toLowerCase(Locale.ROOT);
        int brackets = 0;
        Matcher m = LEGEND_ENTRY.matcher(p);
        while (m.find()) brackets++;
        return brackets >= 2 && (p.contains("online") || p.contains("banned") || p.startsWith("key"));
    }

    /** Maps each bracketed label to the colour it is printed in. */
    static Map<String, String> readLegend(String line) {
        Map<String, String> legend = new LinkedHashMap<>();
        for (Span s : spans(line)) {
            Matcher m = LEGEND_ENTRY.matcher(s.text());
            while (m.find()) {
                String label = m.group(1).trim();
                if (label.isEmpty()) continue;
                // Last writer wins is wrong here: two labels must not collapse
                // onto one colour, so only record the first use of a colour.
                legend.putIfAbsent(s.colour(), label.toLowerCase(Locale.ROOT));
            }
        }
        return legend;
    }

    /** Splits a line into (colour token, following text) runs. */
    static List<Span> spans(String line) {
        List<Span> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String colour = "";
        int i = 0;
        while (i < line.length()) {
            Matcher sec = SECTION.matcher(line);
            Matcher ansi = ANSI.matcher(line);
            boolean secHit = sec.find(i) && sec.start() == i;
            boolean ansiHit = !secHit && ansi.find(i) && ansi.start() == i;
            if (secHit) {
                if (cur.length() > 0) { out.add(new Span(colour, cur.toString())); cur.setLength(0); }
                colour = "§" + sec.group(1).toLowerCase(Locale.ROOT);
                i = sec.end();
            } else if (ansiHit) {
                if (cur.length() > 0) { out.add(new Span(colour, cur.toString())); cur.setLength(0); }
                colour = "ansi:" + ansi.group(1);
                i = ansi.end();
            } else {
                cur.append(line.charAt(i));
                i++;
            }
        }
        if (cur.length() > 0) out.add(new Span(colour, cur.toString()));
        return out;
    }

    /** All colour codes removed. */
    public static String plain(String s) {
        if (s == null) return "";
        return ANSI.matcher(SECTION.matcher(s).replaceAll("")).replaceAll("");
    }
}
