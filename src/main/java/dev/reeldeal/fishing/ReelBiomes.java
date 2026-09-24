package dev.reeldeal.fishing;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

import java.util.HashSet;
import java.util.Set;

/**
 * Ports every {@code data/reel/tags/worldgen/biome/**} tag as a plain
 * {@code Set<Biome>} -- these datapack tags only ever gate loot table
 * conditions, so there's no need to keep them as real registry tags on
 * the plugin side.
 * <p>
 * {@code Biome} stopped being an enum as of 1.21.3 (it's a registry-backed
 * interface now, so custom/datapack biomes can exist) -- instances come
 * from {@link Registry#BIOME} by key instead of enum constants.
 */
public final class ReelBiomes {

    public static final Set<Biome> JUNK_BADLANDS = biomes("badlands", "eroded_badlands", "wooded_badlands");

    public static final Set<Biome> JUNK_BIRCH_FOREST = biomes("birch_forest", "old_growth_birch_forest");

    public static final Set<Biome> JUNK_COLD = biomes(
            "snowy_beach", "snowy_plains", "snowy_slopes", "ice_spikes",
            "frozen_peaks", "frozen_river", "grove", "meadow",
            "stony_peaks", "windswept_hills", "windswept_forest", "windswept_gravelly_hills");

    public static final Set<Biome> JUNK_JUNGLE = biomes("jungle", "bamboo_jungle", "sparse_jungle");

    public static final Set<Biome> JUNK_OCEAN = biomes(
            "ocean", "cold_ocean", "frozen_ocean", "deep_ocean", "deep_cold_ocean", "deep_frozen_ocean");

    public static final Set<Biome> JUNK_WARM_OCEAN = biomes("warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean");

    public static final Set<Biome> JUNK_RIVER = biomes("river", "frozen_river");

    public static final Set<Biome> JUNK_SAVANNA = biomes("savanna", "savanna_plateau", "windswept_savanna");

    public static final Set<Biome> JUNK_SWAMP = biomes("swamp", "mangrove_swamp");

    public static final Set<Biome> JUNK_TAIGA = biomes(
            "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "snowy_taiga");

    public static final Set<Biome> HAS_BULLFISH = JUNK_SWAMP;

    public static final Set<Biome> HAS_FISHROOM = biomes("dark_forest", "pale_garden", "mushroom_fields");

    public static final Set<Biome> HAS_ICEHORN = biomes(
            "snowy_beach", "snowy_plains", "snowy_slopes", "snowy_taiga",
            "ice_spikes", "frozen_peaks", "frozen_river", "taiga",
            "old_growth_pine_taiga", "old_growth_spruce_taiga", "grove",
            "meadow", "stony_peaks", "windswept_hills", "windswept_forest", "windswept_gravelly_hills");

    public static final Set<Biome> IS_MOUNTAINS = biomes(
            "meadow", "cherry_grove", "grove", "snowy_slopes", "jagged_peaks",
            "frozen_peaks", "stony_peaks", "windswept_hills", "windswept_gravelly_hills", "windswept_forest");

    public static final Set<Biome> PERMITS_FROGS = JUNK_SWAMP;

    /** #minecraft:is_ocean + #minecraft:is_river, reconstructed from the junk ocean/warm_ocean/river sets. */
    public static final Set<Biome> PERMITS_SQUIDS = combine(JUNK_OCEAN, JUNK_WARM_OCEAN, JUNK_RIVER);

    private static Set<Biome> biomes(String... paths) {
        Set<Biome> result = new HashSet<>();
        for (String path : paths) {
            Biome biome = Registry.BIOME.get(NamespacedKey.minecraft(path));
            if (biome != null) {
                result.add(biome);
            }
        }
        return result;
    }

    @SafeVarargs
    private static Set<Biome> combine(Set<Biome>... sets) {
        Set<Biome> result = new HashSet<>();
        for (Set<Biome> s : sets) {
            result.addAll(s);
        }
        return result;
    }

    private ReelBiomes() {
    }
}
