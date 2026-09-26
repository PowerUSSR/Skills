package org.mineserver.skills;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class EnderChestSkill implements Listener {

    static final String UPGRADE_TITLE = "§5Эндер-сундук: улучшение";
    static final String CHEST_TITLE   = "§5Эндер-сундук";

    private final SkillsPlugin plugin;
    private final File dataFolder;
    private Economy economy;

    private final Map<UUID, Integer>   levelCache  = new HashMap<>();
    private final Map<UUID, Inventory> invCache    = new HashMap<>();
    private final Set<UUID>            openChests  = new HashSet<>();
    private final Set<UUID>            openUpgrade = new HashSet<>();

    // ==================== REQUIREMENTS ====================

    private static final Object[][] REQ1 = {
        { Material.CHEST,            16 },
        { Material.OBSIDIAN,         20 },
        { Material.ENDER_PEARL,      16 },
        { Material.ENCHANTING_TABLE,  1 },
        { Material.IRON_INGOT,       96 },
        { Material.GOLD_INGOT,       32 },
    };
    private static final Object[][] REQ2 = {
        { Material.CHEST,            32 },
        { Material.ENDER_CHEST,       4 },
        { Material.OBSIDIAN,         42 },
        { Material.ENDER_PEARL,      24 },
        { Material.ENCHANTING_TABLE,  4 },
        { Material.GOLD_INGOT,       96 },
        { Material.DIAMOND,          32 },
    };
    private static final Object[][] REQ3 = {
        { Material.CHEST,            40 },
        { Material.ENDER_CHEST,      10 },
        { Material.OBSIDIAN,         64 },
        { Material.CRYING_OBSIDIAN,  16 },
        { Material.ENDER_PEARL,      32 },
        { Material.END_CRYSTAL,       4 },
        { Material.ENCHANTING_TABLE,  8 },
        { Material.DIAMOND,          40 },
        { Material.NETHERITE_INGOT,   6 },
        { Material.NETHER_STAR,       2 },
    };
    private static final double MONEY1 =   0;
    private static final double MONEY2 =  50;
    private static final double MONEY3 = 100;

    // ==================== INIT ====================

    public EnderChestSkill(SkillsPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "data/ender_chest");
        if (!dataFolder.exists()) dataFolder.mkdirs();
        setupEconomy();
    }

    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp =
            plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    // ==================== LEVEL ====================

    public int getLevel(UUID uuid) {
        if (levelCache.containsKey(uuid)) return levelCache.get(uuid);
        File f = new File(dataFolder, uuid + ".yml");
        int lvl = f.exists() ? YamlConfiguration.loadConfiguration(f).getInt("level", 0) : 0;
        levelCache.put(uuid, lvl);
        return lvl;
    }

    private void setLevel(UUID uuid, int level) {
        levelCache.put(uuid, level);
        saveData(uuid);
    }

    private int sizeForLevel(int level) {
        return level >= 3 ? 45 : level == 2 ? 36 : 27;
    }

    // ==================== INVENTORY ====================

    public Inventory getEnderInventory(UUID uuid) {
        int size = sizeForLevel(getLevel(uuid));
        if (invCache.containsKey(uuid)) {
            Inventory cached = invCache.get(uuid);
            if (cached.getSize() == size) return cached;
            // Размер изменился — копируем предметы в новый инвентарь
            Inventory bigger = Bukkit.createInventory(null, size, CHEST_TITLE);
            ItemStack[] old = cached.getContents();
            for (int i = 0; i < Math.min(old.length, size); i++) bigger.setItem(i, old[i]);
            invCache.put(uuid, bigger);
            return bigger;
        }
        Inventory inv = Bukkit.createInventory(null, size, CHEST_TITLE);
        File f = new File(dataFolder, uuid + ".yml");
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            for (int i = 0; i < size; i++) {
                ItemStack item = cfg.getItemStack("inventory." + i);
                if (item != null) inv.setItem(i, item);
            }
        }
        invCache.put(uuid, inv);
        return inv;
    }

    public void saveData(UUID uuid) {
        File f = new File(dataFolder, uuid + ".yml");
        YamlConfiguration cfg = f.exists()
            ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        cfg.set("level", levelCache.getOrDefault(uuid, 0));
        cfg.set("inventory", null);
        if (invCache.containsKey(uuid)) {
            ItemStack[] c = invCache.get(uuid).getContents();
            for (int i = 0; i < c.length; i++) {
                if (c[i] != null) cfg.set("inventory." + i, c[i]);
            }
        }
        try { cfg.save(f); } catch (IOException e) { e.printStackTrace(); }
    }

    public void saveAll() {
        for (UUID uuid : new HashSet<>(invCache.keySet())) saveData(uuid);
    }

    public void resetPlayer(UUID uuid) {
        levelCache.put(uuid, 0);
        invCache.remove(uuid);
        File f = new File(dataFolder, uuid + ".yml");
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            cfg.set("level", 0);
            try { cfg.save(f); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    public void openEnderChest(Player player) {
        player.openInventory(getEnderInventory(player.getUniqueId()));
        openChests.add(player.getUniqueId());
        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 1.0f);
    }

    // ==================== UPGRADE MENU ====================

    public void openUpgradeMenu(Player player) {
        int level = getLevel(player.getUniqueId());
        Inventory menu = Bukkit.createInventory(null, 27, UPGRADE_TITLE);

        // Фон
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta(); gm.setDisplayName("§7"); glass.setItemMeta(gm);
        for (int i = 0; i < 27; i++) menu.setItem(i, glass);

        // Слот 4: текущий уровень
        ItemStack info = new ItemStack(Material.ENDER_CHEST);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§5§lЭндер-сундук");
        im.setLore(Arrays.asList(
            "§7Уровень: " + (level == 0 ? "§cне открыт" : "§e" + level + " §7/ §e3"),
            "§7Размер: §e" + (level <= 1 ? "9×3 (27 яч.)" : level == 2 ? "9×4 (36 яч.)" : "9×5 (45 яч.)")
        ));
        info.setItemMeta(im);
        menu.setItem(4, info);

        if (level >= 3) {
            ItemStack max = new ItemStack(Material.NETHER_STAR);
            ItemMeta mm = max.getItemMeta();
            mm.setDisplayName("§6§lМАКСИМАЛЬНЫЙ УРОВЕНЬ");
            max.setItemMeta(mm);
            menu.setItem(13, max);
        } else {
            // Слот 13: требования
            ItemStack req = new ItemStack(Material.BOOK);
            ItemMeta rm = req.getItemMeta();
            rm.setDisplayName("§e§lТребования для уровня " + (level + 1));
            rm.setLore(buildReqLore(level + 1, player));
            req.setItemMeta(rm);
            menu.setItem(13, req);

            // Слот 22: кнопка улучшения
            ItemStack btn = new ItemStack(Material.EMERALD);
            ItemMeta bm = btn.getItemMeta();
            bm.setDisplayName("§a§l▶ УЛУЧШИТЬ ДО УРОВНЯ " + (level + 1));
            bm.setLore(Arrays.asList("§7Убедитесь, что у вас есть все ресурсы"));
            btn.setItemMeta(bm);
            menu.setItem(22, btn);
        }

        player.openInventory(menu);
        openUpgrade.add(player.getUniqueId()); // add ПОСЛЕ open — та же причина
    }

    private List<String> buildReqLore(int targetLevel, Player player) {
        Object[][] reqs = targetLevel == 1 ? REQ1 : targetLevel == 2 ? REQ2 : REQ3;
        double money   = targetLevel == 1 ? MONEY1 : targetLevel == 2 ? MONEY2 : MONEY3;
        List<String> lore = new ArrayList<>();
        for (Object[] r : reqs) {
            Material mat = (Material) r[0];
            int need = (int) r[1];
            int has  = countItems(player, mat);
            String col = has >= need ? "§a" : "§c";
            lore.add(col + matName(mat) + ": §f" + need + " §7(у вас: " + has + ")");
        }
        if (money > 0) {
            double has = (economy != null) ? economy.getBalance(player) : 0;
            String col = has >= money ? "§a" : "§c";
            lore.add(col + "Деньги: §f$" + (int) money + " §7(у вас: $" + (int) has + ")");
        }
        return lore;
    }

    private String matName(Material m) {
        switch (m) {
            case CHEST:            return "Сундук";
            case ENDER_CHEST:      return "Эндер-сундук";
            case OBSIDIAN:         return "Обсидиан";
            case CRYING_OBSIDIAN:  return "Плачущий обсидиан";
            case ENDER_PEARL:      return "Эндер-жемчуг";
            case END_CRYSTAL:      return "Кристалл Энда";
            case ENCHANTING_TABLE: return "Чародейский стол";
            case IRON_INGOT:       return "Железный слиток";
            case GOLD_INGOT:       return "Золотой слиток";
            case DIAMOND:          return "Алмаз";
            case NETHERITE_INGOT:  return "Незеритовый слиток";
            case NETHER_STAR:      return "Звезда Незера";
            default:               return m.name();
        }
    }

    // ==================== UPGRADE LOGIC ====================

    private void tryUpgrade(Player player) {
        UUID uuid  = player.getUniqueId();
        int  level = getLevel(uuid);
        if (level >= 3) return;
        int target = level + 1;

        Object[][] reqs  = target == 1 ? REQ1 : target == 2 ? REQ2 : REQ3;
        double     money = target == 1 ? MONEY1 : target == 2 ? MONEY2 : MONEY3;

        // Проверка предметов
        for (Object[] r : reqs) {
            Material mat  = (Material) r[0];
            int      need = (int) r[1];
            int      has  = countItems(player, mat);
            if (has < need) {
                player.sendMessage("§c[Навыки] §7Недостаточно: §f" + matName(mat)
                    + " §7(нужно §f" + need + "§7, у вас §f" + has + "§7)");
                return;
            }
        }

        // Проверка денег
        if (money > 0) {
            if (economy == null) {
                player.sendMessage("§c[Навыки] §7Плагин экономики (Vault) недоступен.");
                return;
            }
            double has = economy.getBalance(player);
            if (has < money) {
                player.sendMessage("§c[Навыки] §7Недостаточно денег (нужно §f$"
                    + (int) money + "§7, у вас §f$" + (int) has + "§7)");
                return;
            }
        }

        // Забираем предметы
        for (Object[] r : reqs) removeItems(player, (Material) r[0], (int) r[1]);

        // Забираем деньги
        if (money > 0 && economy != null) economy.withdrawPlayer(player, money);

        // Увеличиваем уровень
        invCache.remove(uuid); // сбросить кэш → при следующем открытии создастся с новым размером
        setLevel(uuid, target);
        plugin.setUnlocked(player, SkillsPlugin.SKILL_IDS[0], true);

        player.sendMessage("§a[Навыки] §7Навык §5Эндер-сундук §7улучшен до уровня §e" + target + "§7!");
        player.closeInventory();
    }

    private int countItems(Player player, Material mat) {
        int count = 0;
        for (ItemStack i : player.getInventory().getContents())
            if (i != null && i.getType() == mat) count += i.getAmount();
        return count;
    }

    private void removeItems(Player player, Material mat, int amount) {
        int rem = amount;
        ItemStack[] contents = player.getInventory().getContents();
        for (int idx = 0; idx < contents.length && rem > 0; idx++) {
            ItemStack item = contents[idx];
            if (item == null || item.getType() != mat) continue;
            int take = Math.min(item.getAmount(), rem);
            if (take >= item.getAmount()) {
                player.getInventory().setItem(idx, null);
            } else {
                item.setAmount(item.getAmount() - take);
            }
            rem -= take;
        }
        player.updateInventory();
    }

    // ==================== EVENTS ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getClickedBlock() == null || e.getClickedBlock().getType() != Material.ENDER_CHEST) return;
        e.setCancelled(true);
        e.setUseInteractedBlock(Event.Result.DENY);
        Player player = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> openEnderChest(player));
    }

    // Запасной перехватчик — на случай если Mohist всё равно открывает ванильный инвентарь
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        if (e.getInventory().getType() != InventoryType.ENDER_CHEST) return;
        e.setCancelled(true);
        Player player = (Player) e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> openEnderChest(player));
    }

    @EventHandler
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        String lower = e.getMessage().toLowerCase();
        if (!lower.equals("/ec") && !lower.startsWith("/ec ")
         && !lower.equals("/enderchest") && !lower.startsWith("/enderchest ")) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (getLevel(p.getUniqueId()) == 0) {
            p.sendMessage("§c[Навыки] §7Откройте навык §5Эндер-сундук §7(уровень 1) для доступа к §f/ec");
        } else {
            plugin.getServer().getScheduler().runTask(plugin, () -> openEnderChest(p));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        String title = e.getView().getTitle();
        if (CHEST_TITLE.equals(title)) {
            openChests.remove(uuid);
            if (invCache.containsKey(uuid)) saveData(uuid);
            Player p = (Player) e.getPlayer();
            p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 1.0f, 1.0f);
        }
        if (UPGRADE_TITLE.equals(title)) {
            openUpgrade.remove(uuid);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player player = (Player) e.getWhoClicked();
        if (!UPGRADE_TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (e.getRawSlot() == 22) {
            tryUpgrade(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (openChests.contains(uuid) || invCache.containsKey(uuid)) saveData(uuid);
        openChests.remove(uuid);
        openUpgrade.remove(uuid);
        levelCache.remove(uuid);
        invCache.remove(uuid);
    }
}
