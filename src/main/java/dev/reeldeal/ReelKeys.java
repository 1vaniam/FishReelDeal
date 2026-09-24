package dev.reeldeal;

import org.bukkit.NamespacedKey;

/**
 * PDC keys mirroring the datapack's {@code minecraft:custom_data} tags under
 * the {@code reel:} namespace. Kept 1:1 with the datapack's tag names so the
 * mapping between JSON and code stays obvious.
 */
public final class ReelKeys {

    /** Identifies which registry item a stack represents, e.g. "reel:wooden_hook". */
    public static final NamespacedKey IDENTIFIER = new NamespacedKey("reel", "identifier");

    /** On a constructed fishing_rod: which line item was used. */
    public static final NamespacedKey LINE = new NamespacedKey("reel", "line");

    /** On a constructed fishing_rod: which hook item was used. */
    public static final NamespacedKey HOOK = new NamespacedKey("reel", "hook");

    /** Marks a fishing_rod as fully assembled (line + hook applied). */
    public static final NamespacedKey CONSTRUCTED = new NamespacedKey("reel", "constructed");

    /**
     * Originally: on a dropped item, the entity identifier it should turn
     * into (tick.mcfunction -> substitute.mcfunction behavior). Currently
     * unused -- FishLoot's anchor_hook redesign spawns mobs directly at
     * catch time instead of tagging an item for later conversion. Left
     * defined in case a future item-based substitution need comes up.
     */
    public static final NamespacedKey SUBSTITUTION = new NamespacedKey("reel", "substitution");

    private ReelKeys() {
    }
}
