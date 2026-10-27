package dev.reeldeal.command;

import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import dev.reeldeal.item.RodWorkbench;
import dev.reeldeal.recipe.RecipeRegistrar;
import dev.reeldeal.fishing.CatchRecords;
import dev.reeldeal.fishing.BarrelCatchManager;
import dev.reeldeal.fishing.HookedFishManager;
import dev.reeldeal.weather.WeatherEventManager;
import dev.reeldeal.weather.FrostwakeBossManager;
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
    private final CatchRecords catchRecords;
    private final HookedFishManager hookedFish;
    private final BarrelCatchManager barrels;
    private final RodWorkbench workbench;
    private final WeatherEventManager weatherEvents;
    private final FrostwakeBossManager frostwakeBoss;

    public ReelCommand(ItemRegistry items, FishingRodFactory rods, Runnable reloadAction, CatchRecords catchRecords,
                       HookedFishManager hookedFish, BarrelCatchManager barrels, RodWorkbench workbench) {
        this(items, rods, reloadAction, catchRecords, hookedFish, barrels, workbench, null);
    }

    public ReelCommand(ItemRegistry items, FishingRodFactory rods, Runnable reloadAction, CatchRecords catchRecords,
                       HookedFishManager hookedFish, BarrelCatchManager barrels, RodWorkbench workbench,
                       WeatherEventManager weatherEvents) {
        this(items, rods, reloadAction, catchRecords, hookedFish, barrels, workbench, weatherEvents, null);
    }

    public ReelCommand(ItemRegistry items, FishingRodFactory rods, Runnable reloadAction, CatchRecords catchRecords,
                       HookedFishManager hookedFish, BarrelCatchManager barrels, RodWorkbench workbench,
                       WeatherEventManager weatherEvents, FrostwakeBossManager frostwakeBoss) {
        this.items = items;
        this.rods = rods;
        this.reloadAction = reloadAction;
        this.catchRecords = catchRecords;
        this.hookedFish = hookedFish;
        this.barrels = barrels;
        this.workbench = workbench;
        this.weatherEvents = weatherEvents;
        this.frostwakeBoss = frostwakeBoss;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();

        if (args.length == 0) {
            sendUsage(sender);
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "stats" -> handleStats(sender, args);
            case "leaderboard" -> handleLeaderboard(sender, args);
            case "workbench" -> {
                if (sender instanceof Player player) {
                    workbench.open(player);
                } else {
                    sender.sendMessage(Component.text("Only players can open the rod workbench.", NamedTextColor.RED));
                }
            }
            case "test" -> {
                if (requireAdmin(sender)) handleTest(sender, args);
            }
            case "give" -> {
                if (requireAdmin(sender)) handleGive(sender, args);
            }
            case "add" -> {
                if (requireAdmin(sender)) handleAdd(sender, args);
            }
            case "reload" -> {
                if (requireAdmin(sender)) handleReload(sender);
            }
            default -> sendUsage(sender);
        }
    }

    @Override
    public String permission() {
        return null;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(PERMISSION)) {
            return true;
        }
        sender.sendMessage(Component.text("You don't have permission to use that command.", NamedTextColor.RED));
        return false;
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sender.sendMessage(Component.text("Usage: /reel stats [player]", NamedTextColor.RED));
            return;
        }
        Player target;
        if (args.length == 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player '" + args[1] + "' isn't online.", NamedTextColor.RED));
                return;
            }
            if (!target.equals(sender) && !sender.hasPermission(PERMISSION)) {
                sender.sendMessage(Component.text("You can only view your own fishing record.", NamedTextColor.RED));
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(Component.text("Console must specify an online player.", NamedTextColor.RED));
            return;
        }

        CatchRecords.PlayerRecord record = catchRecords.get(target.getUniqueId());
        sender.sendMessage(Component.text("3D fish record for " + target.getName(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("Fish models caught: " + record.total(), NamedTextColor.YELLOW));
        if (record.largestModel() == null) {
            sender.sendMessage(Component.text("Largest fish: none recorded", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("Largest fish: " + displayModel(record.largestModel()) + " — "
                    + record.largestLengthCm() + " cm", NamedTextColor.AQUA));
        }
        if (record.rarestModel() == null) {
            sender.sendMessage(Component.text("Rarest size tier: none recorded", NamedTextColor.GRAY));
        } else {
            String[] tiers = {"Tiny", "Small", "Medium", "Large", "Trophy"};
            String tier = record.rarestTier() >= 0 && record.rarestTier() < tiers.length
                    ? tiers[record.rarestTier()] : "Unknown";
            sender.sendMessage(Component.text("Rarest size tier: " + tier + " — "
                    + displayModel(record.rarestModel()) + " (" + record.rarestLengthCm() + " cm)", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private String displayModel(String model) {
        String[] words = model.replace('_', ' ').replace('-', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private void handleLeaderboard(CommandSender sender, String[] args) {
        if (args.length > 2) {
            sender.sendMessage(Component.text("Usage: /reel leaderboard [largest|catches]", NamedTextColor.RED));
            return;
        }
        String mode = args.length == 1 ? "largest" : args[1].toLowerCase(Locale.ROOT);
        if (!mode.equals("largest") && !mode.equals("catches")) {
            sender.sendMessage(Component.text("Choose 'largest' or 'catches'.", NamedTextColor.RED));
            return;
        }

        List<CatchRecords.LeaderEntry> entries = mode.equals("largest")
                ? catchRecords.topByLargest(10) : catchRecords.topByTotal(10);
        sender.sendMessage(Component.text(mode.equals("largest")
                ? "Top 3D fish by size" : "Top anglers by 3D fish catches", NamedTextColor.GOLD));
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("No fish records yet.", NamedTextColor.GRAY));
            return;
        }

        int rank = 1;
        for (CatchRecords.LeaderEntry entry : entries) {
            String name = entry.playerName() == null || entry.playerName().isBlank()
                    ? entry.playerId().toString().substring(0, 8) : entry.playerName();
            String detail = mode.equals("largest")
                    ? displayModel(entry.record().largestModel()) + " — " + entry.record().largestLengthCm() + " cm"
                    : entry.record().total() + " catches";
            sender.sendMessage(Component.text(rank++ + ". " + name + " — " + detail, NamedTextColor.YELLOW));
        }
    }

    private void handleTest(CommandSender sender, String[] args) {
        if (args.length == 3 && args[1].equalsIgnoreCase("frostwake") && args[2].equalsIgnoreCase("stop")) {
            if (frostwakeBoss != null && frostwakeBoss.stopTestBoss()) {
                sender.sendMessage(Component.text("Removed the test Frostwake boss.", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("There is no test Frostwake boss to remove.", NamedTextColor.YELLOW));
            }
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("frostwake")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Run this command in-game to spawn the Frostwake boss.",
                        NamedTextColor.RED));
                return;
            }
            if (frostwakeBoss != null && frostwakeBoss.spawnTest(player)) {
                sender.sendMessage(Component.text("Spawned a test Frostwake Illusioner. Move beyond its configured "
                        + "teleport distance to test its pursuit.", NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("Couldn't spawn the test boss. A Frostwake boss may already be "
                        + "active, or there is no safe nearby spawn location.", NamedTextColor.RED));
            }
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("weather")) {
            handleTestWeather(sender, args);
            return;
        }
        if (args.length != 2 || (!args[1].equalsIgnoreCase("fish") && !args[1].equalsIgnoreCase("barrel"))) {
            sender.sendMessage(Component.text("Usage: /reel test <fish|barrel|frostwake> or /reel test weather <event> [seconds]",
                    NamedTextColor.RED));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Run this command in-game so the test entity has a spawn location.",
                    NamedTextColor.RED));
            return;
        }
        if (args[1].equalsIgnoreCase("fish")) {
            if (!hookedFish.spawnTest(player)) {
                sender.sendMessage(Component.text("3D fish spawning is disabled or has no models loaded.",
                        NamedTextColor.RED));
                return;
            }
            sender.sendMessage(Component.text("Spawned a test 3D fish. It does not count toward your records.",
                    NamedTextColor.GREEN));
        } else if (barrels.spawnTest(player)) {
            sender.sendMessage(Component.text("Spawned a test barrel. Open it by hitting it repeatedly.",
                    NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("Couldn't spawn the test barrel; check the server log.",
                    NamedTextColor.RED));
        }
    }

    private void handleTestWeather(CommandSender sender, String[] args) {
        if (args.length == 3 && args[2].equalsIgnoreCase("stop")) {
            if (weatherEvents != null && weatherEvents.stopTestEvent()) {
                sender.sendMessage(Component.text("Stopped the active event or canceled its countdown.",
                        NamedTextColor.GREEN));
            } else {
                sender.sendMessage(Component.text("There is no active event or countdown to stop.",
                        NamedTextColor.YELLOW));
            }
            return;
        }
        if (args.length < 3 || args.length > 4) {
            sender.sendMessage(Component.text("Usage: /reel test weather <event> [seconds]", NamedTextColor.RED));
            sender.sendMessage(Component.text("       /reel test weather stop", NamedTextColor.RED));
            return;
        }
        String eventId = args[2].toLowerCase(Locale.ROOT);
        if (weatherEvents == null || !weatherEvents.isSupportedEvent(eventId)) {
            sender.sendMessage(Component.text("Unknown weather event. Choose one of the registered event IDs.",
                    NamedTextColor.RED));
            return;
        }
        int seconds = 10;
        if (args.length == 4) {
            try {
                seconds = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(Component.text("Countdown must be a whole number of seconds.", NamedTextColor.RED));
                return;
            }
        }
        if (seconds < 1 || seconds > 300) {
            sender.sendMessage(Component.text("Countdown must be between 1 and 300 seconds.", NamedTextColor.RED));
            return;
        }
        if (!weatherEvents.scheduleTestEvent(eventId, seconds)) {
            sender.sendMessage(Component.text("A weather event is already active; wait for it to end first.",
                    NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("Scheduled " + displayModel(eventId) + " to start in " + seconds
                + (seconds == 1 ? " second." : " seconds."), NamedTextColor.GREEN));
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
        var leftovers = target.getInventory().addItem(stack);
        for (ItemStack leftover : leftovers.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }
        sender.sendMessage(Component.text("Gave " + target.getName() + " " + description
                + (leftovers.isEmpty() ? "." : "; inventory was full, so the item dropped nearby."),
                leftovers.isEmpty() ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel stats [player]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel leaderboard [largest|catches]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel workbench", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel test <fish|barrel>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel test frostwake", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel test frostwake stop", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel test weather <event> [seconds]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel test weather stop", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel give <item> [player]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel give rod <line> <hook> [player]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel add <line|hook> <item>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("  /reel reload", NamedTextColor.YELLOW));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length > 0 && !source.getSender().hasPermission(PERMISSION)
                && !args[0].equalsIgnoreCase("stats")
                && !args[0].equalsIgnoreCase("leaderboard")
                && !args[0].equalsIgnoreCase("workbench")) {
            return List.of();
        }
        List<String> options = switch (args.length) {
            case 1 -> sender.hasPermission(PERMISSION)
                    ? List.of("stats", "leaderboard", "workbench", "test", "give", "add", "reload")
                    : List.of("stats", "leaderboard", "workbench");
            case 2 -> args[0].equalsIgnoreCase("stats") ? onlinePlayerNames()
                    : args[0].equalsIgnoreCase("leaderboard") ? List.of("largest", "catches")
                    : !sender.hasPermission(PERMISSION) ? List.of()
                    : args[0].equalsIgnoreCase("test") ? List.of("fish", "barrel", "frostwake", "weather")
                    : args[0].equalsIgnoreCase("give") ? concat(items.ids(), List.of("rod"))
                    : args[0].equalsIgnoreCase("add") ? List.of("line", "hook")
                    : List.of();
            case 3 -> args[0].equalsIgnoreCase("give") && args[1].equalsIgnoreCase("rod") ? RecipeRegistrar.LINES
                    : args[0].equalsIgnoreCase("give") ? onlinePlayerNames()
                    : args[0].equalsIgnoreCase("test") && args[1].equalsIgnoreCase("weather")
                    ? weatherEvents == null ? List.of("acid-rain", "pollen-drift", "stop")
                    : concat(weatherEvents.eventIds(), List.of("stop"))
                    : args[0].equalsIgnoreCase("test") && args[1].equalsIgnoreCase("frostwake")
                    ? List.of("stop")
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
