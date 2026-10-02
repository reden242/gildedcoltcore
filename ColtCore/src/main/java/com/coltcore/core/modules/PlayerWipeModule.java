package com.coltcore.core.modules;

import com.coltcore.core.SchedulerCompat;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /playerwipe} — reset a player's shards, money, stats, playtime or homes.
 *
 * <h2>Why this replaces the old one</h2>
 * The previous menu rendered nothing but filler panes. The cause was in
 * config.yml, not in code: {@code playerwipe:} appeared twice, and YAML keeps
 * the last occurrence, which had no {@code categories} block. The GUI dutifully
 * drew zero categories. That is fixed, and the menu is rebuilt here so that it
 * is a real thing rather than an inner class hidden inside a decompiled bundle.
 *
 * <h2>Partial wipes</h2>
 * Shards and money are rarely all-or-nothing — you usually want to claw back a
 * duped amount, not zero someone. Those categories offer preset amounts and a
 * custom amount typed in chat, alongside the full reset. Stats are listed one
 * by one so a single counter can be corrected without wiping the rest.
 *
 * <h2>Every destructive click is confirmed</h2>
 * There is no path from opening the menu to running a command without passing
 * a confirm screen that names the player, the category and the amount.
 */
public final class PlayerWipeModule implements Listener {

    public static final String PERM = "coltcore.playerwipe";

    private final JavaPlugin plugin;

    private boolean enabled = true;
    private final Set<String> allowedUsernames = ConcurrentHashMap.newKeySet();
    private boolean usernameAccess = false;
    private String guiTitle = "&8Wipe &8| &f%player%";
    private String statCommand = "statsmgr set %player% %stat% %value%";
    /** Also clear the server's own vanilla playtime statistic on playtime wipes. */
    private boolean resetBukkitStats = true;
    /** Queue offline wipes so online-only commands land on the target's next login. */
    private boolean queueOfflineWipes = true;
    /** Load an offline target into the server for one tick so online-only commands work. */
    private boolean syntheticLoad = true;
    /** Mirror every wipe to Discord. Optional; needs DiscordSRV installed. */
    private boolean discordEnabled = true;
    private String discordChannel = "staff";
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final List<String> fullOrder = new ArrayList<>();
    private Category fullWipe;

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    /** Page each viewer is on in the known-player picker. */
    private final Map<UUID, Integer> pickerSessions = new ConcurrentHashMap<>();
    /** The exact picker inventory per viewer; Bukkit exposes no title on Inventory. */
    private final Map<UUID, Inventory> pickerInventories = new ConcurrentHashMap<>();
    private final Map<UUID, String> amountPrompts = new ConcurrentHashMap<>();

    public PlayerWipeModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /*  Config model                                                      */
    /* ------------------------------------------------------------------ */

    private static final class Category {
        String id;
        int slot = -1;
        Material icon = Material.PAPER;
        String display = "&fCategory";
        String description = "";
        final List<String> information = new ArrayList<>();
        String warning = "";
        boolean amounts;
        final List<Integer> presets = new ArrayList<>();
        final List<String> allCommands = new ArrayList<>();
        final List<String> takeCommands = new ArrayList<>();
        final List<String> stats = new ArrayList<>();
    }

    private static final class Session {
        Inventory inventory;
        String stage;      // main | amount | stats | confirm
        String target;
        String targetUuid;
        String category;
        String amount;     // "all", a number, or a stat name
        String label;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        readConfig();
        if (this.syntheticLoad && !SyntheticPlayerLoader.isSupported()) {
            this.plugin.getLogger().warning("[PlayerWipe] offline synthetic load unavailable ("
                    + SyntheticPlayerLoader.unavailableReason() + "). Offline wipes will be queued"
                    + " and applied on the target's next login instead.");
        }
    }

    public void reload() { readConfig(); }

    public void disable() {
        this.sessions.clear();
        this.amountPrompts.clear();
    }

    private void readConfig() {
        ConfigurationSection c = this.plugin.getConfig().getConfigurationSection("playerwipe");
        this.categories.clear();
        this.fullOrder.clear();
        this.fullWipe = null;
        if (c == null) { this.enabled = false; return; }
        this.enabled = c.getBoolean("enabled", true);
        this.usernameAccess = c.getBoolean("allow-username-access", false);
        this.allowedUsernames.clear();
        for (String name : c.getStringList("allowed-usernames")) {
            String normalized = normalizeName(name);
            if (!normalized.isBlank()) this.allowedUsernames.add(normalized);
        }
        String legacyOwner = normalizeName(c.getString("owner-name", ""));
        if (!legacyOwner.isBlank()) this.allowedUsernames.add(legacyOwner);
        if (!this.usernameAccess && !this.allowedUsernames.isEmpty()) {
            this.plugin.getLogger().warning("[PlayerWipe] allowed-usernames/owner-name"
                    + " configured but allow-username-access is off - names are"
                    + " spoofable, so they are ignored. Grant coltcore.playerwipe"
                    + " or set allow-username-access: true to re-enable.");
        }
        this.guiTitle = c.getString("gui-title", this.guiTitle);
        this.statCommand = c.getString("stat-command", this.statCommand);
        this.resetBukkitStats = c.getBoolean("reset-bukkit-statistics", true);
        this.queueOfflineWipes = c.getBoolean("queue-offline-wipes", true);
        this.syntheticLoad = c.getBoolean("offline-synthetic-load", true);
        ConfigurationSection dl = c.getConfigurationSection("discord-log");
        if (dl != null) {
            this.discordEnabled = dl.getBoolean("enabled", true);
            this.discordChannel = dl.getString("channel", "staff");
        }

        ConfigurationSection cats = c.getConfigurationSection("categories");
        if (cats != null) for (String id : cats.getKeys(false)) {
            ConfigurationSection s = cats.getConfigurationSection(id);
            if (s == null || !s.getBoolean("enabled", true)) continue;
            this.categories.put(id.toLowerCase(Locale.ROOT), category(id, s));
        }
        ConfigurationSection full = c.getConfigurationSection("full-wipe");
        if (full != null && full.getBoolean("enabled", true)) {
            this.fullWipe = category("full", full);
            this.fullOrder.addAll(full.getStringList("categories"));
            if (this.fullOrder.isEmpty()) this.fullOrder.addAll(this.categories.keySet());
        }
        if (this.enabled && this.categories.isEmpty()) {
            this.plugin.getLogger().warning("[PlayerWipe] no categories configured - the menu "
                    + "would open empty. Check that config.yml has exactly one 'playerwipe:' block.");
        }
    }

