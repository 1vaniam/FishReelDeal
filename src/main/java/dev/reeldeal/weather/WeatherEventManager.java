package dev.reeldeal.weather;

import dev.reeldeal.ReelKeys;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Schedules global weather events and exposes forecast and interference state to the Weather Radio. */
public final class WeatherEventManager {

    private static final String ACID_RAIN = "acid-rain";
    private static final String POLLEN_DRIFT = "pollen-drift";
    private static final String ASHFALL = "ashfall";
    private static final String COLD_SNAP = "cold-snap";
    private static final String DUST_HAZE = "dust-haze";
    private static final String FIREFLY_NIGHT = "firefly-night";
    private static final String METEOR_DUST = "meteor-dust";
    private static final String SPORE_BLOOM = "spore-bloom";
    private static final String MAGNETIC_DISTURBANCE = "magnetic-disturbance";
    private static final String FROSTWAKE = "frostwake";
    private static final List<String> EVENT_IDS = List.of(ACID_RAIN, POLLEN_DRIFT, ASHFALL, COLD_SNAP,
            DUST_HAZE, FIREFLY_NIGHT, METEOR_DUST, SPORE_BLOOM, MAGNETIC_DISTURBANCE, FROSTWAKE);

    private final Plugin plugin;
    private final Map<UUID, WeatherSnapshot> previousWeather = new HashMap<>();
    private final Set<UUID> warnedPlayers = new HashSet<>();
    private String activeEvent;
    private String upcomingEvent;
    private long eventEndsAtTick;
    private long nextActivationCheckTick;
    private long nextEffectTick;
    private long upcomingEventStartTick = -1L;
    private boolean testCountdown;
    private int lastTestCountdownAnnouncement = -1;

