package dev.reeldeal.weather;

import dev.reeldeal.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class WeatherRadioManager implements Listener {

    private static final String RADIO_ID = "weather_radio";

    private final Plugin plugin;
    private final ItemRegistry items;
    private final WeatherEventManager weatherEvents;
    private final Map<UUID, BukkitTask> animations = new HashMap<>();
    private final Map<UUID, Boolean> showingNextForecast = new HashMap<>();

    public WeatherRadioManager(Plugin plugin, ItemRegistry items, WeatherEventManager weatherEvents) {
        this.plugin = plugin;
        this.items = items;
        this.weatherEvents = weatherEvents;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onRadioUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack item = event.getItem();
        if (!RADIO_ID.equals(strip(items.identifierOf(item)))) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (animations.containsKey(playerId)) {
            return;
        }
        boolean interference = weatherEvents.isRadioInterferenceActive();
        boolean showNext = showingNextForecast.getOrDefault(playerId, false);
        showingNextForecast.put(playerId, !showNext);
        animateForecast(player, showNext, interference);
        if (interference) {
            String sound = plugin.getConfig().getString("weather-radio.interference-sound",
                    "minecraft:block.redstone_torch.burnout");
            float volume = (float) Math.max(0.0, Math.min(1.0,
                    plugin.getConfig().getDouble("weather-radio.interference-volume", 0.1)));
            float pitch = (float) Math.max(0.5, Math.min(2.0,
                    plugin.getConfig().getDouble("weather-radio.interference-pitch", 0.85)));
            if (sound != null && !sound.isBlank() && volume > 0.0f) {
                player.playSound(player.getLocation(), sound, SoundCategory.AMBIENT, volume, pitch);
            }
        }
    }

    private void animateForecast(Player player, boolean showNext, boolean interference) {
        UUID playerId = player.getUniqueId();
        BukkitTask previous = animations.remove(playerId);
        if (previous != null) previous.cancel();

        String crackle = "shh... shhhss...";
        BukkitRunnable animation = new BukkitRunnable() {
            private int character;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    animations.remove(playerId);
                    cancel();
                    return;
                }
                if (character < crackle.length()) {
                    player.sendActionBar(Component.text(crackle.substring(0, ++character), NamedTextColor.DARK_GRAY)
                            .decorate(TextDecoration.ITALIC));
                    return;
                }

                String label = showNext ? "NEXT" : "NOW";
                String forecast = showNext
                        ? weatherEvents.nextWeather(player.getWorld())
                        : weatherEvents.currentWeather(player.getWorld());
                Component result = Component.text("RADIO  »  ", NamedTextColor.GOLD)
                        .decorate(TextDecoration.BOLD)
                        .append(Component.text(label + ": ", NamedTextColor.GRAY).decorate(TextDecoration.BOLD))
                        .append(Component.text(forecast, showNext ? NamedTextColor.GRAY : NamedTextColor.YELLOW));
                if (interference) {
                    result = result.append(Component.text("  ~ static", NamedTextColor.DARK_GRAY)
                            .decorate(TextDecoration.ITALIC));
                }
                player.sendActionBar(result);
                animations.remove(playerId);
                cancel();
            }
        };
        animations.put(playerId, animation.runTaskTimer(plugin, 0L, 2L));
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        BukkitTask animation = animations.remove(playerId);
        if (animation != null) animation.cancel();
        showingNextForecast.remove(playerId);
    }

    private String strip(String id) {
        return id != null && id.startsWith("reel:") ? id.substring(5) : id;
    }
}