    private Category category(String id, ConfigurationSection s) {
        Category cat = new Category();
        cat.id = id.toLowerCase(Locale.ROOT);
        cat.slot = s.getInt("slot", -1);
        cat.icon = UiKit.material(s.getString("icon"), Material.PAPER);
        cat.display = s.getString("display-name", "&f" + id);
        cat.description = s.getString("description", "");
        cat.information.addAll(s.getStringList("information"));
        if (cat.information.isEmpty()) {
            String lore = s.getString("lore", "");
            if (!lore.isBlank()) cat.information.add(ChatColor.stripColor(UiKit.colour(lore)));
            for (String l : s.getStringList("lore")) {
                cat.information.add(ChatColor.stripColor(UiKit.colour(l)));
            }
        }
        cat.warning = s.getString("warning", "This cannot be undone without a backup.");
        cat.amounts = s.getBoolean("amounts", false);
        for (int n : s.getIntegerList("amount-presets")) cat.presets.add(n);
        cat.allCommands.addAll(s.getStringList("all-commands"));
        // "commands" is the old key. Read it so an existing config keeps working.
        cat.allCommands.addAll(s.getStringList("commands"));
        cat.takeCommands.addAll(s.getStringList("take-commands"));
        cat.stats.addAll(s.getStringList("stats"));
        return cat;
    }

    /* ------------------------------------------------------------------ */
    /*  Command                                                           */
    /* ------------------------------------------------------------------ */

