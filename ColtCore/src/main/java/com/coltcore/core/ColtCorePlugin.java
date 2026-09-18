/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  io.papermc.paper.event.player.AsyncChatEvent
 *  net.kyori.adventure.audience.Audience
 *  net.kyori.adventure.text.Component
 *  net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
 *  net.kyori.adventure.title.Title
 *  net.kyori.adventure.title.Title$Times
 *  net.luckperms.api.LuckPerms
 *  net.luckperms.api.model.user.User
 *  org.bukkit.Bukkit
 *  org.bukkit.ChatColor
 *  org.bukkit.Location
 *  org.bukkit.Material
 *  org.bukkit.OfflinePlayer
 *  org.bukkit.Sound
 *  org.bukkit.World
 *  org.bukkit.block.Block
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.ConsoleCommandSender
 *  org.bukkit.configuration.file.FileConfiguration
 *  org.bukkit.configuration.file.YamlConfiguration
 *  org.bukkit.entity.HumanEntity
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.EventPriority
 *  org.bukkit.event.HandlerList
 *  org.bukkit.event.Listener
 *  org.bukkit.event.inventory.InventoryClickEvent
 *  org.bukkit.event.player.AsyncPlayerChatEvent
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.event.player.PlayerRespawnEvent
 *  org.bukkit.inventory.Inventory
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredListener
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 *  org.bukkit.scheduler.BukkitTask
 */
package com.coltcore.core;

