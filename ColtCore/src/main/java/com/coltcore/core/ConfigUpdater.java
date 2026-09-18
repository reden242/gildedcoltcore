package com.coltcore.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds {@code config.yml} from the bundled template, carrying the owner's
 * settings across.
 *
 * <h2>Why rebuild rather than merge</h2>
 * The previous version merged missing keys into the live file, and it was
 * broken in two ways that compounded into silent data loss:
 *
 * <ol>
 *   <li>It tested {@code live.contains(key)}, but {@code plugin.getConfig()}
 *       has the bundled copy attached as <em>defaults</em>, and
 *       {@code contains} consults defaults. Every key therefore looked like it
 *       was already present and <b>nothing was ever copied</b> — the migration
 *       reported success having done nothing.</li>
 *   <li>It then attached comments to every bundled section. Calling
 *       {@code setComments} on a path that exists only in the defaults
 *       <em>creates</em> that path, so the save wrote out
 *       {@code local-ai: {}}, {@code combat-watch: {}}
 *       and so on — empty sections that then shadowed the defaults and left the
 *       modules with no configuration at all.</li>
 * </ol>
 *
 * <p>Both are avoided here by never consulting a defaults-backed view. The live
 * file is read directly, the template is the base, and the owner's real values
 * are laid over the top.
 *
 * <h2>What survives a rebuild</h2>
 * Every value the owner actually set, including keys the template has never
 * heard of — custom AI providers, anything hand-added. What
 * changes is the surrounding structure: comments, ordering and any newly added
 * keys all come from the template, so the file is always complete and always
 * documented.
 *
 * <p>An empty section in the live file is treated as absent rather than as an
 * instruction to keep it empty. That is what repairs a file damaged by the old
 * updater: {@code local-ai: {}} carries no values, so the template's content
 * stands.
 *
 * <p>A timestamped backup is written first, so any rebuild is one file copy away
 * from being undone.
 */
public final class ConfigUpdater {

    private ConfigUpdater() { }

    /** Bump this whenever a key is added to the bundled config.yml. */
    public static final int CURRENT_VERSION = 45;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Keys that are structural rather than settings. */
    private static final String VERSION_KEY = "config-version";

    /**
     * Settings that no longer exist and must not survive a rebuild.
     *
     * <p>The carry loop treats any key the template has never heard of as an
     * owner customisation and faithfully copies it across, which is right for
     * hand-added settings and wrong for a feature that has been deleted. Left
     * alone, {@code cheatdetector-loading} would be re-added as "yours" and
     * would keep regenerating itself in the live file for ever.
     *
     * <p>These are struck out after the carry loop, so the template copy is
     * deleted whether it came from the old template or from the live file.
     *
     * <p>{@code anti-ad.llm} is listed as a whole section: the old layer 3 was
     * an HTTP call to an OpenAI-compatible endpoint, and both layers now run
     * the classifier in-process, so every key under it is dead. Removing the
     * section wholesale is also what stops it being left as an empty shell -
     * an empty section is indistinguishable from the old updater's damage and
     * would make the file rebuild on every single start.
     */
    private static final List<String> REMOVED_KEYS = List.of(
            "cheatdetector-loading",
            "anti-ad.llm",
            "anti-ad.l3.min-call-gap-ms"
    );

    /**
     * Settings whose old value names something that no longer exists, mapped to
     * {old value, new value}.
     *
     * <p>Striking a key only helps when the key is gone. {@code
     * anti-ad.l2.model-resource} still exists - it just used to point at the
     * fastText binary, and the carry loop would faithfully preserve that value
     * as the owner's choice. The result would be a live server whose L2 loads
     * nothing and silently passes every message, which is the one failure mode
     * that looks exactly like a working filter. So the stale value is migrated
     * explicitly instead.
     */
    private static final java.util.Map<String, String[]> RETIRED_VALUES =
            java.util.Map.of(
                    "anti-ad.l2.model-resource", new String[]{"/antiad.ft.bin", "/antiad.m5.bin"}
            );

