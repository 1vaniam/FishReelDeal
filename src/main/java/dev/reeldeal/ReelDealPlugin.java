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
import dev.reeldeal.weather.FrostwakeBossManager;
import dev.reeldeal.weather.WeatherRadioManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Map;

public final class ReelDealPlugin extends JavaPlugin {

    private static final String BARREL_HEART_MIGRATION = "internal-migrations.barrel-heart-of-the-sea";
    private static final String BARREL_XP_HOOK_MIGRATION = "internal-migrations.barrel-xp-hook";
    private static final String XP_HOOK_TIER_BONUS_MIGRATION = "internal-migrations.xp-hook-tier-bonuses";
    private static final String FROSTWAKE_COOLDOWN_MIGRATION = "internal-migrations.frostwake-cooldown-five-minutes";
    private static final String WEATHER_CUE_INTENSITY_MIGRATION = "internal-migrations.weather-cue-intensity-v1";

    private ItemRegistry itemRegistry;
    private FishingRodFactory rodFactory;
    private HookedFishManager hookedFish;
    private BarrelCatchManager barrelCatches;
    private CatchRecords catchRecords;
    private DisplayFollowTicker displayTicker;
    private WeatherEventManager weatherEvents;
    private FrostwakeBossManager frostwakeBoss;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.weatherEvents = new WeatherEventManager(this);
        this.itemRegistry = new ItemRegistry(this);
        this.rodFactory = new FishingRodFactory(this, itemRegistry);
        this.catchRecords = new CatchRecords(this);
        RodWorkbench workbench = new RodWorkbench(itemRegistry, rodFactory);
        reload();

        this.frostwakeBoss = new FrostwakeBossManager(this, weatherEvents, itemRegistry);
        getServer().getPluginManager().registerEvents(frostwakeBoss, this);

        getServer().getPluginManager().registerEvents(new ReelItemGuard(itemRegistry), this);
        getServer().getPluginManager().registerEvents(new WeatherRadioManager(this, itemRegistry, weatherEvents), this);
        getServer().getPluginManager().registerEvents(workbench, this);
        this.displayTicker = new DisplayFollowTicker(this);
        this.barrelCatches = new BarrelCatchManager(this, displayTicker, itemRegistry);
        getServer().getPluginManager().registerEvents(barrelCatches, this);
        getServer().getPluginManager().registerEvents(
                new FishingLootResolver(this, itemRegistry, rodFactory, barrelCatches, weatherEvents), this);

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
                hookedFish, barrelCatches, workbench, weatherEvents, frostwakeBoss);
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
        if (frostwakeBoss != null) {
            frostwakeBoss.stop();
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

        if (!config.getBoolean(FROSTWAKE_COOLDOWN_MIGRATION, false)) {
            String cooldownPath = "weather-events.frostwake.boss.cooldown-seconds";
            if (config.getLong(cooldownPath, 900L) == 900L) {
                config.set(cooldownPath, 300L);
            }
            config.set(FROSTWAKE_COOLDOWN_MIGRATION, true);
        }

        if (!config.getBoolean(WEATHER_CUE_INTENSITY_MIGRATION, false)) {
            migrateWeatherCueDefaults(config);
            config.set(WEATHER_CUE_INTENSITY_MIGRATION, true);
        }

        saveConfig();
    }

    private void migrateWeatherCueDefaults(org.bukkit.configuration.file.FileConfiguration config) {
        WeatherCueDefaults[] events = {
                new WeatherCueDefaults("acid-rain", 5, 4, 0.20, 0.45, 0.12, 0.16, 4, 6, 0.35, 0.50, 1.5, 1.5),
                new WeatherCueDefaults("pollen-drift", 8, 6, 0.20, 0.35, 0.08, 0.13, 5, 7, 0.35, 0.55, 1.5, 1.5),
                new WeatherCueDefaults("ashfall", 8, 6, 0.20, 0.35, 0.08, 0.14, 5, 8, 0.35, 0.60, 1.5, 1.5),
                new WeatherCueDefaults("cold-snap", 10, 6, 0.20, 0.35, 0.07, 0.13, 3, 6, 0.35, 0.50, 1.5, 1.5),
                new WeatherCueDefaults("frostwake", 12, 8, 0.12, 0.30, 0.06, 0.12, 3, 6, 0.40, 0.55, 1.5, 1.5),
                new WeatherCueDefaults("dust-haze", 8, 6, 0.18, 0.35, 0.06, 0.12, 4, 7, 0.35, 0.55, 1.5, 1.5),
                new WeatherCueDefaults("firefly-night", 8, 5, 0.20, 0.40, 0.06, 0.13, 2, 5, 0.35, 0.55, 1.5, 1.5),
                new WeatherCueDefaults("meteor-dust", 15, 10, 0.10, 0.30, 0.08, 0.15, 2, 4, 0.80, 1.20, 10.0, 12.0),
                new WeatherCueDefaults("spore-bloom", 8, 6, 0.20, 0.35, 0.08, 0.14, 4, 7, 0.35, 0.60, 1.5, 1.5),
                new WeatherCueDefaults("magnetic-disturbance", 12, 6, 0.15, 0.35, 0.08, 0.14, 2, 5, 0.35, 0.55, 1.5, 1.5)
        };
        for (WeatherCueDefaults event : events) {
            String path = "weather-events." + event.id() + ".";
            migrateLongIfDefault(config, path + "effect-check-interval-seconds",
                    event.oldInterval(), event.newInterval());
            migrateDoubleIfDefault(config, path + "effect-chance-per-check",
                    event.oldChance(), event.newChance());
            migrateDoubleIfDefault(config, path + "sound-volume", event.oldVolume(), event.newVolume());
            migrateIntIfDefault(config, path + "particle-count", event.oldParticleCount(), event.newParticleCount());
            migrateDoubleIfDefault(config, path + "particle-spread", event.oldSpread(), event.newSpread());
            migrateDoubleIfDefault(config, path + "particle-height", event.oldHeight(), event.newHeight());
        }
    }

    private void migrateLongIfDefault(org.bukkit.configuration.file.FileConfiguration config,
                                      String path, long oldValue, long newValue) {
        if (config.getLong(path, oldValue) == oldValue) config.set(path, newValue);
    }

    private void migrateIntIfDefault(org.bukkit.configuration.file.FileConfiguration config,
                                     String path, int oldValue, int newValue) {
        if (config.getInt(path, oldValue) == oldValue) config.set(path, newValue);
    }

    private void migrateDoubleIfDefault(org.bukkit.configuration.file.FileConfiguration config,
                                       String path, double oldValue, double newValue) {
        if (Double.compare(config.getDouble(path, oldValue), oldValue) == 0) config.set(path, newValue);
    }

    private record WeatherCueDefaults(String id,
                                      long oldInterval, long newInterval,
                                      double oldChance, double newChance,
                                      double oldVolume, double newVolume,
                                      int oldParticleCount, int newParticleCount,
                                      double oldSpread, double newSpread,
                                      double oldHeight, double newHeight) { }

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
