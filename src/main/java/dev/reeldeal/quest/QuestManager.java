package dev.reeldeal.quest;

import dev.reeldeal.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Item;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Config-driven fishing objectives, progression, and claimable rewards. */
public final class QuestManager implements Listener {
    private final Plugin plugin;
    private final ItemRegistry items;
    private final File questsFile;
    private final File progressFile;
    private final Map<UUID, PlayerProgress> players = new HashMap<>();
    private final Map<UUID, Villager> questNpcs = new HashMap<>();
    private final Map<UUID, BukkitTask> npcFollowTasks = new HashMap<>();
    private final Object saveLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private final NamespacedKey questNpcKey;
    private YamlConfiguration quests;
    private long version;
    private long savedVersion;

    private static final class PlayerProgress {
        private String name;
        private final Map<String, QuestProgress> quests = new HashMap<>();
    }

    private static final class QuestProgress {
        private int progress;
        private boolean claimed;
    }

    public QuestManager(Plugin plugin, ItemRegistry items) {
        this.plugin = plugin;
        this.items = items;
        this.questNpcKey = new NamespacedKey(plugin, "quest_npc");
        this.questsFile = new File(plugin.getDataFolder(), "quests.yml");
        this.progressFile = new File(plugin.getDataFolder(), "quest-progress.yml");
        reload();
        loadProgress();
    }

    public synchronized void reload() {
        if (!questsFile.isFile()) {
            if (plugin.getResource("quests.yml") == null) {
                throw new IllegalStateException("Bundled quests.yml is missing");
            }
            plugin.saveResource("quests.yml", false);
        }
        quests = YamlConfiguration.loadConfiguration(questsFile);
        mergeBundledDefaults();
        ConfigurationSection entries = quests.getConfigurationSection("quests");
        if (entries == null) {
            plugin.getLogger().warning("quests.yml has no 'quests' section; no quests are available.");
            return;
        }
        for (String id : entries.getKeys(false)) {
            String type = quests.getString("quests." + id + ".objective.type", "");
            if (!List.of("catches", "weather-catches", "pyro-tier", "hook-catches", "line-catches",
                    "hook-line-catches", "biome-catches", "item-catches", "mob-catches",
                    "open-water-catches", "barrels-opened", "hooked-fish", "fish-size",
                    "catch-categories", "frostwake-defeats", "radio-checks", "workbench-upgrades")
                    .contains(type.toLowerCase(Locale.ROOT))) {
                plugin.getLogger().warning("Quest '" + id + "' uses unsupported objective type '" + type + "'.");
            }
            if (quests.getInt("quests." + id + ".objective.amount", 0) < 1) {
                plugin.getLogger().warning("Quest '" + id + "' objective amount must be at least 1.");
            }
        }
        refreshQuestNpcs();
        plugin.getLogger().info("Loaded " + entries.getKeys(false).size() + " configurable quests.");
    }

    private void mergeBundledDefaults() {
        try (InputStream input = plugin.getResource("quests.yml")) {
            if (input == null) return;
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(input, StandardCharsets.UTF_8));
            boolean changed = mergeSection(defaults.getConfigurationSection("npc"),
                    quests.getConfigurationSection("npc"), "npc");
            changed |= mergeSection(defaults.getConfigurationSection("quests"),
                    quests.getConfigurationSection("quests"), "quests");
            if (changed) quests.save(questsFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Couldn't merge quest defaults: " + exception.getMessage());
        }
    }

    private boolean mergeSection(ConfigurationSection defaults, ConfigurationSection existing, String path) {
        if (defaults == null) return false;
        boolean changed = false;
        if (existing == null) {
            existing = quests.createSection(path);
            changed = true;
        }
        for (String key : defaults.getKeys(false)) {
            String childPath = path + "." + key;
            if (defaults.isConfigurationSection(key)) {
                if (!existing.isConfigurationSection(key) && existing.contains(key)) continue;
                changed |= mergeSection(defaults.getConfigurationSection(key),
                        existing.getConfigurationSection(key), childPath);
            } else if (!existing.contains(key)) {
                existing.set(key, defaults.get(key));
                changed = true;
            }
        }
        return changed;
    }

    public synchronized List<String> questIds() {
        ConfigurationSection section = quests.getConfigurationSection("quests");
        return section == null ? List.of() : List.copyOf(section.getKeys(false));
    }

