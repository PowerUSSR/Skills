package org.mineserver.skills;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

public class SkillsPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private File dataFile;
    private org.bukkit.configuration.file.FileConfiguration dataConfig;
    EnderChestSkill enderChestSkill;
    ThiefSkill thiefSkill;
    StealthSkill stealthSkill;

    // ==================== IDs / NAMES ====================

    static final String[] SKILL_IDS   = { "ender_chest", "thief", "stealth" };
    static final String[] SKILL_NAMES = { "§5Эндер-сундук", "§8Навык вора", "§7Скрытность" };
    static final String[] SKILL_DESC  = {
        "§7Позволяет открывать Эндер-сундук",
        "§7Особые способности вора",
        "§7Скрытность от карт и радаров"
    };

    // Base64-текстуры голов (из minecraft-heads.com, раздел «For Developers → Value»)
    // ender_chest: Treasure Chest (purple) #45975
    // thief:       Man #8592
    // stealth:     Enchanting Table #2116
    private static final String[] DEFAULT_TEXTURES = {
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWIwZTA2MDAyODg1MTE2MTUyYmFkNGI2NjI4NmYxZjMxN2Y1OTljZDYwYWNkMWI5MDhiYTZhNWM1MDhiZjVlMSJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjI2NjViZWU1MzdlYjAyMzI2ZWY5MWNkMTVkMjliYzQ5OWI1N2ZjYWE2NjcxODM3MjNhM2E3MjNkYjk0NmQ2MyJ9fX0=",
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNjI2NzJjODdlZWY2ODE4ODI4OTE4ZGQzY2EwMzg1NmNjYjQzNjZlN2M5YWMyNjI0YTk0MmYwZGI3ZTk2YSJ9fX0="
    };

    // Слоты в 9-слотовом инвентаре
    private static final int[] SKILL_SLOTS = { 2, 4, 6 };

    static final String GUI_TITLE = "§8⚔ Навыки";

    // ==================== ENABLE / DISABLE ====================

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
        }
        dataConfig = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(dataFile);

        enderChestSkill = new EnderChestSkill(this);
        thiefSkill = new ThiefSkill(this);
        stealthSkill = new StealthSkill(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(enderChestSkill, this);
        getServer().getPluginManager().registerEvents(thiefSkill, this);
        getServer().getPluginManager().registerEvents(stealthSkill, this);
        getCommand("skills").setExecutor(this);
        getCommand("rob").setExecutor(this);
        getLogger().info("Skills включён.");
    }

    @Override
    public void onDisable() {
        if (enderChestSkill != null) enderChestSkill.saveAll();
        if (thiefSkill != null) thiefSkill.cancelAllRobberies();
        saveDataConfig();
        getLogger().info("Skills отключён.");
    }

    void saveDataConfig() {
        try { dataConfig.save(dataFile); } catch (IOException e) { e.printStackTrace(); }
    }

    // ==================== COMMAND ====================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("rob")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cТолько для игроков!");
                return true;
            }
            return thiefSkill.handleRob((Player) sender, args);
        }

        // /skills [ник] — если аргумент есть, это обнуление для оператора
        if (args.length > 0) {
            if (!sender.isOp()) {
                sender.sendMessage("§c[Навыки] §7Нет доступа.");
                return true;
            }
            @SuppressWarnings("deprecation")
            org.bukkit.OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            if (!target.hasPlayedBefore() && !target.isOnline()) {
                sender.sendMessage("§c[Навыки] §7Игрок §f" + args[0] + " §7не найден.");
                return true;
            }
            UUID targetUUID = target.getUniqueId();
            enderChestSkill.resetPlayer(targetUUID);
            thiefSkill.resetPlayer(targetUUID);
            stealthSkill.resetPlayer(targetUUID);
            dataConfig.set("unlocked." + targetUUID, null);
            saveDataConfig();
            String name = target.getName() != null ? target.getName() : args[0];
            sender.sendMessage("§a[Навыки] §7Навыки игрока §f" + name + " §7обнулены.");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage("§cТолько для игроков!");
            return true;
        }
        openGui((Player) sender);
        return true;
    }

    // ==================== GUI ====================

    void openGui(Player player) {
        Inventory inv = getServer().createInventory(null, 9, GUI_TITLE);

        // Фон — серые стеклянные панели
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta gm = glass.getItemMeta();
        gm.setDisplayName(" ");
        glass.setItemMeta(gm);
        for (int i = 0; i < 9; i++) inv.setItem(i, glass);

        // Три навыка
        for (int i = 0; i < 3; i++) {
            inv.setItem(SKILL_SLOTS[i], buildSkillItem(player, i));
        }

        player.openInventory(inv);
    }

    ItemStack buildSkillItem(Player player, int index) {
        // Для эндер-сундука — отдельная логика с уровнями
        if (index == 0) {
            int ecLevel = enderChestSkill.getLevel(player.getUniqueId());
            String tex = getConfig().getString("skill-textures.ender_chest", DEFAULT_TEXTURES[0]);
            ItemStack skull = makeSkull(tex);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setDisplayName((ecLevel >= 1 ? "§a✅ " : "§c") + SKILL_NAMES[0]);
            List<String> lore = new ArrayList<>();
            lore.add(SKILL_DESC[0]);
            lore.add("");
            if (ecLevel >= 1) lore.add("§7Уровень: §e" + ecLevel + " §7/ §e3");
            lore.add(ecLevel >= 1 ? "§eНажмите для улучшения" : "§eНажмите для открытия");
            meta.setLore(lore);
            skull.setItemMeta(meta);
            return skull;
        }
        // Для навыка вора — отдельная логика с уровнями
        if (index == 1) {
            int thiefLevel = thiefSkill.getLevel(player.getUniqueId());
            String tex = getConfig().getString("skill-textures.thief", DEFAULT_TEXTURES[1]);
            ItemStack skull = makeSkull(tex);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setDisplayName((thiefLevel >= 1 ? "§a✅ " : "§c") + SKILL_NAMES[1]);
            List<String> lore = new ArrayList<>();
            lore.add(SKILL_DESC[1]);
            lore.add("");
            if (thiefLevel >= 1) lore.add("§7Уровень: §e" + thiefLevel + " §7/ §e3");
            lore.add(thiefLevel >= 1 ? "§eНажмите для улучшения" : "§eНажмите для открытия");
            meta.setLore(lore);
            skull.setItemMeta(meta);
            return skull;
        }
        // Для навыка скрытности — только один уровень (unlocked / locked)
        if (index == 2) {
            boolean stealthOn = stealthSkill.isUnlocked(player.getUniqueId());
            String tex = getConfig().getString("skill-textures.stealth", DEFAULT_TEXTURES[2]);
            ItemStack skull = makeSkull(tex);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            meta.setDisplayName((stealthOn ? "§a✅ " : "§c") + SKILL_NAMES[2]);
            List<String> lore = new ArrayList<>();
            lore.add(SKILL_DESC[2]);
            lore.add("");
            lore.add(stealthOn ? "§aНавык разблокирован" : "§eНажмите для открытия");
            meta.setLore(lore);
            skull.setItemMeta(meta);
            return skull;
        }

        boolean unlocked = isUnlocked(player, SKILL_IDS[index]);
        String texUrl = getConfig().getString(
            "skill-textures." + SKILL_IDS[index], DEFAULT_TEXTURES[index]);
        ItemStack skull = makeSkull(texUrl);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setDisplayName((unlocked ? "§a✅ " : "§c🔒 ") + SKILL_NAMES[index]);
        List<String> lore = new ArrayList<>();
        lore.add(SKILL_DESC[index]);
        lore.add("");
        lore.add(unlocked ? "§aНавык открыт" : "§eНажмите для подробностей");
        meta.setLore(lore);
        skull.setItemMeta(meta);
        return skull;
    }

    private ItemStack makeSkull(String base64) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        try {
            Class<?> gpClass  = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> prpClass = Class.forName("com.mojang.authlib.properties.Property");
            Object profile  = gpClass.getConstructor(UUID.class, String.class)
                                     .newInstance(UUID.randomUUID(), null);
            Object property = prpClass.getConstructor(String.class, String.class)
                                      .newInstance("textures", base64);
            Object propMap = gpClass.getMethod("getProperties").invoke(profile);
            propMap.getClass().getMethod("put", Object.class, Object.class)
                              .invoke(propMap, "textures", property);
            Field f = meta.getClass().getDeclaredField("profile");
            f.setAccessible(true);
            f.set(meta, profile);
        } catch (Exception e) { e.printStackTrace(); }
        item.setItemMeta(meta);
        return item;
    }

    // ==================== INVENTORY CLICK ====================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 9) return;

        Player player = (Player) event.getWhoClicked();
        for (int i = 0; i < SKILL_SLOTS.length; i++) {
            if (slot == SKILL_SLOTS[i]) {
                onSkillClick(player, i);
                return;
            }
        }
    }

    private void onSkillClick(Player player, int index) {
        if (index == 0) {
            enderChestSkill.openUpgradeMenu(player);
            return;
        }
        if (index == 1) {
            thiefSkill.openUpgradeMenu(player);
            return;
        }
        if (index == 2) {
            stealthSkill.openUpgradeMenu(player);
            return;
        }
        player.sendMessage("§8[Навыки] §7Страница навыка «"
            + SKILL_NAMES[index] + "§7» — скоро будет доступна.");
        player.closeInventory();
    }

    // ==================== DATA ====================

    boolean isUnlocked(Player player, String skillId) {
        return dataConfig.getBoolean("unlocked." + player.getUniqueId() + "." + skillId, false);
    }

    void setUnlocked(Player player, String skillId, boolean value) {
        dataConfig.set("unlocked." + player.getUniqueId() + "." + skillId, value);
        saveDataConfig();
    }

    org.bukkit.configuration.file.FileConfiguration getDataConfig() {
        return dataConfig;
    }
}
