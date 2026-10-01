package dev.reeldeal.integration;

import dev.reeldeal.fishing.HookedFishManager;
import dev.reeldeal.item.FishingRodFactory;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PyroFishingIntegration implements Listener {

    private static final String PLUGIN_NAME = "PyroFishingPro";
    private static final String EVENT_CLASS_NAME = "me.arsmagica.API.PyroFishCatchEvent";

    private static final EntityType[] LIVE_FISH = {
            EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH
    };

    public static boolean tryRegister(Plugin plugin, FishingRodFactory rods, boolean enabled, double triggerChance,
                                      boolean replaceCatch, boolean debug, HookedFishManager hooked) {
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

        PyroFishingIntegration integration = new PyroFishingIntegration(plugin, rods, triggerChance, replaceCatch, debug, hooked);
        instance = integration;
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

    private static volatile PyroFishingIntegration instance;

    public static boolean replacingAnchorCatches() {
        PyroFishingIntegration i = instance;
        return i != null && i.replaceCatch && i.hooked.enabled();
    }

    public static boolean caughtSince(UUID player, long millis) {
        PyroFishingIntegration i = instance;
        Long last = i != null ? i.lastCatch.get(player) : null;
        return last != null && last >= millis;
    }

    private final Map<UUID, Long> lastCatch = new ConcurrentHashMap<>();
    private final HookedFishManager hooked;
    private final Plugin plugin;
    private final FishingRodFactory rods;
    private final double triggerChance;
    private final boolean replaceCatch;
    private final boolean debug;
    private final Random random = new Random();

    private PyroFishingIntegration(Plugin plugin, FishingRodFactory rods, double triggerChance,
                                   boolean replaceCatch, boolean debug, HookedFishManager hooked) {
        this.hooked = hooked;
        this.plugin = plugin;
        this.rods = rods;
        this.triggerChance = triggerChance;
        this.replaceCatch = replaceCatch;
        this.debug = debug;
    }

    private void onPyroCatch(Event event) {
        try {
            Player player = (Player) invoke(event, "getPlayer");
            lastCatch.put(player.getUniqueId(), System.currentTimeMillis());
            ItemStack rod = player.getInventory().getItemInMainHand();
            if (!rods.isConstructed(rod) || !"reel:anchor_hook".equals(rods.hookOf(rod))) {
                return;
            }

            if (replaceCatch && hooked.enabled()) {
                ItemStack pyroItem = (ItemStack) invoke(event, "getItemStack");
                if (pyroItem != null && !pyroItem.getType().isAir()) {
                    String model = hooked.modelFor(pyroItem);
                    if (model != null) {
                        ItemStack fish = pyroItem.clone();
                        hooked.spawn(player, fish, model);
                        suppressLater(player, fish);
                    } else {
                        log("No hooked model for Pyro fish: " + pyroItem.getType() + " -- left as is.");
                    }
                }
                return;
            }

            if (random.nextDouble() > triggerChance) {
                return;
            }
            spawnAndFlingLiveFish(player);
        } catch (ReflectiveOperationException | ClassCastException e) {
            Bukkit.getLogger().warning("[ReelDeal] PyroFishingPro integration reflection call failed "
                    + "(likely an API shape change on their end): " + e);
        }
    }

    private static final int SUPPRESS_TRIES = 20;

    private void suppressLater(Player player, ItemStack pyroStack) {
        final String name = plainName(pyroStack);
        new BukkitRunnable() {
            private int tries;
            private int remaining = pyroStack.getAmount();

            @Override
            public void run() {
                tries++;
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                ItemStack exact = pyroStack.clone();
                exact.setAmount(remaining);
                var leftover = player.getInventory().removeItem(exact);
                remaining = leftover.isEmpty() ? 0 : leftover.values().iterator().next().getAmount();

                var inv = player.getInventory();
                for (int slot = 0; slot < inv.getSize() && remaining > 0; slot++) {
                    ItemStack it = inv.getItem(slot);
                    if (it != null && it.getType() == pyroStack.getType() && plainName(it).equals(name)) {
                        int take = Math.min(remaining, it.getAmount());
                        remaining -= take;
                        if (take >= it.getAmount()) {
                            inv.setItem(slot, null);
                        } else {
                            it.setAmount(it.getAmount() - take);
                        }
                    }
                }

                if (remaining > 0) {
                    for (Entity e : player.getNearbyEntities(8, 8, 8)) {
                        if (remaining <= 0) {
                            break;
                        }
                        if (e instanceof Item drop && drop.getTicksLived() < 60
                                && drop.getItemStack().getType() == pyroStack.getType()
                                && plainName(drop.getItemStack()).equals(name)) {
                            ItemStack st = drop.getItemStack();
                            int take = Math.min(remaining, st.getAmount());
                            remaining -= take;
                            if (take >= st.getAmount()) {
                                drop.remove();
                            } else {
                                st.setAmount(st.getAmount() - take);
                                drop.setItemStack(st);
                            }
                        }
                    }
                }

                if (remaining <= 0) {
                    log("Pyro reward removed (" + pyroStack.getType() + ", try " + tries + ")");
                    cancel();
                } else if (tries >= SUPPRESS_TRIES) {
                    plugin.getLogger().warning("Couldn't find Pyro's reward to remove ("
                            + pyroStack.getType() + " '" + name + "' x" + pyroStack.getAmount()
                            + ") after " + tries + " ticks.");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static String plainName(ItemStack stack) {
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return "";
        }
        Component c = meta.hasDisplayName() ? meta.displayName()
                : meta.hasItemName() ? meta.itemName() : null;
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    private void log(String msg) {
        if (debug) {
            plugin.getLogger().info(msg);
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
