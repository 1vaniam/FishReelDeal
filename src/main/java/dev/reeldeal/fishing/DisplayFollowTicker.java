package dev.reeldeal.fishing;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Shares one repeating task across every active fish and barrel display. */
public final class DisplayFollowTicker {

    private static final class Follow {
        private final Entity target;
        private final Entity display;
        private final Float yaw;
        private final int actionPeriod;
        private final Runnable periodicAction;
        private final Runnable onInvalid;
        private Location lastLocation;
        private int ticks;

        private Follow(Entity target, Entity display, Float yaw, int actionPeriod,
                       Runnable periodicAction, Runnable onInvalid) {
            this.target = target;
            this.display = display;
            this.yaw = yaw;
            this.actionPeriod = actionPeriod;
            this.periodicAction = periodicAction;
            this.onInvalid = onInvalid;
            this.lastLocation = display.getLocation();
        }
    }

    private final Plugin plugin;
    private final Map<UUID, Follow> followers = new HashMap<>();
    private BukkitTask task;

    public DisplayFollowTicker(Plugin plugin) {
        this.plugin = plugin;
    }

    public void track(Entity target, Entity display, Float yaw, int actionPeriod,
                      Runnable periodicAction, Runnable onInvalid) {
        UUID id = target.getUniqueId();
        followers.put(id, new Follow(target, display, yaw, actionPeriod, periodicAction, onInvalid));
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        }
    }

    public void untrack(UUID targetId) {
        followers.remove(targetId);
        stopIfEmpty();
    }

    public void clear() {
        followers.clear();
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Follow>> iterator = followers.entrySet().iterator();
        while (iterator.hasNext()) {
            Follow follow = iterator.next().getValue();
            if (!follow.target.isValid() || !follow.display.isValid()) {
                iterator.remove();
                follow.onInvalid.run();
                continue;
            }

            Location location = follow.target.getLocation();
            if (follow.yaw != null) {
                location.setYaw(follow.yaw);
            }
            location.setPitch(0f);
            if (!sameLocation(follow.lastLocation, location)) {
                follow.display.teleport(location);
                follow.lastLocation = location.clone();
            }

            if (follow.actionPeriod > 0 && ++follow.ticks % follow.actionPeriod == 0
                    && follow.periodicAction != null) {
                follow.periodicAction.run();
            }
        }
        stopIfEmpty();
    }

    private boolean sameLocation(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && a.getX() == b.getX()
                && a.getY() == b.getY()
                && a.getZ() == b.getZ()
                && a.getYaw() == b.getYaw()
                && a.getPitch() == b.getPitch();
    }

    private void stopIfEmpty() {
        if (!followers.isEmpty() || task == null) {
            return;
        }
        task.cancel();
        task = null;
    }
}
