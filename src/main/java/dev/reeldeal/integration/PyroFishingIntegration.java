package dev.reeldeal.integration;

import dev.reeldeal.fishing.HookedFishManager;
import dev.reeldeal.quest.QuestManager;
import dev.reeldeal.item.FishingRodFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
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
    private static final long PENDING_REWARD_TTL_TICKS = 20L;

    private static final EntityType[] LIVE_FISH = {
            EntityType.COD, EntityType.SALMON, EntityType.PUFFERFISH, EntityType.TROPICAL_FISH
    };

    public static boolean tryRegister(Plugin plugin, FishingRodFactory rods, boolean enabled, double triggerChance,
                                      boolean replaceCatch, boolean debug, HookedFishManager hooked, QuestManager quests) {
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

        PyroFishingIntegration integration = new PyroFishingIntegration(plugin, rods, triggerChance, replaceCatch,
                debug, hooked, quests);
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

        Bukkit.getPluginManager().registerEvents(integration, plugin);

        plugin.getLogger().info(PLUGIN_NAME + " detected -- ReelDeal catches can use hooked-fish models.");
        return true;
    }

    private static volatile PyroFishingIntegration instance;

    private static final class PendingReward {
        private final UUID id = UUID.randomUUID();
        private final Player player;
        private final ItemStack fish;
        private final int inventoryBefore;
        private final Location origin;
        private final Set<UUID> spawnedDrops = new HashSet<>();

        private PendingReward(Player player, ItemStack fish, int inventoryBefore) {
            this.player = player;
            this.fish = fish;
            this.inventoryBefore = inventoryBefore;
            this.origin = player.getLocation().clone();
        }
    }

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
    private final Map<UUID, PendingReward> pendingRewards = new java.util.HashMap<>();
    private final HookedFishManager hooked;
    private final QuestManager quests;
    private final Plugin plugin;
    private final FishingRodFactory rods;
    private final double triggerChance;
    private final boolean replaceCatch;
    private final boolean debug;
    private final Random random = new Random();

    private PyroFishingIntegration(Plugin plugin, FishingRodFactory rods, double triggerChance,
                                   boolean replaceCatch, boolean debug, HookedFishManager hooked, QuestManager quests) {
        this.hooked = hooked;
        this.quests = quests;
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
            Object pyroTier = null;
            String tierKey = null;
            try {
                pyroTier = invoke(event, "getTier");
                tierKey = tierKey(pyroTier);
            } catch (ReflectiveOperationException missingTierMethod) {
                log("PyroFishing catch event has no getTier() method; tier quest progress is unavailable.");
            }
            if (quests != null && tierKey != null) quests.recordPyroTierCatch(player, tierKey);
            ItemStack rod = player.getInventory().getItemInMainHand();
            boolean reelRod = rods.isConstructed(rod);
            boolean anchorHook = reelRod && "reel:anchor_hook".equals(rods.hookOf(rod));
            String hookId = reelRod ? strip(rods.hookOf(rod)) : null;
            if ("xp_hook".equals(hookId)
                    && plugin.getConfig().getBoolean("pyrofishing-integration.enabled", true)) {
                applyTierScaledXp(player, pyroTier);
            }
            String model = replaceCatch && hooked.enabled() ? hooked.modelFor(fish) : null;
            Location catchLocation = hooked.catchLocation(player);
            boolean suppressThroughEvent = model != null && catchLocation != null;
            if (model != null && catchLocation == null) {
                log("No bobber location was captured for " + fish.getType()
                        + "; leaving the Pyro reward untouched rather than spawning the model at the player.");
            }
            boolean eventSuppressed = suppressThroughEvent && suppressRewardThroughEvent(event, pyroItem);
            // Only take an inventory baseline when the event cannot suppress a replacement,
            // or when a non-model catch could still become a barrel during the short correlation window.
            boolean needsCleanupFallback = !eventSuppressed
                    && (suppressThroughEvent || barrelCatchCanOccur(hookId));
            int inventoryBefore = needsCleanupFallback ? countSimilar(player, fish) : -1;
            PendingReward pendingReward = needsCleanupFallback
                    ? beginPendingReward(player, fish, inventoryBefore) : null;
            if (suppressThroughEvent) {
                // Capture the bobber now; the hook may be gone by the time the delayed spawn runs.
                log("Requested Pyro reward suppression for " + fish.getType() + ".");
            }

            // Delay the decision briefly so PlayerFishEvent can mark a barrel catch first.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Long barrelAt = lastBarrelCatch.get(player.getUniqueId());
                if (barrelAt != null && barrelAt >= caughtAt - 250) {
                    if (!suppressThroughEvent && pendingReward != null) {
                        suppressNewReward(pendingReward);
                    }
                    lastBarrelCatch.remove(player.getUniqueId(), barrelAt);
                    return;
                }
                if (!suppressThroughEvent && pendingReward != null) {
                    finishPendingReward(pendingReward);
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
            if (suppressThroughEvent && !eventSuppressed && needsCleanupFallback) {
                suppressNewReward(pendingReward);
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            Bukkit.getLogger().warning("[ReelDeal] PyroFishingPro integration reflection call failed "
                    + "(likely an API shape change on their end): " + e);
        }
    }

    /** The vanilla fishing event already gives the XP Hook's base bonus; add only the tier difference. */
    private void applyTierScaledXp(Player player, Object pyroTier) {
        String tierKey = tierKey(pyroTier);
        if (tierKey == null) {
            log("Couldn't read PyroFishing tier for XP Hook bonus; using its base bonus only.");
            return;
        }
        int baseBonus = Math.max(0, plugin.getConfig().getInt("fishing.xp-hook.bonus", 3));
        int scaledBonus = Math.max(baseBonus,
                plugin.getConfig().getInt("fishing.xp-hook.pyrofishing-tier-bonuses." + tierKey, baseBonus));
        int extra = scaledBonus - baseBonus;
        if (extra > 0) {
            // Run after PlayerFishEvent listeners so its configured base bonus is already included.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) {
                    player.giveExp(extra);
                }
            });
        }
    }

    private String tierKey(Object tier) {
        if (tier == null) return null;
        String value = String.valueOf(tier).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        String[] tiers = {"bronze", "silver", "gold", "diamond", "platinum", "mythical"};
        for (String name : tiers) {
            if (value.contains(name)) return name;
        }
        if (tier instanceof Number number) {
            int index = number.intValue();
            if (index == 0) return tiers[0];
            if (index >= 1 && index <= tiers.length) return tiers[index - 1];
        }
        return null;
    }

    /** Prefer Pyro's own event controls so common model catches need no inventory/entity scans. */
    private boolean suppressRewardThroughEvent(Event event, ItemStack reward) {
        reward.setAmount(0);
        boolean suppressed = false;
        if (event instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
            suppressed |= cancellable.isCancelled();
        }
        try {
            Method setter = event.getClass().getMethod("setItemStack", ItemStack.class);
            setter.invoke(event, reward);
            suppressed = true;
        } catch (NoSuchMethodException ignored) {
            // Some versions expose only the mutable ItemStack returned by getItemStack().
        } catch (ReflectiveOperationException setterFailure) {
            log("Pyro event reward setter failed: " + setterFailure.getMessage());
        }
        if (!suppressed) {
            try {
                Object current = invoke(event, "getItemStack");
                suppressed = current instanceof ItemStack stack && stack.getAmount() <= 0;
            } catch (ReflectiveOperationException ignored) {
                // The caller will take the exact cleanup fallback snapshot.
            }
        }
        return suppressed;
    }

    private boolean barrelCatchCanOccur(String hookId) {
        if (!plugin.getConfig().getBoolean("barrel-catch.enabled", true)) {
            return false;
        }
        String hookKey = hookId == null ? "normal" : hookId;
        double baseChance = plugin.getConfig().getDouble("barrel-catch.base-chance", 0.01);
        double multiplier = plugin.getConfig().getDouble("barrel-catch.hook-multipliers." + hookKey, 1.0);
        return baseChance > 0.0 && multiplier > 0.0;
    }

    private String strip(String reelId) {
        return reelId != null && reelId.startsWith("reel:") ? reelId.substring(5) : reelId;
    }

    private PendingReward beginPendingReward(Player player, ItemStack fish, int inventoryBefore) {
        PendingReward pending = new PendingReward(player, fish, inventoryBefore);
        pendingRewards.put(pending.id, pending);
        Bukkit.getScheduler().runTaskLater(plugin, () -> finishPendingReward(pending), PENDING_REWARD_TTL_TICKS);
        return pending;
    }

    private void finishPendingReward(PendingReward pending) {
        pendingRewards.remove(pending.id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item drop = event.getEntity();
        ItemStack stack = drop.getItemStack();
        if (stack.getType().isAir() || pendingRewards.isEmpty()) {
            return;
        }
        Location location = event.getLocation();
        for (PendingReward pending : pendingRewards.values()) {
            if (pending.fish.isSimilar(stack)
                    && pending.origin.getWorld() == location.getWorld()
                    && pending.origin.distanceSquared(location) <= 64.0) {
                pending.spawnedDrops.add(drop.getUniqueId());
            }
        }
    }

    // Pyro's reward is normally delivered immediately after its catch event. Keep a short
    // inventory-check window for versions that ignore event cancellation or stack mutation;
    // matching dropped rewards are tracked from ItemSpawnEvent instead of nearby-entity scans.
    private static final int SUPPRESS_TRIES = 4;

    /** Remove only the matching reward added by this catch, preserving identical items already owned. */
    private void suppressNewReward(PendingReward pending) {
        new BukkitRunnable() {
            private int tries;
            private int removed;

            @Override
            public void run() {
                tries++;
                Player player = pending.player;
                ItemStack pyroStack = pending.fish;
                if (!player.isOnline()) {
                    finishPendingReward(pending);
                    cancel();
                    return;
                }
                int excess = Math.max(0, countSimilar(player, pyroStack) - pending.inventoryBefore);
                int removeFromInventory = Math.min(pyroStack.getAmount() - removed, excess);
                if (removeFromInventory > 0) {
                    removed += removeSimilar(player, pyroStack, removeFromInventory);
                }

                for (UUID dropId : pending.spawnedDrops) {
                    if (removed >= pyroStack.getAmount()) {
                        break;
                    }
                    Entity entity = Bukkit.getEntity(dropId);
                    if (entity instanceof Item drop && drop.isValid()
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
                    }
                }

                if (removed >= pyroStack.getAmount()) {
                    log("Pyro reward removed (" + pyroStack.getType() + ", try " + tries + ")");
                    finishPendingReward(pending);
                    cancel();
                } else if (tries >= SUPPRESS_TRIES) {
                    log("No new Pyro reward found to remove for " + pyroStack.getType()
                            + " after " + tries + " ticks; kept existing matching items.");
                    finishPendingReward(pending);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 2L);
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
