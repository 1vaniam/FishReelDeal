package dev.reeldeal.fishing;

import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

public final class FishLoot {

    private final ItemRegistry items;

    public FishLoot(ItemRegistry items) {
        this.items = items;
    }

    public CaughtResult resolve(String lineId, String hookId, Location catchLocation, Random random) {
        boolean anchor = "anchor_hook".equals(hookId);
        // Special hooks are usable starting with woven line. Better lines keep their own
        // benefits, while the anchor's live-catch effect is independent of the line tier.
        if (anchor) {
            return anchorCatchTable(lineId, catchLocation, random);
        }
        return switch (lineId) {
            case "fiber_line" -> defaultFiber(catchLocation, random);
            case "reinforced_line" -> defaultCommon(random);
            default -> defaultWoven(random);
        };
    }

    private CaughtResult anchorCatchTable(String lineId, Location loc, Random random) {
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
        if ("fiber_line".equals(lineId)) {
            ItemStack special = resolveSpecialFish(loc);
            if (special != null) {
                pool.add(CaughtResult.item(special), 1);
            }
        }

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


    private CaughtResult defaultCommon(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COD), 60);
        pool.add(new ItemStack(Material.SALMON), 25);
        pool.add(new ItemStack(Material.PUFFERFISH), 13);
        pool.add(new ItemStack(Material.TROPICAL_FISH), 2);
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }


    private CaughtResult defaultWoven(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COD), 60);
        pool.add(new ItemStack(Material.SALMON), 40);
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }
}
