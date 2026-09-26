package org.mineserver.skills;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ThiefSkill implements Listener {

    static final String UPGRADE_TITLE = "§8Навык вора: улучшение";

    private final SkillsPlugin plugin;
    private final File dataFolder;
    private Economy economy;

    private final Map<UUID, Integer>        levelCache       = new HashMap<>();
    private final Map<UUID, RobberySession> activeRobberies  = new HashMap<>();
    private final Map<UUID, Long>           cooldowns        = new HashMap<>();

    private static final long COOLDOWN_MS = 60_000L;
    private static final String ALLOWED_WORLD = "world";

    // ==================== SESSION ====================

    private static class RobberySession {
        final UUID      thiefId;
        final UUID      victimId;
        final BossBar   thiefBar;
        final BossBar   victimBar;
        BukkitTask      task;
        int             ticksLeft; // units of 10 ticks (0.5 s), starts at 30 = 15 s

        RobberySession(UUID t, UUID v, BossBar tb, BossBar vb) {
            thiefId = t; victimId = v; thiefBar = tb; victimBar = vb;
            ticksLeft = 30;
        }
    }

    // ==================== REQUIREMENTS ====================

    private static final Object[][] REQ1 = {
        { Material.BOOKSHELF,      16 },
        { Material.BOOK,           30 },
        { Material.IRON_INGOT,     32 },
        { Material.SHEARS,          2 },
        { Material.CRAFTING_TABLE,  8 },
    };
    private static final Object[][] REQ2 = {
        { Material.BOOKSHELF,      32 },
        { Material.BOOK,           64 },
        { Material.IRON_INGOT,     64 },
        { Material.GOLD_INGOT,     32 },
        { Material.SHEARS,          3 },
        { Material.CRAFTING_TABLE, 16 },
        { Material.SPYGLASS,        3 },
    };
    private static final Object[][] REQ3 = {
        { Material.BOOKSHELF,      64 },
        { Material.BOOK,          128 },
        { Material.GOLD_INGOT,     96 },
        { Material.DIAMOND,        21 },
        { Material.NETHERITE_INGOT,10 },
        { Material.ENDER_PEARL,    32 },
        { Material.SHEARS,          4 },
        { Material.CRAFTING_TABLE, 32 },
        { Material.SPYGLASS,        4 },
    };
    private static final double MONEY1 =   0;
    private static final double MONEY2 =  70;
    private static final double MONEY3 = 150;

    // ==================== INIT ====================

    public ThiefSkill(SkillsPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "data/thief");
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
        File f = new File(dataFolder, uuid + ".yml");
        YamlConfiguration cfg = f.exists()
            ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        cfg.set("level", level);
        try { cfg.save(f); } catch (IOException e) { e.printStackTrace(); }
    }

    // ==================== ROB COMMAND ====================

    public boolean handleRob(Player thief, String[] args) {
        if (args.length == 0) {
            thief.sendMessage("§c[Вор] §7Использование: §f/rob <ник>");
            return true;
        }

        int level = getLevel(thief.getUniqueId());
        if (level == 0) {
            thief.sendMessage("§c[Вор] §7У вас нет навыка §8Вора§7. Откройте его через §f/skills§7.");
            return true;
        }

        // Только в разрешённом мире
        if (!ALLOWED_WORLD.equals(thief.getWorld().getName())) {
            thief.sendMessage("§c[Вор] §7Эта команда не работает на Спавне.");
            return true;
        }

        // Перезарядка
        long now = System.currentTimeMillis();
        Long lastUse = cooldowns.get(thief.getUniqueId());
        if (lastUse != null) {
            long elapsed = now - lastUse;
            if (elapsed < COOLDOWN_MS) {
                long left = (COOLDOWN_MS - elapsed) / 1000 + 1;
                thief.sendMessage("§c[Вор] §7Перезарядка: §e" + left + " сек§7.");
                return true;
            }
        }

        if (activeRobberies.containsKey(thief.getUniqueId())) {
            thief.sendMessage("§c[Вор] §7Вы уже грабите кого-то.");
            return true;
        }

        Player victim = Bukkit.getPlayer(args[0]);
        if (victim == null || !victim.isOnline()) {
            thief.sendMessage("§c[Вор] §7Игрок §f" + args[0] + " §7не в сети.");
            return true;
        }

        if (victim.equals(thief)) {
            thief.sendMessage("§c[Вор] §7Нельзя грабить самого себя.");
            return true;
        }

        // Проверка дистанции
        if (!thief.getWorld().equals(victim.getWorld())
         || thief.getLocation().distance(victim.getLocation()) > 3.0) {
            thief.sendMessage("§c[Вор] §7Подойдите ближе (не более 3 блоков).");
            return true;
        }

        // Уже кто-то грабит этого игрока?
        for (RobberySession rs : activeRobberies.values()) {
            if (rs.victimId.equals(victim.getUniqueId())) {
                thief.sendMessage("§c[Вор] §7Этого игрока уже грабят.");
                return true;
            }
        }

        startRobbery(thief, victim);
        return true;
    }

    private void startRobbery(Player thief, Player victim) {
        String title = "§c⚔ Грабят игрока §f" + victim.getName();

        BossBar tb = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID);
        BossBar vb = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SOLID);
        tb.setProgress(1.0); tb.addPlayer(thief);
        vb.setProgress(1.0); vb.addPlayer(victim);

        RobberySession session = new RobberySession(
            thief.getUniqueId(), victim.getUniqueId(), tb, vb);
        activeRobberies.put(thief.getUniqueId(), session);

        thief.sendMessage("§8[Вор] §7Вы начали грабить §f" + victim.getName()
            + "§7. Не отходите дальше §c3 блоков §7в течение §e15 секунд§7...");
        victim.sendMessage("§c[Вор] §7Вас пытаются ограбить! Убегайте!");

        session.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            RobberySession s = activeRobberies.get(session.thiefId);
            if (s == null) return;

            Player t = Bukkit.getPlayer(s.thiefId);
            Player v = Bukkit.getPlayer(s.victimId);

            // Кто-то вышел
            if (t == null || !t.isOnline() || v == null || !v.isOnline()) {
                cleanupSession(s, null, null, null, null);
                return;
            }

            // Дистанция
            if (!t.getWorld().equals(v.getWorld())
             || t.getLocation().distance(v.getLocation()) > 3.0) {
                cleanupSession(s,
                    t, "§c[Вор] §7Ограбление прервано: слишком далеко.",
                    v, "§a[Вор] §7Грабитель отступил — вы в безопасности.");
                return;
            }

            s.ticksLeft--;
            double progress = s.ticksLeft / 30.0;
            s.thiefBar.setProgress(Math.max(0.0, progress));
            s.victimBar.setProgress(Math.max(0.0, progress));

            if (s.ticksLeft <= 0) {
                completeRobbery(s, t, v);
            }
        }, 10L, 10L);
    }

    private void cleanupSession(RobberySession s,
                                Player notifyA, String msgA,
                                Player notifyB, String msgB) {
        if (s.task != null) s.task.cancel();
        s.thiefBar.removeAll();
        s.victimBar.removeAll();
        activeRobberies.remove(s.thiefId);
        cooldowns.put(s.thiefId, System.currentTimeMillis());
        if (notifyA != null && msgA != null) notifyA.sendMessage(msgA);
        if (notifyB != null && msgB != null) notifyB.sendMessage(msgB);
    }

    private void completeRobbery(RobberySession s, Player thief, Player victim) {
        if (s.task != null) s.task.cancel();
        s.thiefBar.removeAll();
        s.victimBar.removeAll();
        activeRobberies.remove(s.thiefId);

        if (economy == null) {
            thief.sendMessage("§c[Вор] §7Экономика недоступна.");
            return;
        }

        int level = getLevel(thief.getUniqueId());
        double pct = level == 1 ? 0.05 : level == 2 ? 0.10 : 0.15;
        double balance = economy.getBalance(victim);
        double stolen = Math.floor(balance * pct * 100.0) / 100.0;

        if (stolen <= 0) {
            thief.sendMessage("§8[Вор] §7У §f" + victim.getName() + " §7нет денег.");
            victim.sendMessage("§c[Вор] §7Вас ограбили, но у вас не было денег.");
            return;
        }

        economy.withdrawPlayer(victim, stolen);
        economy.depositPlayer(thief, stolen);

        thief.sendMessage("§a[Вор] §7Вы украли §f$" + String.format("%.2f", stolen)
            + " §7у §f" + victim.getName() + "§7!");
        victim.sendMessage("§c[Вор] §7Игрок §f" + thief.getName()
            + " §7украл у вас §f$" + String.format("%.2f", stolen) + "§7!");
    }

    public void cancelAllRobberies() {
        for (RobberySession s : new ArrayList<>(activeRobberies.values())) {
            cleanupSession(s, null, null, null, null);
        }
    }

    public void resetPlayer(UUID uuid) {
        levelCache.put(uuid, 0);
        File f = new File(dataFolder, uuid + ".yml");
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            cfg.set("level", 0);
            try { cfg.save(f); } catch (IOException e) { e.printStackTrace(); }
        }
    }

    // ==================== UPGRADE MENU ====================

    public void openUpgradeMenu(Player player) {
        int level = getLevel(player.getUniqueId());
        Inventory menu = Bukkit.createInventory(null, 27, UPGRADE_TITLE);

        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta(); gm.setDisplayName("§7"); glass.setItemMeta(gm);
        for (int i = 0; i < 27; i++) menu.setItem(i, glass);

        // Слот 4: информация
        ItemStack info = new ItemStack(Material.IRON_SWORD);
        ItemMeta im = info.getItemMeta();
        im.setDisplayName("§8§lНавык вора");
        im.setLore(Arrays.asList(
            "§7Уровень: " + (level == 0 ? "§cне открыт" : "§e" + level + " §7/ §e3"),
            "§7Кража: §e" + (level == 0 ? "§cнет" : (level == 1 ? "5%" : level == 2 ? "10%" : "15%")),
            "§7Команда: §f/rob <ник>"
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
            ItemStack req = new ItemStack(Material.BOOK);
            ItemMeta rm = req.getItemMeta();
            rm.setDisplayName("§e§lТребования для уровня " + (level + 1));
            rm.setLore(buildReqLore(level + 1, player));
            req.setItemMeta(rm);
            menu.setItem(13, req);

            ItemStack btn = new ItemStack(Material.EMERALD);
            ItemMeta bm = btn.getItemMeta();
            bm.setDisplayName("§a§l▶ УЛУЧШИТЬ ДО УРОВНЯ " + (level + 1));
            bm.setLore(Arrays.asList("§7Убедитесь, что у вас есть все ресурсы"));
            btn.setItemMeta(bm);
            menu.setItem(22, btn);
        }

        player.openInventory(menu);
    }

    private List<String> buildReqLore(int targetLevel, Player player) {
        Object[][] reqs = targetLevel == 1 ? REQ1 : targetLevel == 2 ? REQ2 : REQ3;
        double money = targetLevel == 1 ? MONEY1 : targetLevel == 2 ? MONEY2 : MONEY3;
        List<String> lore = new ArrayList<>();
        for (Object[] r : reqs) {
            Material mat = (Material) r[0];
            int need = (int) r[1];
            int has  = countItems(player, mat);
            lore.add((has >= need ? "§a" : "§c") + matName(mat)
                + ": §f" + need + " §7(у вас: " + has + ")");
        }
        if (money > 0) {
            double has = economy != null ? economy.getBalance(player) : 0;
            lore.add((has >= money ? "§a" : "§c") + "Деньги: §f$" + (int) money
                + " §7(у вас: $" + (int) has + ")");
        }
        return lore;
    }

    private String matName(Material m) {
        switch (m) {
            case BOOKSHELF:        return "Книжная полка";
            case BOOK:             return "Книга";
            case IRON_INGOT:       return "Железный слиток";
            case GOLD_INGOT:       return "Золотой слиток";
            case SHEARS:           return "Ножницы";
            case CRAFTING_TABLE:   return "Верстак";
            case SPYGLASS:         return "Подзорная труба";
            case DIAMOND:          return "Алмаз";
            case NETHERITE_INGOT:  return "Незеритовый слиток";
            case ENDER_PEARL:      return "Эндер-жемчуг";
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

        if (money > 0) {
            if (economy == null) {
                player.sendMessage("§c[Навыки] §7Vault/экономика недоступна.");
                return;
            }
            double has = economy.getBalance(player);
            if (has < money) {
                player.sendMessage("§c[Навыки] §7Недостаточно денег (нужно §f$"
                    + (int) money + "§7, у вас §f$" + (int) has + "§7)");
                return;
            }
        }

        for (Object[] r : reqs) removeItems(player, (Material) r[0], (int) r[1]);
        if (money > 0 && economy != null) economy.withdrawPlayer(player, money);

        setLevel(uuid, target);
        plugin.setUnlocked(player, SkillsPlugin.SKILL_IDS[1], true);

        player.sendMessage("§a[Навыки] §7Навык §8Вора §7улучшен до уровня §e" + target + "§7!");
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
        if (e.getRawSlot() == 22) tryUpgrade((Player) e.getWhoClicked());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        // Вор вышел — отменяем его ограбление
        RobberySession s = activeRobberies.get(uuid);
        if (s != null) cleanupSession(s, null, null, null, null);
        // Жертва вышла — будет обнаружено в таймере (v == null)
        levelCache.remove(uuid);
    }
}
