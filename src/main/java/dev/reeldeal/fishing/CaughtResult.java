package dev.reeldeal.fishing;

import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

public sealed interface CaughtResult {

    record AsItem(ItemStack stack) implements CaughtResult {
    }

    record AsMob(EntityType type) implements CaughtResult {
    }

    static CaughtResult item(ItemStack stack) {
        return new AsItem(stack);
    }

    static CaughtResult mob(EntityType type) {
        return new AsMob(type);
    }
}
