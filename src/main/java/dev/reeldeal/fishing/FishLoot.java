package dev.reeldeal.fishing;

import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Random;

public final class FishLoot {

    private final ItemRegistry items;
    private final Plugin plugin;

    public FishLoot(Plugin plugin, ItemRegistry items) {
        this.plugin = plugin;
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
            case null -> defaultWoven(random);
            case "fiber_line" -> defaultFiber(catchLocation, random);
            case "reinforced_line" -> defaultCommon(random);
            default -> defaultWoven(random);
        };
    }

    private CaughtResult anchorCatchTable(String lineId, Location loc, Random random) {
        Biome biome = loc.getBlock().getBiome();
        boolean anyOcean = biomeMatches("fishing.anchor-hook.eligibility.dolphin-biomes", biome,
                List.of("ocean", "cold_ocean", "frozen_ocean", "deep_ocean", "deep_cold_ocean",
                        "deep_frozen_ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean"));

        WeightedPool<CaughtResult> pool = new WeightedPool<>();
        addCommonFishResults(pool, "fishing.anchor-hook.fish-weights.");
        int squidWeight = Math.max(0, plugin.getConfig().getInt("fishing.anchor-hook.mob-weights.squid", 5));
        int dolphinWeight = Math.max(0, plugin.getConfig().getInt("fishing.anchor-hook.mob-weights.dolphin", 5));
        boolean squidEligible = biomeMatches("fishing.anchor-hook.eligibility.squid-biomes", biome,
                List.of("river", "frozen_river"));
        pool.add(CaughtResult.mob(EntityType.SQUID), squidWeight, squidEligible);
        pool.add(CaughtResult.mob(EntityType.DOLPHIN), dolphinWeight, anyOcean);
        int glowSquidMaxY = plugin.getConfig().getInt("fishing.anchor-hook.eligibility.glow-squid-max-y", 32);
        int glowSquidWeight = Math.max(0, plugin.getConfig().getInt("fishing.anchor-hook.mob-weights.glow-squid", 20));
        pool.add(CaughtResult.mob(EntityType.GLOW_SQUID), glowSquidWeight, loc.getBlockY() <= glowSquidMaxY);
        int frogWeight = Math.max(0, plugin.getConfig().getInt("fishing.anchor-hook.mob-weights.frog", 20));
        boolean frogEligible = biomeMatches("fishing.anchor-hook.eligibility.frog-biomes", biome,
                List.of("swamp", "mangrove_swamp"));
        pool.add(CaughtResult.mob(EntityType.FROG), frogWeight, frogEligible);
        if ("fiber_line".equals(lineId)) {
            ItemStack special = resolveSpecialFish(loc);
            if (special != null) {
                pool.add(CaughtResult.item(special), Math.max(0,
                        plugin.getConfig().getInt("fishing.anchor-hook.special-fish-weight", 1)));
            }
        }

        CaughtResult result = pool.pick(random);
        return result != null ? result : CaughtResult.item(new ItemStack(Material.COD));
    }

    private CaughtResult defaultFiber(Location loc, Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        addCommonFishItems(pool, "fishing.fish-tables.common.");

        ItemStack special = resolveSpecialFish(loc);
        if (special != null) {
            pool.add(special, Math.max(0, plugin.getConfig().getInt("fishing.special-fish.weight", 1)));
        }
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }

    private ItemStack resolveSpecialFish(Location loc) {
        String deepFish = plugin.getConfig().getString("fishing.special-fish.deep-item", "deepfish");
        String lowDepthFish = plugin.getConfig().getString("fishing.special-fish.low-depth-item", "cobblefish");
        if (loc.getBlockY() <= plugin.getConfig().getInt("fishing.special-fish.deep-max-y", 0)) {
            return createSpecialItem(deepFish, "deepfish");
        }
        if (loc.getBlockY() <= plugin.getConfig().getInt("fishing.special-fish.low-depth-max-y", 48)) {
            return createSpecialItem(lowDepthFish, "cobblefish");
        }
        Biome biome = loc.getBlock().getBiome();
        for (String key : List.of("bullfish", "fishroom", "icehorn")) {
            if (plugin.getConfig().getBoolean("fishing.special-fish.enabled." + key, true)
                    && biomeMatches("fishing.special-fish.biomes." + key, biome, defaultSpecialBiomes(key))) {
                String itemId = plugin.getConfig().getString("fishing.special-fish.items." + key, key);
                return createSpecialItem(itemId, key);
            }
        }
        return null;
    }

    private List<String> defaultSpecialBiomes(String fish) {
        return switch (fish) {
            case "bullfish" -> List.of("swamp", "mangrove_swamp");
            case "fishroom" -> List.of("dark_forest", "pale_garden", "mushroom_fields");
            case "icehorn" -> List.of("snowy_beach", "snowy_plains", "snowy_slopes", "snowy_taiga", "ice_spikes",
                    "frozen_peaks", "frozen_river", "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
                    "grove", "meadow", "stony_peaks", "windswept_hills", "windswept_forest",
                    "windswept_gravelly_hills");
            default -> List.of();
        };
    }

    private boolean biomeMatches(String path, Biome biome, List<String> defaults) {
        List<String> configured = plugin.getConfig().getStringList(path);
        List<String> names = plugin.getConfig().isSet(path) ? configured : defaults;
        String biomeName = biome.getKey().getKey();
        for (String name : names) {
            String normalized = name.toLowerCase(java.util.Locale.ROOT);
            if (normalized.startsWith("minecraft:")) normalized = normalized.substring("minecraft:".length());
            if (normalized.equals(biomeName)) return true;
        }
        return false;
    }

    private ItemStack createSpecialItem(String configuredId, String fallbackId) {
        try {
            return items.create(configuredId);
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown special-fish item ID '" + configuredId
                    + "'; falling back to '" + fallbackId + "'.");
            return items.create(fallbackId);
        }
    }

    private void addCommonFishResults(WeightedPool<CaughtResult> pool, String path) {
        pool.add(CaughtResult.item(new ItemStack(Material.COD)), Math.max(0, plugin.getConfig().getInt(path + "cod", 60)));
        pool.add(CaughtResult.item(new ItemStack(Material.SALMON)), Math.max(0, plugin.getConfig().getInt(path + "salmon", 25)));
        pool.add(CaughtResult.item(new ItemStack(Material.PUFFERFISH)), Math.max(0, plugin.getConfig().getInt(path + "pufferfish", 13)));
        pool.add(CaughtResult.item(new ItemStack(Material.TROPICAL_FISH)), Math.max(0, plugin.getConfig().getInt(path + "tropical-fish", 2)));
    }

    private void addCommonFishItems(WeightedPool<ItemStack> pool, String path) {
        pool.add(new ItemStack(Material.COD), Math.max(0, plugin.getConfig().getInt(path + "cod", 60)));
        pool.add(new ItemStack(Material.SALMON), Math.max(0, plugin.getConfig().getInt(path + "salmon", 25)));
        pool.add(new ItemStack(Material.PUFFERFISH), Math.max(0, plugin.getConfig().getInt(path + "pufferfish", 13)));
        pool.add(new ItemStack(Material.TROPICAL_FISH), Math.max(0, plugin.getConfig().getInt(path + "tropical-fish", 2)));
    }


    private CaughtResult defaultCommon(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        addCommonFishItems(pool, "fishing.fish-tables.common.");
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }


    private CaughtResult defaultWoven(Random random) {
        WeightedPool<ItemStack> pool = new WeightedPool<>();
        pool.add(new ItemStack(Material.COD), Math.max(0,
                plugin.getConfig().getInt("fishing.fish-tables.woven.cod", 60)));
        pool.add(new ItemStack(Material.SALMON), Math.max(0,
                plugin.getConfig().getInt("fishing.fish-tables.woven.salmon", 40)));
        ItemStack result = pool.pick(random);
        return CaughtResult.item(result != null ? result : new ItemStack(Material.COD));
    }
}
