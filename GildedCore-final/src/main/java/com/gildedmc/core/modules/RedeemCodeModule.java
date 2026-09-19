package com.gildedmc.core.modules;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Content-creator redeem codes.
 *
 * <h2>Who can create them</h2>
 * Manager and above only — checked against the live LuckPerms group, not a
 * permission node a builder could be handed. Console bypasses the check,
 * because the console has no group to check.
 *
 * <h2>The one-per-person rule</h2>
 * A redemption is refused when either the account or the address was seen
 * before on that code. Both stay recorded forever: the address catches the
 * alt on the same box, the account catches the alt on a different one.
 *
 * <h2>Supply limits</h2>
 * On top of the per-account rule a code may carry a supply limit, set as the
 * last create token: {@code 50x} = fifty redemptions total, or a lifetime
 * {@code 30min|10h|10d|2w|1m|1y} (m = 30 days). Hitting either refuses the
 * redemption and disables the code until an admin resets it.
 *
 * <h2>What a code gives</h2>
 * The creator picks per code — crate keys, money, or both:
 * <pre>
 * /redeem createcode xcv 1 gold-crate 100000   &lt;- 1 gold key + $100k
 * /redeem createcode xcv 25000                 &lt;- $25k only
 * /redeem createcode xcv 2 vote-crate          &lt;- 2 vote keys only
 * </pre>
 */
public final class RedeemCodeModule {

    /** Trailing create token: 50x = 50 total redemptions, 30min/10h/10d/2w/1m/1y = lifetime. */
    private static final Pattern LIMIT = Pattern.compile("^(\\d+)(x|min|h|d|w|m|y)$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Long> LIMIT_UNIT = Map.of(
            "min", 60L, "h", 3600L, "d", 86400L, "w", 604800L, "m", 2592000L, "y", 31536000L);

    private final JavaPlugin plugin;
    private File file;
    private YamlConfiguration codes;
    /** Set by the host plugin: true when this sender outranks manager. */
    private Predicate<Player> creatorGate = p -> false;

    public RedeemCodeModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setCreatorGate(Predicate<Player> gate) {
        this.creatorGate = gate == null ? p -> false : gate;
    }

    public void enable() {
        this.file = new File(this.plugin.getDataFolder(), "redeemcodes.yml");
        if (!this.file.getParentFile().exists()) this.file.getParentFile().mkdirs();
        this.codes = YamlConfiguration.loadConfiguration(this.file);
        save();
    }

    private void save() {
        try {
            this.codes.save(this.file);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("[Redeem] could not save redeemcodes.yml: " + ex.getMessage());
        }
    }

    private String key(String code) {
        return "codes." + code.toUpperCase(Locale.ROOT).trim();
    }

    /** True for console or manager-and-above. */
    private boolean canManage(CommandSender sender) {
        if (!(sender instanceof Player p)) return true; // console
        return this.creatorGate.test(p);
    }

