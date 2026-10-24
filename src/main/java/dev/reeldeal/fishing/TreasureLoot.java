package dev.reeldeal.fishing;

import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class TreasureLoot {

    private final Plugin plugin;
    private final ItemRegistry items;

    public TreasureLoot(Plugin plugin, ItemRegistry items) {
        this.plugin = plugin;
        this.items = items;
    }

    public ItemStack resolve(Location catchLocation, Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(enchantedBook(random), 65);
        pool.add(new ItemStack(Material.NAUTILUS_SHELL), 15);
        pool.add(items.create("fiber"), 15);

        ItemStack hookItem = resolveHookSlot(catchLocation);
        if (hookItem != null) {
            pool.add(hookItem, 2);
        }

        ItemStack result = pool.pick(random);
        return result != null ? result : items.create("fiber");
    }

    private ItemStack resolveHookSlot(Location loc) {
        if (loc.getBlockY() <= 48) {
            return items.create("cluster_hook");
        }
        int skyhookMinY = Math.max(0, plugin.getConfig().getInt("fishing.skyhook.minimum-y", 128));
        boolean requireSky = plugin.getConfig().getBoolean("fishing.skyhook.require-open-sky", true);
        boolean exposed = loc.getBlockY() >= loc.getWorld().getHighestBlockYAt(loc.getBlockX(), loc.getBlockZ());
        if (loc.getBlockY() >= skyhookMinY && (!requireSky || exposed)) {
            return items.create("skyhook");
        }
        var biome = loc.getBlock().getBiome();
        if (ReelBiomes.JUNK_OCEAN.contains(biome) || ReelBiomes.JUNK_WARM_OCEAN.contains(biome)) {
            return items.create("anchor_hook");
        }
        return items.create("golden_hook");
    }

    private ItemStack enchantedBook(Random random) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        List<Enchantment> eligible = new ArrayList<>();
        for (Enchantment enchantment : org.bukkit.Registry.ENCHANTMENT) {
            if (!enchantment.isCursed()) {
                eligible.add(enchantment);
            }
        }
        Enchantment chosen = eligible.get(random.nextInt(eligible.size()));
        int level = 1 + random.nextInt(Math.max(1, chosen.getMaxLevel()));

        var meta = (org.bukkit.inventory.meta.EnchantmentStorageMeta) book.getItemMeta();
        meta.addStoredEnchant(chosen, level, true);
        book.setItemMeta((ItemMeta) meta);
        return book;
    }
}
