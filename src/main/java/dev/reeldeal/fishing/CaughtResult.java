package dev.reeldeal.fishing;

import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;

/**
 * What an anchor_hook catch resolves to: either a normal item (cod, salmon,
 * ...) or a live mob to spawn and fling at the player instead of handing
 * over an item at all.
 */
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
