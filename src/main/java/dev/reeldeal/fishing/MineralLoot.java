package dev.reeldeal.fishing;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

public final class MineralLoot {

    public ItemStack resolve(String lineId, Location catchLocation, Random random) {
        return switch (lineId) {
            case "fiber_line" -> fiberTier(catchLocation, random);
            case "reinforced_line" -> reinforcedTier(random);
            default -> wovenTier(random);
        };
    }

    private ItemStack fiberTier(Location loc, Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COAL), 30);
        pool.add(new ItemStack(Material.RAW_COPPER), 25);
        pool.add(new ItemStack(Material.RAW_IRON), 25);
        pool.add(new ItemStack(Material.RAW_GOLD), 20);
        pool.add(uniformCount(Material.REDSTONE, 4, 5, random), 15, loc.getBlockY() <= 16);
        pool.add(uniformCount(Material.LAPIS_LAZULI, 4, 9, random), 15, loc.getBlockY() <= 30);
        pool.add(new ItemStack(Material.DIAMOND), 5, loc.getBlockY() <= 16);
        pool.add(new ItemStack(Material.EMERALD), 10, ReelBiomes.IS_MOUNTAINS.contains(loc.getBlock().getBiome()));
        ItemStack result = pool.pick(random);
        return result != null ? result : new ItemStack(Material.COAL);
    }

    private ItemStack reinforcedTier(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COAL), 30);
        pool.add(new ItemStack(Material.RAW_COPPER), 25);
        pool.add(new ItemStack(Material.RAW_IRON), 25);
        pool.add(new ItemStack(Material.RAW_GOLD), 20);
        return pool.pick(random);
    }

    private ItemStack wovenTier(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COAL), 65);
        pool.add(new ItemStack(Material.RAW_COPPER), 35);
        return pool.pick(random);
    }

    private ItemStack uniformCount(Material material, int min, int max, Random random) {
        ItemStack stack = new ItemStack(material);
        stack.setAmount(min + random.nextInt(max - min + 1));
        return stack;
    }
}
