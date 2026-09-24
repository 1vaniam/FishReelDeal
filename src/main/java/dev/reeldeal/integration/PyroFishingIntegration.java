package dev.reeldeal.integration;

import dev.reeldeal.item.FishingRodFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Random;

/**
 * Optional integration with PyroFishingPro: anchor_hook gets a chance to
 * spawn a live cod/salmon/pufferfish/tropical_fish (flung at the player,
 * same flavor as our own anchor_hook + fiber_line mechanic) whenever Pyro
 * resolves a catch, on the idea that killing that live fish is what
 * Pyro's own drop-on-kill behavior already turns into a Pyro fish item --
 * so this is a bonus path into Pyro's fish economy through our gear, not
 * a replacement system for it.
 * <p>
 * Deliberately reflection-based, not a compile-time dependency: Pyro's
 * {@code me.arsmagica.API.PyroFishCatchEvent} isn't on our build classpath
 * (and shouldn't be -- it's a commercial plugin's internals), so this
 * resolves the event class and its getters by name at runtime, and does
 * nothing at all if PyroFishingPro isn't installed. Still needs
 * {@code join-classpath: true} on the PyroFishingPro entry in
 * paper-plugin.yml's dependencies so our classloader is even allowed to
 * see Pyro's classes at runtime.
 * <p>
 * Enabled/chance are read from config.yml
 * (pyrofishing-integration.enabled / .anchor-hook-chance) and passed in
 * via {@link #tryRegister}, so this can be tuned or disabled without a
 * rebuild.
 * <p>
 * IMPORTANT CAVEAT: PyroFishCatchEvent is not Cancellable, and I don't
 * have visibility into whether Pyro reads getItemStack() again after
 * firing the event or already committed to handing it over. The
 * suppression attempt below (zeroing the stack) is a best-effort guess,
 * untested against the real plugin -- if it doesn't work, players will
 * just get both the Pyro item and the live-mob bonus, which is a safe
 * failure mode, not a broken one. Confirm in-game and simplify this
 * class if suppression turns out not to do anything.
 */
public final class PyroFishingIntegration implements Listener {

    private static final String PLUGIN_NAME = "PyroFishingPro";
    private static final String EVENT_CLASS_NAME = "me.arsmagica.API.PyroFishCatchEvent";

    private static final EntityType[] LIVE_FISH = {
            EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH
    };

    /** @return true if PyroFishingPro was found and the integration registered. */
    public static boolean tryRegister(Plugin plugin, FishingRodFactory rods, boolean enabled, double triggerChance) {
        if (!enabled) {
            return false;
        }
        Plugin pyro = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (pyro == null || !pyro.isEnabled()) {
            return false;
        }

        Class<?> eventClass;
        try {
            eventClass = pyro.getClass().getClassLoader().loadClass(EVENT_CLASS_NAME);
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning(PLUGIN_NAME + " is installed but " + EVENT_CLASS_NAME
                    + " couldn't be found -- skipping the integration. (" + e.getMessage() + ")");
            return false;
        }
        if (!Event.class.isAssignableFrom(eventClass)) {
            plugin.getLogger().warning(EVENT_CLASS_NAME + " isn't an Event -- skipping the integration.");
            return false;
        }

        PyroFishingIntegration integration = new PyroFishingIntegration(rods, triggerChance);
        @SuppressWarnings("unchecked")
        Class<? extends Event> typedEventClass = (Class<? extends Event>) eventClass;

        Bukkit.getPluginManager().registerEvent(
                typedEventClass,
                integration,
                EventPriority.NORMAL,
                (listener, event) -> integration.onPyroCatch(event),
                plugin,
                false
        );

        plugin.getLogger().info(PLUGIN_NAME + " detected -- anchor_hook live-fish bonus enabled.");
        return true;
    }

    private final FishingRodFactory rods;
    private final double triggerChance;
    private final Random random = new Random();

    private PyroFishingIntegration(FishingRodFactory rods, double triggerChance) {
        this.rods = rods;
        this.triggerChance = triggerChance;
    }

    private void onPyroCatch(Event event) {
        try {
            Player player = (Player) invoke(event, "getPlayer");
            ItemStack rod = player.getInventory().getItemInMainHand();
            if (!rods.isConstructed(rod) || !"reel:anchor_hook".equals(rods.hookOf(rod))) {
                return;
            }
            if (random.nextDouble() > triggerChance) {
                return;
            }

            // Best-effort, unconfirmed suppression of Pyro's own item -- see class doc.
            ItemStack pyroItem = (ItemStack) invoke(event, "getItemStack");
            if (pyroItem != null) {
                pyroItem.setAmount(0);
            }

            spawnAndFlingLiveFish(player);
        } catch (ReflectiveOperationException | ClassCastException e) {
            Bukkit.getLogger().warning("[ReelDeal] PyroFishingPro integration reflection call failed "
                    + "(likely an API shape change on their end): " + e);
        }
    }

    private void spawnAndFlingLiveFish(Player player) {
        Location loc = player.getEyeLocation();
        World world = loc.getWorld();
        if (world == null) {
            return;
        }
        EntityType type = LIVE_FISH[random.nextInt(LIVE_FISH.length)];
        Entity mob = world.spawnEntity(player.getLocation(), type);

        Vector toward = loc.getDirection().multiply(-1);
        toward.setY(0.3);
        mob.setVelocity(toward.multiply(0.6));
    }

    private Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }
}
