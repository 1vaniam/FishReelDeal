package dev.reeldeal.item;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.reeldeal.ReelKeys;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ConsumeEffect;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemRarity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ItemRegistry {

    private final Plugin plugin;
    private final File itemsFile;
    private final Map<String, ItemStack> templates = new LinkedHashMap<>();
    private final Map<String, ItemDefinition> definitions = new LinkedHashMap<>();

    public ItemRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.itemsFile = new File(plugin.getDataFolder(), "items.json");
    }

    public void load() {
        ensureItemsFileExists();
        templates.clear();
        definitions.clear();
        definitions.putAll(readDefinitions());
        for (ItemDefinition def : definitions.values()) {
            templates.put(def.id(), build(def));
        }
        plugin.getLogger().info("Loaded " + templates.size() + " reel items: " + templates.keySet());
    }

    private void ensureItemsFileExists() {
        if (itemsFile.exists()) {
            return;
        }
        itemsFile.getParentFile().mkdirs();
        try (InputStream in = plugin.getResource("reel/items.json")) {
            if (in == null) {
                throw new IllegalStateException("Bundled reel/items.json missing from the plugin jar");
            }
            Files.copy(in, itemsFile.toPath());
            plugin.getLogger().info("Seeded default items.json at " + itemsFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create default items.json at " + itemsFile, e);
        }
    }

    public ItemDefinition definition(String id) {
        ItemDefinition def = definitions.get(id);
        if (def == null) {
            throw new IllegalArgumentException("Unknown reel item id: " + id);
        }
        return def;
    }

    public java.util.Set<String> ids() {
        return templates.keySet();
    }

    public String categoryOf(String id) {
        return definition(id).category();
    }

    public boolean isConsumable(String id) {
        ItemDefinition def = definitions.get(id);
        return def != null && def.consumable() != null;
    }

    public java.util.List<Component> ownLoreBody(String id) {
        ItemDefinition def = definition(id);
        JsonArray lore = def.loreJson();
        if (lore == null || lore.size() < 5) {
            return java.util.List.of();
        }
        java.util.List<Component> body = new java.util.ArrayList<>();
        for (int i = 3; i < lore.size() - 2; i++) {
            body.add(parseLoreLine(lore.get(i)));
        }
        return body;
    }

    public Component appliedNameHeader(String id) {
        ItemDefinition def = definition(id);
        Component name = def.itemNameJson() != null
                ? GsonComponentSerializer.gson().deserialize(def.itemNameJson().toString())
                : Component.text(id);
        return Component.text(" ")
                .append(Component.text("\u0001")
                        .font(net.kyori.adventure.key.Key.key("reel", "specification")))
                .append(Component.text(" "))
                .append(name.color(net.kyori.adventure.text.format.NamedTextColor.WHITE))
                .append(Component.text(" "));
    }

    public ItemStack create(String id) {
        ItemStack template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("Unknown reel item id: " + id);
        }
        return template.clone();
    }

    public String identifierOf(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta.getPersistentDataContainer()
                .get(ReelKeys.IDENTIFIER, org.bukkit.persistence.PersistentDataType.STRING);
    }

    private Map<String, ItemDefinition> readDefinitions() {
        try (Reader reader = new InputStreamReader(new FileInputStream(itemsFile), StandardCharsets.UTF_8)) {
            Gson gson = new Gson();
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            Map<String, ItemDefinition> result = new LinkedHashMap<>();
            for (String id : root.keySet()) {
                result.put(id, ItemDefinition.fromJson(id, root.getAsJsonObject(id)));
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load items.json from " + itemsFile, e);
        }
    }

    private ItemStack build(ItemDefinition def) {
        Material base = Material.matchMaterial(stripNamespace(def.baseItem()));
        if (base == null) {
            throw new IllegalStateException("Unknown base material for " + def.id() + ": " + def.baseItem());
        }

        ItemStack stack = new ItemStack(base);
        ItemMeta meta = stack.getItemMeta();

        meta.getPersistentDataContainer().set(
                ReelKeys.IDENTIFIER, org.bukkit.persistence.PersistentDataType.STRING, "reel:" + def.id());

        if (def.itemNameJson() != null) {
            meta.itemName(GsonComponentSerializer.gson().deserialize(def.itemNameJson().toString()));
        }

        if (def.itemModel() != null) {
            meta.setItemModel(NamespacedKey.fromString(def.itemModel()));
        }

        meta.setMaxStackSize(def.maxStackSize());

        applyRarity(meta, def.rarity());

        if (def.loreJson() != null) {
            meta.lore(parseLore(def.loreJson()));
        }

        if (def.food() != null) {
            applyFood(meta, def.food());
        }

        stack.setItemMeta(meta);

        if (def.consumable() != null) {
            applyConsumable(stack, def.consumable());
        }

        return stack;
    }

    private void applyRarity(ItemMeta meta, String rarity) {
        try {
            meta.setRarity(ItemRarity.valueOf(rarity.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown item rarity '" + rarity + "'");
        }
    }

    private void applyFood(ItemMeta meta, JsonObject food) {
        FoodComponent component = meta.getFood();
        component.setNutrition(food.get("nutrition").getAsInt());
        component.setSaturation(food.get("saturation").getAsFloat());
        if (food.has("can_always_eat")) {
            component.setCanAlwaysEat(food.get("can_always_eat").getAsBoolean());
        }
        meta.setFood(component);
    }

    private void applyConsumable(ItemStack stack, JsonObject consumable) {
        Consumable.Builder builder = Consumable.consumable()
                .consumeSeconds(consumable.get("consume_seconds").getAsFloat());

        if (consumable.has("animation")) {
            builder.animation(ItemUseAnimation.valueOf(
                    consumable.get("animation").getAsString().toUpperCase(Locale.ROOT)));
        }
        if (consumable.has("sound")) {
            builder.sound(net.kyori.adventure.key.Key.key(consumable.get("sound").getAsString()));
        }
        if (consumable.has("on_consume_effects")) {
            for (JsonElement el : consumable.getAsJsonArray("on_consume_effects")) {
                JsonObject effect = el.getAsJsonObject();
                if (!"minecraft:apply_effects".equals(effect.get("type").getAsString())) {
                    continue;
                }
                List<PotionEffect> potionEffects = new ArrayList<>();
                for (JsonElement pe : effect.getAsJsonArray("effects")) {
                    JsonObject peObj = pe.getAsJsonObject();
                    PotionEffectType type = Registry.EFFECT.get(NamespacedKey.fromString(peObj.get("id").getAsString()));
                    if (type == null) {
                        continue;
                    }
                    int amplifier = peObj.has("amplifier") ? peObj.get("amplifier").getAsInt() : 0;
                    int duration = peObj.has("duration") ? peObj.get("duration").getAsInt() : 20;
                    potionEffects.add(new PotionEffect(type, duration, amplifier));
                }
                float probability = effect.has("probability") ? effect.get("probability").getAsFloat() : 1.0f;
                builder.addEffect(ConsumeEffect.applyStatusEffects(potionEffects, probability));
            }
        }

        stack.setData(DataComponentTypes.CONSUMABLE, builder.build());
    }

    private java.util.List<Component> parseLore(JsonArray loreJson) {
        java.util.List<Component> lore = new java.util.ArrayList<>();
        for (JsonElement el : loreJson) {
            lore.add(parseLoreLine(el));
        }
        return lore;
    }

    private Component parseLoreLine(JsonElement el) {
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            return LegacyComponentSerializer.legacySection().deserialize(el.getAsString());
        }
        return GsonComponentSerializer.gson().deserialize(el.toString());
    }

    private static String stripNamespace(String id) {
        int colon = id.indexOf(':');
        return (colon >= 0 ? id.substring(colon + 1) : id).toUpperCase(Locale.ROOT);
    }
}