import com.coltcore.core.modules.AntiAdPipeline;
import com.coltcore.core.modules.BillFordModule;
import com.coltcore.core.modules.ChatLimiterModule;
import com.coltcore.core.modules.CreativeGuardModule;
import com.coltcore.core.modules.IntegratedCoreModuleX;
import com.coltcore.core.modules.JoinPacketIsolation;
import com.coltcore.core.modules.ReviewModule;
import com.coltcore.core.modules.StashModule;
import com.coltcore.core.modules.RedeemCodeModule;
import com.coltcore.core.modules.EntityLimitModule;
import com.coltcore.core.modules.RewardsModule;
import com.coltcore.core.modules.DeepslateDecoyModule;
import com.coltcore.core.modules.KelpGrowthModule;
import com.coltcore.core.modules.StaffMonitorModule;
import com.coltcore.core.modules.ChatGuardModule;
import com.coltcore.core.modules.ActiveRankModule;
import com.coltcore.core.modules.DiagnosticsModule;
import com.coltcore.core.modules.UiKit;
import com.coltcore.core.modules.RedstoneThrottle;
import com.coltcore.core.modules.RedstoneUnstaler;
import com.coltcore.core.modules.PlayerWipeModule;
import com.coltcore.core.modules.ConsoleGuard;
import com.coltcore.core.modules.StaffMacroModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class ColtCorePlugin
extends JavaPlugin
implements Listener {
    private static final Pattern HEX_COLOR = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final Map<UUID, Long> rtpCooldowns = new HashMap<UUID, Long>();
    private final List<UUID> rtpQueue = new ArrayList<UUID>();
    /**
     * The canonical staff ladder, low to high.
     *
     * <p>This is the single source of truth for "is this group staff?". The
     * plugin used to answer that question in four different places with four
     * different lists: this field (which stopped at {@code media} and had no
     * executives at all), {@code staffmanager.ladder} in config.yml (which
     * stopped at {@code sradmin}), {@link #isManagerPlus} (a hardcoded
     * {@code Set.of} that knew about {@code executive} but not
     * {@code developer}), and {@code staff-macro.staff-ranks}. A Senior Admin
     * was staff to one of them and not the next.
     *
     * <p>{@link #STAFF_ROLES} is now the one list. Config still wins where it
     * is set - {@code staffmanager.ladder} and {@code staff-macro.staff-ranks}
     * are read first and only fall back to this - but the fallback is no
     * longer a truncated copy.
     */
    private static final List<String> STAFF_ROLES = List.of(
            "helper", "jrmod", "mod", "srmod", "jradmin", "admin", "sradmin",
            "manager", "executive", "dev", "developer", "co-owner", "owner");

    /** Non-staff ranks that are still shown in staff listings. */
    private static final List<String> STAFF_ADJACENT_ROLES = List.of("partner", "media", "builder");

    /**
     * Manager and above - the gate for creator codes, promoting, demoting and
     * hiring. Kept as a set so the check is one lookup rather than a walk up
     * the ladder: {@code executive} and {@code dev} sit below the owner but
     * above the managers and must not be locked out.
     */
    private static final Set<String> MANAGER_PLUS_ROLES = Set.of(
            "manager", "executive", "dev", "developer", "co-owner", "owner");

    private final List<String> staffRanks = STAFF_ROLES;
    private String activeRtpQueueWorld;
    private SchedulerCompat.ManagedTask tipTask;
    private SchedulerCompat.ManagedTask rtpTask;
    private int tipIndex;
    private int rtpTimer;
    private LuckPerms luckPerms;
    private File blocksFile;
    private FileConfiguration blocks;
    private File codesFile;
    private FileConfiguration codes;
    private ChatLimiterModule chatLimiterModule;
    private CreativeGuardModule creativeGuardModule;
    private BillFordModule billFordModule;
    private StashModule stashModule;
    private DeepslateDecoyModule deepslateDecoyModule;
    private KelpGrowthModule kelpGrowthModule;
    private StaffMonitorModule staffMonitorModule;
    private ChatGuardModule chatGuardModule;
    private AntiAdPipeline antiAdPipeline;

    public ChatGuardModule chatGuard() { return this.chatGuardModule; }
    private ActiveRankModule activeRankModule;
    private RedstoneThrottle redstoneThrottle;
    private RedstoneUnstaler redstoneUnstaler;
    private StaffMacroModule staffMacroModule;
    private PlayerWipeModule playerWipeModule;
    private DiagnosticsModule diagnosticsModule;
    private ReviewModule reviewModule;
    private RedeemCodeModule redeemModule;
    private RewardsModule rewardsModule;
    private EntityLimitModule entityLimitModule;
    private ConsoleGuard consoleGuard;
    private JoinPacketIsolation joinPacketIsolation;

    public void onEnable() {
        this.saveDefaultConfig();
        // Additive merge of any keys added since the file on disk was written.
        // Backs up first; never overwrites a value the owner has set.
        ConfigUpdater.run(this);
        this.migrateVirtualSpawnerMoneyEvent();
        this.loadFiles();
        RegisteredServiceProvider provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        this.luckPerms = provider == null ? null : (LuckPerms)provider.getProvider();
        Bukkit.getPluginManager().registerEvents((Listener)this, (Plugin)this);
        this.chatLimiterModule = new ChatLimiterModule(this);
        this.billFordModule = new BillFordModule(this);
        this.billFordModule.enable();
        // The dedicated minecart limiter is retired: EntityLimitModule now owns
        // the per-chunk cap and the minecart refund, and it does so silently.
        Bukkit.getPluginManager().registerEvents((Listener)this.chatLimiterModule, (Plugin)this);
        Bukkit.getPluginManager().registerEvents((Listener)this.billFordModule, (Plugin)this);
        IntegratedCoreModuleX.register(this);
        this.joinPacketIsolation = new JoinPacketIsolation(this);
        this.joinPacketIsolation.enable();
        this.stashModule = new StashModule(this);
        this.stashModule.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.stashModule, (Plugin)this);
        this.deepslateDecoyModule = new DeepslateDecoyModule(this);
        this.deepslateDecoyModule.enable();
        this.kelpGrowthModule = new KelpGrowthModule(this);
        this.kelpGrowthModule.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.kelpGrowthModule, (Plugin)this);
        this.staffMonitorModule = new StaffMonitorModule(this);
        this.staffMonitorModule.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.staffMonitorModule, (Plugin)this);
        this.chatGuardModule = new ChatGuardModule(this);
        this.chatGuardModule.enable();
        // Anti-ad pipeline: layer 2 and layer 3, both the in-process classifier.
        this.antiAdPipeline = new AntiAdPipeline(this);
        this.chatGuardModule.setAntiAd(this.antiAdPipeline);
        Bukkit.getPluginManager().registerEvents((Listener)this.chatGuardModule, (Plugin)this);
        this.activeRankModule = new ActiveRankModule(this);
        this.activeRankModule.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.activeRankModule, (Plugin)this);
        // Grim is optional. The bridge binds by name and reports unavailable
        // rather than failing when it is absent.
        this.redstoneThrottle = new RedstoneThrottle(this);
        this.redstoneThrottle.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.redstoneThrottle, (Plugin)this);
        this.redstoneUnstaler = new RedstoneUnstaler(this);
        this.redstoneUnstaler.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.redstoneUnstaler, (Plugin)this);
        this.staffMacroModule = new StaffMacroModule(this, null);
        this.staffMacroModule.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.staffMacroModule, (Plugin)this);
        this.playerWipeModule = new PlayerWipeModule(this);
        this.playerWipeModule.enable();
        Bukkit.getPluginManager().registerEvents((Listener)this.playerWipeModule, (Plugin)this);
        // IntegratedCoreModuleX binds /playerwipe to its own broken menu during
        // register(). Take the command back for the rebuilt one.
        rebind("playerwipe");
        // Retention: evidence-keep-days had never actually been enforced.
        // Human sign-off. Wired after the guard so it can hand it the queue.
        this.reviewModule = new ReviewModule(this, this.staffMacroModule,
                this.chatGuardModule.localAi());
        this.reviewModule.enable();
        this.chatGuardModule.setReview(this.reviewModule);
        this.redeemModule = new RedeemCodeModule(this);
        this.redeemModule.enable();
        this.redeemModule.setCreatorGate(this::isManagerPlus);
        this.rewardsModule = new RewardsModule(this);
        this.rewardsModule.enable();
        // Chunk entity cap, minecart refund and machine-launch refusal. Silent
        // to players by design; staff alerts go to the console.
        this.entityLimitModule = new EntityLimitModule(this);
        this.entityLimitModule.enable();
        // Console output is screened with the same rules as chat.
        this.consoleGuard = new ConsoleGuard(this, this.chatGuardModule);
        this.consoleGuard.enable();
        this.diagnosticsModule = new DiagnosticsModule(this,
                this.chatGuardModule, this.staffMacroModule, null,
                null, this.playerWipeModule, this.activeRankModule);
        Bukkit.getPluginManager().registerEvents((Listener)this.diagnosticsModule, (Plugin)this);
    }

    /** Points a command declared in plugin.yml back at this plugin's onCommand. */
    private void rebind(String command) {
        try {
            org.bukkit.command.PluginCommand pc = this.getCommand(command);
            if (pc != null) {
                pc.setExecutor(this);
                pc.setTabCompleter(this);
            }
        } catch (Throwable ex) {
            this.getLogger().warning("Could not rebind /" + command + ": " + ex.getMessage());
        }
    }

    private void migrateVirtualSpawnerMoneyEvent() {
        File file = new File(this.getDataFolder().getParentFile(), "VirtualSpawner/other/events.yml");
        if (!file.isFile()) return;
        try {
            YamlConfiguration events = YamlConfiguration.loadConfiguration(file);
            if (!events.contains("spawner-events.double-xp") && events.contains("spawner-events.double-money")) return;
            String oldPath = "spawner-events.double-xp";
            String path = "spawner-events.double-money";
            events.set(path + ".weight", events.getInt(oldPath + ".weight", 10));
            events.set(path + ".xp-multiplier", 1.0D);
            events.set(path + ".cash-multiplier", 2.0D);
            events.set(path + ".duration", events.getInt(oldPath + ".duration", 180));
            events.set(path + ".days", events.getStringList(oldPath + ".days"));
            events.set(path + ".time-range.start", events.getString(oldPath + ".time-range.start", "14:00"));
            events.set(path + ".time-range.end", events.getString(oldPath + ".time-range.end", "22:00"));
            events.set(path + ".season.start-month", events.getInt(oldPath + ".season.start-month", 6));
            events.set(path + ".season.end-month", events.getInt(oldPath + ".season.end-month", 8));
            events.set(path + ".start-messages", List.of(
                    "&8&l&m----------------------------------------", "&a&l✦ Double Money Spawner Event Started ✦", " ",
                    "&fDuration: &a{duration} seconds", "&fMoney Boost: &ax{cash_multiplier}", " ",
                    "&8&l&m----------------------------------------"));
            events.set(path + ".end-messages", List.of(
                    "&8&l&m----------------------------------------", "&c&l✦ Double Money Spawner Event Ended ✦", " ",
                    "&7Thanks for playing - see you at the next one.", "&8&l&m----------------------------------------"));
            events.set(path + ".start-commands", List.of("broadcast Double Money spawner event has begun!"));
            events.set(path + ".end-commands", List.of("broadcast Double Money spawner event has ended!"));
            events.set(oldPath, null);
            events.save(file);
            this.getLogger().info("Migrated VirtualSpawner double XP event to the 2x money event.");
        } catch (Exception exception) {
            this.getLogger().warning("Could not migrate VirtualSpawner events.yml: " + exception.getMessage());
        }
    }

    public void onDisable() {
        if (this.joinPacketIsolation != null) this.joinPacketIsolation.disable();
        if (this.tipTask != null) {
            this.tipTask.cancel();
        }
        if (this.rtpTask != null) {
            this.rtpTask.cancel();
        }
        if (this.stashModule != null) this.stashModule.disable();
        if (this.kelpGrowthModule != null) this.kelpGrowthModule.disable();
        if (this.staffMonitorModule != null) this.staffMonitorModule.disable();
        if (this.chatGuardModule != null) this.chatGuardModule.disable();
        if (this.activeRankModule != null) this.activeRankModule.disable();
        if (this.staffMacroModule != null) this.staffMacroModule.disable();
        if (this.redstoneThrottle != null) this.redstoneThrottle.disable();
        if (this.redstoneUnstaler != null) this.redstoneUnstaler.disable();
        if (this.playerWipeModule != null) this.playerWipeModule.disable();
        if (this.reviewModule != null) this.reviewModule.disable();
        if (this.diagnosticsModule != null) this.diagnosticsModule.disable();
        if (this.rewardsModule != null) this.rewardsModule.disable();
        if (this.entityLimitModule != null) this.entityLimitModule.disable();
        if (this.consoleGuard != null) this.consoleGuard.disable();
        if (this.antiAdPipeline != null) this.antiAdPipeline.disable();
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name;
        name = command.getName().toLowerCase(Locale.ROOT);
        if (!authorise(sender, name)) return true;
        return switch (name) {
            case "coltcore" -> this.reload(sender, args);
            case "announce" -> this.announce(sender, args);
            case "keyall" -> this.keyall(sender, args);
            case "kitall" -> this.kitall(sender, args);
            case "shardall" -> this.shardall(sender, args);
            case "rankgive" -> this.rankGive(sender, args);
            case "staffcode" -> this.staffCode(sender, args);
            case "promote" -> this.promote(sender, args);
            case "demote" -> this.demote(sender, args);
            case "hire" -> this.hire(sender, args);
            case "joinalerts" -> this.togglePreference(sender, "join-alerts", "Join alerts");
            case "announcements" -> this.togglePreference(sender, "announcements", "Announcements");
            case "block", "ignore" -> this.block(sender, label, args);
            case "rtpqueue" -> this.rtpQueue(sender, args);
            case "billfordtoggle" -> this.billFordModule.toggle(sender);
            case "billfordadmin" -> this.billFordModule.admin(sender, args);
            case "spawnstash" -> this.stashModule.command(sender, args);
            case "deepslatedecoy" -> this.deepslateDecoyModule.command(sender, args);
            case "staffmonitor", "staffactivity", "staffafk" ->
                    this.staffMonitorModule.command(sender, name, args);
            case "textguard", "coltguard" -> this.chatGuardModule.command(sender, args);
            case "activerank", "coltactive" -> this.activeRankModule.command(sender, args);
            case "staffmacro" -> this.staffMacroModule.command(sender, args);
            case "playerwipe" -> this.playerWipeModule.command(sender, args);
            case "review" -> this.reviewModule.command(sender, args);
            case "rewards", "dailyrewards", "playtimerewards" -> this.rewardsModule.command(sender, args);
            case "redeem" -> this.redeemModule.redeem(sender, args);
            case "redeemcode" -> this.redeemModule.admin(sender, args);
            default -> false;
        };
    }

    /* ------------------------------------------------------------------ */
    /*  Command authorisation                                             */
    /* ------------------------------------------------------------------ */

    /**
     * The permission every command requires, keyed by command name and alias.
     *
     * <h2>Why this exists when plugin.yml already has a {@code permission:} key</h2>
     * Because eight commands did not have one, and Bukkit only enforces what is
     * declared. {@code /joinalerts}, {@code /announcements}, {@code /block},
     * {@code /ignore}, {@code /rtpqueue}, {@code /joinmessage},
     * {@code /activerank} and {@code /redeem} were reachable by anybody with no
     * check anywhere — not in plugin.yml, not in the handler. Four more
     * ({@code announce.use}, {@code *.admin.keyall}, {@code kitall},
     * {@code shardall}) named a permission that was never declared under
     * {@code permissions:}, so it had no default and resolved by whatever the
     * permission plugin happened to decide.
     *
     * <p>Declaring them in plugin.yml is also done, and is the primary gate.
     * This table is the second one: it covers aliases, it covers the commands
     * {@link IntegratedCoreModuleX} binds to its own executors, and it survives
     * somebody editing plugin.yml. A command absent from this map is refused
     * rather than allowed, so adding a command without deciding who may run it
     * is not possible.
     */
    private static final Map<String, String> COMMAND_PERMISSIONS = Map.ofEntries(
            // --- admin ---
            Map.entry("coltcore",         "coltcore.admin"),
            Map.entry("announce",         "coltcore.announce"),
            Map.entry("keyall",           "coltcore.admin.keyall"),
            Map.entry("kitall",           "coltcore.admin.kitall"),
            Map.entry("shardall",         "coltcore.admin.shardall"),
            Map.entry("rankgive",         "coltcore.rankgive"),
            Map.entry("staffcode",        "coltcore.staffmanager"),
            Map.entry("promote",          "coltcore.staffmanager"),
            Map.entry("demote",           "coltcore.staffmanager"),
            Map.entry("hire",             "coltcore.staffmanager"),
            Map.entry("billfordadmin",    "gildedbillford.admin"),
            Map.entry("spawnstash",       "coltcore.spawnstash"),
            Map.entry("deepslatedecoy",   "coltcore.deepslatedecoy"),
            Map.entry("staffmonitor",     "staffmonitor.view"),
            Map.entry("staffactivity",    "staffmonitor.view"),
            Map.entry("staffafk",         "staffmonitor.view"),
            Map.entry("textguard",        "coltcore.chatguard.admin"),
            Map.entry("coltguard",        "coltcore.chatguard.admin"),
            Map.entry("staffmacro",       "coltcore.staffmacro.admin"),
            Map.entry("playerwipe",       "coltcore.playerwipe"),
            Map.entry("review",           "coltcore.review"),
            Map.entry("redeemcode",       "coltcore.redeemcode"),
            Map.entry("rewards",          "coltcore.rewards"),
            Map.entry("dailyrewards",     "coltcore.rewards"),
            Map.entry("playtimerewards",  "coltcore.rewards"),
            // --- everyone, but still named, so it can be revoked ---
            Map.entry("joinalerts",       "coltcore.joinalerts"),
            Map.entry("announcements",    "coltcore.announcements"),
            Map.entry("block",            "coltcore.block"),
            Map.entry("ignore",           "coltcore.block"),
            Map.entry("rtpqueue",         "coltcore.rtpqueue"),
            Map.entry("joinmessage",      "coltcore.joinmessage"),
            Map.entry("activerank",       "coltcore.activerank"),
            Map.entry("coltactive",       "coltcore.activerank"),
            Map.entry("redeem",           "coltcore.redeem"),
            Map.entry("billfordtoggle",   "gildedbillford.use"),
            Map.entry("ping",             "coltcore.ping"));

    /** The permission a command needs, or null when it is not one of ours. */
    public static String permissionFor(String command) {
        return command == null ? null
                : COMMAND_PERMISSIONS.get(command.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether this sender may run this command, with the refusal already sent.
     *
     * <p>Console is always allowed: it is the server operator by definition, and
     * refusing it would break the console integrations that call
     * {@code /rankgive} and {@code /shardall}. A {@code ProxiedCommandSender}
     * (e.g. a player proxying a command via another plugin) is NOT a console
     * sender, so it falls through to the permission check below like any
     * other player.
     */
    private boolean authorise(CommandSender sender, String command) {
        return authorise(sender, command, this);
    }

    /** Shared with {@link IntegratedCoreModuleX}, which owns its own executors. */
    public static boolean authorise(CommandSender sender, String command, Plugin plugin) {
        if (sender instanceof ConsoleCommandSender) return true;
        String perm = permissionFor(command);
        if (perm == null) {
            // Refuse rather than allow. An unmapped command reaching here is a
            // mistake in this file, and the safe reading of a mistake about
            // authorisation is "no".
            sender.sendMessage(ChatColor.RED + "That command is not available.");
            if (plugin != null) {
                plugin.getLogger().warning("Command /" + command
                        + " has no entry in COMMAND_PERMISSIONS and was refused.");
            }
            return false;
        }
        if (sender.hasPermission(perm)) return true;
        sender.sendMessage(ChatColor.RED + "You do not have permission to use that."
                + ChatColor.DARK_GRAY + " (" + perm + ")");
        return false;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length > 0 && !args[0].equalsIgnoreCase("reload")
                && this.diagnosticsModule.command(sender, args)) {
            return true;
        }
        if (args.length == 0) return this.diagnosticsModule.command(sender, args);
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            this.reloadConfig();
            // Re-read the classifier settings and reopen the log database.
            // Without this, a model swap or a scan-mode change needs a full
            // restart and the previous database handle is left open.
            if (this.antiAdPipeline != null) this.antiAdPipeline.reload();
            this.loadFiles();
            this.chatLimiterModule.reload();
            this.billFordModule.reload();
            this.stashModule.reload();
            this.deepslateDecoyModule.reload();
            this.kelpGrowthModule.reload();
            this.staffMonitorModule.reload();
            this.chatGuardModule.reload();
            this.activeRankModule.reload();
            this.staffMacroModule.reload();
            this.playerWipeModule.reload();
            this.reviewModule.reload();
            if (this.entityLimitModule != null) this.entityLimitModule.reload();
            if (this.redstoneThrottle != null) this.redstoneThrottle.reload();
            if (this.redstoneUnstaler != null) this.redstoneUnstaler.reload();
            this.consoleGuard.reload();
            sender.sendMessage(this.color("&aColtCore reloaded."));
            return true;
        }
        sender.sendMessage(this.color("&e/coltcore <status|selftest|perms|prune|menu|reload>"));
        return true;
    }

    private boolean announce(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(this.color("&cUsage: /announce <message>"));
            return true;
        }
        Player player = sender instanceof Player ? (Player)sender : null;
        String displayPrefix = player == null ? "&#00ff00&lSERVER&f" : this.prefix(player);
        String rank = player == null ? "default" : this.group(player);
        String titleColor = player == null ? "&#00FF00" : this.rankColor(rank);
        String title = (titleColor == null || titleColor.isBlank() ? "&#00FF00" : titleColor) + "&lANNOUNCEMENT";
        String actor = player == null ? "ColtCore" : player.getName();
        String border = player == null ? "&#00ff00&l&m------------------------------&f" : this.gradientBorder(player);
        String msg = String.join((CharSequence)" ", args);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!this.announcementsEnabled(online)) continue;
            online.sendMessage(this.colorComponent(border));
            online.sendMessage(this.colorComponent(displayPrefix + " " + actor));
            online.sendMessage(this.colorComponent("&f" + msg));
            online.sendMessage(this.colorComponent(border));
            online.showTitle(Title.title((Component)this.colorComponent(title), (Component)this.colorComponent("&fFrom " + displayPrefix + " " + actor + "&f."), (Title.Times)Title.Times.times((Duration)Duration.ofMillis(300L), (Duration)Duration.ofSeconds(3L), (Duration)Duration.ofMillis(500L))));
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        return true;
    }

    private boolean keyall(CommandSender sender, String[] args) {
        int amount;
        if (args.length < 1) {
            sender.sendMessage(this.color("&cUsage: /keyall <crate> [amount]"));
            return true;
        }
        String crate = args[0].toLowerCase(Locale.ROOT).replace('-', '_');
        int n = amount = args.length >= 2 ? this.parseInt(args[1], 1) : 1;
        if (amount < 1 || amount > 64) {
            sender.sendMessage(this.color("&cAmount must be 1-64."));
            return true;
        }
        String actor = sender.getName();
        this.startReward("KEYALL", actor, "&e", () -> {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.dispatch(this.getConfig().getString("rewards.key-command", "crate give %player% %crate% %amount%").replace("%player%", player.getName()).replace("%crate%", crate).replace("%amount%", String.valueOf(amount)));
                this.playRewardSound(player);
                ++count;
            }
            this.broadcastRewardSummary(actor, this.coloredKeyName(crate), amount, count);
        });
        return true;
    }

    private boolean kitall(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(this.color("&cUsage: /kitall <kit> [amount]"));
            return true;
        }
        String raw = args[0].toLowerCase(java.util.Locale.ROOT).trim();
        if (raw.equals("cpvp") || raw.equals("cpvpneth")) {
            sender.sendMessage(this.color("&cThat kit is disabled."));
            return true;
        }
        String kit = this.kitId(args[0]);
        int amount = args.length >= 2 ? this.parseInt(args[1], 1) : 1;
        if (amount < 1 || amount > 64) {
            sender.sendMessage(this.color("&cAmount must be 1-64."));
            return true;
        }
        String actor = sender.getName();
        this.startReward("KITALL", actor, "&a", () -> {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                for (int i = 0; i < amount; i++) this.dispatch(this.getConfig().getString("rewards.kit-command", "kit give %kit% %player%").replace("%player%", player.getName()).replace("%kit%", kit));
                player.showTitle(Title.title((Component)this.colorComponent("&#00ff00&lYOU HAVE BEEN REWARDED"), (Component)this.colorComponent("&f" + kit + " kit")));
                this.playRewardSound(player);
                ++count;
            }
            this.broadcastRewardSummary(actor, kit + " kit", amount, count);
        });
        return true;
    }

    private boolean shardall(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage(this.color("&cUsage: /shardall <user> <text1> <text2> <number>"));
            return true;
        }
        String buyer = args[0];
        int amount = this.parseInt(args[3], 0);
        String purchase = (args[1] + " " + args[2]).replace('_', ' ');
        if (amount < 1) {
            sender.sendMessage(this.color("&cAmount must be positive."));
            return true;
        }
        OfflinePlayer buyerPlayer = Bukkit.getOfflinePlayer(buyer);
        String displayPrefix;
        String fallbackColor;
        Player onlineBuyer = buyerPlayer instanceof Player ? (Player) buyerPlayer : Bukkit.getPlayerExact(buyer);
        if (onlineBuyer != null) {
            displayPrefix = this.prefix(onlineBuyer);
            fallbackColor = this.rankColor(this.group(onlineBuyer));
        } else {
            String rank = this.group(buyerPlayer);
            fallbackColor = this.rankColor(rank);
            displayPrefix = this.rankDisplay(rank);
        }
        this.broadcastAnnouncement(fallbackColor, displayPrefix + " " + buyer, "&f" + buyer + " bought " + purchase + ", GG! Rewards in 5s.");
        this.startReward("SHARDALL", buyer, "&d", () -> {
            int count = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                this.dispatch(this.getConfig().getString("rewards.shard-command", "shard give %player% %amount%").replace("%player%", player.getName()).replace("%amount%", String.valueOf(amount)));
                ++count;
            }
            OfflinePlayer op = Bukkit.getOfflinePlayer(buyer);
            String bDisplayPrefix;
            String bBorder;
            Player p = Bukkit.getPlayerExact(buyer);
            if (p != null) {
                bDisplayPrefix = this.prefix(p);
                bBorder = this.gradientBorder(p);
            } else {
                String r = this.group(op);
                bDisplayPrefix = this.rankDisplay(r);
                bBorder = this.gradientBorder(op);
            }
            String announceTitle = (fallbackColor != null && !fallbackColor.isBlank() ? fallbackColor : "&#00FF00") + "&lANNOUNCEMENT";
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(this.colorComponent(bBorder));
                online.sendMessage(this.colorComponent(bDisplayPrefix + " " + buyer + " &7gave away &f" + amount + " &7shards/player"));
                online.sendMessage(this.colorComponent("&eshards &fgiven away to &e" + count + " &fplayers, GG!"));
                online.sendMessage(this.colorComponent(bBorder));
                online.showTitle(Title.title(this.colorComponent(announceTitle), this.colorComponent("&fFrom " + bDisplayPrefix + " " + buyer + "&f."), Title.Times.times(Duration.ofMillis(300L), Duration.ofSeconds(3L), Duration.ofMillis(500L))));
                online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        });
        return true;
    }

    private void startReward(String label, String actor, String color, Runnable reward) {
        int seconds = 5;
        long introDelay = 30L;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.showTitle(Title.title(this.colorComponent(color + "&l" + label + " BY " + actor + "!!"), Component.empty(), Title.Times.times(Duration.ofMillis(250L), Duration.ofMillis(1000L), Duration.ofMillis(350L))));
        }
        for (int remaining = seconds; remaining >= 1; remaining--) {
            int shown = remaining;
            SchedulerCompat.runLater(this, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.showTitle(Title.title(this.colorComponent(color + "&l" + label), this.colorComponent("&fIn " + color + shown + "s"), Title.Times.times(Duration.ofMillis(100L), Duration.ofMillis(700L), Duration.ofMillis(200L))));
                    player.sendMessage(this.color(color + label + " &fin " + color + shown + "s&f."));
                }
            }, introDelay + (seconds - remaining) * 20L);
        }
        SchedulerCompat.runLater(this, reward, introDelay + seconds * 20L);
    }

    private void broadcastRewardSummary(String actor, String reward, int amount, int playerCount) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(actor);
        String displayPrefix;
        String border;
        Player onlineActor = Bukkit.getPlayerExact(actor);
        if (onlineActor != null) {
            displayPrefix = this.prefix(onlineActor);
            border = this.gradientBorder(onlineActor);
        } else if (op != null && op.getUniqueId() != null) {
            displayPrefix = this.rankDisplay(this.group(op));
            border = this.gradientBorder(op);
        } else {
            displayPrefix = "&#00ff00&lSERVER&f";
            border = "&#00ff00&l&m------------------------------&f";
        }
        boolean isCrateReward = reward.toLowerCase(java.util.Locale.ROOT).contains("crate");
        String firstReward = reward;
        String secondReward = reward;
        if (isCrateReward) {
            String suffixFirst = amount == 1 ? " Key" : " Keys";
            String suffixSecond = (amount * playerCount > 1) ? " Keys" : " Key";
            if (!reward.toLowerCase(java.util.Locale.ROOT).contains("key")) {
                firstReward = reward + suffixFirst;
                secondReward = reward + suffixSecond;
            }
            Bukkit.broadcast(this.colorComponent(border));
            Bukkit.broadcast(this.colorComponent(displayPrefix + " " + actor + " &7gave away &f" + amount + " &7" + firstReward));
            Bukkit.broadcast(this.colorComponent("&e" + secondReward + " &fgiven away to &e" + playerCount + " &fplayers, GG!"));
            Bukkit.broadcast(this.colorComponent(border));
            return;
        }
        Bukkit.broadcast(this.colorComponent(border));
        Bukkit.broadcast(this.colorComponent(displayPrefix + " " + actor + " &7gave away &f" + amount + " &7" + reward + "/player"));
        Bukkit.broadcast(this.colorComponent("&e" + reward + " &fgiven away to &e" + playerCount + " &fplayers, GG!"));
        Bukkit.broadcast(this.colorComponent(border));
    }

    private void broadcastAnnouncement(String color, String heading, String message) {
        String border = gradientBorder(heading, color);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(this.colorComponent(border));
            player.sendMessage(this.colorComponent(heading));
            player.sendMessage(this.colorComponent(message));
            player.sendMessage(this.colorComponent(border));
        }
    }

    private void playRewardSound(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        SchedulerCompat.runLater(this, () -> player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f), 4L);
    }

    /**
     * Mints a one-time staff code and delivers it to Discord.
     *
     * <h2>What was wrong before</h2>
     * This printed "Staff codes are Discord-only" and did nothing else, and
     * nothing anywhere else wrote a code either. {@link #consumeCode} reads
     * codes.yml, codes.yml was never written, so every code was invalid and
     * /promote, /demote and /staffremove could not succeed at all.
     *
     * <h2>Discord-only means delivery, not absence</h2>
     * The code is generated here and sent to the configured DiscordSRV channel.
     * It is never printed in game, so holding the rank is not enough — you also
     * have to be in the staff channel to read it. That is the property the
     * original message was describing; it just was not implemented.
     *
     * <p>Console is the exception, and deliberately: DiscordSRV's own console
     * channel relays commands from Discord, so a code requested that way is
     * already being read in Discord. It is printed there so the flow works with
     * nothing but a console relay configured.
     */
    private boolean staffCode(CommandSender sender, String[] args) {
        boolean console = !(sender instanceof Player);
        if (!console && !this.isStaffManager((Player) sender)) {
            sender.sendMessage(this.color("&cOnly Managers, Co-Owners and Owners can request a code."));
            return true;
        }

        OfflinePlayer target;
        if (args.length >= 2) {
            target = Bukkit.getOfflinePlayer(args[1]);
        } else if (!console) {
            target = (Player) sender;
        } else {
            sender.sendMessage(this.color("&cUsage from console: /staffcode <player>"));
            return true;
        }
        if (target.getUniqueId() == null) {
            sender.sendMessage(this.color("&cUnknown player."));
            return true;
        }

        long ttlMinutes = Math.max(1L, this.getConfig().getLong("staffmanager.code-ttl-minutes", 10L));
        String code = this.randomCode();
        String path = "codes." + code;
        this.codes.set(path + ".owner", target.getUniqueId().toString());
        this.codes.set(path + ".name", target.getName());
        this.codes.set(path + ".expires", Instant.now().getEpochSecond() + ttlMinutes * 60L);
        this.codes.set(path + ".issued-by", sender.getName());
        this.saveCodes();

        String channel = this.getConfig().getString("staffmanager.discord-channel", "staff");
        String body = "**Staff code** for `" + target.getName() + "`\n"
                + "`" + code + "`\n"
                + "Requested by **" + sender.getName() + "**, expires in "
                + ttlMinutes + " minute(s). One use.";
        boolean sent = com.coltcore.core.modules.DiscordBridge.available();
        com.coltcore.core.modules.DiscordBridge.send(this, "[StaffCode]", channel, body);

        if (console) {
            sender.sendMessage(this.color("&#00ff00Staff code for &f" + target.getName()
                    + "&#00ff00: &e" + code + " &7(expires in " + ttlMinutes + "m, one use)"));
        } else {
            this.boxMessage(sender, "&#00ff00Staff Code",
                    sent ? "&7Sent to the &f#" + channel + " &7Discord channel."
                         : "&cDiscordSRV is not installed, so there is nowhere to send it. "
                           + "&7Run &f/staffcode " + target.getName() + " &7from console.");
        }
        this.getLogger().info("[StaffCode] issued for " + target.getName()
                + " by " + sender.getName() + ", expires in " + ttlMinutes + "m.");
        return true;
    }

    private boolean rankGive(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) && !sender.hasPermission("coltcore.rankgive")) {
            sender.sendMessage(this.color("&cOnly console or admins can give paid ranks."));
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(this.color("&cUsage: /rankgive <player> <rank>"));
            return true;
        }
        String rank = args[1].toLowerCase(Locale.ROOT);
        if (!this.availableRewardPurchases().contains(rank) && !this.getConfig().getStringList("rankgive.allowed-ranks").contains(rank)) {
            sender.sendMessage(this.color("&cUnknown or disallowed rank: &f" + rank));
            return true;
        }
        this.dispatch(this.getConfig().getString("rankgive.command", "lp user %player% parent set %rank%").replace("%player%", args[0]).replace("%rank%", rank));
        sender.sendMessage(this.color("&aRank given: &f" + args[0] + " &8-> &e" + rank));
        return true;
    }

    private boolean promote(CommandSender sender, String[] args) {
        Player actor;
        if (!(sender instanceof Player) || !this.isStaffManager(actor = (Player)sender)) {
            sender.sendMessage(this.color("&cOnly Managers, Co-Owners, and Owners can promote."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(this.color("&cUsage: /promote <player> [rank] <code>"));
            return true;
        }
        String code = args[args.length - 1];
        if (!this.consumeCode(actor, code)) {
            sender.sendMessage(this.color("&cInvalid or expired staff code."));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer((String)args[0]);
        CompletableFuture.runAsync(() -> {
            String current = this.group(target);
            String targetRank = args.length >= 3 ? args[1].toLowerCase(Locale.ROOT) : this.nextRank(current);
            SchedulerCompat.run(this, () -> this.setRank(actor, target, targetRank, "promoted"));
        });
        return true;
    }

    private boolean demote(CommandSender sender, String[] args) {
        Player actor;
        if (!(sender instanceof Player) || !this.isStaffManager(actor = (Player)sender)) {
            sender.sendMessage(this.color("&cOnly Managers, Co-Owners, and Owners can demote."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(this.color("&cUsage: /demote <player> <code> [reason]"));
            return true;
        }
        if (!this.consumeCode(actor, args[1])) {
            sender.sendMessage(this.color("&cInvalid or expired staff code."));
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer((String)args[0]);
        String reason = args.length >= 3 ? String.join((CharSequence)" ", List.of(args).subList(2, args.length)) : "No reason provided.";
        CompletableFuture.runAsync(() -> {
            String next = this.previousRank(this.group(target));
            SchedulerCompat.run(this, () -> this.setRank(actor, target, next, "demoted: " + reason));
        });
        return true;
    }

    private boolean hire(CommandSender sender, String[] args) {
        Player actor;
        if (!(sender instanceof Player) || !this.isStaffManager(actor = (Player)sender)) {
            sender.sendMessage(this.color("&cOnly Managers, Co-Owners, and Owners can hire."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(this.color("&cUsage: /hire <player> [helper|jrmod|media] <code>"));
            return true;
        }
        String code = args[args.length - 1];
        if (!this.consumeCode(actor, code)) {
            sender.sendMessage(this.color("&cInvalid or expired staff code."));
            return true;
        }
        String rank = args.length >= 3 ? args[1].toLowerCase(Locale.ROOT) : "helper";
        // Entry-level only. Hiring straight to manager or above would bypass
        // the ladder entirely; those moves go through /promote.
        if (!rank.equals("helper") && !rank.equals("jrmod") && !rank.equals("media")) {
            sender.sendMessage(this.color("&cHire rank must be helper, jrmod or media."));
            return true;
        }
        this.setRank(actor, Bukkit.getOfflinePlayer((String)args[0]), rank, "hired");
        return true;
    }

    private void setRank(Player actor, OfflinePlayer target, String rank, String action) {
        this.dispatch("lp user " + target.getName() + " parent set " + rank);
        this.boxMessage(actor, "&#00ff00Staff &7action by &f" + actor.getName(), "&f" + target.getName() + " &7was &e" + action + " &7to &f" + rank + "&7.");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.hasPermission("coltcore.staffnotify") && !online.hasPermission("staffmanager.staff")) continue;
            online.sendMessage(this.color("&8[&#00ff00Staff&8] &f" + target.getName() + " &7" + action + " by &f" + actor.getName() + " &8-> &e" + rank));
        }
    }

    private boolean staffList(CommandSender sender) {
        sender.sendMessage(this.color("&#00ff00Online Staff:"));
        for (Player player : Bukkit.getOnlinePlayers()) {
            String group = this.group(player);
            if (!this.isStaffGroup(group) && !STAFF_ADJACENT_ROLES.contains(group)) continue;
            sender.sendMessage(this.color("&7- " + this.rankColor(group) + group + " &f" + player.getName()));
        }
        return true;
    }

    private boolean block(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.color("&cOnly players can use this."));
            return true;
        }
        Player player = (Player)sender;
        if (args.length != 1) {
            player.sendMessage(this.color("&e/" + label + " <player>"));
            return true;
        }
        String path = "blocked." + String.valueOf(player.getUniqueId());
        ArrayList<String> list = new ArrayList<String>(this.blocks.getStringList(path));
        String target = args[0].toLowerCase(Locale.ROOT);
        if (target.equals(player.getName().toLowerCase(Locale.ROOT))) {
            player.sendMessage(this.color("&cYou cannot block yourself."));
            return true;
        }
        if (list.removeIf(value -> value.equalsIgnoreCase(target))) {
            this.blocks.set(path, list);
            this.saveBlocks();
            player.sendMessage(this.color("&aUnblocked &f" + args[0] + "&a."));
        } else {
            list.add(target);
            this.blocks.set(path, list);
            this.saveBlocks();
            player.sendMessage(this.color("&cBlocked &f" + args[0] + "&c."));
        }
        return true;
    }

    private boolean togglePreference(CommandSender sender, String key, String displayName) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.color("&cOnly players can use this."));
            return true;
        }
        Player player = (Player)sender;
        String path = "preferences." + String.valueOf(player.getUniqueId()) + "." + key;
        boolean enabled = !this.blocks.getBoolean(path, true);
        this.blocks.set(path, (Object)enabled);
        this.saveBlocks();
        player.sendMessage(this.color((enabled ? "&a" : "&c") + displayName
                + (enabled ? " enabled!" : " disabled!")));
        return true;
    }

    private boolean rtpQueue(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(this.color("&cOnly players can use RTPQueue."));
            return true;
        }
        Player player = (Player)sender;
        if (args.length == 0) {
            this.openRtpQueueMenu(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("leave") || args[0].equalsIgnoreCase("exit")) {
            this.rtpQueue.remove(player.getUniqueId());
            if (this.rtpQueue.isEmpty()) {
                this.activeRtpQueueWorld = null;
                this.stopRtpQueue();
            }
            player.sendMessage(this.color("&#00ff00Left RTPQueue"));
            return true;
        }
        if (args[0].equalsIgnoreCase("overworld") || args[0].equalsIgnoreCase("world")) {
            this.joinRtpQueue(player, "world");
            return true;
        }
        if (args[0].equalsIgnoreCase("nether") || args[0].equalsIgnoreCase("world_nether")) {
            this.joinRtpQueue(player, "world_nether");
            return true;
        }
        player.sendMessage(this.color("&#00ff00Choose an RTPQueue world or use &e/rtpqueue exit&#00ff00."));
        return true;
    }

    private void openRtpQueueMenu(Player player) {
        // Same card layout as /playerwipe, so every ColtCore menu
        // reads the same way.
        Inventory inventory = com.coltcore.core.modules.UiKit.chest(3, "&8RTP Queue");
        inventory.setItem(11, com.coltcore.core.modules.UiKit.card(Material.GRASS_BLOCK,
                "&a&lOVERWORLD QUEUE", "Shared overworld drop",
                List.of("Everyone in the queue is teleported",
                        "to one shared overworld location.",
                        "",
                        "Waiting: " + this.rtpQueue.size() + " player(s)"),
                null, "to join"));
        inventory.setItem(15, com.coltcore.core.modules.UiKit.card(Material.NETHERRACK,
                "&c&lNETHER QUEUE", "Shared nether drop",
                List.of("Everyone in the queue is teleported",
                        "to one shared nether location.",
                        "",
                        "Waiting: " + this.rtpQueue.size() + " player(s)"),
                null, "to join"));
        inventory.setItem(22, com.coltcore.core.modules.UiKit.card(Material.BARRIER,
                "&c&lLEAVE QUEUE", "Drop out",
                List.of("Removes you from whichever queue you joined."),
                null, "to leave"));
        com.coltcore.core.modules.UiKit.fill(inventory);
        player.openInventory(inventory);
    }

    private void joinRtpQueue(Player player, String worldName) {
        if (Bukkit.getWorld((String)worldName) == null) {
            this.boxMessage(player, "&#00ff00&lRTP Queue", "&cWorld is not loaded: &f" + worldName);
            return;
        }
        if (this.activeRtpQueueWorld != null && !this.activeRtpQueueWorld.equalsIgnoreCase(worldName) && !this.rtpQueue.isEmpty()) {
            this.boxMessage(player, "&#00ff00&lRTP Queue", "&cA queue is already running for &f" + this.displayWorld(this.activeRtpQueueWorld) + "&c.");
            return;
        }
        long now = System.currentTimeMillis();
        long until = this.rtpCooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            this.boxMessage(player, "&#00ff00&lRTP Queue", "&cWait before joining again.");
            return;
        }
        this.activeRtpQueueWorld = worldName;
        if (!this.rtpQueue.contains(player.getUniqueId())) {
            this.rtpQueue.add(player.getUniqueId());
        }
        player.sendMessage(this.color("&#00ff00RTPQueue Started &e" + this.rtpTimer + "s &#00ff00left, &e" + this.rtpQueue.size() + " &#00ff00queued"));
        this.showRtpQueueTitle(player, "Joined", this.rtpQueue.size());
        if (this.rtpTask == null) {
            this.rtpTimer = this.getConfig().getInt("rtpqueue.timer-seconds", 30);
            this.rtpTask = SchedulerCompat.timer(this, this::tickRtpQueue, 0L, 20L);
        }
        if (this.rtpQueue.size() >= this.getConfig().getInt("rtpqueue.min-players", 4)) {
            this.rtpTimer = Math.min(this.rtpTimer, 1);
        }
    }

    private void tickRtpQueue() {
        this.rtpQueue.removeIf(uuid -> Bukkit.getPlayer((UUID)uuid) == null);
        if (this.rtpQueue.isEmpty()) {
            this.activeRtpQueueWorld = null;
            this.stopRtpQueue();
            return;
        }
        if (this.rtpTimer <= 0) {
            this.launchRtpQueue();
            return;
        }
        for (UUID uuid2 : this.rtpQueue) {
            Player player = Bukkit.getPlayer((UUID)uuid2);
            if (player == null) continue;
            player.sendActionBar(this.colorComponent("&#00ff00RTPQueue &8- &flaunching in &e" + this.rtpTimer + "s &8- &e" + this.rtpQueue.size() + " &fqueued"));
            player.showTitle(Title.title((Component)this.colorComponent("&#00ff00&lRTP QUEUE"), (Component)this.colorComponent("&fShared drop in &e" + this.rtpTimer + "s &8| &f" + this.displayWorld(this.activeRtpQueueWorld)), (Title.Times)Title.Times.times((Duration)Duration.ZERO, (Duration)Duration.ofMillis(900L), (Duration)Duration.ofMillis(200L))));
        }
        --this.rtpTimer;
    }

    private void launchRtpQueue() {
        Location location = this.findSafeLocation();
        if (location == null) {
            for (UUID uuid : this.rtpQueue) {
                Player player = Bukkit.getPlayer((UUID)uuid);
                if (player == null) continue;
                this.boxMessage(player, "&#00ff00&lRTP Queue", "&cCould not find a safe shared location.");
            }
            this.rtpQueue.clear();
            this.activeRtpQueueWorld = null;
            this.stopRtpQueue();
            return;
        }
        long cooldown = this.getConfig().getLong("rtpqueue.cooldown-seconds", 60L) * 1000L;
        for (UUID uuid : new ArrayList<UUID>(this.rtpQueue)) {
            Player player = Bukkit.getPlayer((UUID)uuid);
            if (player == null) continue;
            player.teleport(location);
            player.sendMessage(this.color("&#00ff00RTPQueue completed at the shared location"));
            player.showTitle(Title.title((Component)this.colorComponent("&#00ff00&lRTP QUEUE"), (Component)this.colorComponent("&fDropped together in &e" + location.getWorld().getName()), (Title.Times)Title.Times.times((Duration)Duration.ofMillis(150L), (Duration)Duration.ofSeconds(2L), (Duration)Duration.ofMillis(400L))));
            this.rtpCooldowns.put(uuid, System.currentTimeMillis() + cooldown);
        }
        this.rtpQueue.clear();
        this.activeRtpQueueWorld = null;
        this.stopRtpQueue();
    }

    private Location findSafeLocation() {
        String worldName = this.activeRtpQueueWorld == null ? this.getConfig().getString("rtpqueue.world", "world") : this.activeRtpQueueWorld;
        World world = Bukkit.getWorld((String)worldName);
        if (world == null) {
            return null;
        }
        int minX = this.getConfig().getInt("rtpqueue.min-x", -2500);
        int maxX = this.getConfig().getInt("rtpqueue.max-x", 2500);
        int minZ = this.getConfig().getInt("rtpqueue.min-z", -2500);
        int maxZ = this.getConfig().getInt("rtpqueue.max-z", 2500);
        for (int i = 0; i < 30; ++i) {
            int x = minX + RANDOM.nextInt(maxX - minX + 1);
            int z = minZ + RANDOM.nextInt(maxZ - minZ + 1);
            Block highest = world.getHighestBlockAt(x, z);
            Material below = highest.getType();
            Location loc = highest.getLocation().add(0.5, 1.0, 0.5);
            if (below == Material.LAVA || below == Material.MAGMA_BLOCK || !loc.getBlock().isPassable() || !loc.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()) continue;
            return loc;
        }
        return null;
    }

    private void showRtpQueueTitle(Player player, String state, int queued) {
        player.showTitle(Title.title((Component)this.colorComponent("&#00ff00&lRTP QUEUE"), (Component)this.colorComponent("&f" + state + " &8| &e" + queued + " &fqueued &8| &f" + this.displayWorld(this.activeRtpQueueWorld)), (Title.Times)Title.Times.times((Duration)Duration.ofMillis(150L), (Duration)Duration.ofSeconds(2L), (Duration)Duration.ofMillis(400L))));
    }

    private String displayWorld(String worldName) {
        if (worldName == null) {
            return "Overworld";
        }
        return worldName.equalsIgnoreCase("world_nether") ? "Nether" : "Overworld";
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onRtpQueueMenuClick(InventoryClickEvent event) {
        Player player;
        block11: {
            block10: {
                HumanEntity humanEntity = event.getWhoClicked();
                if (!(humanEntity instanceof Player)) break block10;
                player = (Player)humanEntity;
                if (ChatColor.stripColor((String)event.getView().getTitle()).equalsIgnoreCase("RTP Queue")) break block11;
            }
            return;
        }
        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) {
            return;
        }
        player.closeInventory();
        if (clicked.getType() == Material.GRASS_BLOCK) {
            this.joinRtpQueue(player, "world");
        } else if (clicked.getType() == Material.NETHERRACK) {
            this.joinRtpQueue(player, "world_nether");
        } else if (clicked.getType() == Material.BARRIER) {
            this.rtpQueue.remove(player.getUniqueId());
            if (this.rtpQueue.isEmpty()) {
                this.activeRtpQueueWorld = null;
                this.stopRtpQueue();
            }
            player.sendMessage(this.color("&#00ff00Left RTPQueue"));
        }
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        return;
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        event.joinMessage(null);
        SchedulerCompat.runLater(this, () -> this.completeJoin(player), 1L);
    }

    private void completeJoin(Player player) {
        if (!player.isOnline()) return;
        if (this.joinPacketIsolation != null) this.joinPacketIsolation.unlock(player);
        // Never disclose a vanished player's presence. This suppresses the
        // ordinary broadcast, rank alert and custom /joinmessage alike.
        if (com.coltcore.core.modules.VanishSupport.isVanished(player)) return;
        String group = this.group(player);
        String displayPrefix = this.prefix(player);
        String border = this.gradientBorder(player);
        player.showTitle(Title.title(this.colorComponent("&#00ff00&lWelcome Back!"), this.colorComponent(displayPrefix + " " + player.getName() + "&f."), Title.Times.times(Duration.ofMillis(300L), Duration.ofSeconds(2L), Duration.ofMillis(500L))));
        Bukkit.broadcast(this.colorComponent("&a[+] &f" + player.getName()));
        if (!this.getConfig().getBoolean("join-alerts.enabled", true) || !this.joinAlertsEnabledForRank(group)) return;
        String joinMessage = IntegratedCoreModuleX.joinMessage(player);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!this.joinAlertsEnabled(online)) continue;
            online.sendMessage(this.colorComponent(border));
            online.sendMessage(this.colorComponent(displayPrefix + " " + player.getName()));
            online.sendMessage(this.colorComponent(joinMessage.isBlank() ? "&fhas joined!" : joinMessage));
            online.sendMessage(this.colorComponent(border));
            if (!joinMessage.isBlank()) online.sendActionBar(this.colorComponent(joinMessage));
            online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onJoinGateQuit(PlayerQuitEvent event) {
        if (this.joinPacketIsolation != null) this.joinPacketIsolation.unlock(event.getPlayer());
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        World world = Bukkit.getWorld((String)this.getConfig().getString("spawn.world", "Spawn"));
        if (world == null) {
            return;
        }
        double x = this.getConfig().getDouble("spawn.x", 8.0);
        double y = this.getConfig().getDouble("spawn.y", 106.0);
        double z = this.getConfig().getDouble("spawn.z", 8.0);
        event.setRespawnLocation(new Location(world, x + 0.5, y, z + 0.5));
    }


    private void stopRtpQueue() {
        if (this.rtpTask != null) {
            this.rtpTask.cancel();
            this.rtpTask = null;
        }
    }

    @EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
    public void onModernChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        Iterator iterator = event.viewers().iterator();
        while (iterator.hasNext()) {
            Player player;
            Audience viewer = (Audience)iterator.next();
            if (!(viewer instanceof Player) || !this.isBlocked(player = (Player)viewer, sender)) continue;
            iterator.remove();
        }
    }

    @EventHandler(priority=EventPriority.LOWEST, ignoreCancelled=true)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        event.getRecipients().removeIf(viewer -> this.isBlocked((Player)viewer, event.getPlayer()));
    }

    private boolean isBlocked(Player viewer, Player sender) {
        return this.blocks.getStringList("blocked." + String.valueOf(viewer.getUniqueId())).stream().anyMatch(value -> value.equalsIgnoreCase(sender.getName()));
    }

    private boolean joinAlertsEnabled(Player viewer) {
        return this.blocks.getBoolean("preferences." + String.valueOf(viewer.getUniqueId()) + ".join-alerts", true);
    }

    private boolean announcementsEnabled(Player viewer) {
        return this.blocks.getBoolean("preferences." + String.valueOf(viewer.getUniqueId()) + ".announcements", true);
    }

    private boolean joinAlertsEnabledForRank(String group) {
        if (group == null) return false;
        String g = group.toLowerCase(java.util.Locale.ROOT);
        if (g.equals("default") || g.equals("member")) return false;
        List<String> list = this.getConfig().getStringList("join-alerts.alert-groups");
        if (list.isEmpty()) return true;
        for (String s : list) if (s.equals("*") || s.equalsIgnoreCase(g)) return true;
        return true;
    }

    /**
     * Who may cut a staff code and run the ladder commands.
     *
     * <p>Config-driven via {@code staffmanager.allowed-actors}, but the
     * configured list is unioned with {@link #MANAGER_PLUS_ROLES} so an
     * Executive or Developer is never locked out by a config file that
     * predates their rank. Config can still add names; it can no longer
     * silently subtract the senior ones.
     */
    private boolean isStaffManager(Player player) {
        String group = this.group(player);
        return this.getConfig().getStringList("staffmanager.allowed-actors").contains(group)
                || MANAGER_PLUS_ROLES.contains(group);
    }

    private boolean consumeCode(Player player, String code) {
        String path = "codes." + code.toUpperCase(Locale.ROOT);
        if (!player.getUniqueId().toString().equals(this.codes.getString(path + ".owner", ""))) {
            return false;
        }
        if (this.codes.getLong(path + ".expires", 0L) < Instant.now().getEpochSecond()) {
            return false;
        }
        this.codes.set(path, null);
        this.saveCodes();
        return true;
    }

    private String randomCode() {
        StringBuilder out = new StringBuilder(8);
        for (int i = 0; i < 8; ++i) {
            out.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return out.toString();
    }

    /**
     * Manager and above on the Colt ladder — the gate for creator-code
     * management. Live LuckPerms primary group, not a permission node.
     *
     * <p>Delegates to {@link #MANAGER_PLUS_ROLES} so this and the redeem-code
     * creator gate answer the same way. They used to carry separate copies of
     * a set that omitted {@code developer}.
     */
    public boolean isManagerPlus(Player player) {
        return MANAGER_PLUS_ROLES.contains(this.group(player));
    }

    private String group(Player player) {
        return this.group((OfflinePlayer)player);
    }

    private String group(OfflinePlayer player) {
        if (this.luckPerms == null || player.getUniqueId() == null) {
            return "default";
        }
        try {
            User user = (User)this.luckPerms.getUserManager().loadUser(player.getUniqueId()).join();
            return user == null ? "default" : user.getPrimaryGroup().toLowerCase(Locale.ROOT);
        }
        catch (RuntimeException ex) {
            return "default";
        }
    }

    public String prefix(Player player) {
        if (this.luckPerms == null) {
            return this.rankColor(this.group(player)) + this.group(player);
        }
        User user = this.luckPerms.getUserManager().getUser(player.getUniqueId());
        String prefix = user == null ? null : user.getCachedData().getMetaData().getPrefix();
        return prefix == null || prefix.isBlank() ? this.rankColor(this.group(player)) + this.group(player) : prefix;
    }

    private String nextRank(String current) {
        List<String> ladder = this.staffLadder();
        int index = ladder.indexOf(current);
        // At the top, or not on the ladder at all: stay put rather than wrap
        // around to helper. The old fallback silently demoted an owner to
        // helper on a /promote with no explicit rank.
        if (index < 0 || index >= ladder.size() - 1) {
            return current == null || current.isBlank() ? "helper" : current;
        }
        return ladder.get(index + 1);
    }

    private String previousRank(String current) {
        List<String> ladder = this.staffLadder();
        int index = ladder.indexOf(current);
        // Nothing below the bottom rung: demoting a helper leaves them a
        // helper rather than bouncing them to a rank the caller did not name.
        if (index <= 0) {
            return current == null || current.isBlank() ? "member" : current;
        }
        return ladder.get(index - 1);
    }

    /**
     * The staff ladder to walk. Config wins when it is set; otherwise the
     * canonical {@link #STAFF_ROLES} list is used.
     *
     * <p>Config used to be the only source and stopped at {@code sradmin}, so
     * promoting a Senior Admin fell through to {@code "helper"} — a silent
     * fourteen-rank demotion with no way to notice except by reading the
     * group afterwards.
     */
    private List<String> staffLadder() {
        List<String> configured = this.getConfig().getStringList("staffmanager.ladder");
        if (configured.isEmpty()) return STAFF_ROLES;
        // Guard the truncated config case: if the configured ladder does not
        // reach the top of the canonical list, append the missing senior ranks
        // rather than letting a promotion fall off the end.
        List<String> merged = new ArrayList<>(configured);
        for (String role : STAFF_ROLES) {
            if (!merged.contains(role)) merged.add(role);
        }
        return merged;
    }

    private boolean isStaffGroup(String group) {
        if (group == null) return false;
        String g = group.toLowerCase(Locale.ROOT);
        return !g.equals("member") && !g.equals("default") && this.staffLadder().contains(g);
    }

    private String rankColor(String rank) {
        if (rank == null) return "&7";
        String r = rank.toLowerCase(java.util.Locale.ROOT);
        String known = switch (r) {
            case "owner" -> "&#2e287f";
            case "co-owner" -> "&#55b4bb";
            case "partner" -> "&#0c7ef8";
            case "executive" -> "&#6f5ce8";
            case "manager" -> "&#5b54d8";
            case "dev", "developer" -> "&#934af1";
            case "sradmin" -> "&#a168ea";
            case "admin" -> "&#b491e2";
            case "jradmin" -> "&#4ec36b";
            case "srmod" -> "&#4baf64";
            case "mod" -> "&#47905a";
            case "jrmod", "helper" -> "&#407385";
            case "builder" -> "&#bdf80b";
            case "media" -> "&#F73F9B";
            case "colt" -> "&#00ff00";
            case "colt+" -> "&#AFFF00";
            case "titan" -> "&#8600FF";
            case "elite" -> "&#4EB5A5";
            case "champion" -> "&#FF5C5C";
            case "knight" -> "&#A8B0B8";
            case "booster" -> "&#BF00FF";
            case "default", "member" -> "&7";
            default -> null;
        };
        if (known != null) return known;
        int h = Math.abs(r.hashCode());
        int hue = h % 360;
        double s = 0.85, v = 0.95;
        double c = v * s;
        double x = c * (1 - Math.abs((hue / 60.0) % 2 - 1));
        double m = v - c;
        double rp=0,gp=0,bp=0;
        if (hue < 60) { rp=c; gp=x; bp=0; } else if (hue < 120) { rp=x; gp=c; bp=0; } else if (hue < 180) { rp=0; gp=c; bp=x; } else if (hue < 240) { rp=0; gp=x; bp=c; } else if (hue < 300) { rp=x; gp=0; bp=c; } else { rp=c; gp=0; bp=x; }
        int ri=(int)Math.round((rp+m)*255), gi=(int)Math.round((gp+m)*255), bi=(int)Math.round((bp+m)*255);
        return String.format("&#%02X%02X%02X", ri,gi,bi);
    }

    private String rankLabel(String rank) {
        return switch (rank) {
            case "owner" -> "OWNER";
            case "co-owner" -> "CO-OWNER";
            case "partner" -> "PARTNER";
            case "executive" -> "EXECUTIVE";
            case "manager" -> "MANAGER";
            case "dev", "developer" -> "DEVELOPER";
            case "sradmin" -> "SR. ADMIN";
            case "admin" -> "ADMIN";
            case "jradmin" -> "JR. ADMIN";
            case "srmod" -> "SR. MOD";
            case "mod" -> "MOD";
            case "jrmod" -> "JR. MOD";
            case "helper" -> "HELPER";
            case "builder" -> "BUILDER";
            case "media" -> "MEDIA";
            // Paid ranks
            case "colt" -> "COLT";
            case "colt+" -> "COLT+";
            case "titan" -> "TITAN";
            case "elite" -> "ELITE";
            case "champion" -> "CHAMPION";
            case "knight" -> "KNIGHT";
            case "booster" -> "BOOSTER";
            case "default", "member" -> "MEMBER";
            default -> rank.toUpperCase(Locale.ROOT).replace('-', ' ');
        };
    }

    /**
     * The rank name rendered one letter at a time, bold, each letter its own
     * colour. For the solid-colour ranks this renders identically to a single
     * colour prefix; for colt+ it lays a green gradient across the letters.
     *
     * <p>Matches the purchase-board layout: {@code &#A8B0B8&lK&#A8B0B8&lN...&lT&f}
     * over the plain name on the line below.
     */
    private String rankDisplay(String rank) {
        String name = this.rankLabel(rank);
        if ("colt+".equals(rank) || "coltplus".equals(rank)) {
            String start = "AFFF00"; String end = "00FF0B";
            int[] s = hexToRgb(start); int[] e = hexToRgb(end);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                double t = name.length() == 1 ? 0 : (double) i / (name.length() - 1);
                int r = (int) Math.round(s[0] + (e[0] - s[0]) * t);
                int g = (int) Math.round(s[1] + (e[1] - s[1]) * t);
                int b = (int) Math.round(s[2] + (e[2] - s[2]) * t);
                sb.append("&#").append(rgbToHex(r, g, b)).append("&l").append(name.charAt(i));
            }
            sb.append("&f");
            return sb.toString();
        }
        String c = this.rankColor(rank);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            sb.append(c).append("&l").append(name.charAt(i));
        }
        sb.append("&f");
        return sb.toString();
    }

    private List<String> extractHexColors(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("&#([0-9A-Fa-f]{6})").matcher(text);
        while (m.find()) out.add(m.group(1).toUpperCase(Locale.ROOT));
        Matcher m2 = Pattern.compile("&([0-9a-fA-F])").matcher(text);
        while (m2.find()) {
            String legacy = legacyToHex(m2.group(1));
            if (legacy != null) out.add(legacy);
        }
        return out;
    }

    private String legacyToHex(String code) {
        if (code == null || code.isEmpty()) return null;
        return switch (code.toLowerCase(Locale.ROOT)) {
            case "0" -> "000000";
            case "1" -> "0000AA";
            case "2" -> "00AA00";
            case "3" -> "00AAAA";
            case "4" -> "AA0000";
            case "5" -> "AA00AA";
            case "6" -> "FFAA00";
            case "7" -> "AAAAAA";
            case "8" -> "555555";
            case "9" -> "5555FF";
            case "a" -> "55FF55";
            case "b" -> "55FFFF";
            case "c" -> "FF5555";
            case "d" -> "FF55FF";
            case "e" -> "FFFF55";
            case "f" -> "FFFFFF";
            default -> null;
        };
    }

    private int[] hexToRgb(String hex) {
        if (hex == null || hex.length() != 6) return new int[]{0, 255, 0};
        try { return new int[]{Integer.parseInt(hex.substring(0, 2), 16), Integer.parseInt(hex.substring(2, 4), 16), Integer.parseInt(hex.substring(4, 6), 16)}; } catch (NumberFormatException e) { return new int[]{0, 255, 0}; }
    }

    private String rgbToHex(int r, int g, int b) {
        r = Math.max(0, Math.min(255, r)); g = Math.max(0, Math.min(255, g)); b = Math.max(0, Math.min(255, b));
        return String.format("%02X%02X%02X", r, g, b);
    }

    private String gradientBorder(String prefix, String fallbackColor) {
        List<String> colors = extractHexColors(prefix);
        if (colors.isEmpty()) {
            String fb = fallbackColor == null ? "" : fallbackColor.trim();
            if (fb.startsWith("&#") && fb.length() >= 7) colors.add(fb.substring(2, 8).toUpperCase(Locale.ROOT));
            else if (fb.startsWith("&") && fb.length() >= 2) {
                String hx = legacyToHex(fb.substring(1, 2));
                if (hx != null) colors.add(hx);
            }
        }
        if (colors.isEmpty()) return "&#00FF00&l&m------------------------------&f";
        List<String> distinct = new ArrayList<>();
        for (String c : colors) if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equalsIgnoreCase(c)) distinct.add(c);
        if (distinct.isEmpty()) distinct = colors;
        if (distinct.size() == 1) return "&#" + distinct.get(0) + "&l&m------------------------------&f";
        String base = "------------------------------";
        StringBuilder sb = new StringBuilder();
        int segments = distinct.size() - 1;
        for (int i = 0; i < base.length(); i++) {
            double pos = segments * (base.length() == 1 ? 0 : (double) i / (base.length() - 1));
            int lo = (int) Math.floor(pos);
            int hi = Math.min(segments, (int) Math.ceil(pos));
            double t = pos - lo;
            int[] a = hexToRgb(distinct.get(lo));
            int[] b = hexToRgb(distinct.get(hi));
            int r = (int) Math.round(a[0] + (b[0] - a[0]) * t);
            int g = (int) Math.round(a[1] + (b[1] - a[1]) * t);
            int bl = (int) Math.round(a[2] + (b[2] - a[2]) * t);
            sb.append("&#").append(rgbToHex(r, g, bl)).append("&l&m").append(base.charAt(i));
        }
        sb.append("&f");
        return sb.toString();
    }

    private String gradientBorder(Player player) {
        String prefix = this.prefix(player);
        String fallback = this.rankColor(this.group(player));
        return gradientBorder(prefix, fallback);
    }

    private String gradientBorder(OfflinePlayer player) {
        if (player instanceof Player p) return gradientBorder(p);
        String group = this.group(player);
        String fallback = this.rankColor(group);
        String prefix;
        try {
            if (this.luckPerms != null && player.getUniqueId() != null) {
                User user = this.luckPerms.getUserManager().loadUser(player.getUniqueId()).join();
                String lp = user == null ? null : user.getCachedData().getMetaData().getPrefix();
                prefix = (lp == null || lp.isBlank()) ? this.rankDisplay(group) : lp;
            } else {
                prefix = this.rankDisplay(group);
            }
        } catch (Throwable ignored) {
            prefix = this.rankDisplay(group);
        }
        return gradientBorder(prefix, fallback);
    }

    private String rankIcon(String rank) {
        return switch (rank) {
            case "owner" -> "\u265b";
            case "co-owner" -> "\u2655";
            case "partner" -> "\u2727";
            case "executive" -> "\u2605";
            case "manager" -> "\u25c6";
            case "dev", "developer" -> "\u2699";
            case "sradmin", "admin" -> "\u2605";
            case "jradmin" -> "\u2726";
            case "srmod", "mod", "jrmod" -> "\u2694";
            case "helper" -> "\u2764";
            case "builder" -> "\u271a";
            case "media" -> "\u25b6";
            default -> "\u2726";
        };
    }

    private String cleanPrefix(String prefix, String fallback) {
        return prefix == null || prefix.isBlank() ? fallback : prefix;
    }

    private String keyId(String raw) {
        String key = raw.toLowerCase(Locale.ROOT).replace("-", "_");
        return key.endsWith("_crate") ? key : key + "_crate";
    }

    private String keyName(String key) {
        String[] parts = key.replace("_crate", "").split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return String.valueOf(out) + " Crate";
    }

    /**
     * Returns the color for a crate key based on its name.
     * Common = green, Amethyst = purple, Crimson = red, Gold = gold, Prime = cyan
     */
    private String keyColor(String key) {
        String lower = key.toLowerCase(Locale.ROOT).replace("_crate", "").replace("-", "_");
        return switch (lower) {
            case "common" -> "&a";        // Green
            case "amethyst" -> "&#9945FF"; // Purple
            case "crimson" -> "&c";        // Red
            case "gold" -> "&e";           // Gold
            case "prime" -> "&b";          // Cyan
            default -> "&f";               // White fallback
        };
    }

    /**
     * Returns a colored key name for broadcast messages.
     */
    private String coloredKeyName(String key) {
        return this.keyColor(key) + this.keyName(key);
    }

    private String kitId(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void startTips() {
        if (this.tipTask != null) {
            this.tipTask.cancel();
        }
        if (!this.getConfig().getBoolean("tips.enabled", true)) {
            return;
        }
        long interval = Math.max(20L, this.getConfig().getLong("tips.interval-seconds", 180L) * 20L);
        this.tipTask = SchedulerCompat.timer(this, () -> {
            List tips = this.getConfig().getStringList("tips.messages");
            if (tips.isEmpty()) {
                return;
            }
            String tip = (String) tips.get(this.tipIndex++ % tips.size());
            Bukkit.broadcast(this.colorComponent("&8&l&m----------------------------------------"));
            Bukkit.broadcast(this.colorComponent("&#00ff00&lTIP"));
            Bukkit.broadcast(this.colorComponent("&f" + tip));
            Bukkit.broadcast(this.colorComponent("&8&l&m----------------------------------------"));
        }, interval, interval);
    }

    private void loadFiles() {
        this.blocksFile = new File(this.getDataFolder(), "blocks.yml");
        this.codesFile = new File(this.getDataFolder(), "codes.yml");
        this.blocks = YamlConfiguration.loadConfiguration((File)this.blocksFile);
        this.codes = YamlConfiguration.loadConfiguration((File)this.codesFile);
    }

    private void saveBlocks() {
        try {
            this.blocks.save(this.blocksFile);
        }
        catch (IOException ex) {
            this.getLogger().warning("Could not save blocks.yml: " + ex.getMessage());
        }
    }

    private void saveCodes() {
        try {
            this.codes.save(this.codesFile);
        }
        catch (IOException ex) {
            this.getLogger().warning("Could not save codes.yml: " + ex.getMessage());
        }
    }

    private void dispatch(String command) {
        Bukkit.dispatchCommand((CommandSender)Bukkit.getConsoleSender(), (String)command);
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("coltcore")) {
            return this.complete(args, this.diagnosticsModule.subCommands());
        }
        if (name.equals("staffmonitor")) {
            return args.length == 1 ? this.complete(args, List.of("reload")) : Collections.emptyList();
        }
        if (name.equals("staffactivity") || name.equals("staffafk")) {
            return Collections.emptyList();
        }
        if (name.equals("textguard") || name.equals("coltguard")) {
            if (args.length == 1) {
                return this.complete(args, List.of("status", "mutes", "unmute", "unmuteip",
                        "history", "test", "reload"));
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            return args.length == 2 && (sub.equals("unmute") || sub.equals("history"))
                    ? this.onlineNames(args[1]) : Collections.emptyList();
        }
        if (name.equals("activerank") || name.equals("coltactive")) {
            if (args.length == 1) {
                return this.complete(args, List.of("check", "grant", "regrant", "reload"));
            }
            return args.length == 2 ? this.onlineNames(args[1]) : Collections.emptyList();
        }
        if (name.equals("staffmacro")) {
            return args.length == 1
                    ? this.complete(args, List.of("status", "check", "reload"))
                    : (args.length == 2 ? this.onlineNames(args[1]) : Collections.emptyList());
        }
        if (name.equals("playerwipe")) {
            return args.length == 1 ? this.onlineNames(args[0]) : Collections.emptyList();
        }
        if (name.equals("rtpqueue")) {
            return this.complete(args, List.of("overworld", "nether", "leave", "exit"));
        }
        if (name.equals("joinalerts") || name.equals("announcements")) {
            return Collections.emptyList();
        }
        if (name.equals("redeem")) {
            return Collections.emptyList();
        }
        if (name.equals("redeemcode")) {
            return this.complete(args, this.redeemModule.adminTab(args));
        }
        if (name.equals("rewards") || name.equals("dailyrewards") || name.equals("playtimerewards")) {
            return args.length == 1
                    ? this.complete(args, List.of("daily", "playtime", "claimall", "status", "reload"))
                    : Collections.emptyList();
        }
        if (name.equals("rankgive")) {
            return args.length == 1 ? this.onlineNames(args[0]) : (args.length == 2 ? this.complete(args, this.getConfig().getStringList("rankgive.allowed-ranks")) : Collections.emptyList());
        }
        if (name.equals("coltclear") || name.equals("gclear") || name.equals("gildedclear")) {
            return this.complete(args, List.of("clear", "status", "reload"));
        }
        if (name.equals("billfordadmin")) {
            return args.length == 1 ? this.complete(args, List.of("open", "reload", "setcost", "setname")) : Collections.emptyList();
        }
        if (name.equals("greset")) {
            return args.length == 1 ? this.complete(args, List.of("code", "economy", "shards", "soulshards", "playerdata", "user", "worlds", "all", "resetall", "reload")) : (args.length == 2 && args[0].equalsIgnoreCase("code") ? this.complete(args, List.of("generate", "new")) : (args.length == 2 ? this.complete(args, List.of("confirm")) : Collections.emptyList()));
        }
        if (name.equals("spawnstash")) {
            return args.length == 1 ? this.complete(args, this.stashModule.schematicNames()) : Collections.emptyList();
        }
        if (name.equals("gpurge")) {
            return args.length == 1 ? this.complete(args, List.of("code", "run", "disable", "restore", "backups", "reload")) : (args.length == 2 && args[0].equalsIgnoreCase("code") ? this.complete(args, List.of("generate", "new")) : (args.length == 2 ? this.complete(args, List.of("confirm", "latest")) : Collections.emptyList()));
        }
        if (name.equals("promote") || name.equals("hire")) {
            return args.length == 1 ? this.onlineNames(args[0]) : (args.length == 2 ? this.complete(args, this.staffRanks) : Collections.emptyList());
        }
        if (name.equals("demote") || name.equals("block") || name.equals("ignore")) {
            return args.length == 1 ? this.onlineNames(args[0]) : Collections.emptyList();
        }
        if (name.equals("keyall")) {
            return args.length == 1 ? this.complete(args, this.availableKeys()) : (args.length == 2 ? this.complete(args, List.of("1", "2", "3", "5", "10")) : Collections.emptyList());
        }
        if (name.equals("kitall")) {
            return args.length == 1 ? this.complete(args, this.availableKits()) : (args.length == 2 ? this.complete(args, List.of("1", "2", "3", "5", "10")) : Collections.emptyList());
        }
        if (name.equals("shardall")) {
            return args.length == 1 ? this.onlineNames(args[0]) : (args.length == 2 ? this.complete(args, this.getConfig().getStringList("rankgive.allowed-ranks")) : (args.length == 3 ? this.complete(args, this.getConfig().getStringList("rewards.purchase-type-tabs")) : (args.length == 4 ? this.complete(args, List.of("10", "100", "1000", "10000", "100000", "1000000")) : Collections.emptyList())));
        }
        return Collections.emptyList();
    }

    private List<String> availableKeys() {
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        this.addYamlFileNames(keys, new File("plugins/DonutCore/Crates/crates"), "_crate");
        this.addYamlFileNames(keys, new File("plugins/ExcellentCrates/crates"), "");
        this.addYamlFileNames(keys, new File("plugins/ExcellentCrates/Crates"), "");
        return keys.isEmpty() ? List.of("amethyst", "common", "crimson", "gold", "prime") : new ArrayList<String>(keys);
    }

    private List<String> availableKits() {
        LinkedHashSet<String> kits = new LinkedHashSet<String>();
        this.addYamlFileNames(kits, new File("plugins/PlayerKits2/kits"), "");
        kits.removeIf(k -> k.equalsIgnoreCase("cpvp") || k.equalsIgnoreCase("cpvpneth"));
        return kits.isEmpty() ? List.of("Coal", "Colt") : new ArrayList<String>(kits);
    }

    private List<String> availableRewardPurchases() {
        LinkedHashSet<String> values = new LinkedHashSet<String>();
        File groupsFile = new File("plugins/LuckPerms/yaml-storage/groups.yml");
        if (groupsFile.isFile()) {
            YamlConfiguration groupsConfig = YamlConfiguration.loadConfiguration((File)groupsFile);
            List excluded = this.getConfig().getStringList("rewards.rank-tab-exclude");
            for (String key : groupsConfig.getKeys(false)) {
                if (excluded.contains(key.toLowerCase(Locale.ROOT))) continue;
                values.add(key);
            }
        }
        values.addAll(this.getConfig().getStringList("rewards.extra-purchase-tabs"));
        return values.isEmpty() ? List.of("coal", "iron", "gold", "lapis", "diamond", "netherite", "colt") : new ArrayList<String>(values);
    }

    private void addYamlFileNames(Set<String> values, File folder, String suffixToRemove) {
        Object[] files = folder.listFiles((ignored, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (Object file : files) {
            String name2 = ((File)file).getName().replaceFirst("(?i)\\.yml$", "");
            if (!suffixToRemove.isBlank() && name2.toLowerCase(Locale.ROOT).endsWith(suffixToRemove)) {
                name2 = name2.substring(0, name2.length() - suffixToRemove.length());
            }
            values.add(name2);
        }
    }

    private List<String> complete(String[] args, List<String> values) {
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }

    private List<String> onlineNames(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private Component colorComponent(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(this.color(text));
    }

    /**
     * Sends a boxed message to a single recipient:
     * ------------------------------
     * {summaryLine}
     * {contentLine}
     * ------------------------------
     */
    private void boxMessage(CommandSender recipient, String summaryLine, String contentLine) {
        recipient.sendMessage(this.color("&8&l&m----------------------------------------"));
        recipient.sendMessage(this.color(summaryLine));
        recipient.sendMessage(this.color(contentLine));
        recipient.sendMessage(this.color("&8&l&m----------------------------------------"));
    }

    private String color(String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = HEX_COLOR.matcher(text);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder replacement = new StringBuilder("\u00a7x");
            for (char c : hex.toCharArray()) {
                replacement.append('\u00a7').append(c);
            }
            matcher.appendReplacement(buffer, replacement.toString());
        }
        matcher.appendTail(buffer);
        return ChatColor.translateAlternateColorCodes((char)'&', (String)buffer.toString());
    }

}



