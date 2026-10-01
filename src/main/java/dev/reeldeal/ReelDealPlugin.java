package dev.reeldeal;

import dev.reeldeal.command.ReelCommand;
import dev.reeldeal.fishing.FishingLootResolver;
import dev.reeldeal.fishing.HookedFishManager;
import dev.reeldeal.integration.PyroFishingIntegration;
import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import dev.reeldeal.item.ReelItemGuard;
import dev.reeldeal.recipe.RecipeRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

public final class ReelDealPlugin extends JavaPlugin {

    private ItemRegistry itemRegistry;
    private FishingRodFactory rodFactory;
    private HookedFishManager hookedFish;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.itemRegistry = new ItemRegistry(this);
        this.rodFactory = new FishingRodFactory(this, itemRegistry);
        reload();

        getServer().getPluginManager().registerEvents(new ReelItemGuard(), this);
        getServer().getPluginManager().registerEvents(new FishingLootResolver(this, itemRegistry, rodFactory), this);

        this.hookedFish = new HookedFishManager(this);
        getServer().getPluginManager().registerEvents(hookedFish, this);

        boolean pyroEnabled = getConfig().getBoolean("pyrofishing-integration.enabled", true);
        double pyroChance = getConfig().getDouble("pyrofishing-integration.anchor-hook-chance", 0.25);
        boolean pyroReplace = getConfig().getBoolean("pyrofishing-integration.replace-catch-on-anchor-hook", true);
        boolean pyroDebug = getConfig().getBoolean("pyrofishing-integration.debug", false);
        PyroFishingIntegration.tryRegister(this, rodFactory, pyroEnabled, pyroChance, pyroReplace, pyroDebug, hookedFish);

        ReelCommand reelCommand = new ReelCommand(itemRegistry, rodFactory, this::reload);
        registerCommand("reel", "ReelDeal admin/debug commands", java.util.List.of(), reelCommand);

        getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(
                ReelCommand.PERMISSION,
                "Allows giving ReelDeal items and rods via /reel give",
                org.bukkit.permissions.PermissionDefault.OP));

    }

    @Override
    public void onDisable() {
        if (hookedFish != null) {
            hookedFish.clear();
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
}
