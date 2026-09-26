package org.mineserver.skills;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class StealthSkill implements Listener {

    static final String UPGRADE_TITLE = "§7Скрытность: разблокировка";

    private final SkillsPlugin plugin;
    private final File dataFolder;
    private Economy economy;

    private final Map<UUID, Boolean> unlockedCache = new HashMap<>();

    // ==================== REQUIREMENTS ====================

    private static final Object[][] REQS = {
        { Material.ENCHANTING_TABLE, 10  },
        { Material.BEACON,            2  },
        { Material.BREWING_STAND,    20  },
        { Material.CAULDRON,         10  },
        { Material.DRAGON_HEAD,       6  },
        { Material.REDSTONE_BLOCK,   64  },
        { Material.REDSTONE,        128  },
        { Material.STICKY_PISTON,    10  },
        { Material.MUSIC_DISC_11,     1  },
        { Material.NETHER_STAR,       6  },
        { Material.IRON_BLOCK,       128 },
        { Material.GOLD_BLOCK,       128 },
        { Material.DIAMOND_BLOCK,    64  },
        { Material.EMERALD_BLOCK,    64  },
        { Material.NETHERITE_BLOCK,  10  },
    };

    // ==================== INIT ====================

    public StealthSkill(SkillsPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "data/stealth");
        if (!dataFolder.exists()) dataFolder.mkdirs();
        setupEconomy();
    }

    private void setupEconomy() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) return;
        RegisteredServiceProvider<Economy> rsp =
            plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    // ==================== UNLOCK STATE ====================

    public int getLevel(UUID uuid) {
        return isUnlocked(uuid) ? 1 : 0;
    }

    public boolean isUnlocked(UUID uuid) {
        if (unlockedCache.containsKey(uuid)) return unlockedCache.get(uuid);
        File f = new File(dataFolder, uuid + ".yml");
        boolean val = f.exists() && YamlConfiguration.loadConfiguration(f).getBoolean("unlocked", false);
        unlockedCache.put(uuid, val);
        return val;
    }

    private void setUnlocked(UUID uuid, boolean value) {
        unlockedCache.put(uuid, value);
        File f = new File(dataFolder, uuid + ".yml");
        YamlConfiguration cfg = f.exists()
            ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        cfg.set("unlocked", value);
        try { cfg.save(f); } catch (IOException e) { e.printStackTrace(); }
    }

    // ==================== RESET ====================

    public void resetPlayer(UUID uuid) {
        unlockedCache.put(uuid, false);
        File f = new File(dataFolder, uuid + ".yml");
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            cfg.set("unlocked", false);
            try { cfg.save(f); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // ==================== UPGRADE MENU ====================

    public void openUpgradeMenu(Player player) {
        boolean unlocked = isUnlocked(player.getUniqueId());
        Inventory menu = Bukkit.createInventory(null, 27, UPGRADE_TITLE);

        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta(); gm.setDisplayName("§7"); glass.setItemMeta(gm);
        for (int i = 0; i < 27; i++) menu.setItem(i, glass);

        // Слот 4: статус
        ItemStack info = new ItemStack(Material.GLASS);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§7§lСкрытность от карт");
        im.setLore(Arrays.asList(
            "§7Статус: " + (unlocked ? "§aРАЗБЛОКОВАНА" : "§cЗАБЛОКОВАНА"),
            "§7Даёт доступ к §f/dynmap hide §7и §f/dynmap show"
        ));
        info.setItemMeta(im);
        menu.setItem(4, info);

        if (unlocked) {
            ItemStack done = new ItemStack(Material.NETHER_STAR);
            ItemMeta dm = done.getItemMeta();
            dm.setDisplayName("§a§lНАВЫК РАЗБЛОКИРОВАН");
            dm.setLore(Arrays.asList(
                "§7Скройтесь: §f/dynmap hide",
                "§7Появитесь: §f/dynmap show"
            ));
            done.setItemMeta(dm);
            menu.setItem(13, done);
        } else {
            // Слот 13: требования
            ItemStack req = new ItemStack(Material.BOOK);
            ItemMeta rm = req.getItemMeta();
            rm.setDisplayName("§e§lТребования для разблокировки");
            rm.setLore(buildReqLore(player));
            req.setItemMeta(rm);
            menu.setItem(13, req);

            // Слот 22: кнопка
            ItemStack btn = new ItemStack(Material.EMERALD);
            ItemMeta bm = btn.getItemMeta();
            bm.setDisplayName("§a§l▶ РАЗБЛОКИРОВАТЬ");
            bm.setLore(Arrays.asList("§7Убедитесь, что у вас есть все ресурсы"));
            btn.setItemMeta(bm);
            menu.setItem(22, btn);
        }

        player.openInventory(menu);
    }

    private List<String> buildReqLore(Player player) {
        List<String> lore = new ArrayList<>();
        for (Object[] r : REQS) {
            Material mat = (Material) r[0];
            int need = (int) r[1];
            int has  = countItems(player, mat);
            lore.add((has >= need ? "§a" : "§c") + matName(mat)
                + ": §f" + need + " §7(у вас: " + has + ")");
        }
        return lore;
    }

    private String matName(Material m) {
        switch (m) {
            case ENCHANTING_TABLE: return "Чародейский стол";
            case BEACON:           return "Маяк";
            case BREWING_STAND:    return "Зельеварка";
            case CAULDRON:         return "Котёл";
            case DRAGON_HEAD:      return "Голова дракона";
            case REDSTONE_BLOCK:   return "Редстоуновый блок";
            case REDSTONE:         return "Редстоуновая пыль";
            case STICKY_PISTON:    return "Липкий поршень";
            case MUSIC_DISC_11:    return "Пластинка 11";
            case NETHER_STAR:      return "Звезда Незера";
            case IRON_BLOCK:       return "Железный блок";
            case GOLD_BLOCK:       return "Золотой блок";
            case DIAMOND_BLOCK:    return "Алмазный блок";
            case EMERALD_BLOCK:    return "Изумрудный блок";
            case NETHERITE_BLOCK:  return "Незеритовый блок";
            default:               return m.name();
        }
    }

    // ==================== UNLOCK LOGIC ====================

    private void tryUnlock(Player player) {
        if (isUnlocked(player.getUniqueId())) return;

        for (Object[] r : REQS) {
            Material mat  = (Material) r[0];
            int      need = (int) r[1];
            int      has  = countItems(player, mat);
            if (has < need) {
                player.sendMessage("§c[Навыки] §7Недостаточно: §f" + matName(mat)
                    + " §7(нужно §f" + need + "§7, у вас §f" + has + "§7)");
                return;
            }
        }

        for (Object[] r : REQS) removeItems(player, (Material) r[0], (int) r[1]);

        setUnlocked(player.getUniqueId(), true);
        plugin.setUnlocked(player, SkillsPlugin.SKILL_IDS[2], true);

        player.sendMessage("§a[Навыки] §7Навык §7§lСкрытность §7разблокирован!");
        player.sendMessage("§7Скройтесь с карт: §f/dynmap hide");
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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!UPGRADE_TITLE.equals(e.getView().getTitle())) return;
        e.setCancelled(true);
        if (e.getRawSlot() == 22) tryUnlock((Player) e.getWhoClicked());
    }

    // Фильтрация автодополнения Tab для /dynmap
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTabComplete(TabCompleteEvent e) {
        if (!(e.getSender() instanceof Player)) return;
        Player p = (Player) e.getSender();
        if (p.isOp()) return;

        String buffer = e.getBuffer().toLowerCase();
        if (!buffer.startsWith("/dynmap") && !buffer.startsWith("/dmap")) return;

        // Оставляем только hide и show
        List<String> filtered = new ArrayList<>();
        for (String c : e.getCompletions()) {
            String cl = c.toLowerCase();
            if (cl.equals("hide") || cl.equals("show")) filtered.add(c);
        }
        e.setCompletions(filtered);
    }

    // Перехват /dynmap и /dmap команд
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        String msg   = e.getMessage().trim();
        String lower = msg.toLowerCase();
        if (!lower.startsWith("/dynmap") && !lower.startsWith("/dmap")) return;

        Player p = e.getPlayer();
        if (p.isOp()) return; // операторы — без ограничений

        // Разбиваем на субкоманду
        String[] parts = lower.split("\\s+", 2);
        String sub = parts.length > 1 ? parts[1] : "";

        if (sub.equals("hide") || sub.equals("show")) {
            if (!isUnlocked(p.getUniqueId())) {
                e.setCancelled(true);
                p.sendMessage("§c[Навыки] §7Разблокируйте навык §7§lСкрытность §7через §f/skills§7.");
            }
            // Если разблокировано — пропускаем команду дальше
        } else {
            // Все остальные /dynmap субкоманды — запрещены для обычных игроков
            e.setCancelled(true);
            p.sendMessage("§c[Навыки] §7У вас нет доступа к этой команде.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        unlockedCache.remove(e.getPlayer().getUniqueId());
    }
}
