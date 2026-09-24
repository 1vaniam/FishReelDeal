package dev.reeldeal.item;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Deserialized form of one entry in {@code reel/items.json}, which is a
 * near-verbatim extraction of each {@code data/reel/loot_table/definition/*.json}
 * file's {@code minecraft:set_components} block from the original datapack.
 * <p>
 * {@code loreJson} and {@code itemNameJson} are kept as raw Adventure
 * component JSON (exactly as Minecraft would parse them) rather than
 * pre-parsed, so {@link ItemRegistry} can hand them straight to
 * {@code GsonComponentSerializer} instead of us re-encoding formatting
 * codes and translation keys by hand.
 */
public record ItemDefinition(
        String id,
        String baseItem,
        JsonObject itemNameJson,
        String itemModel,
        int maxStackSize,
        String rarity,
        JsonArray loreJson,
        JsonObject food,
        JsonObject consumable
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
                o.has("consumable") && !o.get("consumable").isJsonNull() ? o.getAsJsonObject("consumable") : null
        );
    }
}
