package dev.reeldeal.fishing;

import dev.reeldeal.integration.PyroFishingIntegration;
import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
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

    private enum Category { JUNK, TREASURE, MINERAL, FISH }

    private final Plugin plugin;
    private final FishingRodFactory rods;
    private final JunkLoot junkLoot;
    private final MineralLoot mineralLoot;
    private final FishLoot fishLoot;
    private final TreasureLoot treasureLoot;
    private final Random random = new Random();

    public FishingLootResolver(Plugin plugin, ItemRegistry items, FishingRodFactory rods) {
        this.plugin = plugin;
        this.rods = rods;
        this.junkLoot = new JunkLoot(items);
        this.mineralLoot = new MineralLoot();
        this.fishLoot = new FishLoot(items);
        this.treasureLoot = new TreasureLoot(items);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack rod = player.getInventory().getItemInMainHand();
        if (!rods.isConstructed(rod)) {
            return;
        }
        if (event.isCancelled()) {
            event.setCancelled(false);
        }
        if (!(event.getCaught() instanceof Item caughtItem)) {
            return;
        }

        String hookId = strip(rods.hookOf(rod));
        String lineId = strip(rods.lineOf(rod));
        FishHook hook = event.getHook();
        Location loc = hook.getLocation();

        CaughtResult outcome = resolveOutcome(hookId, lineId, hook, rod, loc);

        if ("anchor_hook".equals(hookId) && PyroFishingIntegration.replacingAnchorCatches()) {
            caughtItem.remove();
            long started = System.currentTimeMillis();
            Location at = loc.clone();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!PyroFishingIntegration.caughtSince(player.getUniqueId(), started - 250)) {
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
        if ("wooden_hook".equals(hookId)) {
            return Category.JUNK;
        }
        boolean lucky = "golden_hook".equals(hookId);
        int luck = luckOfTheSeaLevel(rod);

        boolean openWater = hook.isInOpenWater();
        boolean mineralEligible = "cluster_hook".equals(hookId) && loc.getBlockY() <= 48;

        WeightedPool<Category> pool = new WeightedPool<>();
        pool.add(Category.JUNK, scale(lucky ? 5 : 10, -2, luck));
        pool.add(Category.TREASURE, scale(lucky ? 10 : 5, 2, luck), openWater);
        pool.add(mineralEligible ? Category.MINERAL : Category.FISH, scale(85, -1, luck));

        Category result = pool.pick(random);
        return result != null ? result : Category.JUNK;
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
