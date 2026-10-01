package dev.reeldeal.item;

import dev.reeldeal.ReelKeys;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

public final class ReelItemGuard implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!isReelItem(event.getItem())) {
            return;
        }

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
