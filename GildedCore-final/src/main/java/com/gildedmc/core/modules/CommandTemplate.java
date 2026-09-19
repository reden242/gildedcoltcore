package com.gildedmc.core.modules;

/**
 * Single choke point for expanding console-command templates.
 *
 * <h2>Why this exists</h2>
 * A dozen modules interpolate a player name into a console command
 * ({@code eco give %player% ...}, {@code crate give %player% ...}).
 * Mojang names are {@code [A-Za-z0-9_]{3,16}} and cannot break out of an
 * argument, but bridge/proxy names (Geyser/Floodgate, offline-mode renames)
 * are not Mojang-constrained: a name containing a space turns
 * {@code ban Evil Player grief} into a ban of {@code Evil} with the rest as
 * reason text, and a name containing command syntax hands the target
 * command's parser attacker-chosen tokens with console privileges. Every
 * expansion below funnels through {@link #safeName}, so no caller has to
 * remember to sanitise.
 */
public final class CommandTemplate {

    private CommandTemplate() { }

    /**
     * A player name reduced to characters that cannot escape a command
     * argument: letters, digits, underscore, dot (Floodgate {@code .} prefix)
     * and dash. Anything else is dropped, and the result is capped at 32
     * characters so a hostile name cannot smuggle a paragraph into a command
     * line. Never returns null.
     */
    public static String safeName(String name) {
        if (name == null) return "";
        StringBuilder out = new StringBuilder(Math.min(32, name.length()));
        for (int i = 0; i < name.length() && out.length() < 32; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * A config/token value (crate id, kit id, rank, stat) reduced to the same
     * safe alphabet minus the dot. Used for placeholders that must be single
     * identifiers, never free text.
     */
    public static String safeToken(String token) {
        if (token == null) return "";
        StringBuilder out = new StringBuilder(Math.min(64, token.length()));
        for (int i = 0; i < token.length() && out.length() < 64; i++) {
            char c = token.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Expands {@code %player%} / {@code %PLAYER%} with the sanitised name and
     * strips a leading slash, matching what the callers did by hand before.
     */
    public static String expand(String template, String playerName) {
        if (template == null) return "";
        String safe = safeName(playerName);
        return template.replace("%player%", safe)
                .replace("%PLAYER%", safe)
                .replaceFirst("^/", "");
    }
}
