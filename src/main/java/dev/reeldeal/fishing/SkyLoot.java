package dev.reeldeal.fishing;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Random;

/** Configurable sky-themed catch table for Skyhook rods. */
public final class SkyLoot {

    private final Plugin plugin;

    public SkyLoot(Plugin plugin) {
        this.plugin = plugin;
    }

    public ItemStack resolve(Random random) {
        WeightedPool<SkyDrop> pool = new WeightedPool<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList("fishing.skyhook.loot-table")) {
            Object configuredMaterial = entry.get("material");
            Material material = configuredMaterial == null
                    ? null : Material.matchMaterial(String.valueOf(configuredMaterial));
            if (material == null || !material.isItem()) continue;

            int weight = Math.max(0, configInt(entry.get("weight"), 1));
            if (weight == 0) continue;
            int maxStack = material.getMaxStackSize();
            int min = Math.max(1, Math.min(maxStack, configInt(entry.get("min-amount"), 1)));
            int max = Math.max(min, Math.min(maxStack, configInt(entry.get("max-amount"), min)));
            pool.add(new SkyDrop(material, min, max), weight);
        }

        SkyDrop selected = pool.pick(random);
        if (selected == null) {
            plugin.getLogger().warning("Skyhook loot-table has no valid entries; using FEATHER.");
            return new ItemStack(Material.FEATHER);
        }
        return selected.roll(random);
    }

    private int configInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // Keep the configured entry's fallback value.
            }
        }
        return fallback;
    }

    private record SkyDrop(Material material, int min, int max) {
        private ItemStack roll(Random random) {
            ItemStack stack = new ItemStack(material);
            stack.setAmount(min + random.nextInt(max - min + 1));
            return stack;
        }
    }
}
