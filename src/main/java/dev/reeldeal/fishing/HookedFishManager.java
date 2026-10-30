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
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
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
import dev.reeldeal.quest.QuestManager;

public final class HookedFishManager implements Listener {

    private static final NamespacedKey FISH_KEY = new NamespacedKey("reel", "hooked_fish");
    private static final NamespacedKey MODEL_KEY = new NamespacedKey("reel", "hooked_model");
    private static final NamespacedKey DROP_OWNER_KEY = new NamespacedKey("reel", "hooked_fish_drop_owner");

    private record Hooked(Entity mob, Entity display, BukkitTask despawn, UUID ownerId) {
        void cancelTasks() {
            despawn.cancel();
        }
    }

    private final java.util.Random random = new java.util.Random();
    private final Plugin plugin;
    private final DisplayFollowTicker displayTicker;
    private final CatchRecords catchRecords;
    private final QuestManager quests;
    private FishSize.Settings fishSizeSettings;
    private final List<String> modelKeys = new ArrayList<>();          // longest first
    private final Map<UUID, FishHook> hooks = new HashMap<>();          // player -> active hook
    private final Map<UUID, Location> catchLocations = new HashMap<>(); // last bobber position, captured before retraction
    private final Map<UUID, Hooked> active = new HashMap<>();           // mob uuid -> hooked fish

    public HookedFishManager(Plugin plugin, DisplayFollowTicker displayTicker, CatchRecords catchRecords,
                             QuestManager quests) {
        this.plugin = plugin;
        this.displayTicker = displayTicker;
        this.catchRecords = catchRecords;
        this.quests = quests;
        reload();
    }

    public void reload() {
        fishSizeSettings = FishSize.Settings.from(plugin.getConfig().getConfigurationSection("hooked-fish.size"));
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
    public void spawn(Player player, ItemStack fish, String modelKey, boolean anchorHook, Location spawnLocation) {
        spawn(player, fish, modelKey, anchorHook, spawnLocation, true);
    }

    /** Spawns a representative model without counting it as a real catch. */
    public boolean spawnTest(Player player) {
        if (!enabled()) {
            return false;
        }
        String modelKey = modelKeys.get(0);
        Location spawnLocation = player.getLocation().add(player.getLocation().getDirection().multiply(3)).add(0, 1, 0);
        return spawn(player, new ItemStack(org.bukkit.Material.COD), modelKey, false, spawnLocation, false);
    }

    private boolean spawn(Player player, ItemStack fish, String modelKey, boolean anchorHook,
                          Location spawnLocation, boolean recordCatch) {
        FileConfiguration cfg = plugin.getConfig();
        if (spawnLocation == null) {
            plugin.getLogger().warning("Couldn't spawn hooked fish: no saved bobber location for " + player.getName() + ".");
            return false;
        }
        Location base = spawnLocation.clone();
        World world = base.getWorld();
        if (world == null) {
            return false;
        }

        // Hitbox mob: disable AI/pathfinding but retain gravity, block and entity collisions, and knockback.
        // That keeps it stationary unless it is flung or pushed. Liquids never block movement.
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
            return false;
        }
        mob.setAI(false);
        if (mob instanceof Mob m) {
            m.setAware(false);
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
        FishSize size = FishSize.roll(fishSizeSettings, random, anchorHook);
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
        BukkitTask despawn = Bukkit.getScheduler().runTaskLater(plugin, () -> despawn(id), ticks);
        active.put(id, new Hooked(mob, display, despawn, player.getUniqueId()));
        displayTicker.track(mob, display, yaw, 10, () -> mob.setRemainingAir(mob.getMaximumAir()),
                () -> remove(id));
        if (recordCatch) {
            catchRecords.recordFish(player.getUniqueId(), player.getName(), modelKey,
                    size == null ? 0 : size.lengthCm(), size == null ? -1 : size.tier());
            if (quests != null) quests.recordHookedFish(player, modelKey, size == null ? -1 : size.tier());
        }
        return true;
    }

    private void despawn(UUID id) {
        Hooked hooked = active.get(id);
        if (hooked == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(hooked.ownerId());
        remove(id);
        String message = plugin.getConfig().getString("hooked-fish.despawn-message",
                "Your fish flopped back to the water!");
        if (owner != null && message != null && !message.isBlank()) {
            owner.sendMessage(Component.text(message));
        }
    }

    private void remove(UUID id) {
        Hooked hooked = active.remove(id);
        if (hooked == null) {
            return;
        }
        displayTicker.untrack(id);
        hooked.cancelTasks();
        hooked.mob().remove();
        hooked.display().remove();
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

    public Location catchLocation(Player player) {
        FishHook hook = hooks.get(player.getUniqueId());
        if (hook != null && hook.isValid()) {
            return hook.getLocation();
        }
        Location saved = catchLocations.get(player.getUniqueId());
        return saved == null ? null : saved.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFish(PlayerFishEvent event) {
        PlayerFishEvent.State state = event.getState();
        if (state == PlayerFishEvent.State.FISHING || state == PlayerFishEvent.State.BITE
                || state == PlayerFishEvent.State.CAUGHT_FISH) {
            hooks.put(event.getPlayer().getUniqueId(), event.getHook());
            catchLocations.put(event.getPlayer().getUniqueId(), event.getHook().getLocation().clone());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hooks.remove(event.getPlayer().getUniqueId());
        catchLocations.remove(event.getPlayer().getUniqueId());
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
        if (!hooked.ownerId().equals(player.getUniqueId())) {
            player.sendActionBar(Component.text("This catch belongs to another angler.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
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
        remove(event.getRightClicked().getUniqueId());
        player.getInventory().setItemInMainHand(fish);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Hooked hooked = active.remove(event.getEntity().getUniqueId());
        if (hooked == null) {
            return;
        }
        displayTicker.untrack(event.getEntity().getUniqueId());
        hooked.cancelTasks();
        hooked.display().remove();
        byte[] bytes = event.getEntity().getPersistentDataContainer().get(FISH_KEY, PersistentDataType.BYTE_ARRAY);
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (bytes != null) {
            ItemStack fish = ItemStack.deserializeBytes(bytes);
            // Keep the size on picked-up fish, but make death drops stackable.
            FishSize.clearFrom(fish);
            Item drop = event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), fish);
            drop.setOwner(hooked.ownerId());
            drop.setCanMobPickup(false);
            drop.getPersistentDataContainer().set(DROP_OWNER_KEY, PersistentDataType.STRING,
                    hooked.ownerId().toString());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onOtherPlayerDamage(EntityDamageByEntityEvent event) {
        Hooked hooked = active.get(event.getEntity().getUniqueId());
        if (hooked == null) {
            return;
        }
        Player attacker = responsiblePlayer(event.getDamager());
        if (attacker == null || !hooked.ownerId().equals(attacker.getUniqueId())) {
            event.setCancelled(true);
            if (attacker != null) {
                attacker.sendActionBar(Component.text("This catch belongs to another angler.",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
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

    @EventHandler(ignoreCancelled = true)
    public void onFishDropPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        String owner = event.getItem().getPersistentDataContainer().get(DROP_OWNER_KEY, PersistentDataType.STRING);
        if (owner != null && !owner.equals(player.getUniqueId().toString())) {
            event.setCancelled(true);
        }
    }

    /** Removes every hooked fish (plugin disable). */
    public void clear() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            remove(id);
        }
    }
}
