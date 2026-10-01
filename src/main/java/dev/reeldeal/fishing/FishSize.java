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

    public static int step(int lengthCm) {
        for (int i = 0; i < STEP_MAX.length; i++) {
            if (lengthCm <= STEP_MAX[i]) {
                return i;
            }
        }
        return STEP_MAX.length - 1;
    }

    public static FishSize roll(ConfigurationSection cfg, Random random) {
        if (cfg == null || !cfg.getBoolean("enabled", true)) {
            return null;
        }
        int[] min = new int[5];
        int[] max = new int[5];
        double[] weight = new double[5];
        double total = 0;
        for (int i = 0; i < 5; i++) {
            String p = "tiers." + KEYS[i] + ".";
            min[i] = cfg.getInt(p + "min-cm", DEFAULT_CM[i][0]);
            max[i] = Math.max(min[i], cfg.getInt(p + "max-cm", DEFAULT_CM[i][1]));
            weight[i] = Math.max(0, cfg.getDouble(p + "weight", DEFAULT_WEIGHT[i]));
            total += weight[i];
        }
        int tier = 2;
        if (total > 0) {
            double r = random.nextDouble() * total;
            for (int i = 0; i < 5; i++) {
                r -= weight[i];
                if (r < 0) {
                    tier = i;
                    break;
                }
            }
        }
        double t = random.nextDouble();
        if (tier == 4) {
            t = Math.pow(t, Math.max(1.0, cfg.getDouble("trophy-skew", 2.0))); // biggest are rarest
        }
        int cm = (int) Math.round(min[tier] + t * (max[tier] - min[tier]));

        double lo = min[0];
        double hi = Math.max(lo + 1, max[4]);
        double minScale = cfg.getDouble("min-scale", 0.25);
        double maxScale = cfg.getDouble("max-scale", 5.0);
        double scale = minScale + (cm - lo) / (hi - lo) * (maxScale - minScale);
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
