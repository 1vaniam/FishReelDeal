package dev.reeldeal.integration;

import dev.reeldeal.fishing.HookedFishManager;
import dev.reeldeal.item.FishingRodFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PyroFishingIntegration implements Listener {

    private static final String PLUGIN_NAME = "PyroFishingPro";
    private static final String EVENT_CLASS_NAME = "me.arsmagica.API.PyroFishCatchEvent";
    private static final long CATCH_CORRELATION_WINDOW_NANOS = 50_000_000L;

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
                    EventPriority.HIGHEST,
                (listener, event) -> integration.onPyroCatch(event),
                plugin,
                false
        );

        plugin.getLogger().info(PLUGIN_NAME + " detected -- ReelDeal catches can use hooked-fish models.");
        return true;
    }

    private static volatile PyroFishingIntegration instance;

    public static boolean replacingPyroCatches() {
        PyroFishingIntegration i = instance;
        return i != null && i.replaceCatch && i.hooked.enabled();
    }

    public static boolean consumeCatchMarker(UUID player, long fishingEventStartedNanos) {
        PyroFishingIntegration i = instance;
        if (i == null) {
            return false;
        }
        Long last = i.catchMarkers.remove(player);
        return last != null && last >= fishingEventStartedNanos - CATCH_CORRELATION_WINDOW_NANOS;
    }

    public static void markBarrelCatch(UUID player, long millis) {
        PyroFishingIntegration i = instance;
        if (i != null) {
            i.lastBarrelCatch.put(player, millis);
        }
    }

    private final Map<UUID, Long> catchMarkers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBarrelCatch = new ConcurrentHashMap<>();
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
            long caughtAt = System.currentTimeMillis();
            ItemStack pyroItem = (ItemStack) invoke(event, "getItemStack");
            if (pyroItem == null || pyroItem.getType().isAir()) {
                return;
            }
            UUID playerId = player.getUniqueId();
            long catchMarker = System.nanoTime();
            catchMarkers.put(playerId, catchMarker);
            Bukkit.getScheduler().runTaskLater(plugin, () -> catchMarkers.remove(playerId, catchMarker), 20L);
            ItemStack fish = pyroItem.clone();
            int inventoryBefore = countSimilar(player, fish);
            Set<UUID> nearbyDropsBefore = nearbySimilarDrops(player, fish);
            ItemStack rod = player.getInventory().getItemInMainHand();
            boolean reelRod = rods.isConstructed(rod);
            boolean anchorHook = reelRod && "reel:anchor_hook".equals(rods.hookOf(rod));
            String model = replaceCatch && hooked.enabled() ? hooked.modelFor(fish) : null;
            Location catchLocation = hooked.catchLocation(player);
            boolean suppressThroughEvent = model != null && catchLocation != null;
            log("Pyro catch observed: player=" + player.getName() + ", item=" + fish.getType()
                    + " x" + fish.getAmount() + ", reelRod=" + reelRod + ", anchorHook=" + anchorHook
                    + ", model=" + model + ", bobberLocation=" + (catchLocation != null)
                    + ", inventorySimilarBefore=" + inventoryBefore + ", preexistingNearbyDrops="
                    + nearbyDropsBefore.size() + ", suppressThroughEvent=" + suppressThroughEvent + ".");
            if (model != null && catchLocation == null) {
                log("No bobber location was captured for " + fish.getType()
                        + "; leaving the Pyro reward untouched rather than spawning the model at the player.");
            }
            if (suppressThroughEvent) {
                // Capture the bobber now; the hook may be gone by the time the delayed spawn runs.
                pyroItem.setAmount(0);
                if (event instanceof Cancellable cancellable) {
                    cancellable.setCancelled(true);
                }
                // Some API versions expose a setter while others expose only the mutable stack.
                try {
                    Method setter = event.getClass().getMethod("setItemStack", ItemStack.class);
                    setter.invoke(event, pyroItem);
                } catch (NoSuchMethodException ignored) {
                    // Fall back to the mutable result and exact post-event cleanup below.
                } catch (ReflectiveOperationException setterFailure) {
                    log("Pyro event reward setter failed: " + setterFailure.getMessage());
                }
                log("Requested Pyro reward suppression for " + fish.getType() + ".");
            }

            // Delay the decision briefly so PlayerFishEvent can mark a barrel catch first.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Long barrelAt = lastBarrelCatch.get(player.getUniqueId());
                if (barrelAt != null && barrelAt >= caughtAt - 250) {
                    if (!suppressThroughEvent) {
                        suppressNewReward(player, fish, inventoryBefore, nearbyDropsBefore);
                    }
                    lastBarrelCatch.remove(player.getUniqueId(), barrelAt);
                    return;
                }
                if (replaceCatch && hooked.enabled()) {
                    if (suppressThroughEvent) {
                        hooked.spawn(player, fish, model, anchorHook, catchLocation);
                    } else if (model == null) {
                        log("No hooked model for Pyro fish: " + fish.getType() + " -- left as is.");
                    }
                    return;
                }

                // The chance-based live-fish behavior remains exclusive to anchor_hook rods.
                if (reelRod && anchorHook && random.nextDouble() <= triggerChance) {
                    spawnAndFlingLiveFish(player);
                }
            }, 3L);
            if (suppressThroughEvent) {
                suppressNewReward(player, fish, inventoryBefore, nearbyDropsBefore);
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            Bukkit.getLogger().warning("[ReelDeal] PyroFishingPro integration reflection call failed "
                    + "(likely an API shape change on their end): " + e);
        }
    }

    private static final int SUPPRESS_TRIES = 40;

    /** Remove only the matching reward added by this catch, preserving identical items already owned. */
    private void suppressNewReward(Player player, ItemStack pyroStack, int inventoryBefore,
                                   Set<UUID> nearbyDropsBefore) {
        log("Starting Pyro reward cleanup: player=" + player.getName() + ", item=" + pyroStack.getType()
                + " x" + pyroStack.getAmount() + ", inventoryBaseline=" + inventoryBefore
                + ", protectedNearbyDrops=" + nearbyDropsBefore.size() + ".");
        new BukkitRunnable() {
            private int tries;
            private int removed;
            private int removedFromInventory;
            private int removedFromDrops;

            @Override
            public void run() {
                tries++;
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                int excess = Math.max(0, countSimilar(player, pyroStack) - inventoryBefore);
                int removeFromInventory = Math.min(pyroStack.getAmount() - removed, excess);
                if (removeFromInventory > 0) {
                    int removedNow = removeSimilar(player, pyroStack, removeFromInventory);
                    removed += removedNow;
                    removedFromInventory += removedNow;
                }

                for (Entity e : player.getNearbyEntities(8, 8, 8)) {
                    if (removed >= pyroStack.getAmount()) {
                        break;
                    }
                    if (e instanceof Item drop && !nearbyDropsBefore.contains(drop.getUniqueId())
                            && drop.getItemStack().isSimilar(pyroStack)) {
                        int take = Math.min(pyroStack.getAmount() - removed, drop.getItemStack().getAmount());
                        ItemStack stack = drop.getItemStack();
                        if (take >= stack.getAmount()) {
                            drop.remove();
                        } else {
                            stack.setAmount(stack.getAmount() - take);
                            drop.setItemStack(stack);
                        }
                        removed += take;
                        removedFromDrops += take;
                    }
                }

                if (removed >= pyroStack.getAmount()) {
                    log("Pyro reward removed: player=" + player.getName() + ", item=" + pyroStack.getType()
                            + ", total=" + removed + ", inventory=" + removedFromInventory
                            + ", nearbyDrops=" + removedFromDrops + ", tries=" + tries + ".");
                    cancel();
                } else if (tries >= SUPPRESS_TRIES) {
                    log("Pyro reward cleanup incomplete: player=" + player.getName() + ", item="
                            + pyroStack.getType() + ", expected=" + pyroStack.getAmount() + ", removed=" + removed
                            + " (inventory=" + removedFromInventory + ", nearbyDrops=" + removedFromDrops
                            + ") after " + tries + " ticks; kept existing matching items.");
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private int countSimilar(Player player, ItemStack expected) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.isSimilar(expected)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int removeSimilar(Player player, ItemStack expected, int amount) {
        int remaining = amount;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !item.isSimilar(expected)) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            remaining -= take;
            if (take == item.getAmount()) {
                inventory.setItem(slot, null);
            } else {
                item.setAmount(item.getAmount() - take);
            }
        }
        return amount - remaining;
    }

    private Set<UUID> nearbySimilarDrops(Player player, ItemStack expected) {
        Set<UUID> result = new HashSet<>();
        for (Entity e : player.getNearbyEntities(8, 8, 8)) {
            if (e instanceof Item drop && drop.getItemStack().isSimilar(expected)) {
                result.add(drop.getUniqueId());
            }
        }
        return result;
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