    public void recordCatch(Player player, String activeWeather) {
        recordObjective(player, "catches", null, null, null);
        if (activeWeather != null) recordObjective(player, "weather-catches", activeWeather, null, null);
    }

    public void recordPyroTierCatch(Player player, String tier) {
        recordObjective(player, "pyro-tier", tier, null, null);
    }

    public void recordFishingDetails(Player player, String hook, String line, String biome, String item,
                                     String mob, String category, boolean openWater) {
        recordObjective(player, "hook-catches", hook, hook, line);
        recordObjective(player, "line-catches", line, hook, line);
        recordObjective(player, "hook-line-catches", hook + ":" + line, hook, line);
        recordObjective(player, "biome-catches", biome, hook, line);
        recordObjective(player, "item-catches", item, hook, line);
        if (mob != null) recordObjective(player, "mob-catches", mob, hook, line);
        recordObjective(player, "catch-categories", mob == null ? category : "mob", hook, line);
        if (openWater) recordObjective(player, "open-water-catches", null, hook, line);
    }

    public void recordBarrelOpened(Player player) {
        recordObjective(player, "barrels-opened", null, null, null);
    }

    public void recordHookedFish(Player player, String model, int sizeTier) {
        recordObjective(player, "hooked-fish", model, null, null);
        if (sizeTier >= 0 && sizeTier < List.of("tiny", "small", "medium", "large", "trophy").size()) {
            recordObjective(player, "fish-size", List.of("tiny", "small", "medium", "large", "trophy").get(sizeTier), null, null);
        }
    }

    public void recordFrostwakeDefeat(Player player) {
        recordObjective(player, "frostwake-defeats", null, null, null);
    }

    public void recordRadioCheck(Player player) {
        recordObjective(player, "radio-checks", null, null, null);
    }

    public void recordWorkbenchUpgrade(Player player) {
        recordObjective(player, "workbench-upgrades", null, null, null);
    }

