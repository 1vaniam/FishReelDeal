package dev.reeldeal.item;

import dev.reeldeal.ReelKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FishingRodFactory {

    private static final Component FOOTER =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                    .deserialize("§r§9Reel Deal");

    private final Plugin plugin;
    private final ItemRegistry items;

    public FishingRodFactory(Plugin plugin, ItemRegistry items) {
        this.plugin = plugin;
        this.items = items;
    }

    public ItemStack construct(String lineId, String hookId) {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();

        meta.lore(buildLore(lineId, hookId));

        var pdc = meta.getPersistentDataContainer();
        pdc.set(ReelKeys.CONSTRUCTED, PersistentDataType.BOOLEAN, true);
        pdc.set(ReelKeys.LINE, PersistentDataType.STRING, "reel:" + lineId);
        pdc.set(ReelKeys.HOOK, PersistentDataType.STRING, "reel:" + hookId);
        pdc.set(ReelKeys.NATIVE, PersistentDataType.BOOLEAN, true);

        applyCustomModelDataStrings(meta, List.of("reel:" + lineId, "reel:" + hookId));

        rod.setItemMeta(meta);
        return rod;
    }

    public boolean isConstructed(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FISHING_ROD || !stack.hasItemMeta()) {
            return false;
        }
        var pdc = stack.getItemMeta().getPersistentDataContainer();
        return Boolean.TRUE.equals(pdc.get(ReelKeys.CONSTRUCTED, PersistentDataType.BOOLEAN));
    }

    public ItemStack applyLineAndHook(ItemStack existingRod, String lineId, String hookId) {
        ItemStack rod = existingRod.clone();
        ItemMeta meta = rod.getItemMeta();
        var pdc = meta.getPersistentDataContainer();

        boolean nativeRod = Boolean.TRUE.equals(pdc.get(ReelKeys.NATIVE, PersistentDataType.BOOLEAN))
                || (Boolean.TRUE.equals(pdc.get(ReelKeys.CONSTRUCTED, PersistentDataType.BOOLEAN))
                && !pdc.has(ReelKeys.APPENDED_LORE_LINES, PersistentDataType.INTEGER));
        if (nativeRod) {
            meta.lore(buildLore(lineId, hookId));
            pdc.set(ReelKeys.CONSTRUCTED, PersistentDataType.BOOLEAN, true);
            pdc.set(ReelKeys.LINE, PersistentDataType.STRING, "reel:" + lineId);
            pdc.set(ReelKeys.HOOK, PersistentDataType.STRING, "reel:" + hookId);
            pdc.set(ReelKeys.NATIVE, PersistentDataType.BOOLEAN, true);
            applyCustomModelDataStrings(meta, List.of("reel:" + lineId, "reel:" + hookId));
            rod.setItemMeta(meta);
            return rod;
        }

        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        int previouslyAppended = pdc.getOrDefault(ReelKeys.APPENDED_LORE_LINES, PersistentDataType.INTEGER, 0);
        if (previouslyAppended > 0 && previouslyAppended <= lore.size()) {
            lore.subList(lore.size() - previouslyAppended, lore.size()).clear();
        }

        List<Component> appended = resolveAddLore(lineId, hookId);
        lore.addAll(appended);
        meta.lore(lore);

        pdc.set(ReelKeys.CONSTRUCTED, PersistentDataType.BOOLEAN, true);
        pdc.set(ReelKeys.LINE, PersistentDataType.STRING, "reel:" + lineId);
        pdc.set(ReelKeys.HOOK, PersistentDataType.STRING, "reel:" + hookId);
        pdc.set(ReelKeys.APPENDED_LORE_LINES, PersistentDataType.INTEGER, appended.size());

        rod.setItemMeta(meta);
        return rod;
    }

    private List<Component> resolveAddLore(String lineId, String hookId) {
        String mode = plugin.getConfig().getString("rod-add-lore.mode", "full").toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "none" -> List.of();
            case "summary" -> summaryLore(lineId, hookId);
            default -> buildLore(lineId, hookId);
        };
    }

    private List<Component> summaryLore(String lineId, String hookId) {
        String lineLabel = plugin.getConfig().getString("rod-add-lore.summary.line-label", "Line");
        String hookLabel = plugin.getConfig().getString("rod-add-lore.summary.hook-label", "Hook");
        String colorName = plugin.getConfig().getString("rod-add-lore.summary.color", "dark_aqua");
        NamedTextColor color = NamedTextColor.NAMES.value(colorName.toLowerCase(Locale.ROOT));
        if (color == null) {
            color = NamedTextColor.DARK_AQUA;
        }
        return List.of(
                Component.empty(),
                Component.text(lineLabel + ": ", color).append(items.appliedNameHeader(lineId)),
                Component.text(hookLabel + ": ", color).append(items.appliedNameHeader(hookId)));
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
