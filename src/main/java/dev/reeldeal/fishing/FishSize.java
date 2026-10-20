package dev.reeldeal.fishing;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public record FishSize(int tier, int lengthCm, double scale) {

    public static final NamespacedKey LENGTH_KEY = new NamespacedKey("reel", "fish_length_cm");
    public static final NamespacedKey TIER_KEY = new NamespacedKey("reel", "fish_tier");

    static final String[] KEYS = {"tiny", "small", "medium", "large", "trophy"};
    static final String[] LABELS = {"Tiny", "Small", "Medium", "Large", "Trophy"};
    static final NamedTextColor[] COLORS = {
            NamedTextColor.GRAY, NamedTextColor.WHITE, NamedTextColor.GREEN, NamedTextColor.GOLD,
            NamedTextColor.LIGHT_PURPLE};
    private static final int[][] DEFAULT_CM = {{3, 8}, {10, 25}, {28, 61}, {64, 102}, {104, 150}};
    private static final int[] DEFAULT_WEIGHT = {25, 35, 28, 10, 2};
    private static final int[] STEP_MAX = {8, 15, 25, 38, 52, 68, 85, 104, 125, Integer.MAX_VALUE};

    /** Parsed once on startup/reload so catches don't repeatedly read configuration paths. */
    public static final class Settings {
        private final boolean enabled;
        private final int[] minCm;
        private final int[] maxCm;
        private final double[] weights;
        private final int anchorTierShift;
        private final double trophySkew;
        private final double minScale;
        private final double maxScale;

        private Settings(boolean enabled, int[] minCm, int[] maxCm, double[] weights,
                         int anchorTierShift, double trophySkew, double minScale, double maxScale) {
            this.enabled = enabled;
            this.minCm = minCm;
            this.maxCm = maxCm;
            this.weights = weights;
            this.anchorTierShift = anchorTierShift;
            this.trophySkew = trophySkew;
            this.minScale = minScale;
            this.maxScale = maxScale;
        }

        public static Settings from(ConfigurationSection cfg) {
            int[] min = new int[5];
            int[] max = new int[5];
            double[] weight = new double[5];
            if (cfg == null) {
                return new Settings(false, min, max, weight, 0, 2.0, 0.25, 5.0);
            }
            for (int i = 0; i < KEYS.length; i++) {
                String path = "tiers." + KEYS[i] + ".";
                min[i] = cfg.getInt(path + "min-cm", DEFAULT_CM[i][0]);
                max[i] = Math.max(min[i], cfg.getInt(path + "max-cm", DEFAULT_CM[i][1]));
                weight[i] = Math.max(0, cfg.getDouble(path + "weight", DEFAULT_WEIGHT[i]));
            }
            return new Settings(cfg.getBoolean("enabled", true), min, max, weight,
                    Math.max(0, cfg.getInt("anchor-tier-shift", 1)),
                    Math.max(1.0, cfg.getDouble("trophy-skew", 2.0)),
                    cfg.getDouble("min-scale", 0.25), cfg.getDouble("max-scale", 5.0));
        }
    }

    public static int step(int lengthCm) {
        for (int i = 0; i < STEP_MAX.length; i++) {
            if (lengthCm <= STEP_MAX[i]) {
                return i;
            }
        }
        return STEP_MAX.length - 1;
    }

    public static FishSize roll(ConfigurationSection cfg, Random random) {
        return roll(cfg, random, false);
    }

    public static FishSize roll(ConfigurationSection cfg, Random random, boolean anchorHook) {
        return roll(Settings.from(cfg), random, anchorHook);
    }

    public static FishSize roll(Settings cfg, Random random, boolean anchorHook) {
        if (cfg == null || !cfg.enabled) {
            return null;
        }
        double total = 0;
        for (double weight : cfg.weights) {
            total += weight;
        }
        int tier = 2;
        if (total > 0) {
            double r = random.nextDouble() * total;
            for (int i = 0; i < 5; i++) {
                r -= cfg.weights[i];
                if (r < 0) {
                    tier = i;
                    break;
                }
            }
        }
        if (anchorHook) {
            tier = Math.min(KEYS.length - 1, tier + cfg.anchorTierShift);
        }
        double t = random.nextDouble();
        if (tier == 4) {
            t = Math.pow(t, cfg.trophySkew); // biggest are rarest
        }
        int cm = (int) Math.round(cfg.minCm[tier] + t * (cfg.maxCm[tier] - cfg.minCm[tier]));

        double lo = cfg.minCm[0];
        double hi = Math.max(lo + 1, cfg.maxCm[4]);
        double scale = cfg.minScale + (cm - lo) / (hi - lo) * (cfg.maxScale - cfg.minScale);
        return new FishSize(tier, cm, Math.max(0.05, scale));
    }

    public void stamp(ItemStack fish) {
        ItemMeta meta = fish.getItemMeta();
        if (meta == null) {
            return;
        }
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.text("Length: ", NamedTextColor.GRAY)
                .append(Component.text(lengthCm + " cm", NamedTextColor.WHITE))
                .append(Component.text(" (" + LABELS[tier] + ")", COLORS[tier]))
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(LENGTH_KEY, PersistentDataType.INTEGER, lengthCm);
        pdc.set(TIER_KEY, PersistentDataType.INTEGER, tier);
        fish.setItemMeta(meta);
    }
}
