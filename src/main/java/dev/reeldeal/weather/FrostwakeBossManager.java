package dev.reeldeal.weather;

import dev.reeldeal.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Illusioner;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Handles the local Frostwake fishing gathering and its pursuing Illusioner boss. */
public final class FrostwakeBossManager implements Listener {

    private static final String EVENT_ID = "frostwake";
    private static final NamespacedKey BOSS_KEY = new NamespacedKey("reel", "frostwake_boss");

    private final Plugin plugin;
    private final WeatherEventManager weatherEvents;
    private final ItemRegistry items;
    private final Map<UUID, FishHook> activeHooks = new HashMap<>();
    private final BukkitTask ticker;
    private UUID bossId;
    private boolean testBoss;
    private long nextHookCleanupTick;
    private long nextTeleportCheckTick;
    private long teleportCooldownUntilTick;
    private long lastBossMovementTick;
    private long encounterCooldownUntilTick;
    private Location lastBossLocation;
    private Set<UUID> encounterParticipants = Set.of();

    public FrostwakeBossManager(Plugin plugin, WeatherEventManager weatherEvents, ItemRegistry items) {
        this.plugin = plugin;
        this.weatherEvents = weatherEvents;
        this.items = items;
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        switch (event.getState()) {
            case FISHING, LURED, BITE -> activeHooks.put(
                    event.getPlayer().getUniqueId(), event.getHook());
            case CAUGHT_FISH -> {
                activeHooks.remove(event.getPlayer().getUniqueId());
                trySummonForCatch(event.getPlayer(), event.getHook());
            }
            case CAUGHT_ENTITY, IN_GROUND, FAILED_ATTEMPT, REEL_IN -> activeHooks.remove(
                    event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        activeHooks.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        if (!isBoss(event.getEntity())) return;
        Entity attacker = event.getDamager();
        if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            ((Illusioner) event.getEntity()).setTarget(player);
        } else if (attacker instanceof Player player) {
            ((Illusioner) event.getEntity()).setTarget(player);
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        if (!isBoss(event.getEntity())) return;
        Location deathLocation = event.getEntity().getLocation();
        Set<UUID> recipients = Set.copyOf(encounterParticipants);
        clearBossState();
        event.getDrops().clear();
        event.setDroppedExp(0);
        for (UUID recipientId : recipients) {
            rewardFrostwakeShard(recipientId, deathLocation);
        }
        broadcastNearby(deathLocation, 48,
                Component.text("The Frostwake fades beneath the ice-cold air.", NamedTextColor.GRAY));
    }

    /** Immediately spawns a test boss by the command issuer, ignoring natural event/biome/count checks. */
    public boolean spawnTest(Player player) {
        if (currentBoss() != null) return false;
        Location spawn = findSafeLocation(player.getLocation(), 6.0);
        if (spawn == null) return false;
        return spawnBoss(spawn, player, true, Set.of(player.getUniqueId()));
    }

    public boolean stopTestBoss() {
        Illusioner boss = currentBoss();
        if (boss == null || !testBoss) return false;
        boss.remove();
        clearBossState();
        return true;
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        Illusioner boss = currentBoss();
        if (boss != null) {
            if (!testBoss && !weatherEvents.isEventActive(EVENT_ID)) {
                boss.remove();
                clearBossState();
                return;
            }
            tickBoss(boss, now);
            return;
        }
        if (now >= nextHookCleanupTick) {
            activeHooks.entrySet().removeIf(entry -> {
                Player player = Bukkit.getPlayer(entry.getKey());
                FishHook hook = entry.getValue();
                return player == null || !player.isOnline() || !player.isValid() || player.isDead()
                        || hook == null || !hook.isValid() || !hook.getWorld().equals(player.getWorld());
            });
            nextHookCleanupTick = now + 200L;
        }
    }

    private void trySummonForCatch(Player catcher, FishHook catchHook) {
        long now = Bukkit.getCurrentTick();
        if (!plugin.getConfig().getBoolean("weather-events.frostwake.boss.enabled", true)
                || !weatherEvents.isEventActive(EVENT_ID) || now < encounterCooldownUntilTick
                || currentBoss() != null) return;

        Location catchLocation = catchHook.getLocation();
        if (!isColdBiome(catchLocation)) return;
        int minimum = Math.max(2, plugin.getConfig().getInt("weather-events.frostwake.boss.min-fishers", 5));
        int maximum = Math.max(minimum, plugin.getConfig().getInt("weather-events.frostwake.boss.max-fishers", 10));
        double radius = Math.max(1.0, plugin.getConfig().getDouble("weather-events.frostwake.boss.gather-radius", 24.0));
        double radiusSquared = radius * radius;
        double chance = clamp(plugin.getConfig().getDouble(
                "weather-events.frostwake.boss.summon-chance-per-catch",
                plugin.getConfig().getDouble("weather-events.frostwake.boss.summon-chance-per-check", 0.12)), 0.0, 1.0);

        // A successful catch is automatically one member of the group. Other members must
        // still have bobbers actively floating in cold-biome water close to the catch spot.
        List<NearbyFisher> nearbyFishers = new ArrayList<>();
        for (Map.Entry<UUID, FishHook> entry : activeHooks.entrySet()) {
            if (entry.getKey().equals(catcher.getUniqueId())) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            FishHook hook = entry.getValue();
            if (player == null || !player.isOnline() || !player.isValid() || player.isDead()
                    || hook == null || !hook.isValid() || !hook.getWorld().equals(catchLocation.getWorld())
                    || hook.getState() != FishHook.HookState.BOBBING || !hook.isInWater()) continue;
            Location bobberLocation = hook.getLocation();
            if (isColdBiome(bobberLocation) && bobberLocation.distanceSquared(catchLocation) <= radiusSquared) {
                nearbyFishers.add(new NearbyFisher(player, bobberLocation.distanceSquared(catchLocation)));
            }
        }

        // HashMap iteration order is arbitrary; use the nearest fishers so the reward
        // recipients and configured participant cap are predictable.
        nearbyFishers.sort(Comparator.comparingDouble(NearbyFisher::distanceSquared));
        List<Player> group = new ArrayList<>(Math.min(maximum, nearbyFishers.size() + 1));
        group.add(catcher);
        for (NearbyFisher fisher : nearbyFishers) {
            if (group.size() >= maximum) break;
            group.add(fisher.player());
        }

        if (group.size() < minimum || ThreadLocalRandom.current().nextDouble() >= chance) return;
        Location spawn = findSafeLocation(catchLocation, 10.0);
        if (spawn == null) spawn = findSafeLocation(catcher.getLocation(), 8.0);
        Set<UUID> participantIds = new HashSet<>();
        group.forEach(player -> participantIds.add(player.getUniqueId()));
        if (spawn == null || !spawnBoss(spawn, catcher, false, participantIds)) return;

        long cooldown = Math.max(0L, plugin.getConfig().getLong(
                "weather-events.frostwake.boss.cooldown-seconds", 300L));
        encounterCooldownUntilTick = now + cooldown * 20L;
        broadcastNearby(catchLocation, radius + 32.0,
                Component.text("Something stirs beneath the Frostwake waters...", NamedTextColor.AQUA));
    }

    private boolean spawnBoss(Location location, Player target, boolean test, Set<UUID> participants) {
        World world = location.getWorld();
        if (world == null || currentBoss() != null) return false;
        Entity entity = world.spawnEntity(location, EntityType.ILLUSIONER);
        if (!(entity instanceof Illusioner illusioner)) {
            entity.remove();
            plugin.getLogger().warning("Frostwake boss could not spawn: ILLUSIONER is unavailable.");
            return false;
        }

        double health = Math.max(1.0, plugin.getConfig().getDouble("weather-events.frostwake.boss.health", 160.0));
        Attribute maxHealth = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        AttributeInstance maxHealthAttribute = maxHealth == null ? null : illusioner.getAttribute(maxHealth);
        if (maxHealthAttribute != null) maxHealthAttribute.setBaseValue(health);
        illusioner.setHealth(Math.min(health, maxHealthAttribute == null ? illusioner.getHealth() : maxHealthAttribute.getValue()));
        illusioner.customName(Component.text("Frostwake", NamedTextColor.AQUA));
        illusioner.setCustomNameVisible(true);
        illusioner.setPersistent(true);
        illusioner.setRemoveWhenFarAway(false);
        illusioner.getPersistentDataContainer().set(BOSS_KEY, PersistentDataType.BYTE, (byte) 1);
        if (target != null && target.isValid()) illusioner.setTarget(target);

        bossId = illusioner.getUniqueId();
        testBoss = test;
        encounterParticipants = Set.copyOf(participants);
        nextTeleportCheckTick = Bukkit.getCurrentTick();
        teleportCooldownUntilTick = Bukkit.getCurrentTick() + secondsToTicks(
                "weather-events.frostwake.boss.teleport-cooldown-seconds", 8L);
        lastBossMovementTick = Bukkit.getCurrentTick();
        lastBossLocation = location.clone();
        illusioner.getWorld().spawnParticle(Particle.SNOWFLAKE, location.clone().add(0, 1, 0),
                28, 0.9, 1.2, 0.9, 0.03);
        illusioner.getWorld().spawnParticle(Particle.LARGE_SMOKE, location.clone().add(0, 1, 0),
                7, 0.35, 0.6, 0.35, 0.01);
        illusioner.getWorld().playSound(location, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE,
                1.0f, 0.7f);
        return true;
    }

    private void rewardFrostwakeShard(UUID recipientId, Location deathLocation) {
        ItemStack shard = items.create("frostwake_shard");
        Player player = Bukkit.getPlayer(recipientId);
        if (player != null && player.isOnline()) {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(shard);
            for (ItemStack leftover : leftovers.values()) {
                dropReward(deathLocation, recipientId, leftover);
            }
            player.sendMessage(Component.text("You received a Frostwake Shard.", NamedTextColor.AQUA));
            return;
        }
        // Preserve the share for a participant who disconnected during the fight.
        dropReward(deathLocation, recipientId, shard);
    }

    private void dropReward(Location location, UUID ownerId, ItemStack stack) {
        Item drop = location.getWorld().dropItemNaturally(location, stack);
        drop.setOwner(ownerId);
    }

    private void tickBoss(Illusioner boss, long now) {
        if (now < nextTeleportCheckTick) return;
        nextTeleportCheckTick = now + secondsToTicks("weather-events.frostwake.boss.teleport-check-interval-seconds", 1L);

        Player target = boss.getTarget() instanceof Player player && player.isOnline() && player.isValid()
                && !player.isDead() && player.getWorld().equals(boss.getWorld()) ? player : null;
        if (target == null) {
            target = nearestPlayer(boss, 48.0);
            if (target != null) boss.setTarget(target);
        }
        if (target == null) return;

        Location current = boss.getLocation();
        if (lastBossLocation == null || current.distanceSquared(lastBossLocation) > 0.25) {
            lastBossLocation = current.clone();
            lastBossMovementTick = now;
        }

        double teleportDistance = Math.max(8.0,
                plugin.getConfig().getDouble("weather-events.frostwake.boss.teleport-distance", 32.0));
        double stuckDistance = Math.max(8.0,
                plugin.getConfig().getDouble("weather-events.frostwake.boss.stuck-min-distance", 12.0));
        long stuckTicks = secondsToTicks("weather-events.frostwake.boss.stuck-seconds", 10L);
        double targetDistanceSquared = current.distanceSquared(target.getLocation());
        boolean tooFar = targetDistanceSquared > teleportDistance * teleportDistance;
        boolean stuck = now - lastBossMovementTick >= stuckTicks
                && targetDistanceSquared > stuckDistance * stuckDistance && !boss.hasLineOfSight(target);
        if ((!tooFar && !stuck) || now < teleportCooldownUntilTick) return;

        Location destination = findSafeLocation(target.getLocation(),
                Math.max(3.0, plugin.getConfig().getDouble("weather-events.frostwake.boss.teleport-arrival-radius", 6.0)));
        if (destination == null || !boss.teleport(destination)) return;

        boss.setTarget(target);
        lastBossLocation = destination.clone();
        lastBossMovementTick = now;
        teleportCooldownUntilTick = now + secondsToTicks(
                "weather-events.frostwake.boss.teleport-cooldown-seconds", 8L);
        boss.getWorld().spawnParticle(Particle.SNOWFLAKE, current.clone().add(0, 1, 0),
                14, 0.6, 0.8, 0.6, 0.02);
        boss.getWorld().playSound(current, Sound.ENTITY_ENDERMAN_TELEPORT, 0.55f, 0.7f);
        boss.getWorld().spawnParticle(Particle.SNOWFLAKE, destination.clone().add(0, 1, 0),
                14, 0.6, 0.8, 0.6, 0.02);
        boss.getWorld().playSound(destination, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 0.7f);
    }

    private Player nearestPlayer(Illusioner boss, double range) {
        double bestDistance = range * range;
        Player nearest = null;
        for (Player player : boss.getWorld().getPlayers()) {
            if (!player.isOnline() || player.isDead() || player.getGameMode().name().equals("SPECTATOR")) continue;
            double distance = player.getLocation().distanceSquared(boss.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private Location findSafeLocation(Location around, double radius) {
        World world = around.getWorld();
        if (world == null) return null;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 32; attempt++) {
            double angle = random.nextDouble(Math.PI * 2.0);
            double distance = attempt == 0 ? 0.0 : random.nextDouble(Math.max(1.0, radius * 0.45), radius + 0.01);
            int x = around.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = around.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);

            // Prefer the target's current elevation (useful for caves and test spawns).
            for (int offset = 0; offset <= 6; offset++) {
                int below = around.getBlockY() - 1 - offset;
                Location safe = safeFloor(world, x, below, z, around);
                if (safe != null) return safe;
                if (offset > 0) {
                    safe = safeFloor(world, x, around.getBlockY() - 1 + offset, z, around);
                    if (safe != null) return safe;
                }
            }

            int highest = world.getHighestBlockYAt(x, z);
            if (Math.abs(highest + 1 - around.getBlockY()) > 32) continue;
            for (int floorY = highest; floorY >= Math.max(world.getMinHeight(), highest - 6); floorY--) {
                Location safe = safeFloor(world, x, floorY, z, around);
                if (safe != null) return safe;
            }
        }
        return null;
    }

    private Location safeFloor(World world, int x, int floorY, int z, Location around) {
        int feetY = floorY + 1;
        if (floorY < world.getMinHeight() || feetY + 1 >= world.getMaxHeight()) return null;
        Block floor = world.getBlockAt(x, floorY, z);
        Block feet = world.getBlockAt(x, feetY, z);
        Block head = world.getBlockAt(x, feetY + 1, z);
        if (!floor.getType().isSolid() || floor.isLiquid() || !feet.isPassable() || feet.isLiquid()
                || !head.isPassable() || head.isLiquid()) return null;
        return new Location(world, x + 0.5, feetY, z + 0.5, around.getYaw(), 0.0f);
    }

    private boolean isColdBiome(Location location) {
        if (location.getWorld() == null) return false;
        String biomeId = location.getBlock().getBiome().getKey().toString().toLowerCase(Locale.ROOT);
        String key = biomeId.substring(biomeId.indexOf(':') + 1);
        List<String> configured = plugin.getConfig().getStringList("weather-events.frostwake.boss.cold-biomes");
        if (!configured.isEmpty()) {
            for (String name : configured) {
                String configuredId = name.toLowerCase(Locale.ROOT);
                if (configuredId.equals(biomeId) || configuredId.equals(key)) return true;
            }
            return false;
        }
        return key.contains("frozen") || key.contains("snowy") || key.contains("ice_spikes")
                || key.equals("cold_ocean") || key.equals("deep_cold_ocean")
                || key.equals("taiga") || key.equals("old_growth_pine_taiga")
                || key.equals("old_growth_spruce_taiga") || key.equals("grove")
                || key.equals("snowy_slopes") || key.equals("jagged_peaks")
                || key.equals("stony_peaks") || key.startsWith("windswept_");
    }

    private boolean isBoss(Entity entity) {
        if (bossId != null && bossId.equals(entity.getUniqueId())) return true;
        return entity.getPersistentDataContainer().has(BOSS_KEY, PersistentDataType.BYTE);
    }

    private Illusioner currentBoss() {
        if (bossId == null) return null;
        Entity entity = Bukkit.getEntity(bossId);
        if (entity instanceof Illusioner illusioner && illusioner.isValid() && !illusioner.isDead()) return illusioner;
        clearBossState();
        return null;
    }

    private void clearBossState() {
        bossId = null;
        testBoss = false;
        lastBossLocation = null;
        lastBossMovementTick = 0L;
        encounterParticipants = Set.of();
    }

    private record NearbyFisher(Player player, double distanceSquared) { }

    private void broadcastNearby(Location location, double radius, Component message) {
        double radiusSquared = radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSquared) player.sendMessage(message);
        }
    }

    private long secondsToTicks(String path, long defaultSeconds) {
        return Math.max(1L, plugin.getConfig().getLong(path, defaultSeconds)) * 20L;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public void stop() {
        ticker.cancel();
        Illusioner boss = currentBoss();
        if (boss != null) boss.remove();
        clearBossState();
        activeHooks.clear();
    }

}
