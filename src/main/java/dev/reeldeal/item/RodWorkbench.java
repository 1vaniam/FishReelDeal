package dev.reeldeal.item;

import dev.reeldeal.ReelKeys;
import dev.reeldeal.quest.QuestManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/** Player-facing menu for adding and removing ReelDeal rod attachments. */
public final class RodWorkbench implements Listener {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private static final int SIZE = 45;
    private static final int ROD_SLOT = 19;
    private static final int HOOK_SLOT = 21;
    private static final int LINE_SLOT = 23;
    private static final int PREVIEW_SLOT = 25;
    private static final int APPLY_SLOT = 31;
    private static final int REMOVE_HOOK_SLOT = 29;
    private static final int REMOVE_LINE_SLOT = 33;

    private final ItemRegistry items;
    private final FishingRodFactory rods;
    private final QuestManager quests;

    public RodWorkbench(ItemRegistry items, FishingRodFactory rods, QuestManager quests) {
        this.items = items;
        this.rods = rods;
        this.quests = quests;
    }

    public void open(Player player) {
        MenuHolder holder = new MenuHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                MINI_MESSAGE.deserialize("<dark_aqua><bold>ReelDeal</bold></dark_aqua> <gray>•</gray> <aqua>Rod Workbench</aqua>"));
        holder.inventory = inventory;
        draw(inventory);
        player.openInventory(inventory);
    }

    private void draw(Inventory inventory) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, "<dark_gray> </dark_gray>", List.of());
        for (int i = 0; i < SIZE; i++) inventory.setItem(i, filler);
        inventory.setItem(ROD_SLOT, null);
        inventory.setItem(HOOK_SLOT, null);
        inventory.setItem(LINE_SLOT, null);
        inventory.setItem(PREVIEW_SLOT, button(Material.PAPER, "<aqua><bold>Preview</bold></aqua>", List.of(
                "<gray>Your updated rod will appear here.</gray>")));

        // Keep each label directly above its slot, with arrows showing the flow.
        inventory.setItem(10, button(Material.FISHING_ROD, "<gold><bold>1. FISHING ROD</bold></gold>", List.of(
                "<gray>Place the rod you want to</gray>", "<gray>modify in the slot below.</gray>")));
        inventory.setItem(12, button(Material.TRIPWIRE_HOOK, "<gold><bold>2. HOOK</bold></gold>", List.of(
                "<yellow>Optional</yellow><gray>: place a</gray>", "<aqua>ReelDeal hook</aqua><gray> below.</gray>")));
        inventory.setItem(14, button(Material.STRING, "<gold><bold>3. LINE</bold></gold>", List.of(
                "<yellow>Optional</yellow><gray>: place a</gray>", "<aqua>ReelDeal line</aqua><gray> below.</gray>")));
        inventory.setItem(16, button(Material.LANTERN, "<aqua><bold>4. PREVIEW</bold></aqua>", List.of(
                "<gray>Review your rod before</gray>", "<gray>applying the changes.</gray>")));
        inventory.setItem(20, button(Material.ARROW, "<aqua><bold>➜</bold></aqua>", List.of("<gray>Rod</gray>")));
        inventory.setItem(22, button(Material.ARROW, "<aqua><bold>➜</bold></aqua>", List.of("<gray>Attachments</gray>")));
        inventory.setItem(24, button(Material.ARROW, "<aqua><bold>➜</bold></aqua>", List.of("<gray>Preview</gray>")));

        inventory.setItem(REMOVE_HOOK_SLOT, button(Material.SHEARS, "<red><bold>REMOVE HOOK</bold></red>", List.of(
                "<gray>Returns the rod's current</gray>", "<gold>hook</gold><gray> to your inventory.</gray>",
                "<dark_gray>Requires a rod in slot 1.</dark_gray>")));
        inventory.setItem(APPLY_SLOT, button(Material.SMITHING_TABLE, "<green><bold>APPLY CHANGES</bold></green>", List.of(
                "<gray>Consumes the selected new</gray>", "<gold>hook</gold><gray> and/or </gray><aqua>line</aqua><gray>.</gray>",
                "<gray>Replaced parts are returned.</gray>", "<green>Enchantments and durability stay.</green>")));
        inventory.setItem(REMOVE_LINE_SLOT, button(Material.SHEARS, "<red><bold>REMOVE LINE</bold></red>", List.of(
                "<gray>Returns the rod's current</gray>", "<aqua>line</aqua><gray> to your inventory.</gray>",
                "<dark_gray>Requires a rod in slot 1.</dark_gray>")));
        inventory.setItem(40, button(Material.BOOK, "<aqua><bold>HOW TO USE</bold></aqua>", List.of(
                "<gold>1.</gold> <gray>Place your fishing rod.</gray>",
                "<gold>2.</gold> <gray>Add a ReelDeal hook or line.</gray>",
                "<gold>3.</gold> <gray>Review the preview and apply.</gray>",
                "<dark_gray>Removed parts are returned.</dark_gray>",
                "<dark_gray>Unused items return when you close.</dark_gray>")));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;

        int raw = event.getRawSlot();
        if (raw >= event.getView().getTopInventory().getSize()) {
            // Permit normal inventory clicks, but never let shift-click move items into this menu.
            event.setCancelled(event.isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK);
            return;
        }

        if (event.getClick() == ClickType.LEFT && (raw == ROD_SLOT || raw == HOOK_SLOT || raw == LINE_SLOT)) {
            swapCursor(event, event.getView().getTopInventory(), raw);
            updatePreview(event.getView().getTopInventory());
        } else if (event.getClick() == ClickType.LEFT && raw == APPLY_SLOT) {
            apply(player, event.getView().getTopInventory());
        } else if (event.getClick() == ClickType.LEFT && raw == REMOVE_HOOK_SLOT) {
            remove(player, event.getView().getTopInventory(), true);
        } else if (event.getClick() == ClickType.LEFT && raw == REMOVE_LINE_SLOT) {
            remove(player, event.getView().getTopInventory(), false);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuHolder)) return;
        if (event.getRawSlots().stream().anyMatch(slot -> slot < event.getView().getTopInventory().getSize())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof MenuHolder)) return;
        Player player = (Player) event.getPlayer();
        Inventory inventory = event.getInventory();
        returnToPlayer(player, inventory.getItem(ROD_SLOT));
        returnToPlayer(player, inventory.getItem(HOOK_SLOT));
        returnToPlayer(player, inventory.getItem(LINE_SLOT));
        inventory.setItem(ROD_SLOT, null);
        inventory.setItem(HOOK_SLOT, null);
        inventory.setItem(LINE_SLOT, null);
    }

    private void swapCursor(InventoryClickEvent event, Inventory inventory, int slot) {
        ItemStack cursor = event.getCursor();
        ItemStack old = inventory.getItem(slot);
        inventory.setItem(slot, cursor == null || cursor.getType().isAir() ? null : cursor.clone());
        event.setCursor(old == null || old.getType().isAir() ? null : old.clone());
    }

    private void apply(Player player, Inventory inventory) {
        ItemStack rod = inventory.getItem(ROD_SLOT);
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            tell(player, "Place a fishing rod in the rod slot first.", NamedTextColor.RED);
            return;
        }
        String hookInput = attachmentId(inventory.getItem(HOOK_SLOT), "hook", player);
        if (hookInput == INVALID) return;
        String lineInput = attachmentId(inventory.getItem(LINE_SLOT), "line", player);
        if (lineInput == INVALID) return;
        if (hookInput == null && lineInput == null) {
            tell(player, "Place a hook or line in its slot before applying.", NamedTextColor.RED);
            return;
        }

        boolean reelRod = rods.isConstructed(rod);
        String oldHook = reelRod ? strip(rods.hookOf(rod)) : null;
        String oldLine = reelRod ? strip(rods.lineOf(rod)) : null;
        if (!validCurrentAttachment(oldHook, "hook") || !validCurrentAttachment(oldLine, "line")) {
            tell(player, "This rod has unknown attachment data; it was left unchanged.", NamedTextColor.RED);
            return;
        }
        boolean applyHook = hookInput != null && !hookInput.equals(oldHook);
        boolean applyLine = lineInput != null && !lineInput.equals(oldLine);
        if (!applyHook && !applyLine) {
            tell(player, "That attachment is already on this rod.", NamedTextColor.RED);
            return;
        }

        String newHook = applyHook ? hookInput : oldHook;
        String newLine = applyLine ? lineInput : oldLine;
        ItemStack updated = rods.applyLineAndHook(rod, newLine, newHook);
        inventory.setItem(ROD_SLOT, updated);
        if (applyHook) {
            consumeOne(inventory, HOOK_SLOT);
            if (oldHook != null) returnToPlayer(player, items.create(oldHook));
        }
        if (applyLine) {
            consumeOne(inventory, LINE_SLOT);
            if (oldLine != null) returnToPlayer(player, items.create(oldLine));
        }
        updatePreview(inventory);
        tell(player, "Rod updated. Enchantments and durability were retained.", NamedTextColor.GREEN);
        if (quests != null) quests.recordWorkbenchUpgrade(player);
    }

    private static final String INVALID = "\u0000";

    private String attachmentId(ItemStack stack, String expectedCategory, Player player) {
        if (stack == null || stack.getType().isAir()) return null;
        String identifier = items.identifierOf(stack);
        String id = identifier == null ? null : strip(identifier);
        if (id == null || !items.ids().contains(id) || !expectedCategory.equals(items.categoryOf(id))) {
            tell(player, "That item isn't a valid ReelDeal " + expectedCategory + ".", NamedTextColor.RED);
            return INVALID;
        }
        return id;
    }

    private boolean validCurrentAttachment(String id, String expectedCategory) {
        return id == null || (items.ids().contains(id) && expectedCategory.equals(items.categoryOf(id)));
    }

    private void updatePreview(Inventory inventory) {
        ItemStack rod = inventory.getItem(ROD_SLOT);
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            inventory.setItem(PREVIEW_SLOT, button(Material.PAPER, "<aqua><bold>Preview</bold></aqua>", List.of(
                    "<gray>Place a fishing rod to see</gray>", "<gray>the updated preview.</gray>")));
            return;
        }
        String currentHook = rods.isConstructed(rod) ? strip(rods.hookOf(rod)) : null;
        String currentLine = rods.isConstructed(rod) ? strip(rods.lineOf(rod)) : null;
        String hookInput = validInputId(inventory.getItem(HOOK_SLOT), "hook");
        String lineInput = validInputId(inventory.getItem(LINE_SLOT), "line");
        String previewHook = hookInput == null ? currentHook : hookInput;
        String previewLine = lineInput == null ? currentLine : lineInput;
        ItemStack preview = !validCurrentAttachment(previewHook, "hook")
                || !validCurrentAttachment(previewLine, "line")
                || (previewHook == null && previewLine == null)
                ? rod.clone() : rods.applyLineAndHook(rod, previewLine, previewHook);
        inventory.setItem(PREVIEW_SLOT, preview);
    }

    private String validInputId(ItemStack stack, String category) {
        if (stack == null || stack.getType().isAir()) return null;
        String identifier = items.identifierOf(stack);
        String id = strip(identifier);
        return id != null && items.ids().contains(id) && category.equals(items.categoryOf(id)) ? id : null;
    }

    private void remove(Player player, Inventory inventory, boolean hook) {
        ItemStack rod = inventory.getItem(ROD_SLOT);
        if (rod == null || rod.getType() != Material.FISHING_ROD || !rods.isConstructed(rod)) {
            tell(player, "Place an upgraded ReelDeal rod in the rod slot first.", NamedTextColor.RED);
            return;
        }
        var pdc = rod.getItemMeta().getPersistentDataContainer();
        var key = hook ? ReelKeys.HOOK : ReelKeys.LINE;
        String encoded = pdc.get(key, PersistentDataType.STRING);
        if (encoded == null) {
            tell(player, "This rod has no " + (hook ? "hook" : "line") + " attached.", NamedTextColor.YELLOW);
            return;
        }
        String id = strip(encoded);
        if (id == null || !items.ids().contains(id) || ! (hook ? "hook" : "line").equals(items.categoryOf(id))) {
            tell(player, "The rod contains an unknown attachment; it was left unchanged.", NamedTextColor.RED);
            return;
        }

        ItemStack updated = hook
                ? rods.applyLineAndHook(rod, strip(rods.lineOf(rod)), null)
                : rods.applyLineAndHook(rod, null, strip(rods.hookOf(rod)));
        inventory.setItem(ROD_SLOT, updated);
        returnToPlayer(player, items.create(id));
        updatePreview(inventory);
        tell(player, "Removed and returned the " + (hook ? "hook" : "line") + ".", NamedTextColor.GREEN);
    }

    private void returnToPlayer(Player player, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) return;
        var leftovers = player.getInventory().addItem(stack);
        leftovers.values().forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void consumeOne(Inventory inventory, int slot) {
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.getAmount() <= 1) {
            inventory.setItem(slot, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            inventory.setItem(slot, stack);
        }
    }

    private String strip(String encoded) {
        if (encoded == null) return null;
        return encoded.startsWith("reel:") ? encoded.substring(5) : encoded;
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.displayName(MINI_MESSAGE.deserialize(name).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> MINI_MESSAGE.deserialize(line)
                .decoration(TextDecoration.ITALIC, false)).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private void tell(Player player, String message, NamedTextColor color) {
        Component prefix = Component.text("REELDEAL", NamedTextColor.DARK_AQUA)
                .decorate(TextDecoration.BOLD);
        Component body = Component.text(" » ", NamedTextColor.DARK_GRAY)
                .append(Component.text(message, color));
        player.sendMessage(prefix.append(body));
    }

    private static final class MenuHolder implements InventoryHolder {
        private final UUID owner;
        private Inventory inventory;

        private MenuHolder(UUID owner) { this.owner = owner; }

        @Override
        public Inventory getInventory() { return inventory; }
    }
}
