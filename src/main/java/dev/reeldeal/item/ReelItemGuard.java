package dev.reeldeal.item;

import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

public final class ReelItemGuard implements Listener {

    private final ItemRegistry items;

    public ReelItemGuard(ItemRegistry items) {
        this.items = items;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        ItemStack stack = event.getItem();
        String identifier = items.identifierOf(stack);
        if (identifier == null) {
            return;
        }
        String itemId = identifier.startsWith("reel:") ? identifier.substring(5) : identifier;
        if (items.isConsumable(itemId)) {
            return;
        }

        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
    }
}
