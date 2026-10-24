package dev.reeldeal;

import dev.reeldeal.command.ReelCommand;
import dev.reeldeal.fishing.FishingLootResolver;
import dev.reeldeal.fishing.BarrelCatchManager;
import dev.reeldeal.fishing.CatchRecords;
import dev.reeldeal.fishing.DisplayFollowTicker;
import dev.reeldeal.fishing.HookedFishManager;
import dev.reeldeal.integration.PyroFishingIntegration;
import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import dev.reeldeal.item.ReelItemGuard;
import dev.reeldeal.item.RodWorkbench;
import dev.reeldeal.recipe.RecipeRegistrar;
import dev.reeldeal.weather.WeatherEventManager;
import dev.reeldeal.weather.WeatherRadioManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Map;

public final class ReelDealPlugin extends JavaPlugin {

    private static final String BARREL_HEART_MIGRATION = "internal-migrations.barrel-heart-of-the-sea";
    private static final String BARREL_XP_HOOK_MIGRATION = "internal-migrations.barrel-xp-hook";
    private static final String XP_HOOK_TIER_BONUS_MIGRATION = "internal-migrations.xp-hook-tier-bonuses";

    private ItemRegistry itemRegistry;
    private FishingRodFactory rodFactory;
    private HookedFishManager hookedFish;
    private BarrelCatchManager barrelCatches;
    private CatchRecords catchRecords;
    private DisplayFollowTicker displayTicker;
    private WeatherEventManager weatherEvents;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.weatherEvents = new WeatherEventManager(this);

        this.itemRegistry = new ItemRegistry(this);
        this.rodFactory = new FishingRodFactory(this, itemRegistry);
        this.catchRecords = new CatchRecords(this);
        RodWorkbench workbench = new RodWorkbench(itemRegistry, rodFactory);
        reload();

        getServer().getPluginManager().registerEvents(new ReelItemGuard(itemRegistry), this);
        getServer().getPluginManager().registerEvents(new WeatherRadioManager(this, itemRegistry, weatherEvents), this);
        getServer().getPluginManager().registerEvents(workbench, this);
        this.displayTicker = new DisplayFollowTicker(this);
        this.barrelCatches = new BarrelCatchManager(this, displayTicker, itemRegistry);
        getServer().getPluginManager().registerEvents(barrelCatches, this);
        getServer().getPluginManager().registerEvents(new FishingLootResolver(this, itemRegistry, rodFactory, barrelCatches), this);

        this.hookedFish = new HookedFishManager(this, displayTicker, catchRecords);
        getServer().getPluginManager().registerEvents(hookedFish, this);

        boolean pyroEnabled = getConfig().getBoolean("pyrofishing-integration.enabled", true);
        double pyroChance = getConfig().getDouble("pyrofishing-integration.anchor-hook-chance", 0.25);
        String replaceModelsPath = "pyrofishing-integration.replace-pyro-catch-with-model";
        boolean pyroReplace = getConfig().isSet(replaceModelsPath)
                ? getConfig().getBoolean(replaceModelsPath)
                : getConfig().getBoolean("pyrofishing-integration.replace-catch-on-anchor-hook", true);
        boolean pyroDebug = getConfig().getBoolean("pyrofishing-integration.debug", false);
        PyroFishingIntegration.tryRegister(this, rodFactory, pyroEnabled, pyroChance, pyroReplace, pyroDebug, hookedFish);

        ReelCommand reelCommand = new ReelCommand(itemRegistry, rodFactory, this::reload, catchRecords,
                hookedFish, barrelCatches, workbench, weatherEvents);
        registerCommand("reel", "ReelDeal admin/debug commands", java.util.List.of(), reelCommand);
        getServer().getScheduler().runTaskTimer(this, catchRecords::saveAsync, 1200L, 1200L);

        getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(
                ReelCommand.PERMISSION,
                "Allows giving ReelDeal items and rods via /reel give",
                org.bukkit.permissions.PermissionDefault.OP));

    }

    @Override
    public void onDisable() {
        if (weatherEvents != null) {
            weatherEvents.stop();
        }
        if (catchRecords != null) {
            catchRecords.saveNow();
        }
        if (barrelCatches != null) {
            barrelCatches.clear();
        }
        if (hookedFish != null) {
            hookedFish.clear();
        }
        if (displayTicker != null) {
            displayTicker.clear();
        }
    }

    public void reload() {
        reloadConfig();
        mergeConfigDefaults();
        itemRegistry.load();
        if (hookedFish != null) {
            hookedFish.reload();
        }
        new RecipeRegistrar(this, itemRegistry, rodFactory).registerAll();
    }

    /** Adds new scalar defaults and performs one-time migrations for list-based settings. */
    private void mergeConfigDefaults() {
        var config = getConfig();
        config.options().copyDefaults(true);

        if (!config.getBoolean(BARREL_HEART_MIGRATION, false)) {
            String lootPath = "barrel-catch.loot-table";
            var lootTable = new ArrayList<>(config.getMapList(lootPath));
            boolean alreadyPresent = lootTable.stream().anyMatch(entry -> {
                Object material = entry.get("material");
                if (material == null) return false;
                String id = String.valueOf(material).toUpperCase(java.util.Locale.ROOT);
                return id.equals("HEART_OF_THE_SEA") || id.equals("MINECRAFT:HEART_OF_THE_SEA");
            });
            if (!alreadyPresent) {
                lootTable.add(Map.of(
                        "material", "HEART_OF_THE_SEA",
                        "weight", 3,
                        "min-amount", 1,
                        "max-amount", 1));
                config.set(lootPath, lootTable);
            }
            config.set(BARREL_HEART_MIGRATION, true);
        }

        if (!config.getBoolean(BARREL_XP_HOOK_MIGRATION, false)) {
            String lootPath = "barrel-catch.loot-table";
            var lootTable = new ArrayList<>(config.getMapList(lootPath));
            boolean alreadyPresent = lootTable.stream().anyMatch(entry ->
                    "xp_hook".equalsIgnoreCase(String.valueOf(entry.get("item"))));
            if (!alreadyPresent) {
                lootTable.add(Map.of("item", "xp_hook", "weight", 1,
                        "min-amount", 1, "max-amount", 1));
                config.set(lootPath, lootTable);
            }
            config.set(BARREL_XP_HOOK_MIGRATION, true);
        }

        if (!config.getBoolean(XP_HOOK_TIER_BONUS_MIGRATION, false)) {
            String path = "fishing.xp-hook.pyrofishing-tier-bonuses.";
            String[] tiers = {"bronze", "silver", "gold", "diamond", "platinum", "mythical"};
            int[] previousDefaults = {3, 4, 5, 6, 8, 10};
            int[] updatedDefaults = {5, 10, 20, 30, 100, 1000};
            for (int i = 0; i < tiers.length; i++) {
                String tierPath = path + tiers[i];
                if (config.getInt(tierPath, previousDefaults[i]) == previousDefaults[i]) {
                    config.set(tierPath, updatedDefaults[i]);
                }
            }
            config.set(XP_HOOK_TIER_BONUS_MIGRATION, true);
        }

        saveConfig();
    }

    public ItemRegistry items() {
        return itemRegistry;
    }

    public FishingRodFactory rods() {
        return rodFactory;
    }

    public WeatherEventManager weatherEvents() {
        return weatherEvents;
    }
}
