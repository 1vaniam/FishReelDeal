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

public final class ReelDealPlugin extends JavaPlugin {

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
        this.barrelCatches = new BarrelCatchManager(this, displayTicker);
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
        itemRegistry.load();
        if (hookedFish != null) {
            hookedFish.reload();
        }
        new RecipeRegistrar(this, itemRegistry, rodFactory).registerAll();
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
