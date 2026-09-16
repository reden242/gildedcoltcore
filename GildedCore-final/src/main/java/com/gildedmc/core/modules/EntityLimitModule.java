package com.gildedmc.core.modules;

import com.gildedmc.core.SchedulerCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Caps non-living entities per chunk and refuses machine-launched projectiles.
 *
 * <h2>Why this exists on top of the vanilla limits</h2>
 * Vanilla's per-chunk limits are about <em>spawning</em>, not about entities a
 * machine creates. A dispenser clock, a dropper into a hopper line, or a
 * {@code /summon} loop produces entities that no spawn limit ever sees. Three
 * specific cases are worth naming, because they are the ones that take a server
 * down rather than merely annoy it:
 *
 * <ol>
 *   <li><b>Arrows and wind charges from a dispenser.</b> A dispenser clock
 *       firing arrows builds a standing cloud of projectiles. They are cheap
 *       individually and ruinous in the thousands.</li>
 *   <li><b>Ender pearls.</b> Since 1.21 a thrown pearl <em>loads and ticks the
 *       3x3 chunks around it</em>, takes a chunk ticket whenever it changes
 *       chunk and again every ten seconds, and keeps doing so while it travels.
 *       A pearl that cannot land can hold nine chunks ticking on its own, so a
 *       dispenser aimed at a wall or a pearl loop is a chunk-loading machine.
 *       This module treats pearls as the highest-risk item for that reason.</li>
 *   <li><b>Minecarts.</b> Stacked in one chunk they are a well-known crash
 *       vector, and they are the one entity here a player legitimately wants
 *       back.</li>
 * </ol>
 *
 * <h2>Silence is deliberate</h2>
 * Nothing in this module messages a player. Lag-guard feedback is exactly what
 * a player pushing the limit wants — it confirms the machine is working and
 * tells them the threshold. Refusals are logged once per distinct cause so
 * staff can see them in the console, and can be counted in
 * {@code entitylimit.yml}, but the player sees nothing: the item stays in the
 * dispenser, or the minecart is quietly returned to their inventory.
 *
 * <h2>The three jobs</h2>
 * <ol>
 *   <li><b>Per-chunk cap.</b> Non-living entities in a chunk are counted and the
 *       excess removed, oldest-priority-first by distance from a player.</li>
 *   <li><b>Minecart refund.</b> A minecart removed by the cap is returned to the
 *       nearest player's inventory, or dropped at its own location when nobody
 *       is near. This is the one case where an item is given back, because a
 *       minecart is a player's property and not a byproduct of a machine.</li>
 *   <li><b>Machine-launch refusal.</b> A dispenser, dropper or summon that would
 *       create a refused entity type is cancelled. The dispenser keeps its item.
 *       This is what stops the arrow clock at its source rather than cleaning up
 *       after it.</li>
 * </ol>
 *
 * <h2>What is deliberately NOT capped</h2>
 * Living entities. Spawners, farms, villager halls and pets are all living, and
 * each has its own vanilla limit already. A blanket cap that included them would
 * break a mob farm to solve a problem mob farms do not cause. If a specific
 * world needs that, {@code count-living-entities} exists — it is off by default
 * and turning it on is a deliberate act.
 */
public final class EntityLimitModule implements Listener {
    /** Bypass permission: an operator holding it is not refunded-capped. */
    public static final String PERM_BYPASS = "gildedcore.entitylimit.bypass";

    /**
     * Entity types a machine must not be allowed to create.
     *
     * <p>Kept as an explicit set rather than "everything that is a Projectile"
     * because a few projectiles are wanted: a dispenser firing a firework is a
     * normal build, and a snow golem throwing snowballs is not a lag machine.
     * The set is the things that either accumulate (arrows, eggs, snowballs) or
     * hold chunks (pearls), plus wind charges, which 1.21 added and which
     * activate redstone components on impact — the one projectile that can be
     * used to drive a circuit from a distance.
     */
    private static final Set<EntityType> REFUSED = refusedTypes();

