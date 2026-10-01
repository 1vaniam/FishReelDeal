package dev.reeldeal.fishing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class HookedFishManager implements Listener {

    private static final NamespacedKey FISH_KEY = new NamespacedKey("reel", "hooked_fish");
    private static final NamespacedKey MODEL_KEY = new NamespacedKey("reel", "hooked_model");

    private record Hooked(Entity mob, Entity display, BukkitTask despawn, BukkitTask air) {
        void cancelTasks() {
            despawn.cancel();
            air.cancel();
        }
    }

    private final java.util.Random random = new java.util.Random();
    private final Plugin plugin;
    private final List<String> modelKeys = new ArrayList<>();          // longest first
    private final Map<UUID, FishHook> hooks = new HashMap<>();          // player -> active hook
    private final Map<UUID, Hooked> active = new HashMap<>();           // mob uuid -> hooked fish

    public HookedFishManager(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        modelKeys.clear();
        File file = new File(plugin.getDataFolder(), "hooked_fish.txt");
        try {
            if (!file.exists()) {
                plugin.getDataFolder().mkdirs();
                try (InputStream in = plugin.getResource("reel/hooked_fish.txt")) {
                    if (in != null) {
                        Files.copy(in, file.toPath());
                    }
                }
            }
            if (file.exists()) {
                for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                    line = line.trim().toLowerCase(Locale.ROOT);
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        modelKeys.add(line);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't read hooked_fish.txt: " + e);
        }
        // Longest first so "unbound_caesarmaw" wins over "caesarmaw", "blue_lobster" over "lobster".
        modelKeys.sort(Comparator.comparingInt(String::length).reversed());
        plugin.getLogger().info("Hooked fish models loaded: " + modelKeys.size());
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("hooked-fish.enabled", true) && !modelKeys.isEmpty();
    }

    /** Model key (e.g. "amberjack") for a Pyro fish item, matched on its name, or null if we have no model. */
    public String modelFor(ItemStack fish) {
        String text = fish.getType().name();
        ItemMeta meta = fish.getItemMeta();
        if (meta != null) {
            Component name = meta.hasDisplayName() ? meta.displayName()
                    : meta.hasItemName() ? meta.itemName() : null;
            if (name != null) {
                text = PlainTextComponentSerializer.plainText().serialize(name);
            }
        }
        String norm = text.toLowerCase(Locale.ROOT)
                .replaceAll("[§&][0-9a-fk-orx]", "")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        String padded = "_" + norm + "_";
        for (String key : modelKeys) {
            if (padded.contains("_" + key + "_")) {
                return key;
            }
        }
        return null;
    }

    /** Spawns the hooked fish for this player's current hook. */
    public void spawn(Player player, ItemStack fish, String modelKey) {
        FileConfiguration cfg = plugin.getConfig();
        Location base = hookLocation(player);
        World world = base.getWorld();
        if (world == null) {
            return;
        }

        // Hitbox mob: invisible and silent. setAware(false) removes its AI but keeps gravity, block and
        // entity collisions and knockback, so it can be flung. Liquids never block movement.
        EntityType type;
        try {
            type = EntityType.valueOf(cfg.getString("hooked-fish.hitbox-mob", "TURTLE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = EntityType.TURTLE;
        }
        Location mobLoc = base.clone().add(0, cfg.getDouble("hooked-fish.hitbox-y-offset", 0.3), 0);
        Entity spawned = world.spawnEntity(mobLoc, type);
        if (!(spawned instanceof LivingEntity mob)) {
            spawned.remove();
            plugin.getLogger().warning("hooked-fish.hitbox-mob must be a living entity type.");
            return;
        }
        if (mob instanceof Mob m) {
            m.setAware(false);
        } else {
            mob.setAI(false);
        }
        mob.setGravity(true);
        mob.setCollidable(true);
        mob.setInvisible(true);
        mob.setSilent(true);
        mob.setPersistent(false);
        var fireRes = Registry.EFFECT.get(NamespacedKey.minecraft("fire_resistance"));
        if (fireRes != null) {
            mob.addPotionEffect(new PotionEffect(fireRes, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
        // Rolled size: scales the hitbox and model, and is stamped on the fish item as its length.
        FishSize size = FishSize.roll(cfg.getConfigurationSection("hooked-fish.size"), random);
        double sizeScale = size != null ? size.scale() : 1.0;
        ItemStack stamped = fish.clone();
        if (size != null) {
            size.stamp(stamped);
        }
        setAttribute(mob, "scale", cfg.getDouble("hooked-fish.hitbox-scale", 0.7) * sizeScale);
        double health = cfg.getDouble("hooked-fish.health", 4.0);
        setAttribute(mob, "max_health", health);
        mob.setHealth(Math.min(health, mob.getAttribute(maxHealth()) != null
                ? mob.getAttribute(maxHealth()).getValue() : health));
        mob.getPersistentDataContainer().set(FISH_KEY, PersistentDataType.BYTE_ARRAY, stamped.serializeAsBytes());
        mob.getPersistentDataContainer().set(MODEL_KEY, PersistentDataType.STRING, modelKey);

        // Model: an ItemDisplay using item_model reel:hooked/<name> (see resourcepack-additions/).
        ItemStack modelStack = new ItemStack(org.bukkit.Material.PAPER);
        ItemMeta meta = modelStack.getItemMeta();
        meta.setItemModel(new NamespacedKey("reel", "hooked/" + modelKey));
        modelStack.setItemMeta(meta);

        float yOff = (float) cfg.getDouble("hooked-fish.display-y-offset", 0.0);
        Location displayLoc = mobLoc.clone();
        displayLoc.setYaw(player.getLocation().getYaw() + (float) cfg.getDouble("hooked-fish.yaw-offset", 0.0));
        displayLoc.setPitch(0f);
        float scale = (float) (cfg.getDouble("hooked-fish.model-scale", 0.8) * sizeScale);
        final float yaw = displayLoc.getYaw();
        // The model is centered on the entity, so lift it by half its scaled height to sit on the mob's feet.
        ItemDisplay display = world.spawn(displayLoc, ItemDisplay.class, d -> {
            d.setItemStack(modelStack);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setTransformationMatrix(new Matrix4f().translate(0f, 0.5f * scale + yOff, 0f).scale(scale));
        });
        // Not mounted on purpose: a mob with a passenger can't be pushed, and rider offsets scale with the mob.

        // Fling toward the player, same as the anchor_hook mobs.
        double speed = cfg.getDouble("hooked-fish.fling-speed", 1.1);
        Vector toward = player.getEyeLocation().toVector().subtract(mobLoc.toVector());
        if (toward.lengthSquared() < 1.0e-4) {
            toward = player.getLocation().getDirection().multiply(-1);
        }
        toward.normalize().multiply(speed);
        toward.setY(0.35);
        mob.setVelocity(toward);

        long ticks = Math.max(1L, cfg.getLong("hooked-fish.despawn-seconds", 60L)) * 20L;
        UUID id = mob.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Hooked h = active.remove(id);
            if (h != null) {
                h.cancelTasks();
                h.mob().remove();
                h.display().remove();
            }
        }, ticks);
        // Every tick: the display follows the mob. Every 10 ticks: refill air so it can't drown (only
        // players should be able to kill it).
        BukkitTask follow = new BukkitRunnable() {
            private int n;

            @Override
            public void run() {
                if (!mob.isValid()) {
                    display.remove();
                    active.remove(id);
                    task.cancel();
                    cancel();
                    return;
                }
                Location l = mob.getLocation();
                l.setYaw(yaw);
                l.setPitch(0f);
                display.teleport(l);
                if (++n % 10 == 0) {
                    mob.setRemainingAir(mob.getMaximumAir());
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
        active.put(id, new Hooked(mob, display, task, follow));
    }

    private static Attribute maxHealth() {
        return Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
    }

    /** Attribute lookup by registry key (ids not verified as enum constants in 26.2). */
    private void setAttribute(LivingEntity mob, String key, double value) {
        Attribute attr = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
        AttributeInstance inst = attr != null ? mob.getAttribute(attr) : null;
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    private Location hookLocation(Player player) {
        FishHook hook = hooks.get(player.getUniqueId());
        if (hook != null && hook.isValid()) {
            return hook.getLocation();
        }
        return player.getEyeLocation().add(player.getLocation().getDirection().multiply(2));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFish(PlayerFishEvent event) {
        PlayerFishEvent.State state = event.getState();
        if (state == PlayerFishEvent.State.FISHING || state == PlayerFishEvent.State.BITE) {
            hooks.put(event.getPlayer().getUniqueId(), event.getHook());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hooks.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Shift + right-click with an empty main hand picks the fish up into the hand. The item gets the same
     * item_model as the hooked display, so it renders as the 3D model in hand, on the ground and in frames.
     */
    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        Hooked hooked = active.get(event.getRightClicked().getUniqueId());
        if (hooked == null) {
            return;
        }
        event.setCancelled(true); // no breeding/feeding/etc. on the hitbox mob
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || !player.isSneaking()
                || !player.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        var pdc = event.getRightClicked().getPersistentDataContainer();
        byte[] bytes = pdc.get(FISH_KEY, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return;
        }
        ItemStack fish = ItemStack.deserializeBytes(bytes);
        String modelKey = pdc.get(MODEL_KEY, PersistentDataType.STRING);
        if (modelKey != null && plugin.getConfig().getBoolean("hooked-fish.pickup-shows-model", true)) {
            ItemMeta meta = fish.getItemMeta();
            if (meta != null) {
                meta.setItemModel(new NamespacedKey("reel", "hooked/" + modelKey));
                // Second custom_model_data float = size step (0-9), so the pack shows a matching-size model.
                Integer length = meta.getPersistentDataContainer().get(FishSize.LENGTH_KEY, PersistentDataType.INTEGER);
                if (length != null) {
                    int tier = FishSize.step(length);
                    try {
                        var cmd = meta.getCustomModelDataComponent();
                        var floats = new ArrayList<>(cmd.getFloats());
                        while (floats.size() < 2) {
                            floats.add(0f);
                        }
                        floats.set(1, (float) tier);
                        cmd.setFloats(floats);
                        meta.setCustomModelDataComponent(cmd);
                    } catch (LinkageError e) {
                        plugin.getLogger().warning("CustomModelDataComponent API mismatch setting the fish size tier.");
                    }
                }
                fish.setItemMeta(meta);
            }
        }
        active.remove(event.getRightClicked().getUniqueId());
        hooked.cancelTasks();
        hooked.mob().remove();
        hooked.display().remove();
        player.getInventory().setItemInMainHand(fish);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Hooked hooked = active.remove(event.getEntity().getUniqueId());
        if (hooked == null) {
            return;
        }
        hooked.cancelTasks();
        hooked.display().remove();
        byte[] bytes = event.getEntity().getPersistentDataContainer().get(FISH_KEY, PersistentDataType.BYTE_ARRAY);
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (bytes != null) {
            event.getDrops().add(ItemStack.deserializeBytes(bytes));
        }
    }

    /** Removes every hooked fish (plugin disable). */
    public void clear() {
        for (Hooked h : new ArrayList<>(active.values())) {
            h.cancelTasks();
            h.mob().remove();
            h.display().remove();
        }
        active.clear();
    }
}
