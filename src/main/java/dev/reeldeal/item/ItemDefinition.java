package dev.reeldeal.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record ItemDefinition(
        String id,
        String baseItem,
        JsonObject itemNameJson,
        String itemModel,
        int maxStackSize,
        String rarity,
        JsonArray loreJson,
        JsonObject food,
        JsonObject consumable,
        String category
) {

    static ItemDefinition fromJson(String id, JsonObject o) {
        return new ItemDefinition(
                id,
                o.get("base_item").getAsString(),
                o.has("item_name") ? o.getAsJsonObject("item_name") : null,
                o.has("item_model") ? o.get("item_model").getAsString() : null,
                o.has("max_stack_size") ? o.get("max_stack_size").getAsInt() : 64,
                o.has("rarity") ? o.get("rarity").getAsString() : "common",
                o.has("lore") ? o.getAsJsonArray("lore") : null,
                o.has("food") && !o.get("food").isJsonNull() ? o.getAsJsonObject("food") : null,
                o.has("consumable") && !o.get("consumable").isJsonNull() ? o.getAsJsonObject("consumable") : null,
                o.has("category") ? o.get("category").getAsString() : null
        );
    }
}
