package dev.reeldeal.item;

import dev.reeldeal.ReelKeys;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 9 of the 14 reel items (every hook, every line, fiber) are built on real
 * spawn eggs, which are otherwise fully functional -- right-clicking a
 * block with one spawns the actual mob. The datapack this was ported from
 * never addressed this (no interaction-cancel logic in tick.mcfunction);
 * a datapack has no clean way to override a spawn egg's hardcoded use
 * behavior. A plugin does, so this closes the gap the source material had.
 * <p>
 * Note: this only checks reel:identifier, not reel:substitution -- the
 * PyroFishingPro integration's live-fish spawns bypass items entirely, so
 * there's nothing to guard there currently, but if a future feature ever
 * hands a player a tagged reel:substitution item again, it would need
 * covering here too.
 */
public final class ReelItemGuard implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!isReelItem(event.getItem())) {
            return;
        }
        // Denying both stops the spawn-egg-on-block behavior specifically,
        // without needing to special-case every base material this or a
        // future reel item might use.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    private boolean isReelItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .has(ReelKeys.IDENTIFIER, PersistentDataType.STRING);
    }
}
