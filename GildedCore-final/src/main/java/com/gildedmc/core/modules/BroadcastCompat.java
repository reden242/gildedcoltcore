/*
 * Decompiled with CFR 0.152.
 */
package com.gildedmc.core.modules;

import java.lang.reflect.Method;

public final class BroadcastCompat {
    private BroadcastCompat() {
    }

    /**
     * Formats a message in the standard GildedCore boxed style:
     * ------------------------------
     * {summary line: who/what/counts}
     * {content line: the actual message}
     * ------------------------------
     *
     * Replaces bare "{x} >>" style prefixes. Callers still run the result
     * through their own color translator (&amp; -&gt; §) since this method
     * does not perform color translation itself.
     */
    public static String[] boxLines(String summaryLine, String contentLine) {
        return boxLines("&8", summaryLine, contentLine);
    }

    /**
     * Same as {@link #boxLines(String, String)} but lets the caller pick the
     * border color (e.g. a rank color) instead of the default dark gray.
     */
    public static String[] boxLines(String borderColor, String summaryLine, String contentLine) {
        String border = borderColor + "&m------------------------------";
        return new String[]{border, summaryLine, contentLine, border};
    }

    public static void broadcastMessage(String string) {
        if (string == null) {
            return;
        }
        try {
            Class<?> clazz = Class.forName("org.bukkit.Bukkit");
            Class<?> clazz2 = Class.forName("org.bukkit.command.CommandSender");
            Method method = clazz2.getMethod("sendMessage", String.class);
            Object object = clazz.getMethod("getOnlinePlayers", new Class[0]).invoke(null, new Object[0]);
            if (object instanceof Iterable) {
                for (Object t : (Iterable)object) {
                    try {
                        method.invoke(t, string);
                    }
                    catch (Throwable throwable) {}
                }
            }
            try {
                Object object2 = clazz.getMethod("getConsoleSender", new Class[0]).invoke(null, new Object[0]);
                method.invoke(object2, string);
            }
            catch (Throwable throwable) {}
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }
}

