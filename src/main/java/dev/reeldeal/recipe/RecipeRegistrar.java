package dev.reeldeal.recipe;

import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

public final class RecipeRegistrar {

    private static final String[] ROD_PATTERN = {"  S", " SL", "S H"};
    public static final List<String> LINES = List.of("woven_line", "reinforced_line", "fiber_line");
    public static final List<String> HOOKS = List.of("wooden_hook", "iron_hook", "golden_hook", "cluster_hook", "anchor_hook", "skyhook", "xp_hook");

    private final Plugin plugin;
    private final ItemRegistry items;
    private final FishingRodFactory rods;

    public RecipeRegistrar(Plugin plugin, ItemRegistry items, FishingRodFactory rods) {
        this.plugin = plugin;
        this.items = items;
        this.rods = rods;
    }

    private static final List<String> STATIC_RECIPE_KEYS = List.of(
            "woven_line_h", "woven_line_v", "reinforced_line", "fiber_line_h", "fiber_line_v", "iron_hook",
            "weather_radio");

    public void registerAll() {
        removeManagedRecipes();

        registerLineRecipes();
        registerHookRecipes();
        registerWeatherRadioRecipe();
        registerRodRecipes();
    }

    private void registerWeatherRadioRecipe() {
        addShaped("weather_radio", items.create("weather_radio"),
                new String[]{"CRC", "RMR", "CRC"},
                Map.of(
                        'C', material(Material.COPPER_INGOT),
                        'R', material(Material.REDSTONE),
                        'M', material(Material.COMPASS)));
    }

    private void registerLineRecipes() {
        addShaped("woven_line_h", items.create("woven_line"),
                new String[]{"   ", "SIS", "   "},
                Map.of('S', material(Material.STRING), 'I', material(Material.STICK)));
        addShaped("woven_line_v", items.create("woven_line"),
                new String[]{" S ", " I ", " S "},
                Map.of('S', material(Material.STRING), 'I', material(Material.STICK)));

        addShaped("reinforced_line", items.create("reinforced_line"),
                new String[]{"NNN", "NLN", "NNN"},
                Map.of('N', material(Material.IRON_NUGGET), 'L', exact("woven_line")));

        addShaped("fiber_line_h", items.create("fiber_line"),
                new String[]{"   ", "FLF", "   "},
                Map.of('F', exact("fiber"), 'L', exact("reinforced_line")));
        addShaped("fiber_line_v", items.create("fiber_line"),
                new String[]{" F ", " L ", " F "},
                Map.of('F', exact("fiber"), 'L', exact("reinforced_line")));
    }

    private void registerHookRecipes() {
        addShaped("iron_hook", items.create("iron_hook"),
                new String[]{" I ", " N "},
                Map.of('I', material(Material.IRON_INGOT), 'N', material(Material.IRON_NUGGET)));
    }

    private void removeManagedRecipes() {
        for (String key : STATIC_RECIPE_KEYS) {
            Bukkit.removeRecipe(new NamespacedKey(plugin, key));
        }
        for (String line : LINES) {
            for (String hook : HOOKS) {
                Bukkit.removeRecipe(new NamespacedKey(plugin, "rod_" + line + "_" + hook));
            }
        }
    }

    private void registerRodRecipes() {
        for (String line : LINES) {
            for (String hook : HOOKS) {
                NamespacedKey key = new NamespacedKey(plugin, "rod_" + line + "_" + hook);
                ShapedRecipe recipe = new ShapedRecipe(key, rods.construct(line, hook));
                recipe.shape(ROD_PATTERN);
                recipe.setIngredient('S', material(Material.STICK));
                recipe.setIngredient('L', exact(line));
                recipe.setIngredient('H', exact(hook));
                Bukkit.addRecipe(recipe);
            }
        }
    }

    private void addShaped(String keyName, ItemStack result, String[] shape, Map<Character, RecipeChoice> ingredients) {
        NamespacedKey key = new NamespacedKey(plugin, keyName);
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shape);
        for (Map.Entry<Character, RecipeChoice> entry : ingredients.entrySet()) {
            recipe.setIngredient(entry.getKey(), entry.getValue());
        }
        Bukkit.addRecipe(recipe);
    }

    private RecipeChoice material(Material material) {
        return new RecipeChoice.MaterialChoice(material);
    }

    private RecipeChoice exact(String reelItemId) {
        return RecipeChoice.exactChoice(List.of(items.create(reelItemId)));
    }
}
