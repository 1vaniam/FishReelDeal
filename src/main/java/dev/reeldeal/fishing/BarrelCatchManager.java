package dev.reeldeal.fishing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import dev.reeldeal.item.ItemRegistry;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Spawns a barrel model at the bobber, flings it toward the angler, and opens it after repeated hits. */
public final class BarrelCatchManager implements Listener {

    private final Plugin plugin;
    private final ItemRegistry items;
    private final DisplayFollowTicker displayTicker;
    private final Map<UUID, ActiveBarrel> active = new HashMap<>();
    private final Random lootRandom = new Random();
    private final Random encounterRandom = new Random();

    private static final NamespacedKey DROP_OWNER_KEY = new NamespacedKey("reel", "barrel_drop_owner");

    private record BarrelLoot(ItemStack item, int minAmount, int maxAmount) {
        ItemStack roll(Random random) {
            int amount = minAmount + random.nextInt(maxAmount - minAmount + 1);
            ItemStack result = item.clone();
            result.setAmount(amount);
            return result;
        }
    }

    private record ActiveBarrel(LivingEntity hitbox, ItemDisplay display, BukkitTask despawn,
                                UUID ownerId, int hits) {
        void cancelTasks() {
            despawn.cancel();
        }
    }

    public BarrelCatchManager(Plugin plugin, DisplayFollowTicker displayTicker, ItemRegistry items) {
        this.plugin = plugin;
        this.displayTicker = displayTicker;
        this.items = items;
    }

    public boolean shouldCatch(String hookId, Random random) {
        if (!plugin.getConfig().getBoolean("barrel-catch.enabled", true)) {
            return false;
        }
        String hookKey = hookId == null ? "normal" : hookId;
        double baseChance = plugin.getConfig().getDouble("barrel-catch.base-chance", 0.01);
        double multiplier = plugin.getConfig().getDouble("barrel-catch.hook-multipliers." + hookKey, 1.0);
        double chance = Math.max(0.0, Math.min(1.0, baseChance * Math.max(0.0, multiplier)));
        boolean success = random.nextDouble() < chance;
        if (plugin.getConfig().getBoolean("barrel-catch.debug", false)) {
            plugin.getLogger().info("Barrel catch roll: hook=" + hookKey + ", chance=" + chance
                    + ", success=" + success + ".");
        }
        return success;
    }