    /** /redeem <code> */
    public boolean redeem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(UiKit.colour("&cOnly players can redeem codes."));
            return true;
        }
        if (args.length < 1) {
            player.sendMessage(UiKit.colour("&cUsage: /redeem <code>"));
            return true;
        }
        String code = args[0].toUpperCase(Locale.ROOT).trim();
        String path = key(code);
        if (!this.codes.contains(path)) {
            player.sendMessage(UiKit.colour("&cThat code does not exist."));
            return true;
        }
        if (!this.codes.getBoolean(path + ".enabled", true)) {
            player.sendMessage(UiKit.colour("&cThat code is no longer active."));
            return true;
        }

        long expires = this.codes.getLong(path + ".expires", 0L);
        if (expires > 0 && Instant.now().getEpochSecond() > expires) {
            this.codes.set(path + ".enabled", false);
            save();
            player.sendMessage(UiKit.colour("&cThat code has expired."));
            return true;
        }
        int maxUses = this.codes.getInt(path + ".max-uses", 0);
        if (maxUses > 0
                && this.codes.getStringList(path + ".used-uuids").size() >= maxUses) {
            this.codes.set(path + ".enabled", false);
            save();
            player.sendMessage(UiKit.colour("&cThat code has no redemptions left."));
            return true;
        }

        UUID uuid = player.getUniqueId();
        List<String> usedIps = this.codes.getStringList(path + ".used-ips");
        List<String> usedUuids = this.codes.getStringList(path + ".used-uuids");
        String ip = address(player);
        if (usedUuids.contains(uuid.toString())) {
            player.sendMessage(UiKit.colour("&cYou have already redeemed this code."));
            return true;
        }
        if (ip != null && usedIps.contains(ip)) {
            player.sendMessage(UiKit.colour("&cThis code has already been redeemed from your connection."));
            return true;
        }

        int keys = this.codes.getInt(path + ".keys", 0);
        String crate = this.codes.getString(path + ".crate", "");
        long money = this.codes.getLong(path + ".money", 0);
        if ((keys <= 0 || crate.isBlank()) && money <= 0) {
            player.sendMessage(UiKit.colour("&cThat code has no reward configured. Tell staff."));
            return true;
        }

        // Record BEFORE running rewards so a failing command cannot be farmed.
        if (ip != null) usedIps.add(ip);
        usedUuids.add(uuid.toString());
        this.codes.set(path + ".used-ips", usedIps);
        this.codes.set(path + ".used-uuids", usedUuids);
        List<String> usedBy = this.codes.getStringList(path + ".used-by");
        usedBy.add(player.getName() + " (" + (ip == null ? "?" : ip) + ") " + java.time.Instant.now());
        this.codes.set(path + ".used-by", usedBy);
        save();

        List<String> granted = new ArrayList<>();
        if (keys > 0 && !crate.isBlank()) {
            String cmd = CommandTemplate.expand(
                    this.plugin.getConfig().getString("rewards.key-command",
                            "crate give %player% %crate% %amount%"),
                    player.getName())
                    .replace("%crate%", CommandTemplate.safeToken(crate))
                    .replace("%amount%", String.valueOf(keys));
            com.gildedmc.core.SchedulerCompat.run(this.plugin,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            granted.add(keys + "x " + crate.replace('_', ' ') + " key" + (keys == 1 ? "" : "s"));
        }
        if (money > 0) {
            String cmd = CommandTemplate.expand(
                    this.plugin.getConfig().getString("rewards.money-command",
                            "eco give %player% %amount%"),
                    player.getName())
                    .replace("%amount%", String.valueOf(money));
            com.gildedmc.core.SchedulerCompat.run(this.plugin,
                    () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            granted.add("$" + String.format("%,d", money));
        }

        player.sendMessage(UiKit.colour("&aCode &f" + code + " &aredeemed: &f"
                + String.join(", ", granted)));
        this.plugin.getLogger().info("[Redeem] " + player.getName() + " redeemed " + code
                + " from " + (ip == null ? "?" : ip));
        return true;
    }

    /**
     * Creation plus management. Creation is manager-plus only.
     *
     * /redeem createcode <name> [keys] [crate] [money]
     * /redeem delete|list|reset|reload ...
     */
    public boolean admin(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        boolean creating = sub.equals("createcode");
        if (creating || sub.equals("delete") || sub.equals("reset")) {
            if (!canManage(sender)) {
                sender.sendMessage(UiKit.colour("&cOnly managers and above can manage creator codes."));
                return true;
            }
        } else if (!sender.hasPermission("gildedcore.redeemcode")) {
            sender.sendMessage(UiKit.colour("&cNo permission."));
            return true;
        }

        switch (sub) {
            case "createcode", "create" -> {
                if (args.length < 2) {
                    sender.sendMessage(UiKit.colour("&cUsage: /redeem createcode <name> [keys] [crate] [money] [limit]"));
                    sender.sendMessage(UiKit.colour("&7Examples:"));
                    sender.sendMessage(UiKit.colour("&f/redeem createcode xcv 1 gold-crate 100000"));
                    sender.sendMessage(UiKit.colour("&f/redeem createcode xcv 25000 &8- money only"));
                    sender.sendMessage(UiKit.colour("&f/redeem createcode xcv 2 vote-crate &8- keys only"));
                    sender.sendMessage(UiKit.colour("&7Trailing limits (both allowed, any order): &f50x &8= total redeems, &f30min&8/&f10h&8/&f10d&8/&f2w&8/&f1m&8/&f1y &8= lifetime"));
                    return true;
                }
                String code = args[1].toUpperCase(Locale.ROOT).trim();

                // Optional trailing limits - both may be present, any order:
                // "50x" caps total redemptions, "30min/10h/10d/2w/1m/1y" sets
                // a lifetime. A crate literally named like a limit token
                // (e.g. "5d") would be swallowed here - keep crate names
                // alphabetic.
                Integer maxUses = null;
                Instant expires = null;
                int end = args.length;
                while (end > 2) { // always leave the code name behind
                    Matcher lm = LIMIT.matcher(args[end - 1]);
                    if (!lm.matches()) break;
                    long n = Long.parseLong(lm.group(1));
                    String unit = lm.group(2).toLowerCase(Locale.ROOT);
                    if (unit.equals("x")) {
                        maxUses = (int) Math.max(1, Math.min(Integer.MAX_VALUE, n));
                    } else {
                        expires = Instant.now().plusSeconds(n * LIMIT_UNIT.get(unit));
                    }
                    end--;
                }
                String[] r = Arrays.copyOfRange(args, 2, end);

                Integer keys = null;
                String crate = null;
                Long money = null;

                if (r.length >= 2 && isNumber(r[0]) && !isNumber(r[1])) {
                    // keys + crate [+ money]
                    keys = parseInt(r[0]);
                    crate = normalizeCrate(r[1]);
                    if (r.length >= 3 && isNumber(r[2])) money = parseLong(r[2]);
                } else if (r.length == 1 && isNumber(r[0])) {
                    // money only
                    money = parseLong(r[0]);
                } else if (r.length >= 2 && isNumber(r[0]) && isNumber(r[1])) {
                    // keys given but crate missing — reject, ambiguous
                    sender.sendMessage(UiKit.colour("&cKey count needs a crate: &f/redeem createcode "
                            + code + " " + r[0] + " <crate> [money]"));
                    return true;
                } else {
                    sender.sendMessage(UiKit.colour("&cCould not read those rewards. Formats:"));
                    sender.sendMessage(UiKit.colour("&f/redeem createcode " + code + " <keys> <crate> <money> [limit]"));
                    sender.sendMessage(UiKit.colour("&f/redeem createcode " + code + " <money> [limit]"));
                    return true;
                }

                boolean anyReward = (keys != null && keys > 0 && crate != null && !crate.isBlank())
                        || (money != null && money > 0);
                if (!anyReward) {
                    sender.sendMessage(UiKit.colour("&cAt least one reward is required."));
                    return true;
                }
                String path = key(code);
                this.codes.set(path + ".enabled", true);
                this.codes.set(path + ".keys", keys == null ? 0 : keys);
                this.codes.set(path + ".crate", crate == null ? "" : crate);
                this.codes.set(path + ".money", money == null ? 0 : money);
                // null REMOVES a key, so re-creating without a limit lifts
                // whatever cap an older supply run set.
                this.codes.set(path + ".max-uses", maxUses);
                this.codes.set(path + ".expires", expires == null ? null : expires.getEpochSecond());
                // Re-issuing a code restarts its history. The old names/IPs
                // belonged to the previous supply, not the new one.
                this.codes.set(path + ".used-ips", new ArrayList<String>());
                this.codes.set(path + ".used-uuids", new ArrayList<String>());
                this.codes.set(path + ".used-by", new ArrayList<String>());
                save();

                List<String> parts = new ArrayList<>();
                if (keys != null && keys > 0) parts.add(keys + "x " + crate.replace('_', ' ') + " key" + (keys == 1 ? "" : "s"));
                if (money != null && money > 0) parts.add("$" + String.format("%,d", money));
                List<String> limits = new ArrayList<>();
                if (maxUses != null) limits.add(maxUses + " redemption" + (maxUses == 1 ? "" : "s") + " total");
                if (expires != null) limits.add("expires " + expires);
                sender.sendMessage(UiKit.colour("&aCreated &f" + code + " &a- gives: &f" + String.join(", ", parts)
                        + (limits.isEmpty() ? "" : " &8(&f" + String.join(", ", limits) + "&8)")));
                sender.sendMessage(UiKit.colour("&7One redemption per account and per IP"
                        + (limits.isEmpty() ? ", forever." : ".")));
            }
            case "delete", "remove" -> {
                if (args.length < 2) {
                    sender.sendMessage(UiKit.colour("&cUsage: /redeem delete <code>"));
                    return true;
                }
                String path = key(args[1]);
                if (!this.codes.contains(path)) {
                    sender.sendMessage(UiKit.colour("&cUnknown code."));
                    return true;
                }
                this.codes.set(path, null);
                save();
                sender.sendMessage(UiKit.colour("&aDeleted &f" + args[1].toUpperCase(Locale.ROOT)));
            }
            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(UiKit.colour("&cUsage: /redeem reset <code> &8- clears redemptions"));
                    return true;
                }
                String path = key(args[1]);
                this.codes.set(path + ".used-ips", new ArrayList<String>());
                this.codes.set(path + ".used-uuids", new ArrayList<String>());
                this.codes.set(path + ".used-by", new ArrayList<String>());
                // Resetting also wakes a code the supply limit had shut off.
                this.codes.set(path + ".enabled", true);
                save();
                sender.sendMessage(UiKit.colour("&aRedemption history cleared for &f"
                        + args[1].toUpperCase(Locale.ROOT)));
            }
            case "reload" -> {
                this.codes = YamlConfiguration.loadConfiguration(this.file);
                sender.sendMessage(UiKit.colour("&aredeemcodes.yml reloaded."));
            }
            default -> {
                sender.sendMessage(UiKit.colour("&8&l&m----------------------------------------"));
                sender.sendMessage(UiKit.colour("&#EEBB01Creator codes"));
                var section = this.codes.getConfigurationSection("codes");
                if (section == null || section.getKeys(false).isEmpty()) {
                    sender.sendMessage(UiKit.colour("&7None yet. &f/redeem createcode <name> [keys] [crate] [money]"));
                } else {
                    for (String c : section.getKeys(false)) {
                        String path = "codes." + c;
                        int used = this.codes.getStringList(path + ".used-uuids").size();
                        int k = this.codes.getInt(path + ".keys", 0);
                        String cr = this.codes.getString(path + ".crate", "");
                        long m = this.codes.getLong(path + ".money", 0);
                        int mu = this.codes.getInt(path + ".max-uses", 0);
                        long exp = this.codes.getLong(path + ".expires", 0L);
                        List<String> parts = new ArrayList<>();
                        if (k > 0 && !cr.isBlank()) parts.add(k + "x " + cr);
                        if (m > 0) parts.add("$" + String.format("%,d", m));
                        StringBuilder lim = new StringBuilder();
                        if (mu > 0) lim.append(" &8| &f").append(used).append('/').append(mu).append(" used");
                        if (exp > 0) lim.append(" &8| &f").append(Instant.now().getEpochSecond() < exp
                                ? "until " + Instant.ofEpochSecond(exp) : "expired");
                        sender.sendMessage(UiKit.colour("&f" + c + " &8- &7" + String.join(" + ", parts)
                                + " &8| &7used &f" + used
                                + lim
                                + " &8| &7" + (this.codes.getBoolean(path + ".enabled", true)
                                    ? "&aactive" : "&cdisabled")));
                    }
                }
                sender.sendMessage(UiKit.colour("&8createcode | delete | reset | reload"));
                sender.sendMessage(UiKit.colour("&8&l&m----------------------------------------"));
            }
        }
        return true;
    }

    public List<String> codeNames() {
        if (this.codes == null || this.codes.getConfigurationSection("codes") == null) return List.of();
        return new ArrayList<>(this.codes.getConfigurationSection("codes").getKeys(false));
    }

    /**
     * Candidates for /redeemcode tab completion, unfiltered; the caller runs
     * them through its own prefix filter.
     */
    public List<String> adminTab(String[] args) {
        if (args.length == 1) return List.of("create", "delete", "uses", "reset", "reload");
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (!sub.equals("create") && !sub.equals("createcode")) {
            if (sub.equals("reload")) return List.of();
            return args.length == 2 ? this.codeNames() : List.of();
        }
        // create flow. args[1] is a NEW code name - free text, never completed.
        if (args.length == 2) return List.of();
        if (args.length == 3) return List.of("1000", "5000", "10000", "100000");
        // From the fourth token on: crate names first, then the limit tokens
        // that have not been used yet (one "Nx" and one duration per code).
        List<String> out = new ArrayList<>(this.crateNames());
        boolean seenUses = false, seenTime = false;
        for (int i = 2; i < args.length - 1; i++) {
            Matcher lm = LIMIT.matcher(args[i]);
            if (lm.matches()) {
                if (lm.group(2).equalsIgnoreCase("x")) seenUses = true; else seenTime = true;
            }
        }
        if (!seenUses) out.addAll(List.of("10x", "50x", "100x"));
        if (!seenTime) out.addAll(List.of("30min", "1h", "10d", "2w", "1m", "1y"));
        return out;
    }

    /** Crate names seen on existing codes - the pool create-time completes from. */
    public List<String> crateNames() {
        if (this.codes == null) return List.of();
        var section = this.codes.getConfigurationSection("codes");
        if (section == null) return List.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String c : section.getKeys(false)) {
            String cr = this.codes.getString("codes." + c + ".crate", "");
            if (!cr.isBlank()) {
                out.add(cr);
                out.add(cr.replace('_', '-'));
            }
        }
        return List.copyOf(out);
    }

    private static boolean isNumber(String s) {
        return s != null && s.matches("\\d+");
    }

    private static int parseInt(String s) {
        try { return Math.max(1, Integer.parseInt(s)); } catch (NumberFormatException e) { return 1; }
    }

    private static long parseLong(String s) {
        try { return Math.max(0, Long.parseLong(s)); } catch (NumberFormatException e) { return 0; }
    }

    private static String normalizeCrate(String raw) {
        return raw.toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String address(Player p) {
        try {
            return p.getAddress() == null || p.getAddress().getAddress() == null
                    ? null : p.getAddress().getAddress().getHostAddress();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
