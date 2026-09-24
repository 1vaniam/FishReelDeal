package dev.reeldeal.fishing;

import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

/**
 * Ports {@code data/reel/loot_table/fishing/fish.json} + its 3
 * {@code fish/<line>.json} tier tables -- with one deliberate departure
 * from the source datapack: anchor_hook + fiber_line's squid/glow_squid/
 * frog catches no longer ride along as tagged spawn-egg items waiting for
 * a drop-triggered substitute mechanic. They spawn the real mob directly
 * at catch time and fling it at the player, which is simpler, doesn't
 * depend on the (unported) substitute mechanic, and closes the spawn-egg
 * guard gap for these catches entirely -- there's no longer an item to
 * guard against. Conditions were also redefined at the same time:
 * squid -> river only, dolphin -> any ocean (new), glow_squid -> y<=32
 * (unchanged), frog -> swamp (unchanged). This only applies at the
 * fiber_line tier; anchor_hook has no effect at the other two tiers,
 * same as in the source datapack.
 */
public final class FishLoot {

    private final ItemRegistry items;

    public FishLoot(ItemRegistry items) {
        this.items = items;
    }

    public CaughtResult resolve(String lineId, String hookId, Location catchLocation, Random random) {
        boolean anchor = "anchor_hook".equals(hookId);
        return switch (lineId) {
            case "fiber_line" -> anchor ? anchorFiber(catchLocation, random) : defaultFiber(catchLocation, random);
            case "reinforced_line" -> defaultCommon(random);
            default -> defaultWoven(random);
        };
    }

    // --- fiber_line tier (richest pool) ---

    private CaughtResult anchorFiber(Location loc, Random random) {
        Biome biome = loc.getBlock().getBiome();
        boolean anyOcean = ReelBiomes.JUNK_OCEAN.contains(biome) || ReelBiomes.JUNK_WARM_OCEAN.contains(biome);

        WeightedPool<CaughtResult> pool = new WeightedPool<>();
        pool.add(CaughtResult.item(new ItemStack(Material.COD)), 60);
        pool.add(CaughtResult.item(new ItemStack(Material.SALMON)), 25);
        pool.add(CaughtResult.item(new ItemStack(Material.PUFFERFISH)), 13);
        pool.add(CaughtResult.item(new ItemStack(Material.TROPICAL_FISH)), 2);
        pool.add(CaughtResult.mob(EntityType.SQUID), 20, ReelBiomes.JUNK_RIVER.contains(biome));
        pool.add(CaughtResult.mob(EntityType.DOLPHIN), 20, anyOcean);
        pool.add(CaughtResult.mob(EntityType.GLOW_SQUID), 20, loc.getBlockY() <= 32);
        pool.add(CaughtResult.mob(EntityType.FROG), 20, ReelBiomes.PERMITS_FROGS.contains(biome));

        CaughtResult result = pool.pick(random);
        return result != null ? result : CaughtResult.item(new ItemStack(Material.COD));
    }

    private CaughtResult defaultFiber(Location loc, Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COD), 60);
        pool.add(new ItemStack(Material.SALMON), 25);
        pool.add(new ItemStack(Material.PUFFERFISH), 13);
        pool.add(new ItemStack(Material.TROPICAL_FISH), 2);

        ItemStack special = resolveSpecialFish(loc);
        if (special != null) {
            pool.add(special, 1);
        }
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }

    /** deepfish (y<=0) checked ahead of cobblefish (y<=48) since it's the tighter/rarer bound, then biome fish in file order. */
    private ItemStack resolveSpecialFish(Location loc) {
        if (loc.getBlockY() <= 0) {
            return items.create("deepfish");
        }
        if (loc.getBlockY() <= 48) {
            return items.create("cobblefish");
        }
        Biome biome = loc.getBlock().getBiome();
        if (ReelBiomes.HAS_BULLFISH.contains(biome)) {
            return items.create("bullfish");
        }
        if (ReelBiomes.HAS_FISHROOM.contains(biome)) {
            return items.create("fishroom");
        }
        if (ReelBiomes.HAS_ICEHORN.contains(biome)) {
            return items.create("icehorn");
        }
        return null;
    }

    // --- reinforced_line tier ---

    private CaughtResult defaultCommon(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COD), 60);
        pool.add(new ItemStack(Material.SALMON), 25);
        pool.add(new ItemStack(Material.PUFFERFISH), 13);
        pool.add(new ItemStack(Material.TROPICAL_FISH), 2);
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }

    // --- woven_line tier (weakest pool) ---

    private CaughtResult defaultWoven(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COD), 60);
        pool.add(new ItemStack(Material.SALMON), 40);
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }
}
