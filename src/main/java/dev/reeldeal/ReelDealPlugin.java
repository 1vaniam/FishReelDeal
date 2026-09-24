package dev.reeldeal;

import dev.reeldeal.command.ReelCommand;
import dev.reeldeal.fishing.FishingLootResolver;
import dev.reeldeal.integration.PyroFishingIntegration;
import dev.reeldeal.item.FishingRodFactory;
import dev.reeldeal.item.ItemRegistry;
import dev.reeldeal.item.ReelItemGuard;
import dev.reeldeal.recipe.RecipeRegistrar;
import org.bukkit.plugin.java.JavaPlugin;

public final class ReelDealPlugin extends JavaPlugin {

    private ItemRegistry itemRegistry;
    private FishingRodFactory rodFactory;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.itemRegistry = new ItemRegistry(this);
        this.itemRegistry.load();

        this.rodFactory = new FishingRodFactory(itemRegistry);

        new RecipeRegistrar(this, itemRegistry, rodFactory).registerAll();

        getServer().getPluginManager().registerEvents(new ReelItemGuard(), this);
        getServer().getPluginManager().registerEvents(new FishingLootResolver(itemRegistry, rodFactory), this);

        // Read from config.yml so the integration can be tuned or disabled without a rebuild.
        boolean pyroEnabled = getConfig().getBoolean("pyrofishing-integration.enabled", true);
        double pyroChance = getConfig().getDouble("pyrofishing-integration.anchor-hook-chance", 0.25);
        PyroFishingIntegration.tryRegister(this, rodFactory, pyroEnabled, pyroChance);

        ReelCommand reelCommand = new ReelCommand(itemRegistry, rodFactory);
        registerCommand("reel", "ReelDeal admin/debug commands", java.util.List.of(), reelCommand);

        getServer().getPluginManager().addPermission(new org.bukkit.permissions.Permission(
                ReelCommand.PERMISSION,
                "Allows giving ReelDeal items and rods via /reel give",
                org.bukkit.permissions.PermissionDefault.OP));

        // Advancements and the tick/substitute mechanics build on top of
        // this and get wired in here as they're ported.
    }

    public ItemRegistry items() {
        return itemRegistry;
    }

    public FishingRodFactory rods() {
        return rodFactory;
    }
}
