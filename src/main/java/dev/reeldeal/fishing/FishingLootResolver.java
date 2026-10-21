package dev.reeldeal.fishing;

import dev.reeldeal.integration.PyroFishingIntegration;
import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Random;

public final class FishingLootResolver implements Listener {

    private static final String DEFAULT_BARREL_MESSAGE =
            "<gold><bold>BARREL!</bold></gold> <yellow>You fished up a barrel! Break it open — it might contain some treasure.</yellow>";
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private enum Category { JUNK, TREASURE, MINERAL, FISH }

    private final Plugin plugin;
    private final FishingRodFactory rods;
    private final JunkLoot junkLoot;
    private final MineralLoot mineralLoot;
    private final FishLoot fishLoot;
    private final TreasureLoot treasureLoot;
    private final BarrelCatchManager barrels;
    private final Random random = new Random();

    public FishingLootResolver(Plugin plugin, ItemRegistry items, FishingRodFactory rods, BarrelCatchManager barrels) {
        this.plugin = plugin;
        this.rods = rods;
        this.barrels = barrels;
        this.junkLoot = new JunkLoot(items);
        this.mineralLoot = new MineralLoot();
        this.fishLoot = new FishLoot(items);
        this.treasureLoot = new TreasureLoot(items);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack rod = player.getInventory().getItemInMainHand();
        boolean reelRod = rods.isConstructed(rod);
        if (plugin.getConfig().getBoolean("barrel-catch.debug", false)) {
            plugin.getLogger().info("Fishing catch event: cancelled=" + event.isCancelled()
                    + ", reelRod=" + reelRod + ", caught="
                    + (event.getCaught() == null ? "null" : event.getCaught().getType()) + ".");
        }
        String hookId = reelRod ? strip(rods.hookOf(rod)) : null;
        FishHook hook = event.getHook();
        Location loc = hook.getLocation();
        if (barrels.shouldCatch(hookId, random) && barrels.spawn(player, loc)) {
            if (event.getCaught() instanceof Item caughtItem) {
                caughtItem.remove();
            }
            String message = plugin.getConfig().getString("barrel-catch.catch-message", DEFAULT_BARREL_MESSAGE);
            if (message != null && !message.isBlank()) {
                player.sendMessage(MINI_MESSAGE.deserialize(message));
            }
            PyroFishingIntegration.markBarrelCatch(player.getUniqueId(), System.currentTimeMillis());
            return;
        }
        if (!(event.getCaught() instanceof Item caughtItem)) {
            return;
        }
        if (!reelRod) {
            return;
        }

        String lineId = strip(rods.lineOf(rod));

        CaughtResult outcome = resolveOutcome(hookId, lineId, hook, rod, loc);
        if (plugin.getConfig().getBoolean("fishing.debug", false)) {
            plugin.getLogger().info("Fishing result: player=" + player.getName() + ", hook=" + hookId
                    + ", line=" + lineId + ", resolved=" + outcome.getClass().getSimpleName() + ", pyroReplacement="
                    + PyroFishingIntegration.replacingPyroCatches() + ".");
        }

        if (PyroFishingIntegration.replacingPyroCatches()) {
            caughtItem.remove();
            long startedNanos = System.nanoTime();
            Location at = loc.clone();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!PyroFishingIntegration.consumeCatchMarker(player.getUniqueId(), startedNanos)) {
                    deliver(outcome, at, player);
                }
            }, 3L);
            return;
        }

        if (outcome instanceof CaughtResult.AsMob asMob) {
            caughtItem.remove();
            spawnAndFling(asMob.type(), loc, player);
        } else if (outcome instanceof CaughtResult.AsItem asItem) {
            caughtItem.setItemStack(asItem.stack());
        }
    }

    private CaughtResult resolveOutcome(String hookId, String lineId, FishHook hook, ItemStack rod, Location loc) {
        Category category = resolveCategory(hookId, hook, rod, loc);
        if (plugin.getConfig().getBoolean("fishing.debug", false)) {
            plugin.getLogger().info("Fishing category decision: hook=" + hookId + ", line=" + lineId
                    + ", openWater=" + hook.isInOpenWater() + ", category=" + category + ".");
        }
        return switch (category) {
            case FISH -> fishLoot.resolve(lineId, hookId, loc, random);
            case JUNK -> CaughtResult.item(junkLoot.resolve(hookId, loc, random));
            case TREASURE -> CaughtResult.item(treasureLoot.resolve(loc, random));
            case MINERAL -> CaughtResult.item(mineralLoot.resolve(lineId, loc, random));
        };
    }

    private void deliver(CaughtResult result, Location loc, Player player) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        if (result instanceof CaughtResult.AsMob asMob) {
            spawnAndFling(asMob.type(), loc, player);
        } else if (result instanceof CaughtResult.AsItem asItem) {
            Item drop = world.dropItem(loc, asItem.stack());
            Vector d = player.getLocation().toVector().subtract(loc.toVector());
            drop.setVelocity(new Vector(d.getX() * 0.1, d.getY() * 0.1 + Math.sqrt(d.length()) * 0.08, d.getZ() * 0.1));
        }
    }

    private void spawnAndFling(EntityType type, Location loc, Player player) {
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        Entity mob = world.spawnEntity(loc, type);
        Vector toward = player.getEyeLocation().toVector().subtract(loc.toVector());
        if (toward.lengthSquared() < 1.0e-4) {
            toward = player.getLocation().getDirection().multiply(-1);
        }
        toward.normalize().multiply(1.1);
        toward.setY(0.35);
        mob.setVelocity(toward);
    }

    private Category resolveCategory(String hookId, FishHook hook, ItemStack rod, Location loc) {
        int luck = luckOfTheSeaLevel(rod);

        boolean openWater = hook.isInOpenWater();
        String hookPath = "fishing.category-weights.hooks." + (hookId == null ? "normal" : hookId) + ".";
        int junkWeight = configuredCategoryWeight(hookPath + "junk", "default.junk", 10);
        int treasureWeight = configuredCategoryWeight(hookPath + "treasure", "default.treasure", 5);
        int fishWeight = configuredCategoryWeight(hookPath + "fish", "default.fish", 85);
        boolean mineralEligible = "cluster_hook".equals(hookId)
                && loc.getBlockY() <= plugin.getConfig().getInt(hookPath + "underground-max-y", 48);

        WeightedPool<Category> pool = new WeightedPool<>();
        pool.add(Category.JUNK, scale(junkWeight, -2, luck));
        pool.add(Category.TREASURE, scale(treasureWeight, 2, luck), openWater);
        if (mineralEligible) {
            int mineralWeight = plugin.getConfig().getInt(hookPath + "underground-mineral-weight", fishWeight);
            pool.add(Category.MINERAL, scale(mineralWeight, -1, luck));
        } else {
            pool.add(Category.FISH, scale(fishWeight, -1, luck));
        }

        Category result = pool.pick(random);
        return result != null ? result : Category.JUNK;
    }

    private int configuredCategoryWeight(String hookPath, String defaultKey, int fallback) {
        int defaultWeight = plugin.getConfig().getInt("fishing.category-weights." + defaultKey, fallback);
        return Math.max(0, plugin.getConfig().getInt(hookPath, defaultWeight));
    }

    private int scale(int weight, int quality, int luck) {
        return Math.max(0, weight + quality * luck);
    }

    private int luckOfTheSeaLevel(ItemStack rod) {
        Enchantment luckOfTheSea = Registry.ENCHANTMENT.get(NamespacedKey.minecraft("luck_of_the_sea"));
        return luckOfTheSea != null ? rod.getEnchantmentLevel(luckOfTheSea) : 0;
    }

    private String strip(String reelId) {
        return reelId != null && reelId.startsWith("reel:") ? reelId.substring(5) : reelId;
    }
}
