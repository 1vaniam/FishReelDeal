package dev.reeldeal.command;

import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import dev.reeldeal.recipe.RecipeRegistrar;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ReelCommand implements BasicCommand {

    public static final String PERMISSION = "reeldeal.give";

    private final ItemRegistry items;
    private final FishingRodFactory rods;
    private final Runnable reloadAction;

    public ReelCommand(ItemRegistry items, FishingRodFactory rods, Runnable reloadAction) {
        this.items = items;
        this.rods = rods;
        this.reloadAction = reloadAction;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> handleGive(sender, args);
            case "add" -> handleAdd(sender, args);
            case "reload" -> handleReload(sender);
            default -> sendUsage(sender);
        }
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        if (args[1].equalsIgnoreCase("rod")) {
            handleGiveRod(sender, args);
        } else {
            handleGiveItem(sender, args);
        }
    }

    private void handleGiveItem(CommandSender sender, String[] args) {
        String itemId = args[1];
        if (!items.ids().contains(itemId)) {
            sender.sendMessage(Component.text("Unknown item '" + itemId + "'. Known: "
                    + String.join(", ", items.ids()), NamedTextColor.RED));
            return;
        }
        Player target = resolveTarget(sender, args, 2);
        if (target == null) {
            return;
        }
        ItemStack stack = items.create(itemId);
        give(sender, target, stack, "reel:" + itemId);
    }

    private void handleGiveRod(CommandSender sender, String[] args) {
        if (args.length < 4) {
            sender.sendMessage(Component.text("Usage: /reel give rod <line> <hook> [player]", NamedTextColor.RED));
            return;
        }
        String lineId = args[2];
        String hookId = args[3];
        if (!items.ids().contains(lineId)) {
            sender.sendMessage(Component.text("Unknown line '" + lineId + "'. Known: "
                    + String.join(", ", items.ids()), NamedTextColor.RED));
            return;
        }
        if (!items.ids().contains(hookId)) {
            sender.sendMessage(Component.text("Unknown hook '" + hookId + "'. Known: "
                    + String.join(", ", items.ids()), NamedTextColor.RED));
            return;
        }
        Player target = resolveTarget(sender, args, 4);
        if (target == null) {
            return;
        }
        ItemStack stack = rods.construct(lineId, hookId);
        give(sender, target, stack, "rod (" + lineId + " + " + hookId + ")");
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /reel add <line|hook> <item>", NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only a player can hold an item -- run this in-game.", NamedTextColor.RED));
            return;
        }

        String slot = args[1].toLowerCase(Locale.ROOT);
        if (!slot.equals("line") && !slot.equals("hook")) {
            sender.sendMessage(Component.text("Usage: /reel add <line|hook> <item>", NamedTextColor.RED));
            return;
        }

        String itemId = args[2];
        if (!items.ids().contains(itemId)) {
            sender.sendMessage(Component.text("Unknown item '" + itemId + "'. Known: "
                    + String.join(", ", items.ids()), NamedTextColor.RED));
            return;
        }
        String actualCategory = items.categoryOf(itemId);
        if (!slot.equals(actualCategory)) {
            sender.sendMessage(Component.text(
                    "'" + itemId + "' is " + (actualCategory == null
                            ? "not categorized (no \"category\" field in items.json)"
                            : "a " + actualCategory + ", not a " + slot)
                            + " -- refusing to put it in the " + slot + " slot.",
                    NamedTextColor.RED));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.FISHING_ROD) {
            sender.sendMessage(Component.text("You need to be holding a fishing rod.", NamedTextColor.RED));
            return;
        }

        boolean alreadyReelRod = rods.isConstructed(held);
        boolean hasForeignData = !alreadyReelRod && held.hasItemMeta()
                && !held.getItemMeta().getPersistentDataContainer().getKeys().isEmpty();

        String currentLine = alreadyReelRod ? strip(rods.lineOf(held)) : "woven_line";
        String currentHook = alreadyReelRod ? strip(rods.hookOf(held)) : "iron_hook";
        String newLine = slot.equals("line") ? itemId : currentLine;
        String newHook = slot.equals("hook") ? itemId : currentHook;

        ItemStack updated = rods.applyLineAndHook(held, newLine, newHook);
        player.getInventory().setItemInMainHand(updated);

        if (hasForeignData) {
            sender.sendMessage(Component.text(
                    "That rod has other plugin data on it (Nexo/Oraxen/ItemsAdder, most likely) -- its "
                            + "name and texture are untouched; the same lore block a normal Reel Deal rod "
                            + "shows was appended after its existing lore.",
                    NamedTextColor.AQUA));
        }
        sender.sendMessage(Component.text("Set line=" + newLine + " hook=" + newHook + ".", NamedTextColor.GREEN));
    }

    private void handleReload(CommandSender sender) {
        int before = items.ids().size();
        reloadAction.run();
        int after = items.ids().size();
        sender.sendMessage(Component.text("Reloaded config.yml and items.json -- " + after + " items known"
                + (after != before ? " (was " + before + ")" : "") + ". Recipes rebuilt.", NamedTextColor.GREEN));
        sender.sendMessage(Component.text(
                "Note: the PyroFishingPro integration's enabled/chance settings and event listeners aren't "
                        + "reloaded -- restart for changes to those.",
                NamedTextColor.YELLOW));
    }

    private Player resolveTarget(CommandSender sender, String[] args, int nameIndex) {
        if (args.length > nameIndex) {
            Player target = Bukkit.getPlayerExact(args[nameIndex]);
            if (target == null) {
                sender.sendMessage(Component.text("Player '" + args[nameIndex] + "' isn't online.", NamedTextColor.RED));
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
        return null;
    }

    private void give(CommandSender sender, Player target, ItemStack stack, String description) {
        target.getInventory().addItem(stack);
        sender.sendMessage(Component.text("Gave " + target.getName() + " " + description + ".", NamedTextColor.GREEN));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel give <item> [player]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel give rod <line> <hook> [player]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel add <line|hook> <item>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel reload", NamedTextColor.YELLOW));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> List.of("give", "add", "reload");
            case 2 -> args[0].equalsIgnoreCase("give") ? concat(items.ids(), List.of("rod"))
                    : args[0].equalsIgnoreCase("add") ? List.of("line", "hook")
                    : List.of();
            case 3 -> args[0].equalsIgnoreCase("give") && args[1].equalsIgnoreCase("rod") ? RecipeRegistrar.LINES
                    : args[0].equalsIgnoreCase("give") ? onlinePlayerNames()
                    : args[0].equalsIgnoreCase("add") ? itemsInCategory(args[1])
                    : List.of();
            case 4 -> args[0].equalsIgnoreCase("give") && args[1].equalsIgnoreCase("rod") ? RecipeRegistrar.HOOKS : List.of();
            case 5 -> args[0].equalsIgnoreCase("give") && args[1].equalsIgnoreCase("rod") ? onlinePlayerNames() : List.of();
            default -> List.<String>of();
        };
        String current = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(current)).collect(Collectors.toList());
    }

    private List<String> itemsInCategory(String category) {
        return items.ids().stream()
                .filter(id -> category.equalsIgnoreCase(items.categoryOf(id)))
                .collect(Collectors.toList());
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> concat(java.util.Collection<String> a, java.util.Collection<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }

    private String strip(String reelId) {
        return reelId != null && reelId.startsWith("reel:") ? reelId.substring(5) : reelId;
    }
}