    public WeatherEventManager(Plugin plugin) {
        this.plugin = plugin;
        this.nextActivationCheckTick = Bukkit.getCurrentTick() + activationCheckIntervalTicks();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void tick() {
        long now = Bukkit.getCurrentTick();
        if (activeEvent != null) {
            if (now >= eventEndsAtTick) {
                endEvent();
                return;
            }
            if (ACID_RAIN.equals(activeEvent)) {
                keepNormalRain();
            }
            if (now >= nextEffectTick) {
                playEventCue(activeEvent);
                nextEffectTick = now + effectCheckIntervalTicks(activeEvent);
            }
            return;
        }

        if (upcomingEvent != null) {
            if (testCountdown) {
                announceTestCountdown(now);
            } else {
                warnPlayersAboutUpcomingEvent(now);
            }
            if (now >= upcomingEventStartTick) {
                startEvent(upcomingEvent);
            }
            return;
        }

        if (now < nextActivationCheckTick) {
            return;
        }
        nextActivationCheckTick = now + activationCheckIntervalTicks();
        if (ThreadLocalRandom.current().nextDouble() >= activationChance()) {
            return;
        }

        String selected = pickWeightedEvent();
        if (selected == null) {
            return;
        }
        upcomingEvent = selected;
        upcomingEventStartTick = now + warningLeadTicks();
        testCountdown = false;
        warnedPlayers.clear();
        if (upcomingEventStartTick <= now) {
            startEvent(selected);
        } else {
            warnPlayersAboutUpcomingEvent(now);
        }
    }

    private String pickWeightedEvent() {
        List<WeightedEvent> enabled = new ArrayList<>();
        double totalWeight = 0.0;
        for (String id : EVENT_IDS) {
            String path = eventConfigPath(id);
            if (!plugin.getConfig().getBoolean(path + "enabled", true)) continue;
            double weight = Math.max(0.0, plugin.getConfig().getDouble(path + "weight", 1.0));
            if (weight == 0.0) continue;
            enabled.add(new WeightedEvent(id, weight));
            totalWeight += weight;
        }
        if (enabled.isEmpty() || totalWeight <= 0.0) return null;

        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (WeightedEvent event : enabled) {
            roll -= event.weight;
            if (roll < 0.0) return event.id;
        }
        return enabled.get(enabled.size() - 1).id;
    }

    private void startEvent(String eventId) {
        upcomingEvent = null;
        upcomingEventStartTick = -1L;
        testCountdown = false;
        lastTestCountdownAnnouncement = -1;
        warnedPlayers.clear();
        activeEvent = eventId;
        long now = Bukkit.getCurrentTick();
        eventEndsAtTick = now + durationTicks(eventId);
        nextEffectTick = now + effectCheckIntervalTicks(eventId);
        if (ACID_RAIN.equals(eventId)) {
            keepNormalRain();
        }
        plugin.getLogger().info("Global weather event started: " + displayName(eventId) + ".");
    }

    private void endEvent() {
        String ended = activeEvent;
        if (ACID_RAIN.equals(ended)) {
            restoreWeather();
        }
        activeEvent = null;
        nextActivationCheckTick = Bukkit.getCurrentTick() + activationCheckIntervalTicks();
        plugin.getLogger().info("Global weather event ended: " + displayName(ended) + ".");
    }

    private void warnPlayersAboutUpcomingEvent(long now) {
        long remainingTicks = Math.max(0L, upcomingEventStartTick - now);
        long remainingMinutes = (remainingTicks + 1199L) / 1200L;
        String time = remainingMinutes + (remainingMinutes == 1 ? " minute" : " minutes");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasWeatherRadioInHotbar(player) && warnedPlayers.add(player.getUniqueId())) {
                player.sendMessage(Component.text("Weather Radio: " + displayName(upcomingEvent)
                        + " is expected in " + time + ".", NamedTextColor.YELLOW));
            }
        }
    }

    private boolean hasWeatherRadioInHotbar(Player player) {
        for (int slot = 0; slot < 9; slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack == null || !stack.hasItemMeta()) continue;
            String id = stack.getItemMeta().getPersistentDataContainer()
                    .get(ReelKeys.IDENTIFIER, org.bukkit.persistence.PersistentDataType.STRING);
            if ("reel:weather_radio".equals(id)) return true;
        }
        return false;
    }

    private void keepNormalRain() {
        int remainingTicks = (int) Math.min(Integer.MAX_VALUE,
                Math.max(1L, eventEndsAtTick - Bukkit.getCurrentTick()));
        for (World world : Bukkit.getWorlds()) {
            previousWeather.computeIfAbsent(world.getUID(), ignored -> new WeatherSnapshot(world));
            if (!world.hasStorm()) {
                world.setStorm(true);
                world.setWeatherDuration(remainingTicks);
            }
            if (world.isThundering()) {
                world.setThundering(false);
                world.setThunderDuration(remainingTicks);
            }
        }
    }

    private void restoreWeather() {
        long now = Bukkit.getCurrentTick();
        for (World world : Bukkit.getWorlds()) {
            WeatherSnapshot snapshot = previousWeather.get(world.getUID());
            if (snapshot == null) continue;
            int elapsedTicks = (int) Math.min(Integer.MAX_VALUE,
                    Math.max(0L, now - snapshot.capturedAtTick));
            world.setStorm(snapshot.storming);
            world.setThundering(snapshot.thundering);
            world.setWeatherDuration(remainingWeatherTicks(snapshot.weatherDuration, elapsedTicks));
            world.setThunderDuration(remainingWeatherTicks(snapshot.thunderDuration, elapsedTicks));
        }
        previousWeather.clear();
    }

    private int remainingWeatherTicks(int originalTicks, int elapsedTicks) {
        return Math.max(1, originalTicks - elapsedTicks);
    }

    private void playEventCue(String eventId) {
        String path = eventConfigPath(eventId);
        double chance = clamp(plugin.getConfig().getDouble(path + "effect-chance-per-check", 0.2), 0.0, 1.0);
        String soundId = plugin.getConfig().getString(path + "sound", defaultSound(eventId));
        float volume = (float) clamp(plugin.getConfig().getDouble(path + "sound-volume", 0.1), 0.0, 1.0);
        float pitch = (float) clamp(plugin.getConfig().getDouble(path + "sound-pitch", 1.0), 0.5, 2.0);
        int particles = Math.max(0, plugin.getConfig().getInt(path + "particle-count", 4));
        Particle particle = configuredParticle(path + "particle", eventId);
        double height = plugin.getConfig().getDouble(path + "particle-height", METEOR_DUST.equals(eventId) ? 10.0 : 1.5);
        double spread = Math.max(0.0, plugin.getConfig().getDouble(path + "particle-spread", METEOR_DUST.equals(eventId) ? 0.8 : 0.35));
        double verticalSpread = Math.max(0.0, plugin.getConfig().getDouble(path + "particle-vertical-spread", METEOR_DUST.equals(eventId) ? 0.0 : spread));
        double particleSpeed = Math.max(0.0, plugin.getConfig().getDouble(path + "particle-speed", 0.02));
        boolean requireSky = plugin.getConfig().getBoolean(path + "require-sky-exposure", true);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if ((requireSky && !isExposedToSky(player)) || ThreadLocalRandom.current().nextDouble() >= chance) continue;
            var location = player.getLocation();
            if (soundId != null && !soundId.isBlank() && volume > 0.0f) {
                player.playSound(location, soundId, SoundCategory.AMBIENT, volume, pitch);
            }
            if (particle != null && particles > 0) {
                player.spawnParticle(particle, location.clone().add(0, height, 0),
                        particles, spread, verticalSpread, spread, particleSpeed);
            }
        }
    }

    private boolean isExposedToSky(Player player) {
        if (player.getEyeLocation().getBlock().isLiquid()) return false;
        return player.getLocation().getBlockY() > player.getWorld().getHighestBlockYAt(player.getLocation());
    }

    public boolean isEventActive() {
        return activeEvent != null;
    }

    public boolean isEventActive(String eventId) {
        return eventId != null && eventId.equalsIgnoreCase(activeEvent);
    }

    public boolean isWeatherRadioInterferenceActive() {
        return activeEvent != null && plugin.getConfig().getBoolean(
                eventConfigPath(activeEvent) + "weather-radio-interference", false);
    }

    public boolean isRadioInterferenceActive() {
        return isWeatherRadioInterferenceActive();
    }

    public String activeEventId() {
        return activeEvent;
    }

    public boolean isSupportedEvent(String eventId) {
        return eventId != null && EVENT_IDS.contains(eventId.toLowerCase(java.util.Locale.ROOT));
    }

    public List<String> eventIds() {
        return EVENT_IDS;
    }

    /** Schedules a global test event after a short countdown. */
    public boolean scheduleTestEvent(String eventId, int countdownSeconds) {
        String normalized = eventId == null ? "" : eventId.toLowerCase(java.util.Locale.ROOT);
        if (!isSupportedEvent(normalized) || activeEvent != null || countdownSeconds < 1) return false;

        upcomingEvent = normalized;
        upcomingEventStartTick = Bukkit.getCurrentTick() + countdownSeconds * 20L;
        testCountdown = true;
        lastTestCountdownAnnouncement = -1;
        warnedPlayers.clear();
        announceTestCountdown(Bukkit.getCurrentTick());
        return true;
    }

    /** Cancels a pending test event or ends the currently active event. */
    public boolean stopTestEvent() {
        if (activeEvent != null) {
            endEvent();
            return true;
        }
        if (upcomingEvent != null) {
            upcomingEvent = null;
            upcomingEventStartTick = -1L;
            testCountdown = false;
            lastTestCountdownAnnouncement = -1;
            warnedPlayers.clear();
            nextActivationCheckTick = Bukkit.getCurrentTick() + activationCheckIntervalTicks();
            return true;
        }
        return false;
    }

    private void announceTestCountdown(long now) {
        int seconds = (int) Math.max(0L, (upcomingEventStartTick - now + 19L) / 20L);
        if (seconds == lastTestCountdownAnnouncement) return;
        if (seconds != 10 && seconds != 5 && seconds != 3 && seconds != 2 && seconds != 1 && seconds != 0) return;
        lastTestCountdownAnnouncement = seconds;
        String message = seconds == 0
                ? "Weather event starting now: " + displayName(upcomingEvent) + "."
                : displayName(upcomingEvent) + " starts in " + seconds + (seconds == 1 ? " second." : " seconds.");
        Component notice = Component.text(message, NamedTextColor.YELLOW);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (hasWeatherRadioInHotbar(player)) {
                player.sendMessage(notice);
            }
        }
    }

    public String currentWeather(World world) {
        if (activeEvent != null) return displayName(activeEvent);
        return describeNormalWeather(world.hasStorm(), world.isThundering());
    }

    public String nextWeather(World world) {
        if (upcomingEvent != null) {
            long remainingTicks = Math.max(0L, upcomingEventStartTick - Bukkit.getCurrentTick());
            long remainingSeconds = (remainingTicks + 19L) / 20L;
            if (remainingSeconds == 0L) {
                return displayName(upcomingEvent) + " starting now";
            }
            if (remainingSeconds < 60L) {
                return displayName(upcomingEvent) + " in " + remainingSeconds
                        + (remainingSeconds == 1L ? " second" : " seconds");
            }
            long minutes = (remainingSeconds + 59L) / 60L;
            return displayName(upcomingEvent) + " in about " + minutes
                    + (minutes == 1 ? " minute" : " minutes");
        }
        if (ACID_RAIN.equals(activeEvent)) {
            WeatherSnapshot before = previousWeather.get(world.getUID());
            String after = before == null ? "Rain" : describeNormalWeather(before.storming, before.thundering);
            return after + " after Acid Rain";
        }
        if (activeEvent != null && EVENT_IDS.contains(activeEvent)) {
            return describeNormalWeather(world.hasStorm(), world.isThundering()) + " after " + displayName(activeEvent);
        }
        String next = world.hasStorm() ? "Clear" : "Rain";
        if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRules.ADVANCE_WEATHER))) {
            return next + " (weather cycle paused)";
        }
        int remainingTicks = world.hasStorm()
                ? world.getWeatherDuration()
                : world.getClearWeatherDuration();
        if (remainingTicks <= 0) {
            return next + " soon";
        }
        long ticks = remainingTicks;
        long minutes = (ticks + 1199L) / 1200L;
        return next + " in about " + minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private String describeNormalWeather(boolean storming, boolean thundering) {
        if (thundering) return "Thunderstorm";
        return storming ? "Rain" : "Clear";
    }

    private String displayName(String id) {
        if (id == null) return "Unknown";
        String configured = plugin.getConfig().getString(eventConfigPath(id) + "display-name");
        if (configured != null && !configured.isBlank()) return configured;
        return switch (id) {
            case ACID_RAIN -> "Acid Rain";
            case POLLEN_DRIFT -> "Pollen Drift";
            case ASHFALL -> "Ashfall";
            case COLD_SNAP -> "Cold Snap";
            case DUST_HAZE -> "Dust Haze";
            case FIREFLY_NIGHT -> "Firefly Night";
            case METEOR_DUST -> "Meteor Dust";
            case SPORE_BLOOM -> "Spore Bloom";
            case MAGNETIC_DISTURBANCE -> "Magnetic Disturbance";
            case FROSTWAKE -> "Frostwake";
            default -> id;
        };
    }

    private String defaultSound(String eventId) {
        return switch (eventId) {
            case ACID_RAIN -> "minecraft:block.fire.extinguish";
            case POLLEN_DRIFT -> "minecraft:block.azalea_leaves.hit";
            case ASHFALL -> "minecraft:block.sand.fall";
            case COLD_SNAP -> "minecraft:block.powder_snow.step";
            case DUST_HAZE -> "minecraft:block.sand.idle";
            case FIREFLY_NIGHT -> "minecraft:block.amethyst_block.chime";
            case METEOR_DUST -> "minecraft:entity.firework_rocket.twinkle_far";
            case SPORE_BLOOM -> "minecraft:block.spore_blossom.fall";
            case MAGNETIC_DISTURBANCE -> "minecraft:block.amethyst_block.resonate";
            case FROSTWAKE -> "minecraft:entity.illusioner.ambient";
            default -> "";
        };
    }

    private Particle configuredParticle(String path, String eventId) {
        String particleName = plugin.getConfig().getString(path, defaultParticleName(eventId));
        if (particleName == null || particleName.isBlank() || "none".equalsIgnoreCase(particleName)) return null;
        try {
            return Particle.valueOf(particleName.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Invalid particle '" + particleName + "' at " + path + "; using event default.");
            try {
                return Particle.valueOf(defaultParticleName(eventId));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private String defaultParticleName(String eventId) {
        return switch (eventId) {
            case ACID_RAIN -> "FALLING_WATER";
            case POLLEN_DRIFT -> "SPORE_BLOSSOM_AIR";
            case ASHFALL -> "ASH";
            case COLD_SNAP -> "SNOWFLAKE";
            case DUST_HAZE -> "WHITE_ASH";
            case FIREFLY_NIGHT -> "END_ROD";
            case METEOR_DUST -> "FIREWORK";
            case SPORE_BLOOM -> "SPORE_BLOSSOM_AIR";
            case MAGNETIC_DISTURBANCE -> "ELECTRIC_SPARK";
            case FROSTWAKE -> "SNOWFLAKE";
            default -> "CRIT";
        };
    }

    private String eventConfigPath(String eventId) {
        return "weather-events." + eventId + ".";
    }

    private double activationChance() {
        return clamp(plugin.getConfig().getDouble("weather-events.scheduler.event-chance",
                plugin.getConfig().getDouble("weather-events.acid-rain.activation-chance", 0.05)), 0.0, 1.0);
    }

    private long activationCheckIntervalTicks() {
        long seconds = plugin.getConfig().getLong("weather-events.scheduler.check-interval-seconds",
                plugin.getConfig().getLong("weather-events.acid-rain.activation-check-interval-seconds", 300L));
        return Math.max(1L, seconds) * 20L;
    }

    private long warningLeadTicks() {
        return Math.max(0L, plugin.getConfig().getLong("weather-radio.warning-lead-minutes", 5L)) * 1200L;
    }

    private long durationTicks(String eventId) {
        long seconds = Math.max(1L, plugin.getConfig().getLong(eventConfigPath(eventId) + "duration-seconds", 300L));
        return seconds * 20L;
    }

    private long effectCheckIntervalTicks(String eventId) {
        long seconds = Math.max(1L,
                plugin.getConfig().getLong(eventConfigPath(eventId) + "effect-check-interval-seconds", 5L));
        return seconds * 20L;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Restores world weather if the plugin is disabled during Acid Rain. */
    public void stop() {
        if (ACID_RAIN.equals(activeEvent)) restoreWeather();
        activeEvent = null;
        upcomingEvent = null;
        upcomingEventStartTick = -1L;
        testCountdown = false;
        lastTestCountdownAnnouncement = -1;
        warnedPlayers.clear();
    }

    private record WeightedEvent(String id, double weight) { }

    private static final class WeatherSnapshot {
        private final boolean storming;
        private final boolean thundering;
        private final int weatherDuration;
        private final int thunderDuration;
        private final long capturedAtTick;

        private WeatherSnapshot(World world) {
            this.storming = world.hasStorm();
            this.thundering = world.isThundering();
            this.weatherDuration = world.getWeatherDuration();
            this.thunderDuration = world.getThunderDuration();
            this.capturedAtTick = Bukkit.getCurrentTick();
        }
    }
}