    /** Spawn the barrel at the bobber and launch it toward the player like a hooked-fish model. */
    public boolean spawn(Player player, Location bobberLocation) {
        World world = bobberLocation.getWorld();
        if (world == null) {
            if (plugin.getConfig().getBoolean("barrel-catch.debug", false)) {
                plugin.getLogger().warning("Barrel catch rolled, but the bobber location has no world.");
            }
            return false;
        }

        EntityType type;
        try {
            type = EntityType.valueOf(plugin.getConfig().getString("barrel-catch.hitbox-mob", "TURTLE")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.TURTLE;
        }
        Location hitboxLocation = bobberLocation.clone().add(0, 0.3, 0);
        Entity spawned = world.spawnEntity(hitboxLocation, type);
        if (!(spawned instanceof LivingEntity hitbox)) {
            spawned.remove();
            plugin.getLogger().warning("barrel-catch.hitbox-mob must be a living entity type.");
            return false;
        }
        hitbox.setAI(false);
        if (hitbox instanceof Mob mob) {
            mob.setAware(false);
        }
        hitbox.setGravity(true);
        hitbox.setCollidable(true);
        hitbox.setInvisible(true);
        hitbox.setSilent(true);
        hitbox.setPersistent(false);
        setAttribute(hitbox, "scale", plugin.getConfig().getDouble("barrel-catch.hitbox-scale", 0.8));
        setAttribute(hitbox, "max_health", 100.0);
        Attribute maxHealth = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        AttributeInstance health = maxHealth == null ? null : hitbox.getAttribute(maxHealth);
        if (health != null) {
            hitbox.setHealth(health.getValue());
        }

        ItemDisplay display = world.spawn(hitboxLocation, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.BARREL));
            d.setPersistent(false);
            d.setTeleportDuration(1);
            float scale = (float) plugin.getConfig().getDouble("barrel-catch.model-scale", 0.8);
            d.setTransformationMatrix(new Matrix4f().translate(0f, 0.5f * scale, 0f).scale(scale));
        });

        Vector toward = player.getEyeLocation().toVector().subtract(hitboxLocation.toVector());
        if (toward.lengthSquared() < 1.0e-4) {
            toward = player.getLocation().getDirection().multiply(-1);
        }
        toward.normalize().multiply(plugin.getConfig().getDouble("barrel-catch.fling-speed", 1.1));
        toward.setY(0.35);
        hitbox.setVelocity(toward);

        UUID id = hitbox.getUniqueId();
        long despawnTicks = Math.max(1L, plugin.getConfig().getLong("barrel-catch.despawn-seconds", 45L)) * 20L;
        BukkitTask despawn = org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () -> remove(id), despawnTicks);
        active.put(id, new ActiveBarrel(hitbox, display, despawn, player.getUniqueId(), 0));
        displayTicker.track(hitbox, display, 0f, 0, null, () -> remove(id));
        if (plugin.getConfig().getBoolean("barrel-catch.debug", false)) {
            plugin.getLogger().info("Spawned flying barrel catch for " + player.getName() + ".");
        }
        return true;
    }

    /** Spawns a test barrel a few blocks in front of the player. */
    public boolean spawnTest(Player player) {
        Location at = player.getLocation().add(player.getLocation().getDirection().multiply(3)).add(0, 1, 0);
        return spawn(player, at);
    }

    @EventHandler
    public void onBarrelHit(EntityDamageByEntityEvent event) {
        ActiveBarrel barrel = active.get(event.getEntity().getUniqueId());
        if (barrel == null) {
            return;
        }
        event.setCancelled(true);
        Player player = responsiblePlayer(event.getDamager());
        if (player == null) {
            return;
        }
        if (!barrel.ownerId().equals(player.getUniqueId())) {
            player.sendActionBar(Component.text("This barrel belongs to another angler.", NamedTextColor.RED));
            return;
        }
        int hits = barrel.hits() + 1;
        int required = Math.max(1, plugin.getConfig().getInt("barrel-catch.hits-to-open", 5));
        player.sendActionBar(Component.text("Barrel " + hits + "/" + required, NamedTextColor.GOLD));
        player.playSound(barrel.hitbox().getLocation(), Sound.BLOCK_WOOD_HIT, 0.8f, 0.9f + 0.08f * hits);
        if (hits >= required) {
            open(barrel, player);
        } else {
            active.put(barrel.hitbox().getUniqueId(), new ActiveBarrel(barrel.hitbox(), barrel.display(),
                    barrel.despawn(), barrel.ownerId(), hits));
        }
    }

    private Player responsiblePlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void open(ActiveBarrel barrel, Player player) {
        UUID id = barrel.hitbox().getUniqueId();
        Location dropAt = barrel.hitbox().getLocation();
        remove(id);
        var lootDrop = dropAt.getWorld().dropItemNaturally(dropAt, rollBarrelLoot());
        lootDrop.setOwner(barrel.ownerId());
        lootDrop.setCanMobPickup(false);
        lootDrop.getPersistentDataContainer().set(DROP_OWNER_KEY, PersistentDataType.STRING,
                barrel.ownerId().toString());
        double drownedChance = Math.max(0.0, Math.min(100.0,
                plugin.getConfig().getDouble("barrel-catch.drowned-chance", 10.0)));
        if (encounterRandom.nextDouble() * 100.0 < drownedChance) {
            Entity spawned = dropAt.getWorld().spawnEntity(dropAt, EntityType.DROWNED);
            if (spawned instanceof Mob drowned) {
                drowned.setPersistent(false);
                drowned.setTarget(player);
                player.sendMessage(Component.text("A drowned burst out of the barrel!", NamedTextColor.RED));
            }
        }
        dropAt.getWorld().playSound(dropAt, Sound.BLOCK_BARREL_OPEN, 1.0f, 1.0f);
        player.sendActionBar(Component.text("The barrel opened!", NamedTextColor.GREEN));
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        ActiveBarrel barrel = active.get(event.getEntity().getUniqueId());
        if (barrel == null) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        remove(event.getEntity().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onLootPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        String owner = event.getItem().getPersistentDataContainer().get(DROP_OWNER_KEY, PersistentDataType.STRING);
        if (owner != null && !owner.equals(player.getUniqueId().toString())) {
            event.setCancelled(true);
        }
    }

    private void remove(UUID id) {
        ActiveBarrel barrel = active.remove(id);
        if (barrel == null) {
            return;
        }
        displayTicker.untrack(id);
        barrel.cancelTasks();
        barrel.hitbox().remove();
        barrel.display().remove();
    }

    public void clear() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            remove(id);
        }
    }

    private void setAttribute(LivingEntity entity, String key, double value) {
        Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
        AttributeInstance instance = attr == null ? null : entity.getAttribute(attr);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private ItemStack placeholderLoot() {
        String materialName = plugin.getConfig().getString("barrel-catch.placeholder-loot", "CHEST");
        Material material = Material.matchMaterial(materialName == null ? "CHEST" : materialName);
        if (material == null || !material.isItem()) {
            material = Material.CHEST;
        }
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.itemName(Component.text("Barrel Loot Placeholder", NamedTextColor.GOLD));
        meta.lore(java.util.List.of(Component.text("Replace this with the final barrel loot table.", NamedTextColor.GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack rollBarrelLoot() {
        WeightedPool<BarrelLoot> pool = new WeightedPool<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList("barrel-catch.loot-table")) {
            ItemStack item;
            Object configuredItem = entry.get("item");
            if (configuredItem != null) {
                String itemId = String.valueOf(configuredItem);
                if (!items.ids().contains(itemId) || !"hook".equals(items.categoryOf(itemId))) continue;
                item = items.create(itemId);
            } else {
                Object configuredMaterial = entry.get("material");
                Material material = configuredMaterial == null
                        ? null : Material.matchMaterial(String.valueOf(configuredMaterial));
                if (material == null || !material.isItem() || material.getMaxStackSize() < 1) continue;
                item = new ItemStack(material);
            }

            int weight = configInt(entry.get("weight"), 1);
            if (weight < 1) {
                continue;
            }
            int stackLimit = item.getMaxStackSize();
            int minAmount = Math.max(1, Math.min(stackLimit, configInt(entry.get("min-amount"), 1)));
            int maxAmount = Math.max(minAmount,
                    Math.min(stackLimit, configInt(entry.get("max-amount"), minAmount)));
            pool.add(new BarrelLoot(item, minAmount, maxAmount), weight);
        }

        BarrelLoot selected = pool.pick(lootRandom);
        if (selected == null) {
            if (plugin.getConfig().getBoolean("barrel-catch.debug", false)) {
                plugin.getLogger().warning("Barrel loot-table has no valid entries; using placeholder-loot.");
            }
            return placeholderLoot();
        }
        return selected.roll(lootRandom);
    }

    private int configInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Use the entry's fallback for malformed config values.
            }
        }
        return fallback;
    }
}