    /** Runs the rebuild. Returns the number of keys added, or -1 on failure. */
    public static int run(JavaPlugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");

        // Read the live file directly. plugin.getConfig() is defaults-backed and
        // cannot answer "did the owner actually set this", which is the only
        // question that matters here.
        YamlConfiguration live = loadLive(plugin, file);

        int have = live.getInt(VERSION_KEY, 0);
        if (have >= CURRENT_VERSION && !isDamaged(live, plugin)) return 0;

        YamlConfiguration template = loadBundled(plugin);
        if (template == null) {
            plugin.getLogger().warning("[Config] bundled config.yml unreadable; "
                    + "leaving your file alone.");
            return -1;
        }
        if (!backup(plugin, file, have)) {
            plugin.getLogger().warning("[Config] could not write a backup; refusing to rebuild.");
            return -1;
        }

        // Everything the owner actually set, as leaf paths. Sections are skipped
        // because setting a leaf recreates its parents, and copying a section
        // wholesale would flatten template keys the owner never touched.
        List<String> carried = new ArrayList<>();
        List<String> custom = new ArrayList<>();
        for (String key : live.getKeys(true)) {
            if (key.equals(VERSION_KEY)) continue;
            if (live.isConfigurationSection(key)) continue;
            Object value = live.get(key);
            if (value == null) continue;
            boolean known = template.contains(key, true);
            if (!known) custom.add(key);
            Object shipped = template.get(key);
            if (known && java.util.Objects.equals(shipped, value)) continue;  // unchanged
            template.set(key, value);
            carried.add(key);
        }

        // Strike out settings for features that no longer exist. This has to run
        // after the carry loop, which would otherwise have preserved them as
        // owner customisations and re-added them on every rebuild.
        List<String> struck = new ArrayList<>();
        for (String removed : REMOVED_KEYS) {
            if (template.contains(removed, true) || live.contains(removed, true)) {
                template.set(removed, null);
                struck.add(removed);
            }
        }

        // Move values that named a file that no longer ships. Same reasoning as
        // above: the key survives, the value must not.
        for (java.util.Map.Entry<String, String[]> retired : RETIRED_VALUES.entrySet()) {
            String path = retired.getKey();
            String was = retired.getValue()[0];
            String now = retired.getValue()[1];
            if (was.equals(template.getString(path))) {
                template.set(path, now);
                plugin.getLogger().info("[Config] " + path + ": " + was + " -> " + now
                        + " (the old file no longer ships)");
            }
        }

        // Count what the owner is gaining, before the version key masks it.
        int addedKeys = 0;
        for (String key : template.getKeys(true)) {
            if (template.isConfigurationSection(key)) continue;
            if (!live.contains(key, true)) addedKeys++;
        }

        template.set(VERSION_KEY, CURRENT_VERSION);

        try {
            template.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("[Config] save failed: " + ex.getMessage());
            return -1;
        }
        plugin.reloadConfig();

        plugin.getLogger().info("[Config] rebuilt v" + have + " -> v" + CURRENT_VERSION
                + ": " + addedKeys + " new key(s), " + carried.size()
                + " of your setting(s) carried over.");
        if (!custom.isEmpty()) {
            plugin.getLogger().info("[Config] " + custom.size()
                    + " key(s) of your own are not in the bundled config. Kept:");
            for (String k : custom) plugin.getLogger().info("[Config]   ? " + k);
        }
        if (!struck.isEmpty()) {
            plugin.getLogger().info("[Config] removed " + struck.size()
                    + " setting(s) for features that no longer exist: " + struck);
        }
        return addedKeys;
    }

    /**
     * Detects a file damaged by the old updater.
     *
     * <p>Versions up to 18 could write a section out as {@code {}} — present,
     * empty, and shadowing the defaults, so the module behind it saw no
     * configuration at all and silently did nothing. A file at the current
     * version is normally left alone, but not if it looks like this, because the
     * damage is invisible from the outside and the owner has no reason to
     * suspect it.
     */
    private static boolean isDamaged(YamlConfiguration live, JavaPlugin plugin) {
        List<String> hollow = new ArrayList<>();
        for (String key : live.getKeys(true)) {
            ConfigurationSection section = live.getConfigurationSection(key);
            if (section != null && section.getKeys(false).isEmpty()) hollow.add(key);
        }
        if (hollow.isEmpty()) return false;
        plugin.getLogger().warning("[Config] " + hollow.size()
                + " empty section(s) found - these were written by a bug in an earlier"
                + " version and leave the module unconfigured. Rebuilding: " + hollow);
        return true;
    }

    private static YamlConfiguration loadBundled(JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return null;
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(r);
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** Loads runtime config without allowing malformed YAML to poison getConfig(). */
    private static YamlConfiguration loadLive(JavaPlugin plugin, File file) {
        if (!file.exists()) return new YamlConfiguration();
        try {
            YamlConfiguration live = new YamlConfiguration();
            live.load(file);
            return live;
        } catch (Exception ex) {
            plugin.getLogger().warning("[Config] runtime config is invalid; rebuilding from bundled config: "
                    + ex.getMessage());
            return new YamlConfiguration();
        }
    }

    private static boolean backup(JavaPlugin plugin, File file, int fromVersion) {
        if (!file.exists()) return true;                    // nothing to lose
        File dir = new File(plugin.getDataFolder(), "config-backups");
        if (!dir.exists() && !dir.mkdirs()) return false;
        File out = new File(dir, "config-v" + fromVersion + "-"
                + LocalDateTime.now().format(STAMP) + ".yml");
        try {
            Files.copy(file.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("[Config] backup written: config-backups/" + out.getName());
            return true;
        } catch (IOException ex) {
            plugin.getLogger().warning("[Config] backup failed: " + ex.getMessage());
            return false;
        }
    }
}