    /**
     * Machine-side projectiles that hold chunks or accumulate. Named rather than
     * derived so a new entity type is never silently refused by a broad rule.
     */
    private static Set<EntityType> refusedTypes() {
        Set<EntityType> set = new LinkedHashSet<>();
        add(set, "ARROW", "SPECTRAL_ARROW", "TIPPED_ARROW", "TRIDENT");
        add(set, "SNOWBALL", "EGG", "ENDER_PEARL", "EXPERIENCE_BOTTLE", "SPLASH_POTION",
                "LINGERING_POTION", "FIREWORK_ROCKET", "FIREBALL", "SMALL_FIREBALL",
                "DRAGON_FIREBALL", "WITHER_SKULL", "SHULKER_BULLET", "LLAMA_SPIT");
        // 1.21. Breeze/wind-charge projectiles activate redstone components, and
        // the player-thrown form is a normal item, so both must be named.
        add(set, "WIND_CHARGE", "BREEZE_WIND_CHARGE");
        return java.util.Collections.unmodifiableSet(set);
    }

    private static void add(Set<EntityType> set, String... names) {
        for (String name : names) {
            try {
                set.add(EntityType.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // Type absent on this server version. Skipping keeps the module
                // loadable across 1.16 -> 26.x rather than failing on one name.
            }
        }
    }

    private final JavaPlugin plugin;
    private SchedulerCompat.ManagedTask cleanupTask;
    /** Distinct refusal causes already logged, so the console is not spammed. */
    private final Set<String> loggedCauses = new java.util.HashSet<>();
    private File dataFile;
    private FileConfiguration data;

    public EntityLimitModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /* ------------------------------------------------------------------ */
    /*  Lifecycle                                                         */
    /* ------------------------------------------------------------------ */

    public void enable() {
        this.loadData();
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        long interval = Math.max(20L, this.cleanupSeconds() * 20L);
        this.cleanupTask = SchedulerCompat.timer(this.plugin, () -> {
            if (this.enabled()) this.cleanupLoadedChunks();
        }, interval, interval);
    }

    public void disable() {
        HandlerList.unregisterAll(this);
        if (this.cleanupTask != null) {
            this.cleanupTask.cancel();
            this.cleanupTask = null;
        }
        this.saveData();
        this.loggedCauses.clear();
    }

    public void reload() {
        this.loggedCauses.clear();
        if (this.cleanupTask != null) this.cleanupTask.cancel();
        if (this.enabled()) this.enable();
    }

    public boolean enabled() {
        FileConfiguration config = this.plugin.getConfig();
        return config.getBoolean("lagguard.enabled", true)
                && config.getBoolean("lagguard.entity-limit.enabled", true);
    }

    /* ------------------------------------------------------------------ */
    /*  Job 3 — machine-launch refusal                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Refuses a dispenser or dropper that would create a refused entity.
     *
     * <p>{@link BlockDispenseEvent} covers dispensers and droppers in one place:
     * a dropper firing a projectile behaves identically to a dispenser here.
     * Cancelling leaves the item <em>inside</em> the container rather than
     * ejecting it, so nothing is lost and no entity is created — the item simply
     * sits there, which is the behaviour asked for.
     *
     * <p>This runs at {@code HIGHEST} so it is the last word: another plugin
     * that wants to allow the dispense has already had its say, and this still
     * refuses. {@code ignoreCancelled = false} (the default) is used
     * deliberately — if something else already cancelled it, there is nothing to
     * refuse and the check is skipped.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDispense(BlockDispenseEvent event) {
        if (!this.enabled() || !this.refuseMachineProjectiles()) return;
        if (!(event.getBlock().getState() instanceof Dispenser)
                && !(event.getBlock().getState() instanceof Dropper)) {
            return;
        }
        if (!this.isRefusedItem(event.getItem())) return;
        event.setCancelled(true);
        this.note("dispenser-" + this.itemKey(event.getItem()), event.getBlock().getLocation());
    }

    /**
     * Refuses a plugin or command that summons a refused entity.
     *
     * <p>{@link EntitySpawnEvent} is the catch-all for {@code /summon}, a
     * spawn-egg-equivalent call, or another plugin creating the entity. It fires
     * for every spawn, so the cheap refusal-type test comes first.
     *
     * <p>What this cannot see: a projectile fired by a player's own bow or by
     * hand. Those arrive as a {@link org.bukkit.event.entity.ProjectileLaunchEvent}
     * with no block behind them, and are left alone — a player shooting a bow is
     * not a lag machine, and refusing it would break PvP.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawn(EntitySpawnEvent event) {
        if (!this.enabled() || !this.refuseMachineProjectiles()) return;
        Entity entity = event.getEntity();
        if (entity == null || !REFUSED.contains(entity.getType())) return;
        // A player-fired projectile has an owner that is a player. Those are
        // legitimate; only machine-origin spawns are refused here.
        if (entity instanceof Projectile projectile) {
            try {
                if (projectile.getShooter() instanceof Player) return;
            } catch (Throwable ignored) {
                // Older API without getShooter: fall through and refuse.
            }
        }
        // Summoned by command or plugin: refused.
        event.setCancelled(true);
        this.note("spawn-" + entity.getType().name(), entity.getLocation());
    }

    /**
     * Whether a player may store a refused item in a dispenser is intentionally
     * not restricted: the item is legal to hold. Only the act of firing it is
     * refused, so the item stays where the player put it.
     */
    private boolean isRefusedItem(ItemStack item) {
        if (item == null) return false;
        Material material = item.getType();
        if (material == null || material.isAir()) return false;
        String name = material.name();
        // Map the item to the entity it would create. A dispenser converts the
        // item on fire, so the item name and the entity name differ.
        return switch (name) {
            case "ARROW", "SPECTRAL_ARROW", "TIPPED_ARROW", "TRIDENT",
                 "SNOWBALL", "EGG", "BLUE_EGG", "BROWN_EGG", "ENDER_PEARL",
                 "EXPERIENCE_BOTTLE", "SPLASH_POTION", "LINGERING_POTION",
                 "FIREWORK_ROCKET", "FIRE_CHARGE", "WIND_CHARGE" -> true;
            default -> false;
        };
    }

    private String itemKey(ItemStack item) {
        return item == null ? "none" : item.getType().name().toLowerCase(Locale.ROOT);
    }

    /* ------------------------------------------------------------------ */
    /*  Jobs 1 and 2 — per-chunk cap and minecart refund                  */
    /* ------------------------------------------------------------------ */

    /** A player placing a minecart over the cap is refused, silently. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMinecartPlace(PlayerInteractEvent event) {
        if (!this.enabled() || !this.minecartsEnabled()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !this.isMinecartMaterial(item.getType())) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Location location = block.getLocation();
        if (location.getWorld() == null) return;
        int max = this.maxPerChunk();
        if (this.countCapped(location.getChunk()) < max) return;
        event.setCancelled(true);
        this.note("minecart-place", location);
    }

    /** A placed minecart is checked once it exists, so the cap holds. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (!this.enabled() || !this.minecartsEnabled()) return;
        Vehicle vehicle = event.getVehicle();
        if (vehicle == null || !this.isMinecart(vehicle)) return;
        SchedulerCompat.runLater(this.plugin, () -> {
            if (!vehicle.isDead()) this.enforceChunk(vehicle.getLocation());
        }, 1L);
    }

    /**
     * A minecart that crosses into a full chunk is capped there.
     *
     * <p>The chunk-change test is what keeps this cheap: a cart on a rail fires
     * a move event every tick, and only the ones that actually change chunk do
     * any work at all.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!this.enabled() || !this.minecartsEnabled()) return;
        Vehicle vehicle = event.getVehicle();
        if (vehicle == null || !this.isMinecart(vehicle)) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null || to.getWorld() == null) return;
        if (this.sameChunk(from, to)) return;
        this.enforceChunk(to);
    }

    /**
     * The catch-all cap: a non-living entity spawning into a full chunk pushes
     * the oldest overflow out.
     *
     * <p>Deferred one tick because the entity is not yet positioned when the
     * event fires, so a count taken now can miss it. The deferral also means a
     * burst of spawns is counted once rather than per-spawn.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!this.enabled() || !this.chunkCapEnabled()) return;
        Entity entity = event.getEntity();
        if (entity == null || !this.counts(entity)) return;
        Location location = entity.getLocation();
        if (location.getWorld() == null) return;
        SchedulerCompat.runLater(this.plugin, () -> {
            if (!entity.isDead()) this.enforceChunk(entity.getLocation());
        }, 1L);
    }

    private void cleanupLoadedChunks() {
        for (World world : Bukkit.getWorlds()) {
            if (world == null) continue;
            for (Chunk chunk : world.getLoadedChunks()) {
                try {
                    this.enforceChunk(chunk);
                } catch (Throwable ignored) {
                    // A chunk that unloads mid-sweep is not an error.
                }
            }
        }
    }

    private void enforceChunk(Location location) {
        if (location == null || location.getWorld() == null) return;
        try {
            this.enforceChunk(location.getChunk());
        } catch (Throwable ignored) {
            // Location outside a loaded chunk.
        }
    }

    /**
     * Removes the overflow of capped entities from one chunk.
     *
     * <p>Overflow is chosen furthest-from-a-player first, so the entities that
     * vanish are the ones nobody is looking at. Minecarts are refunded; other
     * entities are simply removed, because a blanket refund of every arrow would
     * hand a lag machine its ammunition back.
     */
    private void enforceChunk(Chunk chunk) {
        if (chunk == null) return;
        int max = this.maxPerChunk();
        List<Entity> capped = new ArrayList<>();
        try {
            Entity[] entities = chunk.getEntities();
            if (entities == null) return;
            for (Entity entity : entities) {
                if (entity == null || entity.isDead()) continue;
                if (!this.counts(entity)) continue;
                capped.add(entity);
            }
        } catch (Throwable ignored) {
            return;
        }
        int overflow = capped.size() - max;
        if (overflow <= 0) return;
        Location origin = this.nearestPlayerLocation(capped);
        capped.sort((a, b) -> {
            if (origin == null) return 0;
            return Double.compare(this.distanceSquared(b.getLocation(), origin),
                    this.distanceSquared(a.getLocation(), origin));
        });
        int removed = 0;
        for (Entity entity : capped) {
            if (removed >= overflow) break;
            if (entity == null || entity.isDead()) continue;
            // A player riding a vehicle is not something to silently delete.
            if (this.hasPlayerPassenger(entity)) continue;
            if (!this.remove(entity)) continue;
            removed++;
        }
        if (removed > 0) this.countRemoved(removed);
    }

    /**
     * Removes one entity, refunding it first when it is a minecart.
     *
     * <p>The refund happens before the removal so the item exists even if the
     * remove call throws: a failed removal then leaves both the cart and the
     * refund, which is a duplicate rather than a loss. Duplication on an error
     * path is the better failure of the two — it is visible and recoverable,
     * where a loss is neither.
     */
    private boolean remove(Entity entity) {
        Location location = entity.getLocation();
        boolean minecart = this.minecartsEnabled() && this.isMinecart(entity);
        if (minecart && this.refundMinecarts()) {
            this.refund(this.materialFor(entity), location);
        }
        try {
            entity.remove();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Returns a minecart to a nearby player's inventory, or drops it in place.
     *
     * <p>Silent by design. The previous behaviour told the player their cart had
     * been refunded, which turns a lag guard into a notification service and
     * tells a player exactly when they tripped it.
     */
    private void refund(Material material, Location location) {
        if (material == null) return;
        ItemStack item = new ItemStack(material, 1);
        Player target = this.refundTarget(location);
        if (target != null) {
            PlayerInventory inventory = target.getInventory();
            HashMap<Integer, ItemStack> leftover = inventory.addItem(item);
            if (leftover != null && !leftover.isEmpty()) {
                for (ItemStack rest : leftover.values()) this.dropSafely(location, rest);
            }
            return;
        }
        this.dropSafely(location, item);
    }

    private Player refundTarget(Location location) {
        if (location == null || location.getWorld() == null) return null;
        double radiusSquared = (double) this.refundRadius() * this.refundRadius();
        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : location.getWorld().getPlayers()) {
            if (player == null || player.isDead()) continue;
            if (player.hasPermission(PERM_BYPASS)) continue;
            double distance = this.distanceSquared(player.getLocation(), location);
            if (distance <= radiusSquared && distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void dropSafely(Location location, ItemStack item) {
        if (location == null || location.getWorld() == null || item == null) return;
        try {
            location.getWorld().dropItemNaturally(location, item);
        } catch (Throwable ignored) {
            // Dropping is best-effort; a failed drop must not throw out of the sweep.
        }
    }

    private Location nearestPlayerLocation(List<Entity> entities) {
        for (Entity entity : entities) {
            if (entity == null || entity.getLocation().getWorld() == null) continue;
            for (Player player : entity.getLocation().getWorld().getPlayers()) {
                if (player != null) return player.getLocation();
            }
        }
        return null;
    }

    private boolean hasPlayerPassenger(Entity entity) {
        try {
            for (Entity passenger : entity.getPassengers()) {
                if (passenger instanceof Player) return true;
            }
        } catch (Throwable ignored) {
            // Older API. A missing passenger check is not worth failing over.
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /*  Classification                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Whether an entity counts toward the per-chunk cap.
     *
     * <p>Non-living is the default line: it covers minecarts, projectiles,
     * dropped items, boats, armour stands and the like — the machine-made
     * population — while leaving mobs, animals and villagers to their own
     * vanilla limits. {@code count-living-entities} widens it, and excluding
     * players is absolute: a cap that could remove a player is never right.
     */
    private boolean counts(Entity entity) {
        if (entity == null || entity.isDead()) return false;
        if (entity instanceof Player) return false;
        if (entity instanceof Item) return this.countItems();
        if (this.isMinecart(entity)) return this.minecartsEnabled();
        if (this.isRefusedType(entity.getType())) return this.refuseMachineProjectiles();
        // Living is the exclusion: the default line is non-living only, and
        // count-living-entities widens it. LivingEntity is the check because
        // Entity has no isLiving() - that lives on the subtype.
        return this.countLivingEntities() || !(entity instanceof LivingEntity);
    }

    private boolean isRefusedType(EntityType type) {
        return type != null && REFUSED.contains(type);
    }

    private boolean isMinecart(Entity entity) {
        if (entity instanceof Minecart) return true;
        try {
            EntityType type = entity.getType();
            return type != null && type.name().toUpperCase(Locale.ROOT).contains("MINECART");
        } catch (Throwable ignored) {
            return entity instanceof Vehicle;
        }
    }

    private boolean isMinecartMaterial(Material material) {
        return material != null && material.name().toUpperCase(Locale.ROOT).contains("MINECART");
    }

    private int countCapped(Chunk chunk) {
        int count = 0;
        try {
            for (Entity entity : chunk.getEntities()) {
                if (this.counts(entity)) count++;
            }
        } catch (Throwable ignored) {
            return 0;
        }
        return count;
    }

    /** The item a removed minecart becomes, matching its variant. */
    private Material materialFor(Entity entity) {
        String name = "";
        try {
            EntityType type = entity.getType();
            if (type != null) name = type.name().toUpperCase(Locale.ROOT);
        } catch (Throwable ignored) {
            // Fall through to the plain minecart.
        }
        String target = name.contains("CHEST") ? "CHEST_MINECART"
                : name.contains("HOPPER") ? "HOPPER_MINECART"
                : name.contains("TNT") ? "TNT_MINECART"
                : name.contains("FURNACE") ? "FURNACE_MINECART"
                : name.contains("COMMAND") ? "COMMAND_BLOCK_MINECART"
                : "MINECART";
        try {
            return Material.valueOf(target);
        } catch (Throwable ignored) {
            return Material.MINECART;
        }
    }

    private boolean sameChunk(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) return false;
        if (!a.getWorld().equals(b.getWorld())) return false;
        return (a.getBlockX() >> 4) == (b.getBlockX() >> 4)
                && (a.getBlockZ() >> 4) == (b.getBlockZ() >> 4);
    }

    private double distanceSquared(Location a, Location b) {
        if (a == null || b == null || a.getWorld() == null || b.getWorld() == null) {
            return Double.MAX_VALUE;
        }
        if (!a.getWorld().equals(b.getWorld())) return Double.MAX_VALUE;
        try {
            return a.distanceSquared(b);
        } catch (Throwable ignored) {
            return Double.MAX_VALUE;
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Configuration                                                     */
    /* ------------------------------------------------------------------ */

    private int maxPerChunk() {
        return Math.max(1, this.plugin.getConfig().getInt("lagguard.entity-limit.max-per-chunk", 32));
    }

    private int refundRadius() {
        return Math.max(1, this.plugin.getConfig().getInt("lagguard.entity-limit.refund-radius", 16));
    }

    private int cleanupSeconds() {
        return Math.max(1, this.plugin.getConfig().getInt("lagguard.entity-limit.cleanup-interval-seconds", 3));
    }

    private boolean chunkCapEnabled() {
        return this.plugin.getConfig().getBoolean("lagguard.entity-limit.per-chunk-cap", true);
    }

    private boolean minecartsEnabled() {
        return this.plugin.getConfig().getBoolean("lagguard.entity-limit.minecarts.enabled", true);
    }

    private boolean refundMinecarts() {
        return this.plugin.getConfig().getBoolean("lagguard.entity-limit.minecarts.refund", true);
    }

    private boolean refuseMachineProjectiles() {
        return this.plugin.getConfig().getBoolean("lagguard.entity-limit.refuse-machine-projectiles", true);
    }

    private boolean countItems() {
        return this.plugin.getConfig().getBoolean("lagguard.entity-limit.count-dropped-items", true);
    }

    private boolean countLivingEntities() {
        return this.plugin.getConfig().getBoolean("lagguard.entity-limit.count-living-entities", false);
    }

    /* ------------------------------------------------------------------ */
    /*  Staff-facing logging                                              */
    /* ------------------------------------------------------------------ */

    /**
     * Records a refusal once per distinct cause.
     *
     * <p>Deliberately not a broadcast and not a per-event log line. A dispenser
     * clock fires twenty times a second; logging each one turns the console into
     * the denial-of-service. The first occurrence of a cause is logged with its
     * location, and the count in {@code entitylimit.yml} carries the volume.
     */
    private void note(String cause, Location location) {
        String key = cause == null ? "unknown" : cause.toLowerCase(Locale.ROOT);
        this.bumpCount(key);
        if (!this.logRefusals()) return;
        if (location != null && location.getWorld() != null) {
            key = key + "@" + location.getWorld().getName() + " "
                    + location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
        }
        if (!this.loggedCauses.add(key)) return;
        this.plugin.getLogger().info("[EntityLimit] refused " + cause
                + (location != null && location.getWorld() != null
                        ? " at " + location.getWorld().getName() + " " + location.getBlockX()
                          + "," + location.getBlockY() + "," + location.getBlockZ()
                        : ""));
    }

    /** Whether staff alerts for this module are wanted. */
    private boolean logRefusals() {
        return this.plugin.getConfig().getBoolean("lagguard.entity-limit.log-refusals", true);
    }

    private void bumpCount(String key) {
        if (this.data == null) return;
        String path = "counts." + key.replace('.', '_');
        this.data.set(path, this.data.getInt(path, 0) + 1);
        // Counters are saved on the timer rather than per event: a dispenser
        // clock would otherwise write to disk twenty times a second.
    }

    private void countRemoved(int amount) {
        if (this.data == null) return;
        this.data.set("totals.removed", this.data.getInt("totals.removed", 0) + amount);
    }

    /* ------------------------------------------------------------------ */
    /*  Persistence                                                       */
    /* ------------------------------------------------------------------ */

    private void loadData() {
        if (!this.plugin.getDataFolder().exists()) this.plugin.getDataFolder().mkdirs();
        this.dataFile = new File(this.plugin.getDataFolder(), "entitylimit.yml");
        this.data = YamlConfiguration.loadConfiguration(this.dataFile);
    }

    private void saveData() {
        if (this.data == null || this.dataFile == null) return;
        try {
            this.data.save(this.dataFile);
        } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save entitylimit.yml: " + ex.getMessage());
        }
    }

    /** One row for the diagnostics page. */
    public String summary() {
        return (this.enabled() ? "enabled" : "disabled")
                + ", cap " + this.maxPerChunk() + "/chunk"
                + (this.minecartsEnabled() ? ", minecarts refunded" : "")
                + (this.refuseMachineProjectiles() ? ", machine projectiles refused" : "");
    }

    /** Exposed for the diagnostics permission dump. */
    public Set<EntityType> refusedTypesView() {
        return REFUSED;
    }
}