    private synchronized void recordObjective(Player player, String type, String actual,
                                              String actualHook, String actualLine) {
        PlayerProgress progress = players.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerProgress());
        progress.name = player.getName();
        for (String id : questIds()) {
            String path = "quests." + id + ".";
            if (!quests.getBoolean(path + "enabled", true)
                    || !isUnlocked(progress, id)
                    || !type.equalsIgnoreCase(quests.getString(path + "objective.type", ""))) continue;
            String targetKey = switch (type) {
                case "weather-catches" -> "weather";
                case "pyro-tier", "fish-size" -> "tier";
                case "hook-catches" -> "hook";
                case "line-catches" -> "line";
                case "hook-line-catches" -> "combination";
                case "biome-catches" -> "biome";
                case "item-catches" -> "item";
                case "mob-catches" -> "mob";
                case "hooked-fish" -> "model";
                case "catch-categories" -> "category";
                default -> null;
            };
            if (targetKey != null) {
                String expected = quests.getString(path + "objective." + targetKey, "");
                if (!expected.isBlank() && !normalizeTarget(type, expected).equals(normalizeTarget(type, actual))) continue;
            }
            String requiredHook = quests.getString(path + "objective.hook", "");
            if (!requiredHook.isBlank() && !normalizeTarget(type, requiredHook).equals(normalizeTarget(type, actualHook))) continue;
            String requiredLine = quests.getString(path + "objective.line", "");
            if (!requiredLine.isBlank() && !normalizeTarget(type, requiredLine).equals(normalizeTarget(type, actualLine))) continue;
            QuestProgress quest = progress.quests.computeIfAbsent(id, ignored -> new QuestProgress());
            if (quest.claimed) continue;
            int goal = Math.max(1, quests.getInt(path + "objective.amount", 1));
            if (quest.progress < goal) {
                quest.progress++;
                version++;
            }
        }
        saveAsync();
    }

    private String normalizeTarget(String type, String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).replace("reel:", "").replace("minecraft:", "")
                .replaceAll("[^a-z0-9:_-]", "");
        return "pyro-tier".equals(type) || "fish-size".equals(type)
                ? normalizeTier(normalized) : normalized;
    }

    public void spawnNpc(Player player) {
        String name = quests.getString("npc.name", "Quest Keeper");
        Villager villager = player.getWorld().spawn(player.getLocation(), Villager.class, npc -> {
            npc.setProfession(Villager.Profession.FISHERMAN);
            npc.setVillagerLevel(5);
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setSilent(true);
            npc.setPersistent(true);
            npc.setCollidable(false);
            npc.customName(Component.text(name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            npc.setCustomNameVisible(true);
            npc.getPersistentDataContainer().set(questNpcKey, PersistentDataType.BYTE, (byte) 1);
        });
        registerQuestNpc(villager);
        player.sendMessage(Component.text("Placed the " + name + " quest NPC. Players can right-click it to open quests.",
                NamedTextColor.GREEN));
    }

    @EventHandler
    public void onEntityAdded(com.destroystokyo.paper.event.entity.EntityAddToWorldEvent event) {
        registerQuestNpc(event.getEntity());
    }

    @EventHandler
    public void onEntitiesLoaded(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) registerQuestNpc(entity);
    }

    @EventHandler
    public void onEntitiesUnloading(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) stopNpcTracking(entity.getUniqueId());
    }

    @EventHandler
    public void onEntityRemoved(com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent event) {
        stopNpcTracking(event.getEntity().getUniqueId());
    }

    public void scanLoadedQuestNpcs() {
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (Entity entity : chunk.getEntities()) registerQuestNpc(entity);
            }
        }
    }

    private void registerQuestNpc(Entity entity) {
        if (!(entity instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(questNpcKey, PersistentDataType.BYTE)) return;
        UUID id = villager.getUniqueId();
        villager.setAI(false);
        villager.setInvulnerable(true);
        villager.setCollidable(false);
        questNpcs.put(id, villager);
        applyNpcName(villager);
        if (npcFollowTasks.containsKey(id)) return;
        long interval = Math.max(5L, quests.getLong("npc.follow.check-interval-ticks", 10L));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> followNearestPlayer(id), interval, interval);
        npcFollowTasks.put(id, task);
    }

    private void followNearestPlayer(UUID npcId) {
        Villager villager = questNpcs.get(npcId);
        if (villager == null || !villager.isValid() || villager.isDead()) {
            stopNpcTracking(npcId);
            return;
        }
        if (!quests.getBoolean("npc.follow.enabled", true)) {
            return;
        }

        double range = Math.max(1.0, quests.getDouble("npc.follow.range", 32.0));
        Player nearest = null;
        double nearestDistanceSquared = range * range;
        for (Player player : villager.getWorld().getPlayers()) {
            double distanceSquared = player.getLocation().distanceSquared(villager.getLocation());
            if (distanceSquared <= range * range && distanceSquared < nearestDistanceSquared) {
                nearest = player;
                nearestDistanceSquared = distanceSquared;
            }
        }

        if (nearest == null) return;
        float rotationSpeed = (float) Math.max(1.0,
                quests.getDouble("npc.follow.head-rotation-speed", 30.0));
        float maxHeadPitch = (float) Math.max(1.0,
                quests.getDouble("npc.follow.max-head-pitch", 45.0));
        villager.lookAt(nearest, rotationSpeed, maxHeadPitch);
    }

    private void applyNpcName(Villager villager) {
        String configuredName = quests.getString("npc.name", "Quest Keeper");
        if (configuredName == null || configuredName.isBlank()) configuredName = "Quest Keeper";
        villager.customName(Component.text(configuredName, NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        villager.setCustomNameVisible(true);
    }

    private void refreshQuestNpcs() {
        List<Villager> loadedNpcs = new ArrayList<>(questNpcs.values());
        for (UUID id : new ArrayList<>(npcFollowTasks.keySet())) stopNpcTracking(id);
        for (Villager villager : loadedNpcs) {
            if (villager.isValid()) registerQuestNpc(villager);
        }
    }

    private void stopNpcTracking(UUID id) {
        BukkitTask task = npcFollowTasks.remove(id);
        if (task != null) task.cancel();
        Villager villager = questNpcs.remove(id);
        if (villager != null && villager.isValid()) villager.setAI(false);
    }

    public void stopNpcTasks() {
        for (UUID id : new ArrayList<>(npcFollowTasks.keySet())) stopNpcTracking(id);
    }

    @EventHandler
    public void onQuestNpcInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Villager villager)
                || !villager.getPersistentDataContainer().has(questNpcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        openMenu(event.getPlayer(), 0);
    }

    @EventHandler
    public void onQuestMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof QuestMenuHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        if (slot == 48 && holder.page > 0) {
            openMenu(player, holder.page - 1);
        } else if (slot == 50 && (holder.page + 1) * 45 < enabledQuestCount()) {
            openMenu(player, holder.page + 1);
        } else if (slot == 53) {
            player.closeInventory();
        } else {
            String questId = holder.questSlots.get(slot);
            if (questId != null) {
                claim(player, questId);
                openMenu(player, holder.page);
            }
        }
    }

    private int enabledQuestCount() {
        return (int) questIds().stream().filter(id -> quests.getBoolean("quests." + id + ".enabled", true)).count();
    }

    public synchronized void openMenu(Player player, int requestedPage) {
        PlayerProgress playerProgress = players.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerProgress());
        playerProgress.name = player.getName();
        List<String> available = questIds().stream()
                .filter(id -> quests.getBoolean("quests." + id + ".enabled", true)).toList();
        int pages = Math.max(1, (available.size() + 44) / 45);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        QuestMenuHolder holder = new QuestMenuHolder(page);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Fishing Quests", NamedTextColor.GOLD));
        holder.inventory = inventory;
        int start = page * 45;
        int end = Math.min(available.size(), start + 45);
        for (int i = start; i < end; i++) {
            String id = available.get(i);
            int slot = i - start;
            holder.questSlots.put(slot, id);
            inventory.setItem(slot, questDisplay(playerProgress, id));
        }
        inventory.setItem(45, menuButton(Material.GRAY_STAINED_GLASS_PANE, " "));
        inventory.setItem(46, menuButton(Material.GRAY_STAINED_GLASS_PANE, " "));
        inventory.setItem(47, menuButton(Material.GRAY_STAINED_GLASS_PANE, " "));
        inventory.setItem(48, menuButton(page > 0 ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                page > 0 ? "Previous page" : " "));
        inventory.setItem(49, menuButton(Material.BOOK, "Page " + (page + 1) + "/" + pages));
        inventory.setItem(50, menuButton(page + 1 < pages ? Material.ARROW : Material.GRAY_STAINED_GLASS_PANE,
                page + 1 < pages ? "Next page" : " "));
        inventory.setItem(51, menuButton(Material.GRAY_STAINED_GLASS_PANE, " "));
        inventory.setItem(52, menuButton(Material.GRAY_STAINED_GLASS_PANE, " "));
        inventory.setItem(53, menuButton(Material.BARRIER, "Close"));
        player.openInventory(inventory);
    }

    private ItemStack questDisplay(PlayerProgress playerProgress, String id) {
        String path = "quests." + id + ".";
        QuestProgress state = playerProgress.quests.getOrDefault(id, new QuestProgress());
        int goal = Math.max(1, quests.getInt(path + "objective.amount", 1));
        boolean unlocked = isUnlocked(playerProgress, id);
        Material icon = state.claimed ? Material.EMERALD : !unlocked ? Material.BARRIER
                : state.progress >= goal ? Material.GOLD_INGOT : Material.PAPER;
        ItemStack stack = new ItemStack(icon);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(quests.getString(path + "name", id), state.claimed
                ? NamedTextColor.GREEN : !unlocked ? NamedTextColor.DARK_GRAY
                : state.progress >= goal ? NamedTextColor.GOLD : NamedTextColor.YELLOW));
        List<Component> lore = new ArrayList<>();
        String description = quests.getString(path + "description", "");
        if (!description.isBlank()) lore.add(Component.text(description, NamedTextColor.GRAY));
        if (unlocked && !state.claimed) lore.add(Component.text("Progress: " + Math.min(state.progress, goal)
                + "/" + goal, NamedTextColor.AQUA));
        if (!unlocked) lore.add(Component.text("Complete the earlier quest first.", NamedTextColor.DARK_GRAY));
        lore.add(Component.text("Rewards: " + rewardSummary(path + "rewards."), NamedTextColor.GOLD));
        if (state.claimed) lore.add(Component.text("Reward claimed", NamedTextColor.GREEN));
        else if (unlocked && state.progress >= goal) lore.add(Component.text("Click to claim", NamedTextColor.GREEN));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private String rewardSummary(String path) {
        List<String> summary = new ArrayList<>();
        int experience = Math.max(0, quests.getInt(path + "experience", 0));
        if (experience > 0) summary.add(experience + " XP");
        for (Map<?, ?> entry : quests.getMapList(path + "items")) {
            Object itemId = entry.get("item");
            Object material = entry.get("material");
            Object amount = entry.get("amount");
            if (itemId != null) summary.add(amount + "x " + itemId);
            else if (material != null) summary.add(amount + "x " + material);
        }
        if (!quests.getStringList(path + "commands").isEmpty()) summary.add("special reward");
        return summary.isEmpty() ? "None configured" : String.join(", ", summary);
    }

    private ItemStack menuButton(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.YELLOW));
        item.setItemMeta(meta);
        return item;
    }

    private static final class QuestMenuHolder implements InventoryHolder {
        private final int page;
        private final Map<Integer, String> questSlots = new HashMap<>();
        private Inventory inventory;

        private QuestMenuHolder(int page) { this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }

    public synchronized boolean claim(Player player, String id) {
        String path = "quests." + id + ".";
        if (!quests.isConfigurationSection(path)) {
            player.sendMessage(Component.text("Unknown quest '" + id + "'.", NamedTextColor.RED));
            return false;
        }
        if (!quests.getBoolean(path + "enabled", true)) {
            player.sendMessage(Component.text("That quest is currently disabled.", NamedTextColor.RED));
            return false;
        }
        PlayerProgress playerProgress = players.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerProgress());
        playerProgress.name = player.getName();
        if (!isUnlocked(playerProgress, id)) {
            player.sendMessage(Component.text("Complete and claim its prerequisite quests first.", NamedTextColor.RED));
            return false;
        }
        QuestProgress quest = playerProgress.quests.computeIfAbsent(id, ignored -> new QuestProgress());
        int goal = Math.max(1, quests.getInt(path + "objective.amount", 1));
        if (quest.claimed) {
            player.sendMessage(Component.text("You already claimed that quest reward.", NamedTextColor.YELLOW));
            return false;
        }
        if (quest.progress < goal) {
            player.sendMessage(Component.text("Quest progress: " + quest.progress + "/" + goal + ".", NamedTextColor.YELLOW));
            return false;
        }
        quest.claimed = true;
        version++;
        grantRewards(player, id, path + "rewards.");
        saveAsync();
        player.sendMessage(Component.text("Quest complete: " + quests.getString(path + "name", id), NamedTextColor.GREEN));
        return true;
    }

    private void grantRewards(Player player, String id, String rewardPath) {
        int experience = Math.max(0, quests.getInt(rewardPath + "experience", 0));
        if (experience > 0) player.giveExp(experience);
        for (Map<?, ?> entry : quests.getMapList(rewardPath + "items")) {
            int amount = Math.max(1, Math.min(64, number(entry.get("amount"), 1)));
            ItemStack stack;
            Object customId = entry.get("item");
            if (customId != null) {
                String itemId = String.valueOf(customId);
                if (!items.ids().contains(itemId)) {
                    plugin.getLogger().warning("Quest '" + id + "' references unknown ReelDeal item '" + itemId + "'.");
                    continue;
                }
                stack = items.create(itemId);
                stack.setAmount(Math.min(amount, stack.getMaxStackSize()));
            } else {
                Object configuredMaterial = entry.get("material");
                Material material = configuredMaterial == null ? null
                        : Material.matchMaterial(String.valueOf(configuredMaterial));
                if (material == null || material.isAir()) {
                    plugin.getLogger().warning("Quest '" + id + "' has an invalid reward material.");
                    continue;
                }
                stack = new ItemStack(material, amount);
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            for (ItemStack leftover : leftovers.values()) {
                Item drop = player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                drop.setOwner(player.getUniqueId());
            }
        }
        for (String command : quests.getStringList(rewardPath + "commands")) {
            String expanded = command.replace("%player%", player.getName()).replace("%uuid%", player.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded);
        }
    }

    private boolean isUnlocked(PlayerProgress progress, String id) {
        for (String prerequisite : quests.getStringList("quests." + id + ".requires")) {
            QuestProgress required = progress.quests.get(prerequisite);
            if (required == null || !required.claimed) return false;
        }
        return true;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private String normalizeTier(String value) {
        if (value == null) return "";
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String tier : List.of("bronze", "silver", "gold", "diamond", "platinum", "mythical")) {
            if (normalized.contains(tier)) return tier;
        }
        return normalized;
    }

    private void loadProgress() {
        if (!progressFile.isFile()) return;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(progressFile);
        ConfigurationSection root = data.getConfigurationSection("players");
        if (root == null) return;
        for (String rawId : root.getKeys(false)) {
            UUID uuid;
            try { uuid = UUID.fromString(rawId); }
            catch (IllegalArgumentException ignored) { continue; }
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) continue;
            PlayerProgress player = new PlayerProgress();
            player.name = section.getString("name");
            ConfigurationSection playerQuests = section.getConfigurationSection("quests");
            if (playerQuests != null) for (String id : playerQuests.getKeys(false)) {
                ConfigurationSection questSection = playerQuests.getConfigurationSection(id);
                if (questSection == null) continue;
                QuestProgress state = new QuestProgress();
                state.progress = Math.max(0, questSection.getInt("progress", 0));
                state.claimed = questSection.getBoolean("claimed", false);
                player.quests.put(id, state);
            }
            players.put(uuid, player);
        }
    }

    public void saveAsync() {
        synchronized (this) {
            if (version == savedVersion || !saveScheduled.compareAndSet(false, true)) return;
        }
        Map<UUID, PlayerProgress> snapshot = new LinkedHashMap<>();
        long snapshotVersion;
        synchronized (this) {
            snapshotVersion = version;
            for (Map.Entry<UUID, PlayerProgress> entry : players.entrySet()) {
                PlayerProgress copy = new PlayerProgress();
                copy.name = entry.getValue().name;
                for (Map.Entry<String, QuestProgress> questEntry : entry.getValue().quests.entrySet()) {
                    QuestProgress state = new QuestProgress();
                    state.progress = questEntry.getValue().progress;
                    state.claimed = questEntry.getValue().claimed;
                    copy.quests.put(questEntry.getKey(), state);
                }
                snapshot.put(entry.getKey(), copy);
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                saveSnapshot(snapshot, snapshotVersion);
                synchronized (this) { savedVersion = Math.max(savedVersion, snapshotVersion); }
            } catch (IOException exception) {
                plugin.getLogger().warning("Couldn't save quest progress: " + exception.getMessage());
            } finally {
                saveScheduled.set(false);
                synchronized (this) { if (version != savedVersion) saveAsync(); }
            }
        });
    }

    public void saveNow() {
        Map<UUID, PlayerProgress> snapshot = new LinkedHashMap<>();
        long snapshotVersion;
        synchronized (this) {
            snapshotVersion = version;
            for (Map.Entry<UUID, PlayerProgress> entry : players.entrySet()) {
                PlayerProgress copy = new PlayerProgress();
                copy.name = entry.getValue().name;
                for (Map.Entry<String, QuestProgress> questEntry : entry.getValue().quests.entrySet()) {
                    QuestProgress state = new QuestProgress();
                    state.progress = questEntry.getValue().progress;
                    state.claimed = questEntry.getValue().claimed;
                    copy.quests.put(questEntry.getKey(), state);
                }
                snapshot.put(entry.getKey(), copy);
            }
        }
        try {
            saveSnapshot(snapshot, snapshotVersion);
            synchronized (this) { savedVersion = Math.max(savedVersion, snapshotVersion); }
        } catch (IOException exception) {
            plugin.getLogger().warning("Couldn't save quest progress: " + exception.getMessage());
        }
    }

    private void saveSnapshot(Map<UUID, PlayerProgress> snapshot, long snapshotVersion) throws IOException {
        synchronized (saveLock) {
            synchronized (this) {
                if (snapshotVersion < savedVersion) return;
            }
            YamlConfiguration data = new YamlConfiguration();
            for (Map.Entry<UUID, PlayerProgress> entry : snapshot.entrySet()) {
                String path = "players." + entry.getKey() + ".";
                data.set(path + "name", entry.getValue().name);
                for (Map.Entry<String, QuestProgress> questEntry : entry.getValue().quests.entrySet()) {
                    String questPath = path + "quests." + questEntry.getKey() + ".";
                    data.set(questPath + "progress", questEntry.getValue().progress);
                    data.set(questPath + "claimed", questEntry.getValue().claimed);
                }
            }
            if (!progressFile.getParentFile().isDirectory() && !progressFile.getParentFile().mkdirs()) {
                throw new IOException("could not create plugin data directory");
            }
            data.save(progressFile);
        }
    }
}
