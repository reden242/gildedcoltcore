package com.gildedmc.core;

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
 *       {@code local-ai: {}}, {@code combat-watch: {}}, {@code offend: {types: {}}}
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
 * heard of — custom AI providers, extra offend types, anything hand-added. What
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
    public static final int CURRENT_VERSION = 39;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Keys that are structural rather than settings. */
    private static final String VERSION_KEY = "config-version";

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
