package dev.reeldeal.fishing;

import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

public final class JunkLoot {

    private final ItemRegistry items;

    public JunkLoot(ItemRegistry items) {
        this.items = items;
    }

    public ItemStack resolve(String currentHookId, Location catchLocation, Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.LEATHER), 10);
        pool.add(new ItemStack(Material.BONE), 10);
        pool.add(new ItemStack(Material.STICK), 10);
        pool.add(uniformCount(Material.CLAY_BALL, 1, 2, random), 10);

        pool.add(items.create("wooden_hook"), "wooden_hook".equals(currentHookId) ? 1 : 5);

        ItemStack biomeItem = resolveBiomeTable(catchLocation, random);
        if (biomeItem != null) {
            pool.add(biomeItem, 55);
        }

        ItemStack result = pool.pick(random);
        return result != null ? result : new ItemStack(Material.STICK);
    }

    private ItemStack resolveBiomeTable(Location loc, Random random) {
        if (loc.getBlockY() <= 48) {
            return pick(random, weighted(Material.FLINT, 40), weighted(Material.STRING, 20),
                    weighted(Material.GUNPOWDER, 15), weighted(Material.POINTED_DRIPSTONE, 15),
                    weighted(weightedDamaged(Material.IRON_PICKAXE, random), 5),
                    weighted(weightedDamaged(Material.GLOW_INK_SAC, random), 5));
        }
        Biome biome = loc.getBlock().getBiome();
        if (ReelBiomes.JUNK_TAIGA.contains(biome)) {
            return pick(random, weighted(Material.SWEET_BERRIES, 50), weighted(Material.FERN, 40), weighted(Material.LARGE_FERN, 10));
        }
        if (ReelBiomes.JUNK_COLD.contains(biome)) {
            return pick(random, weighted(Material.RABBIT_HIDE, 40), weighted(Material.RABBIT_FOOT, 30),
                    weighted(uniformCount(Material.SNOWBALL, 1, 2, random), 10), weighted(Material.ICE, 30));
        }
        if (biome == Biome.FLOWER_FOREST) {
            Material[] flowers = {Material.DANDELION, Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
                    Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP,
                    Material.PINK_TULIP, Material.OXEYE_DAISY, Material.CORNFLOWER, Material.LILY_OF_THE_VALLEY};
            return new ItemStack(flowers[random.nextInt(flowers.length)]);
        }
        if (ReelBiomes.JUNK_BIRCH_FOREST.contains(biome)) {
            return new ItemStack(Material.HONEYCOMB);
        }
        if (ReelBiomes.JUNK_SWAMP.contains(biome)) {
            return pick(random, weighted(Material.LILY_PAD, 40), weighted(uniformCount(Material.REDSTONE, 1, 2, random), 25),
                    weighted(Material.SUGAR, 25), weighted(Material.SLIME_BALL, 10));
        }
        if (biome == Biome.DARK_FOREST) {
            return pick(random, weighted(Material.RED_MUSHROOM, 45), weighted(Material.BROWN_MUSHROOM, 45), weighted(Material.EMERALD, 10));
        }
        if (biome == Biome.PALE_GARDEN) {
            return new ItemStack(Material.RESIN_CLUMP);
        }
        if (ReelBiomes.JUNK_JUNGLE.contains(biome)) {
            return pick(random, weighted(Material.BAMBOO, 50), weighted(Material.COCOA_BEANS, 25), weighted(Material.MELON_SLICE, 25));
        }
        if (ReelBiomes.JUNK_OCEAN.contains(biome)) {
            return pick(random, weighted(Material.KELP, 60), weighted(Material.INK_SAC, 40));
        }
        if (ReelBiomes.JUNK_WARM_OCEAN.contains(biome)) {
            return pick(random, weighted(Material.SEA_PICKLE, 50), weighted(Material.TUBE_CORAL, 10),
                    weighted(Material.BRAIN_CORAL, 10), weighted(Material.BUBBLE_CORAL, 10),
                    weighted(Material.FIRE_CORAL, 10), weighted(Material.HORN_CORAL, 10));
        }
        if (ReelBiomes.JUNK_SAVANNA.contains(biome)) {
            return pick(random, weighted(Material.FLINT, 60), weighted(Material.TALL_GRASS, 30), weighted(Material.ARMADILLO_SCUTE, 10));
        }
        if (ReelBiomes.JUNK_RIVER.contains(biome)) {
            return pick(random, weighted(Material.BONE_MEAL, 50), weighted(Material.SUGAR_CANE, 30),
                    weighted(uniformCount(Material.INK_SAC, 1, 2, random), 20));
        }
        if (ReelBiomes.JUNK_BADLANDS.contains(biome)) {
            return pick(random, weighted(Material.GOLD_NUGGET, 80), weighted(Material.FLINT, 20));
        }
        if (biome == Biome.DESERT) {
            return pick(random, weighted(Material.DEAD_BUSH, 40), weighted(Material.RABBIT_HIDE, 25),
                    weighted(Material.SHORT_DRY_GRASS, 25), weighted(Material.TALL_DRY_GRASS, 10));
        }
        return null;
    }

    private record Weighted(ItemStack stack, int weight) {
    }

    private Weighted weighted(Material material, int weight) {
        return new Weighted(new ItemStack(material), weight);
    }

    private Weighted weighted(ItemStack stack, int weight) {
        return new Weighted(stack, weight);
    }

    private ItemStack pick(Random random, Weighted... options) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        for (Weighted w : options) {
            pool.add(w.stack(), w.weight());
        }
        return pool.pick(random);
    }

    private ItemStack uniformCount(Material material, int min, int max, Random random) {
        ItemStack stack = new ItemStack(material);
        stack.setAmount(min + random.nextInt(max - min + 1));
        return stack;
    }

    private ItemStack weightedDamaged(Material material, Random random) {
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
            float fraction = 0.2f + random.nextFloat() * 0.6f; // 0.2..0.8 of max durability, per source
            int maxDurability = material.getMaxDurability();
            damageable.setDamage((int) (fraction * maxDurability));
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
