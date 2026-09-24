package dev.reeldeal.command;

import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import dev.reeldeal.recipe.RecipeRegistrar;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@code /reel give <item|rod> [line] [hook] [player]} -- a debug command
 * for pulling any registry item or rod combo directly, without needing the
 * wandering trader/villager trades or biome-specific fishing RNG to be
 * ported yet.
 * <p>
 * paper-plugin.yml does not support YAML-based command declarations at
 * all (unlike classic plugin.yml) -- Paper plugins register commands in
 * code via JavaPlugin#registerCommand, using this Brigadier-backed
 * BasicCommand interface instead of the old CommandExecutor/TabCompleter
 * pair. Permission is still just a regular Bukkit permission node
 * ("reeldeal.give", registered in ReelDealPlugin#onEnable with a default
 * of OP), so LuckPerms and friends manage it the normal way -- nothing
 * paper-plugin-specific about that part.
 */
public final class ReelCommand implements BasicCommand {

    public static final String PERMISSION = "reeldeal.give";

    private final ItemRegistry items;
    private final FishingRodFactory rods;

    public ReelCommand(ItemRegistry items, FishingRodFactory rods) {
        this.items = items;
        this.rods = rods;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();

        if (args.length == 0 || !args[0].equalsIgnoreCase("give")) {
            sendUsage(sender);
            return;
        }
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

    @Override
    public String permission() {
        return PERMISSION;
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
        if (!RecipeRegistrar.LINES.contains(lineId)) {
            sender.sendMessage(Component.text("Unknown line '" + lineId + "'. Known: "
                    + String.join(", ", RecipeRegistrar.LINES), NamedTextColor.RED));
            return;
        }
        if (!RecipeRegistrar.HOOKS.contains(hookId)) {
            sender.sendMessage(Component.text("Unknown hook '" + hookId + "'. Known: "
                    + String.join(", ", RecipeRegistrar.HOOKS), NamedTextColor.RED));
            return;
        }
        Player target = resolveTarget(sender, args, 4);
        if (target == null) {
            return;
        }
        ItemStack stack = rods.construct(lineId, hookId);
        give(sender, target, stack, "rod (" + lineId + " + " + hookId + ")");
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
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> List.of("give");
            case 2 -> concat(items.ids(), List.of("rod"));
            case 3 -> args[1].equalsIgnoreCase("rod") ? RecipeRegistrar.LINES : onlinePlayerNames();
            case 4 -> args[1].equalsIgnoreCase("rod") ? RecipeRegistrar.HOOKS : List.of();
            case 5 -> args[1].equalsIgnoreCase("rod") ? onlinePlayerNames() : List.of();
            default -> List.<String>of();
        };
        String current = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(current)).collect(Collectors.toList());
    }

    private List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
    }

    private List<String> concat(java.util.Collection<String> a, java.util.Collection<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
}
