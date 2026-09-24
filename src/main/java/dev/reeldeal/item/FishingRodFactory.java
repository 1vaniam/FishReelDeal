package dev.reeldeal.item;

import dev.reeldeal.ReelKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Ports the ~15 {@code data/reel/recipe/<line>/<hook>.json} recipes. Those
 * were all one-off static results; here a rod is built parametrically from
 * whichever line + hook were used, so this replaces all of them at once
 * (and works for every line/hook combo the item registry knows about, not
 * just the ones the original pack had recipe files for).
 */
public final class FishingRodFactory {

    private static final Component FOOTER =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize("§r§9Reel Deal");

    private final ItemRegistry items;

    public FishingRodFactory(ItemRegistry items) {
        this.items = items;
    }

    /**
     * @param lineId reel item id, e.g. "fiber_line" (without the "reel:" prefix)
     * @param hookId reel item id, e.g. "wooden_hook"
     */
    public ItemStack construct(String lineId, String hookId) {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();

        meta.lore(buildLore(lineId, hookId));

        var pdc = meta.getPersistentDataContainer();
        pdc.set(ReelKeys.CONSTRUCTED, PersistentDataType.BOOLEAN, true);
        pdc.set(ReelKeys.LINE, PersistentDataType.STRING, "reel:" + lineId);
        pdc.set(ReelKeys.HOOK, PersistentDataType.STRING, "reel:" + hookId);

        // custom_model_data.strings -> CustomModelDataComponent#setStrings (confirmed against
        // the 26.x javadocs: getCustomModelDataComponent/setStrings/setCustomModelDataComponent).
        applyCustomModelDataStrings(meta, List.of("reel:" + lineId, "reel:" + hookId));

        rod.setItemMeta(meta);
        return rod;
    }

    /** Is this stack a constructed reel rod, i.e. did the fiber_line/wooden_hook-style recipe fire on it? */
    public boolean isConstructed(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FISHING_ROD || !stack.hasItemMeta()) {
            return false;
        }
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        return Boolean.TRUE.equals(pdc.get(ReelKeys.CONSTRUCTED, PersistentDataType.BOOLEAN));
    }

    public String lineOf(ItemStack rod) {
        return rod.getItemMeta().getPersistentDataContainer().get(ReelKeys.LINE, PersistentDataType.STRING);
    }

    public String hookOf(ItemStack rod) {
        return rod.getItemMeta().getPersistentDataContainer().get(ReelKeys.HOOK, PersistentDataType.STRING);
    }

    private List<Component> buildLore(String lineId, String hookId) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(items.appliedNameHeader(lineId));
        lore.add(Component.empty());
        lore.addAll(items.ownLoreBody(lineId));
        lore.add(Component.empty());
        lore.add(items.appliedNameHeader(hookId));
        lore.add(Component.empty());
        lore.addAll(items.ownLoreBody(hookId));
        lore.add(Component.empty());
        lore.add(FOOTER);
        return lore;
    }

    private void applyCustomModelDataStrings(ItemMeta meta, List<String> strings) {
        try {
            var component = meta.getCustomModelDataComponent();
            component.setStrings(strings);
            meta.setCustomModelDataComponent(component);
        } catch (LinkageError e) {
            org.bukkit.Bukkit.getLogger().warning(
                    "CustomModelDataComponent API mismatch building a reel rod -- fix FishingRodFactory#applyCustomModelDataStrings");
        }
    }
}
