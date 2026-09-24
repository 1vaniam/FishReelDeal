package dev.reeldeal.fishing;

import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
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
import org.bukkit.util.Vector;

import java.util.Random;

/**
 * Ports {@code data/minecraft/loot_table/gameplay/fishing.json} -- the
 * datapack's replacement of vanilla's entire fishing root table.
 * <p>
 * wooden_hook always gives junk. Otherwise it's a weighted draw between
 * junk / treasure (open water only) / mineral-or-fish, with golden_hook
 * improving the treasure odds and reducing junk odds (the vanilla
 * Luck of the Sea role, just baked into the hook instead), further scaled
 * by an actual Luck of the Sea enchantment via the same
 * {@code weight + quality * luck} formula vanilla uses. cluster_hook at
 * y<=48 gets minerals instead of fish.
 * <p>
 * Runs at {@link EventPriority#HIGH} deliberately: on a server also running
 * PyroFishingPro, that plugin hooks the same vanilla event at LOWEST/NORMAL
 * priority (confirmed against its own public method signatures, not its
 * internal logic), so running higher here guarantees Reel Deal's result
 * wins on Reel Deal rods regardless of plugin load order -- while
 * {@code rods.isConstructed(rod)} below still means we never touch
 * anything caught on a non-Reel-Deal rod in the first place.
 */
public final class FishingLootResolver implements Listener {

    private enum Category { JUNK, TREASURE, MINERAL, FISH }

    private final FishingRodFactory rods;
    private final JunkLoot junkLoot;
    private final MineralLoot mineralLoot;
    private final FishLoot fishLoot;
    private final TreasureLoot treasureLoot;
    private final Random random = new Random();

    public FishingLootResolver(ItemRegistry items, FishingRodFactory rods) {
        this.rods = rods;
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
        if (!rods.isConstructed(rod)) {
            return; // not a reel rod (e.g. a plain vanilla one from creative) -- leave vanilla loot alone
        }
        if (!(event.getCaught() instanceof Item caughtItem)) {
            return;
        }

        String hookId = strip(rods.hookOf(rod));
        String lineId = strip(rods.lineOf(rod));
        FishHook hook = event.getHook();
        Location loc = hook.getLocation();

        Category category = resolveCategory(hookId, hook, rod, loc);

        if (category == Category.FISH) {
            CaughtResult result = fishLoot.resolve(lineId, hookId, loc, random);
            if (result instanceof CaughtResult.AsMob asMob) {
                caughtItem.remove();
                spawnAndFling(asMob.type(), loc, player);
            } else if (result instanceof CaughtResult.AsItem asItem) {
                caughtItem.setItemStack(asItem.stack());
            }
            return;
        }

        ItemStack result = switch (category) {
            case JUNK -> junkLoot.resolve(hookId, loc, random);
            case TREASURE -> treasureLoot.resolve(loc, random);
            case MINERAL -> mineralLoot.resolve(lineId, loc, random);
            case FISH -> throw new IllegalStateException("handled above");
        };

        caughtItem.setItemStack(result);
    }

    /** anchor_hook + fiber_line's squid/dolphin/glow_squid/frog catches: spawn the real mob and toss it at the player. */
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

    /** Vanilla loot-quality formula: effective weight = max(weight + quality * luck, 0). */
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
