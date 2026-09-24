package dev.reeldeal.fishing;

import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ports {@code data/reel/loot_table/fishing/treasure.json}. The hook slot
 * (cluster_hook / anchor_hook / golden_hook) is an alternatives -- first
 * matching condition wins, in file order -- and each of its children is
 * weight 2, which carries through to whichever one is chosen.
 */
public final class TreasureLoot {

    private final ItemRegistry items;

    public TreasureLoot(ItemRegistry items) {
        this.items = items;
    }

    public ItemStack resolve(Location catchLocation, Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(enchantedBook(random), 65);
        pool.add(new ItemStack(Material.NAUTILUS_SHELL), 15);
        pool.add(items.create("fiber"), 15);
        pool.add(new ItemStack(Material.HEART_OF_THE_SEA), 3);

        ItemStack hookItem = resolveHookSlot(catchLocation);
        if (hookItem != null) {
            pool.add(hookItem, 2);
        }

        ItemStack result = pool.pick(random);
        return result != null ? result : items.create("fiber");
    }

    /** cluster_hook (y<=48) -> anchor_hook (ocean biome) -> golden_hook (always), first match wins. */
    private ItemStack resolveHookSlot(Location loc) {
        if (loc.getBlockY() <= 48) {
            return items.create("cluster_hook");
        }
        var biome = loc.getBlock().getBiome();
        if (ReelBiomes.JUNK_OCEAN.contains(biome) || ReelBiomes.JUNK_WARM_OCEAN.contains(biome)) {
            return items.create("anchor_hook");
        }
        return items.create("golden_hook");
    }

    /**
     * Approximates vanilla's {@code enchant_with_levels} at level 30 against
     * {@code #minecraft:on_random_loot}: a uniform pick among non-cursed
     * enchantments applicable to books, at a random valid level for that
     * enchantment. This is NOT the real weighted/rarity-based vanilla
     * algorithm (that also considers per-enchantment rarity weights and
     * multi-enchant rolls) -- close enough to preserve "fishing can give
     * you Mending", not a byte-for-byte match of vanilla's odds.
     */
    private ItemStack enchantedBook(Random random) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        List<Enchantment> eligible = new ArrayList<>();
        // Registry iteration rather than the deprecated/removed Enchantment.values().
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