    public boolean command(CommandSender sender, String[] args) {
        if (!this.enabled) {
            sender.sendMessage(UiKit.colour("&cPlayer wipe is disabled in config."));
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(UiKit.colour("&cThis menu is for players."));
            return true;
        }
        if (!allowed(p)) {
            p.sendMessage(UiKit.colour("&cNo permission."));
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(UiKit.colour("&eUsage: &fPlayerwipe <player|uuid|list>"));
            return true;
        }
        if (args[0].equalsIgnoreCase("pending")) {
            List<QueuedWipe> pending = pendingWipes();
            if (pending.isEmpty()) {
                p.sendMessage(UiKit.colour("&eNo wipes are queued."));
                return true;
            }
            p.sendMessage(UiKit.colour("&6&lQueued offline wipes &7(" + pending.size() + ")"));
            for (QueuedWipe q : pending) {
                p.sendMessage(UiKit.colour("&8- &f" + q.name() + " &8(" + q.uuid() + ")"
                        + " &7" + q.label() + " &8[" + q.commands().size() + " cmd, attempt "
                        + q.attempts() + "]"));
            }
            p.sendMessage(UiKit.colour("&8Cancel with &fPlayerwipe cancel <player|uuid>"));
            return true;
        }
        if (args.length > 1 && args[0].equalsIgnoreCase("cancel")) {
            UUID target = parseUuidTarget(args[1]);
            if (target == null) {
                p.sendMessage(UiKit.colour("&cUnknown player: " + args[1]));
                return true;
            }
            p.sendMessage(cancelQueuedWipe(target)
                    ? UiKit.colour("&aCancelled queued wipe for &f" + args[1] + "&a.")
                    : UiKit.colour("&eNothing queued for &f" + args[1] + "&e."));
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            openPicker(p, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("refreshindex")) {
            int n = refreshPlayerIndex();
            p.sendMessage(UiKit.colour("&aPlayer index refreshed: &f" + n + " &aaccount(s) known."));
            openPicker(p, 0);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reload();
            p.sendMessage(UiKit.colour("&aPlayer wipe reloaded, &f"
                    + this.categories.size() + " &acategory(s)."));
            return true;
        }
        OfflinePlayer target;
        String raw = args[0] == null ? "" : args[0].trim();
        UUID parsed = null;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
        }
        if (parsed != null) {
            target = Bukkit.getOfflinePlayer(parsed);
        } else if (raw.matches("(?i)^[0-9a-f]{32}$")) {
            String dashed = raw.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5");
            try {
                target = Bukkit.getOfflinePlayer(UUID.fromString(dashed));
            } catch (IllegalArgumentException ex) {
                p.sendMessage(UiKit.colour("&cThat does not look like a valid UUID."));
                return true;
            }
        } else {
            target = Bukkit.getOfflinePlayer(raw);
        }
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            p.sendMessage(UiKit.colour("&c" + raw + " has never joined this server."));
            return true;
        }
        openMain(p, target.getName() == null ? raw : target.getName(),
                target.getUniqueId() == null ? null : target.getUniqueId().toString());
        return true;
    }

    /** Permission node or a configured username. Legacy owner-name stays additive. */
    private boolean allowed(Player p) {
        if (p.hasPermission(PERM)) return true;
        // Names are not authentication: offline-mode and misconfigured
        // proxies let anyone join as any name, and this menu runs console
        // commands. The username path only exists behind an explicit opt-in.
        return this.usernameAccess && this.allowedUsernames.contains(normalizeName(p.getName()));
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /* ------------------------------------------------------------------ */
    /*  Menus                                                             */
    /* ------------------------------------------------------------------ */

    private void openMain(Player viewer, String target) {
        openMain(viewer, target, null);
    }

    /* ------------------------------------------------------------------ */
    /*  Known-player index                                                */
    /* ------------------------------------------------------------------ */

    /** Accepts a dashed UUID, an undashed one, or a name. */
    private UUID parseUuidTarget(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
        }
        if (value.matches("(?i)^[0-9a-f]{32}$")) {
            try {
                return UUID.fromString(value.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                        "$1-$2-$3-$4-$5"));
            } catch (IllegalArgumentException ignored) {
            }
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(value);
        return op != null && op.hasPlayedBefore() ? op.getUniqueId() : null;
    }

    private File indexFile() {
        return new File(this.plugin.getDataFolder(), "known-players.yml");
    }

    private File pendingStatsFile() {
        return new File(this.plugin.getDataFolder(), "pending-stat-resets.yml");
    }

    /* ------------------------------------------------------------------ */
    /*  Offline wipe queue                                                */
    /* ------------------------------------------------------------------ */

    public record QueuedWipe(UUID uuid, String name, String label,
                             List<String> commands, long queuedAt, int attempts) { }

    private File pendingWipesFile() {
        return new File(this.plugin.getDataFolder(), "pending-wipes.yml");
    }

    /**
     * Stores one pending offline wipe.
     *
     * <p>Deliberately tiny: a UUID, the name at queue time, the staff label for
     * the log, the already-substituted command lines and a timestamp. No player
     * data, no snapshot of anything, so the file stays a few hundred bytes per
     * queued wipe no matter how large the account is.
     */
    public QueuedWipe queueWipe(UUID uuid, String name, String label, List<String> commands) {
        if (uuid == null || commands == null || commands.isEmpty()) return null;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(pendingWipesFile());
        String base = "pending." + uuid;
        int attempts = yml.getInt(base + ".attempts", 0);
        if (attempts >= maxAttempts()) {
            yml.set(base, null);
            savePending(yml);
            this.plugin.getLogger().warning("[PlayerWipe] dropped queued wipe for " + name
                    + " after " + attempts + " attempts.");
            return null;
        }
        yml.set(base + ".name", name);
        yml.set(base + ".label", label);
        yml.set(base + ".queued-at", System.currentTimeMillis());
        yml.set(base + ".attempts", attempts + 1);
        yml.set(base + ".commands", new ArrayList<>(commands));
        if (!savePending(yml)) return null;
        return new QueuedWipe(uuid, name, label, new ArrayList<>(commands),
                System.currentTimeMillis(), attempts + 1);
    }

    private int maxAttempts() {
        return Math.max(1, this.plugin.getConfig().getInt("playerwipe.queue-max-attempts", 3));
    }

    private boolean savePending(YamlConfiguration yml) {
        try {
            File parent = pendingWipesFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            yml.save(pendingWipesFile());
            return true;
        } catch (java.io.IOException ex) {
            this.plugin.getLogger().warning("[PlayerWipe] could not write pending-wipes.yml: " + ex.getMessage());
            return false;
        }
    }

    public List<QueuedWipe> pendingWipes() {
        List<QueuedWipe> out = new ArrayList<>();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(pendingWipesFile());
        ConfigurationSection section = yml.getConfigurationSection("pending");
        if (section == null) return out;
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                out.add(new QueuedWipe(id,
                        section.getString(key + ".name", "?"),
                        section.getString(key + ".label", "?"),
                        new ArrayList<>(section.getStringList(key + ".commands")),
                        section.getLong(key + ".queued-at", 0L),
                        section.getInt(key + ".attempts", 0)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    public boolean cancelQueuedWipe(UUID uuid) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(pendingWipesFile());
        if (!yml.contains("pending." + uuid)) return false;
        yml.set("pending." + uuid, null);
        return savePending(yml);
    }

    /**
     * Runs and clears this player's queued wipe on login.
     *
     * <p>Fires a tick later so the server has finished loading the player's
     * data and any plugin that caches stats on join has already done so, which
     * is the whole point of queueing rather than running up front.
     */
    private void applyPendingWipes(Player player) {
        UUID id = player.getUniqueId();
        if (!pendingWipesFile().isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(pendingWipesFile());
        String base = "pending." + id;
        if (!yml.contains(base)) return;
        List<String> commands = new ArrayList<>(yml.getStringList(base + ".commands"));
        String label = yml.getString(base + ".label", "?");
        int attempts = yml.getInt(base + ".attempts", 1);

        // Clear the vanilla statistic too, now that the account is actually here.
        if (this.resetBukkitStats) {
            player.setStatistic(Statistic.PLAY_ONE_MINUTE, 0);
        }

        int failed = 0;
        for (String cmd : commands) {
            boolean ok;
            try {
                ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                ok = false;
            }
            if (!ok) failed++;
        }

        if (attempts >= maxAttempts() || failed >= commands.size()) {
            yml.set(base, null);
            savePending(yml);
            this.plugin.getLogger().warning("[PlayerWipe] discarded queued wipe '" + label
                    + "' for " + player.getName() + " after " + attempts + " attempt(s), "
                    + failed + " command(s) failed.");
        } else {
            savePending(yml);
            this.plugin.getLogger().info("[PlayerWipe] replayed queued wipe '" + label
                    + "' for " + player.getName() + " (" + commands.size() + " command(s), "
                    + failed + " failed, attempt " + attempts + ").");
        }
    }

    /**
     * Clears the server's own playtime statistic.
     *
     * <p>This is a different number from everything else in the plugin: it lives
     * in the player's vanilla NBT and is what the active-rank check and any
     * external stats viewer read. Bukkit only exposes it for an online player, so
     * an offline target is queued and cleared the moment they next log in
     * rather than being silently skipped the way {@code statsmgr set} is.
     *
     * @return "cleared", "queued" or "no-uuid"
     */
    public String resetBukkitPlaytime(UUID uuid) {
        if (uuid == null) return "no-uuid";
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            online.setStatistic(Statistic.PLAY_ONE_MINUTE, 0);
            return "cleared";
        }
        YamlConfiguration pending = YamlConfiguration.loadConfiguration(pendingStatsFile());
        pending.set("pending." + uuid, System.currentTimeMillis());
        try {
            File parent = pendingStatsFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            pending.save(pendingStatsFile());
        } catch (java.io.IOException ex) {
            this.plugin.getLogger().warning("[PlayerWipe] could not queue stat reset: " + ex.getMessage());
            return "no-uuid";
        }
        return "queued";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoinForPendingStats(PlayerJoinEvent event) {
        if (SyntheticPlayerLoader.isSynthetic(event.getPlayer())) return;
        SchedulerCompat.runLater(this.plugin, () -> applyPendingWipes(event.getPlayer()), 20L);
        File file = pendingStatsFile();
        if (!file.isFile()) return;
        UUID id = event.getPlayer().getUniqueId();
        YamlConfiguration pending = YamlConfiguration.loadConfiguration(file);
        if (!pending.contains("pending." + id)) return;
        event.getPlayer().setStatistic(Statistic.PLAY_ONE_MINUTE, 0);
        pending.set("pending." + id, null);
        try {
            pending.save(file);
        } catch (java.io.IOException ignored) {
        }
        this.plugin.getLogger().info("[PlayerWipe] applied queued playtime reset for "
                + event.getPlayer().getName() + " on join.");
    }

    /**
     * Every account this server has any record of, keyed by UUID.
     *
     * <p>Built from the server's own offline player list plus every UUID that
     * appears in the data files this plugin owns, so an account that joined
     * once, never played since, and only ever earned a reward is still
     * reachable from the wipe menu instead of having to be typed exactly.
     */
    public Map<UUID, String> knownPlayers() {
        Map<UUID, String> out = new LinkedHashMap<>();
        YamlConfiguration index = YamlConfiguration.loadConfiguration(indexFile());
        ConfigurationSection stored = index.getConfigurationSection("players");
        if (stored != null) {
            for (String key : stored.getKeys(false)) {
                try {
                    out.put(UUID.fromString(key), stored.getString(key + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        for (UUID key : collectUuids("rewards.yml", "players")) {
            if (!out.containsKey(key)) out.put(key, "?");
        }
        for (UUID key : collectUuids("antibot.yml", "active-minutes")) {
            if (!out.containsKey(key)) out.put(key, "?");
        }
        for (UUID key : collectUuids("active-rank.yml", "granted")) {
            if (!out.containsKey(key)) out.put(key, "?");
        }
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op == null || op.getUniqueId() == null) continue;
            String name = op.getName();
            out.putIfAbsent(op.getUniqueId(), name == null ? "?" : name);
            if (name != null) out.put(op.getUniqueId(), name);
        }
        return out;
    }

    private List<UUID> collectUuids(String fileName, String path) {
        List<UUID> out = new ArrayList<>();
        File f = new File(this.plugin.getDataFolder(), fileName);
        if (!f.isFile()) return out;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        if (yml.isConfigurationSection(path)) {
            for (String key : yml.getConfigurationSection(path).getKeys(false)) {
                try {
                    out.add(UUID.fromString(key));
                } catch (IllegalArgumentException ignored) {
                }
            }
        } else if (yml.isList(path)) {
            for (Object value : yml.getList(path)) {
                if (value == null) continue;
                try {
                    out.add(UUID.fromString(String.valueOf(value).trim()));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return out;
    }

    /** One-time rebuild of known-players.yml from every source we can see. */
    public int refreshPlayerIndex() {
        Map<UUID, String> players = knownPlayers();
        YamlConfiguration index = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, String> entry : players.entrySet()) {
            String base = "players." + entry.getKey();
            index.set(base + ".name", entry.getValue());
            index.set(base + ".last-seen", now);
        }
        try {
            File parent = indexFile().getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            index.save(indexFile());
        } catch (java.io.IOException ex) {
            this.plugin.getLogger().warning("[PlayerWipe] could not write known-players.yml: " + ex.getMessage());
            return 0;
        }
        this.plugin.getLogger().info("[PlayerWipe] player index rebuilt: " + players.size() + " account(s).");
        return players.size();
    }

    private void openPicker(Player viewer, int page) {
        List<Map.Entry<UUID, String>> all = new ArrayList<>(knownPlayers().entrySet());
        all.sort(Comparator.comparing(e -> e.getValue().toLowerCase(Locale.ROOT)));
        final int perPage = 45;
        int pageCount = Math.max(1, (all.size() + perPage - 1) / perPage);
        int current = Math.max(0, Math.min(page, pageCount - 1));

        Inventory inv = UiKit.themed(6, "Known Players &8- &f" + all.size());
        UiKit.framed(inv, UiKit.FRAME_EDGE, UiKit.FRAME_CORNER);

        int from = current * perPage;
        int to = Math.min(all.size(), from + perPage);
        for (int i = from; i < to; i++) {
            Map.Entry<UUID, String> entry = all.get(i);
            UUID id = entry.getKey();
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            boolean online = op != null && op.isOnline();
            List<String> lore = new ArrayList<>();
            lore.add("&7" + id);
            lore.add(" ");
            lore.add(online ? "&aOnline now" : "&7Offline");
            lore.add(op != null && op.hasPlayedBefore() ? "&7Has played before" : "&cNo join record");
            inv.setItem(i - from, UiKit.head(op, "&f" + entry.getValue(), lore));
        }

        if (current > 0) {
            inv.setItem(37, UiKit.item(Material.ARROW, "&ePrevious page", "&7Page " + current + " of " + pageCount));
        }
        if (current < pageCount - 1) {
            inv.setItem(43, UiKit.item(Material.ARROW, "&eNext page", "&7Page " + (current + 2) + " of " + pageCount));
        }
        inv.setItem(40, UiKit.close());
        this.pickerSessions.put(viewer.getUniqueId(), current);
        this.pickerInventories.put(viewer.getUniqueId(), inv);
        viewer.openInventory(inv);
    }

    private void openMain(Player viewer, String target, String targetUuid) {
        Inventory inv = UiKit.themed(5, this.guiTitle.replace("%player%", target));
        int auto = 10;
        for (Category cat : this.categories.values()) {
            int slot = cat.slot >= 0 && cat.slot < inv.getSize() ? cat.slot : auto++;
            if (slot >= inv.getSize()) break;
            List<String> info = new ArrayList<>(cat.information);
            if (cat.amounts) info.add("Choose a full reset or an exact amount.");
            if (!cat.stats.isEmpty()) info.add(cat.stats.size() + " individual counters.");
            inv.setItem(slot, UiKit.card(cat.icon, cat.display, cat.description, info,
                    cat.warning, "to choose"));
        }
        if (this.fullWipe != null) {
            int slot = this.fullWipe.slot >= 0 && this.fullWipe.slot < inv.getSize()
                    ? this.fullWipe.slot : inv.getSize() - 5;
            List<String> info = new ArrayList<>(this.fullWipe.information);
            info.add("Runs: " + String.join(", ", this.fullOrder));
            inv.setItem(slot, UiKit.card(this.fullWipe.icon, this.fullWipe.display,
                    this.fullWipe.description, info,
                    "Everything above, in one click. No undo.", "to choose"));
        }
        inv.setItem(UiKit.lastInteriorRow(inv.getSize()).get(6), UiKit.close());
        inv.setItem(13, UiKit.head(Bukkit.getOfflinePlayer(target), "&f" + target,
                List.of("&7The account these wipes apply to.")));
        UiKit.fill(inv);

        Session s = new Session();
        s.inventory = inv;
        s.stage = "main";
        s.target = target;
        s.targetUuid = targetUuid;
        this.sessions.put(viewer.getUniqueId(), s);
        viewer.openInventory(inv);
    }

    private void openAmounts(Player viewer, Session s, Category cat) {
        Inventory inv = UiKit.themed(3, "&8" + UiKit.strip(cat.display) + " &8| &f" + s.target);
        inv.setItem(10, UiKit.card(Material.TNT, "&c&lRESET ALL", "Set to zero",
                List.of("Wipes the whole balance."), "No undo.", "to select"));
        int slot = 12;
        for (int preset : cat.presets) {
            if (slot > 15) break;
            inv.setItem(slot++, UiKit.card(Material.GOLD_NUGGET, "&e&lTAKE " + preset,
                    "Remove exactly " + preset,
                    List.of("Leaves the rest of the balance alone."), null, "to select"));
        }
        if (!cat.takeCommands.isEmpty()) {
            inv.setItem(16, UiKit.card(Material.WRITABLE_BOOK, "&d&lCUSTOM AMOUNT",
                    "Type a number in chat",
                    List.of("Whole numbers only."), null, "to type an amount"));
        }
        inv.setItem(UiKit.lastInteriorRow(inv.getSize()).get(6), UiKit.back("the wipe menu"));
        UiKit.fill(inv);
        s.inventory = inv;
        s.stage = "amount";
        s.category = cat.id;
        viewer.openInventory(inv);
    }

    private void openStats(Player viewer, Session s, Category cat) {
        int rows = Math.min(6, Math.max(3, 2 + (cat.stats.size() + 8) / 9));
        Inventory inv = UiKit.themed(rows, "&8Stats &8| &f" + s.target);
        int slot = 9;
        for (String stat : cat.stats) {
            if (slot >= inv.getSize() - 9) break;
            inv.setItem(slot++, UiKit.card(Material.PAPER, "&b" + stat, "Reset this counter",
                    List.of("Runs: " + this.statCommand
                            .replace("%player%", CommandTemplate.safeName(s.target))
                            .replace("%stat%", CommandTemplate.safeToken(stat))
                            .replace("%value%", "0")),
                    null, "to select"));
        }
        inv.setItem(UiKit.lastInteriorRow(inv.getSize()).get(3), UiKit.card(Material.TNT, "&c&lALL STATS",
                "Reset every counter", List.of(cat.stats.size() + " counters set to 0."),
                "No undo.", "to select"));
        inv.setItem(UiKit.lastInteriorRow(inv.getSize()).get(6), UiKit.back("the wipe menu"));
        UiKit.fill(inv);
        s.inventory = inv;
        s.stage = "stats";
        s.category = cat.id;
        viewer.openInventory(inv);
    }

    private void openConfirm(Player viewer, Session s) {
        Inventory inv = UiKit.themed(3, "&8Confirm wipe &8| &f" + s.target);
        inv.setItem(11, UiKit.item(Material.LIME_WOOL, "&a&lCONFIRM",
                "&7Target: &f" + s.target,
                "&7Action: &f" + s.label,
                " ",
                "&cThis runs console commands immediately.",
                " ",
                "&e" + UiKit.ARROW + "&e&l&n CLICK &r&eto run it"));
        inv.setItem(15, UiKit.item(Material.RED_WOOL, "&c&lCANCEL", "&7Change nothing."));
        inv.setItem(13, UiKit.head(Bukkit.getOfflinePlayer(s.target), "&f" + s.target,
                List.of("&7Nothing has run yet.")));
        UiKit.fill(inv);
        s.inventory = inv;
        s.stage = "confirm";
        viewer.openInventory(inv);
    }

    /* ------------------------------------------------------------------ */
    /*  Clicks                                                            */
    /* ------------------------------------------------------------------ */

    private boolean isPicker(Inventory inv) {
        if (inv == null) return false;
        for (Inventory tracked : this.pickerInventories.values()) {
            if (tracked.equals(inv)) return true;
        }
        return false;
    }

    private void handlePickerClick(Player viewer, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;
        int current = this.pickerSessions.getOrDefault(viewer.getUniqueId(), 0);
        if (slot == 45) {
            UiKit.sound(viewer, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPicker(viewer, current - 1);
            return;
        }
        if (slot == 53) {
            UiKit.sound(viewer, org.bukkit.Sound.UI_BUTTON_CLICK, 1.4F);
            openPicker(viewer, current + 1);
            return;
        }
        if (slot == 49) {
            viewer.closeInventory();
            return;
        }
        if (slot >= 45) return;

        List<Map.Entry<UUID, String>> all = new ArrayList<>(knownPlayers().entrySet());
        all.sort(Comparator.comparing(e -> e.getValue().toLowerCase(Locale.ROOT)));
        int index = current * 45 + slot;
        if (index < 0 || index >= all.size()) return;
        Map.Entry<UUID, String> entry = all.get(index);
        viewer.closeInventory();
        this.pickerSessions.remove(viewer.getUniqueId());
        this.pickerInventories.remove(viewer.getUniqueId());
        openMain(viewer, entry.getValue(), entry.getKey().toString());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) return;

        if (isPicker(event.getInventory())) {
            event.setCancelled(true);
            handlePickerClick(viewer, event);
            return;
        }

        Session s = this.sessions.get(viewer.getUniqueId());
        if (s == null || s.inventory == null || !s.inventory.equals(event.getInventory())) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        String name = clicked.getItemMeta() == null ? ""
                : ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (name == null || name.isBlank()) return;

        if (clicked.getType() == Material.BARRIER && name.contains("Close")) {
            viewer.closeInventory();
            this.sessions.remove(viewer.getUniqueId());
            return;
        }
        if (clicked.getType() == Material.ARROW) { openMain(viewer, s.target); return; }

        switch (s.stage) {
            case "main" -> {
                if (this.fullWipe != null && UiKit.strip(this.fullWipe.display).equalsIgnoreCase(name)) {
                    s.category = "full";
                    s.amount = "all";
                    s.label = "FULL WIPE (" + String.join(", ", this.fullOrder) + ")";
                    openConfirm(viewer, s);
                    return;
                }
                Category cat = byDisplay(name);
                if (cat == null) return;
                if (!cat.stats.isEmpty()) { openStats(viewer, s, cat); return; }
                if (cat.amounts && !cat.takeCommands.isEmpty()) { openAmounts(viewer, s, cat); return; }
                s.category = cat.id;
                s.amount = "all";
                s.label = UiKit.strip(cat.display) + " - reset all";
                openConfirm(viewer, s);
            }
            case "amount" -> {
                Category cat = this.categories.get(s.category);
                if (cat == null) return;
                if (clicked.getType() == Material.TNT) {
                    s.amount = "all";
                    s.label = UiKit.strip(cat.display) + " - reset all";
                    openConfirm(viewer, s);
                } else if (clicked.getType() == Material.WRITABLE_BOOK) {
                    viewer.closeInventory();
                    this.amountPrompts.put(viewer.getUniqueId(), s.category);
                    viewer.sendMessage(UiKit.colour("&eType the amount to remove, or &fcancel&e."));
                } else if (clicked.getType() == Material.GOLD_NUGGET) {
                    String digits = name.replaceAll("[^0-9]", "");
                    if (digits.isEmpty()) return;
                    s.amount = digits;
                    s.label = UiKit.strip(cat.display) + " - take " + digits;
                    openConfirm(viewer, s);
                }
            }
            case "stats" -> {
                Category cat = this.categories.get(s.category);
                if (cat == null) return;
                if (clicked.getType() == Material.TNT) {
                    s.amount = "all";
                    s.label = "Reset every stat (" + cat.stats.size() + ")";
                } else {
                    if (!cat.stats.contains(name)) return;
                    s.amount = name;
                    s.label = "Reset stat " + name;
                }
                openConfirm(viewer, s);
            }
            case "confirm" -> {
                if (clicked.getType() == Material.LIME_WOOL) {
                    viewer.closeInventory();
                    run(viewer, s);
                    this.sessions.remove(viewer.getUniqueId());
                } else if (clicked.getType() == Material.RED_WOOL) {
                    openMain(viewer, s.target);
                }
            }
            default -> { }
        }
    }

    private Category byDisplay(String strippedName) {
        for (Category c : this.categories.values()) {
            if (UiKit.strip(c.display).equalsIgnoreCase(strippedName)) return c;
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAmountPrompt(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        String categoryId = this.amountPrompts.remove(p.getUniqueId());
        if (categoryId == null) return;
        event.setCancelled(true);
        String answer = event.getMessage().trim();
        SchedulerCompat.run(this.plugin, () -> {
            if (answer.equalsIgnoreCase("cancel")) {
                p.sendMessage(UiKit.colour("&7Cancelled."));
                return;
            }
            String digits = answer.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) {
                p.sendMessage(UiKit.colour("&cThat is not a number. Nothing was run."));
                return;
            }
            Session s = this.sessions.get(p.getUniqueId());
            if (s == null) { p.sendMessage(UiKit.colour("&cThat menu expired.")); return; }
            Category cat = this.categories.get(categoryId);
            s.category = categoryId;
            s.amount = digits;
            s.label = (cat == null ? categoryId : UiKit.strip(cat.display)) + " - take " + digits;
            openConfirm(p, s);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (SyntheticPlayerLoader.isSynthetic(event.getPlayer())) return;
        this.sessions.remove(event.getPlayer().getUniqueId());
        this.amountPrompts.remove(event.getPlayer().getUniqueId());
    }

    /* ------------------------------------------------------------------ */
    /*  Running                                                           */
    /* ------------------------------------------------------------------ */

    private void run(Player staff, Session s) {
        List<String> commands = new ArrayList<>();
        if ("full".equals(s.category)) {
            for (String id : this.fullOrder) {
                Category cat = this.categories.get(id.toLowerCase(Locale.ROOT));
                if (cat != null) commands.addAll(commandsFor(cat, "all", s.target, s.targetUuid));
            }
            commands.addAll(expand(this.fullWipe == null ? List.of() : this.fullWipe.allCommands,
                    s.target, "0", null, s.targetUuid));
        } else {
            Category cat = this.categories.get(s.category);
            if (cat == null) { staff.sendMessage(UiKit.colour("&cThat category vanished.")); return; }
            commands.addAll(commandsFor(cat, s.amount, s.target, s.targetUuid));
        }

        // The ender chest has no vanilla command. /clear empties the main
        // inventory and does not touch it, so a config line alone would have
        // silently done nothing while reporting success.
        int enderCleared = 0;
        boolean wantsEnder = "full".equals(s.category) || "enderchest".equals(s.category);
        if (wantsEnder) enderCleared = clearEnderChest(s.target);

        // The vanilla playtime statistic is separate from every command-driven
        // category, so it is cleared in code rather than through a command.
        String statResult = null;
        boolean wantsStats = this.resetBukkitStats
                && ("full".equals(s.category) || "playtime".equals(s.category));
        if (wantsStats) {
            UUID targetUuid = s.targetUuid != null ? parseUuidTarget(s.targetUuid) : parseUuidTarget(s.target);
            statResult = resetBukkitPlaytime(targetUuid);
        }

        if (commands.isEmpty() && !wantsEnder && !wantsStats) {
            staff.sendMessage(UiKit.colour("&eNothing to run - that category has no commands "
                    + "configured."));
            return;
        }
        int failed = 0;
        List<String> failedCommands = new ArrayList<>();
        for (String cmd : commands) {
            boolean dispatched;
            try {
                dispatched = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } catch (RuntimeException ex) {
                dispatched = false;
            }
            if (!dispatched) {
                failed++;
                failedCommands.add(cmd);
            }
        }

        if (failed > 0) {
            staff.sendMessage(UiKit.colour("&c&lWIPE INCOMPLETE &7- &f" + s.target));
            staff.sendMessage(UiKit.colour("&7" + failed + " of " + commands.size()
                    + " command(s) were rejected as unknown. The owning plugin is"
                    + " probably missing or disabled, so those parts did NOT run:"));
            for (String cmd : failedCommands) staff.sendMessage(UiKit.colour("&8  /" + cmd));
            this.plugin.getLogger().warning("[PlayerWipe] " + staff.getName() + " ran '"
                    + s.label + "' on " + s.target + " but " + failed
                    + " command(s) failed to dispatch: " + failedCommands);
        } else {
            staff.sendMessage(UiKit.colour("&a&lWIPE COMPLETE &7- &f" + s.target));
        }

        // Offline targets: most wipe commands either need the player online
        // (statsmgr, /clear) or silently no-op on one. Since every configured
        // command here is a reset and therefore idempotent, the set is run now
        // for the offline-capable ones and also queued, so the online-only ones
        // land the next time that account actually loads its data.
        UUID targetId = s.targetUuid != null ? parseUuidTarget(s.targetUuid) : parseUuidTarget(s.target);
        boolean offline = targetId != null && Bukkit.getPlayer(targetId) == null;
        if (offline && !commands.isEmpty() && this.queueOfflineWipes) {
            if (this.syntheticLoad && SyntheticPlayerLoader.withLoadedPlayerHeld(this.plugin, targetId, s.target, 100L, () -> {
                for (String cmd : commands) {
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    } catch (RuntimeException ignored) {
                    }
                }
            })) {
                staff.sendMessage(UiKit.colour("&aTarget loaded offline for 5s while its"
                        + " data loads, then &f" + commands.size() + " &acommand(s) applied and saved."));
            } else {
                QueuedWipe queued = queueWipe(targetId, s.target, s.label, commands);
                if (queued != null) {
                    staff.sendMessage(UiKit.colour("&eCould not load the offline target, so &f"
                            + queued.commands().size()
                            + " &ecommand(s) are queued until they next log in."));
                    staff.sendMessage(UiKit.colour("&8  Playerwipe pending &7to review the queue,"
                            + " &8Playerwipe cancel <uuid> &7to drop one."));
                } else {
                    staff.sendMessage(UiKit.colour("&cCould not load or queue the offline wipe; only the"
                            + " online-capable commands above actually ran."));
                }
            }
        }
        staff.sendMessage(UiKit.colour("&7" + s.label));
        if (wantsEnder) {
            staff.sendMessage(UiKit.colour(enderCleared >= 0
                    ? "&8  ender chest: " + enderCleared + " stack(s) removed"
                    : "&8  ender chest: player has never joined, nothing to clear"));
        }
        if (statResult != null) {
            staff.sendMessage("queued".equals(statResult)
                    ? UiKit.colour("&8  vanilla playtime stat: queued until they next log in")
                    : "no-uuid".equals(statResult)
                            ? UiKit.colour("&c  vanilla playtime stat: no UUID for this target")
                            : UiKit.colour("&8  vanilla playtime stat: cleared"));
        }
        for (String cmd : commands) staff.sendMessage(UiKit.colour("&8  /" + cmd));
        this.plugin.getLogger().info("[PlayerWipe] " + staff.getName() + " ran '" + s.label
                + "' on " + s.target + " (" + commands.size() + " command(s)"
                + (wantsEnder ? ", " + enderCleared + " ender stack(s)" : "") + ").");
        discordLog(staff.getName(), s.target, s.label, commands, enderCleared, wantsEnder);
        UiKit.sound(staff, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.4F);
    }

    /**
     * Empties the ender chest, online or off.
     *
     * @return stacks removed, or -1 when the account has no data to load
     */
    private int clearEnderChest(String target) {
        Player online = Bukkit.getPlayerExact(target);
        if (online != null) {
            int n = 0;
            for (ItemStack it : online.getEnderChest().getContents()) {
                if (it != null && it.getType() != Material.AIR) n++;
            }
            online.getEnderChest().clear();
            return n;
        }
        // Offline: Bukkit exposes the ender chest on OfflinePlayer only from
        // 1.20.4 onward, so this is reflective. On an older server the wipe
        // reports that it could not be done rather than pretending it was.
        OfflinePlayer off = Bukkit.getOfflinePlayer(target);
        if (!off.hasPlayedBefore()) return -1;
        try {
            Object inv = OfflinePlayer.class.getMethod("getEnderChest").invoke(off);
            org.bukkit.inventory.Inventory ender = (org.bukkit.inventory.Inventory) inv;
            int n = 0;
            for (ItemStack it : ender.getContents()) {
                if (it != null && it.getType() != Material.AIR) n++;
            }
            ender.clear();
            return n;
        } catch (Throwable ex) {
            this.plugin.getLogger().warning("[PlayerWipe] could not open " + target
                    + "'s offline ender chest (" + ex.getClass().getSimpleName()
                    + "). They must be online for that part.");
            return -1;
        }
    }

    /**
     * Mirrors the wipe to Discord through DiscordSRV.
     *
     * <p>Reflective and entirely optional — DiscordSRV is not a dependency, and
     * with it absent this does nothing at all. A wipe is destructive and
     * irreversible, so it belongs in a channel staff cannot quietly clear,
     * which is the point of logging it off-server.
     */
    private void discordLog(String staff, String target, String label,
                            List<String> commands, int enderCleared, boolean wantsEnder) {
        if (!this.discordEnabled || this.discordChannel.isBlank()) return;
        StringBuilder body = new StringBuilder();
        body.append("**").append(staff).append("** wiped **").append(target).append("**\n");
        body.append("Category: `").append(label).append("`\n");
        if (wantsEnder) {
            body.append("Ender chest: ")
                .append(enderCleared >= 0 ? enderCleared + " stack(s)" : "unavailable")
                .append('\n');
        }
        // The commands themselves are deliberately NOT listed. They name the
        // console syntax, the stat keys and the vault plugin behind the wipe, and
        // the Discord channel is read by more people than can run them. The count
        // is the part a reader needs; the syntax is not.
        body.append(commands.size()).append(" action(s) applied\n");
        final String message = body.toString();
        SchedulerCompat.runAsync(this.plugin, () -> {
            try {
                Class<?> srv = Class.forName("github.scarsz.discordsrv.DiscordSRV");
                Object instance = srv.getMethod("getPlugin").invoke(null);
                Object channel = srv.getMethod("getDestinationTextChannelForGameChannelName",
                        String.class).invoke(instance, this.discordChannel);
                if (channel == null) {
                    this.plugin.getLogger().warning("[PlayerWipe] DiscordSRV has no channel "
                            + "mapped to '" + this.discordChannel
                            + "'. Add it under channels in DiscordSRV's config.");
                    return;
                }
                // Found by shape, not by signature. DiscordUtil.sendMessage has
                // several overloads and the channel parameter type has moved
                // package twice across JDA versions; matching on "two params,
                // second is a String, first accepts what we hold" survives that.
                Class<?> util = Class.forName("github.scarsz.discordsrv.util.DiscordUtil");
                for (java.lang.reflect.Method m : util.getMethods()) {
                    if (!m.getName().equals("sendMessage")) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 2 || p[1] != String.class) continue;
                    if (!p[0].isInstance(channel)) continue;
                    m.invoke(null, channel, message);
                    return;
                }
                this.plugin.getLogger().warning("[PlayerWipe] DiscordSRV is installed but no "
                        + "usable DiscordUtil.sendMessage(channel, String) was found; "
                        + "wipe not mirrored.");
            } catch (ClassNotFoundException ex) {
                // DiscordSRV not installed. Nothing worth saying.
            } catch (Throwable ex) {
                this.plugin.getLogger().warning("[PlayerWipe] DiscordSRV log failed: "
                        + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
        });
    }

    /** Resolves one category plus one choice into console commands. */
    private List<String> commandsFor(Category cat, String choice, String target) {
        return commandsFor(cat, choice, target, null);
    }

    private List<String> commandsFor(Category cat, String choice, String target, String uuid) {
        if (!cat.stats.isEmpty()) {
            List<String> out = new ArrayList<>();
            List<String> stats = "all".equals(choice) ? cat.stats : List.of(choice);
            for (String stat : stats) {
                String line = this.statCommand
                        .replace("%player%", CommandTemplate.safeName(target))
                        .replace("%stat%", CommandTemplate.safeToken(stat))
                        .replace("%value%", "0");
                if (uuid != null && !uuid.isBlank()) {
                    line = line.replace("%uuid%", uuid).replace("%UUID%", uuid);
                } else {
                    line = line.replace("%uuid%", "").replace("%UUID%", "");
                }
                out.add(line.replaceFirst("^/", ""));
            }
            return out;
        }
        if ("all".equals(choice) || cat.takeCommands.isEmpty()) {
            return expand(cat.allCommands, target, "0", null, uuid);
        }
        return expand(cat.takeCommands, target, choice, null, uuid);
    }

    private static List<String> expand(List<String> templates, String player,
                                       String amount, String stat) {
        return expand(templates, player, amount, stat, null);
    }

    private static List<String> expand(List<String> templates, String player,
                                       String amount, String stat, String uuid) {
        List<String> out = new ArrayList<>();
        String safePlayer = CommandTemplate.safeName(player);
        String safeUuid = uuid == null ? "" : uuid.trim();
        for (String t : templates) {
            if (t == null || t.isBlank()) continue;
            String line = t.replace("%player%", safePlayer)
                     .replace("%PLAYER%", safePlayer)
                     .replace("%amount%", amount)
                     .replace("%stat%", stat == null ? "" : stat);
            if (!safeUuid.isEmpty()) {
                line = line.replace("%uuid%", safeUuid).replace("%UUID%", safeUuid);
            } else {
                line = line.replace("%uuid%", "").replace("%UUID%", "");
            }
            out.add(line.replaceFirst("^/", ""));
        }
        return out;
    }

    /** Tab completion source for the second argument. */
    public List<String> categoryNames() {
        return new ArrayList<>(this.categories.keySet());
    }
}
