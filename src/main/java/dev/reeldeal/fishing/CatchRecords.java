package dev.reeldeal.fishing;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent records for spawned 3D fish catches. */
public final class CatchRecords {

    public record PlayerRecord(long total, String largestModel, int largestLengthCm,
                               String rarestModel, int rarestLengthCm, int rarestTier) {
        public static PlayerRecord empty() {
            return new PlayerRecord(0, null, 0, null, 0, -1);
        }
    }

    public record LeaderEntry(UUID playerId, String playerName, PlayerRecord record) {
    }

    private static final class MutableRecord {
        private String playerName;
        private long total;
        private String largestModel;
        private int largestLengthCm;
        private String rarestModel;
        private int rarestLengthCm;
        private int rarestTier = -1;

        PlayerRecord snapshot() {
            return new PlayerRecord(total, largestModel, largestLengthCm,
                    rarestModel, rarestLengthCm, rarestTier);
        }
    }

    private final Plugin plugin;
    private final File file;
    private final Map<UUID, MutableRecord> records = new HashMap<>();
    private final Object fileLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private long version;
    private long savedVersion;

    public CatchRecords(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "catch-records.yml");
        load();
    }

    public synchronized PlayerRecord get(UUID playerId) {
        MutableRecord record = records.get(playerId);
        return record == null ? PlayerRecord.empty() : record.snapshot();
    }

    public synchronized List<LeaderEntry> topByLargest(int limit) {
        return records.entrySet().stream()
                .filter(entry -> entry.getValue().largestModel != null)
                .sorted(Comparator.comparingInt((Map.Entry<UUID, MutableRecord> entry) ->
                        entry.getValue().largestLengthCm).reversed()
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(Math.max(0, limit))
                .map(entry -> new LeaderEntry(entry.getKey(), entry.getValue().playerName,
                        entry.getValue().snapshot()))
                .toList();
    }

    public synchronized List<LeaderEntry> topByTotal(int limit) {
        return records.entrySet().stream()
                .filter(entry -> entry.getValue().total > 0)
                .sorted(Comparator.comparingLong((Map.Entry<UUID, MutableRecord> entry) ->
                        entry.getValue().total).reversed()
                        .thenComparing(entry -> entry.getKey().toString()))
                .limit(Math.max(0, limit))
                .map(entry -> new LeaderEntry(entry.getKey(), entry.getValue().playerName,
                        entry.getValue().snapshot()))
                .toList();
    }

    public synchronized void recordFish(UUID playerId, String playerName, String model, int lengthCm, int tier) {
        MutableRecord record = records.computeIfAbsent(playerId, ignored -> new MutableRecord());
        if (playerName != null && !playerName.isBlank()) {
            record.playerName = playerName;
        }
        record.total++;
        if (lengthCm > record.largestLengthCm) {
            record.largestLengthCm = lengthCm;
            record.largestModel = model;
        }
        if (tier > record.rarestTier) {
            record.rarestTier = tier;
            record.rarestLengthCm = lengthCm;
            record.rarestModel = model;
        }
        version++;
    }

    public void saveAsync() {
        synchronized (this) {
            if (version == savedVersion || !saveScheduled.compareAndSet(false, true)) {
                return;
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                saveToDisk();
            } catch (IOException e) {
                plugin.getLogger().warning("Couldn't save catch records: " + e.getMessage());
            } finally {
                saveScheduled.set(false);
            }
        });
    }

    public void saveNow() {
        try {
            saveToDisk();
        } catch (IOException e) {
            plugin.getLogger().warning("Couldn't save catch records: " + e.getMessage());
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID playerId;
            try {
                playerId = UUID.fromString(key);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Skipping invalid player id in catch-records.yml: " + key);
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            MutableRecord record = new MutableRecord();
            record.playerName = section.getString("player-name");
            record.total = Math.max(0, section.getLong("total", 0));
            record.largestModel = section.getString("largest.model");
            record.largestLengthCm = Math.max(0, section.getInt("largest.length-cm", 0));
            record.rarestModel = section.getString("rarest.model");
            record.rarestLengthCm = Math.max(0, section.getInt("rarest.length-cm", 0));
            record.rarestTier = section.getInt("rarest.tier", -1);
            records.put(playerId, record);
        }
    }

    private void saveToDisk() throws IOException {
        synchronized (fileLock) {
            YamlConfiguration data = new YamlConfiguration();
            long snapshotVersion;
            synchronized (this) {
                if (version == savedVersion) {
                    return;
                }
                snapshotVersion = version;
                for (Map.Entry<UUID, MutableRecord> entry : records.entrySet()) {
                    String path = "players." + entry.getKey() + ".";
                    data.set(path + "player-name", entry.getValue().playerName);
                    PlayerRecord record = entry.getValue().snapshot();
                    data.set(path + "total", record.total());
                    data.set(path + "largest.model", record.largestModel());
                    data.set(path + "largest.length-cm", record.largestLengthCm());
                    data.set(path + "rarest.model", record.rarestModel());
                    data.set(path + "rarest.length-cm", record.rarestLengthCm());
                    data.set(path + "rarest.tier", record.rarestTier());
                }
            }
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
                throw new IOException("could not create plugin data directory");
            }
            data.save(file);
            synchronized (this) {
                savedVersion = Math.max(savedVersion, snapshotVersion);
            }
        }
    }
}
