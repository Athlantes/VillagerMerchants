package org.riftlabs.villagerMerchants;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.riftlabs.villagerMerchants.commands.ShopCommand;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.listeners.ChatPromptListener;
import org.riftlabs.villagerMerchants.listeners.CustomerTradeListener;
import org.riftlabs.villagerMerchants.listeners.GuiListener;
import org.riftlabs.villagerMerchants.listeners.JoinListener;
import org.riftlabs.villagerMerchants.listeners.NpcLookTask;
import org.riftlabs.villagerMerchants.listeners.PricePromptListener;
import org.riftlabs.villagerMerchants.listeners.ShopInteractListener;
import org.riftlabs.villagerMerchants.manager.Access;
import org.riftlabs.villagerMerchants.manager.AntiAbuse;
import org.riftlabs.villagerMerchants.manager.CategoryRegistry;
import org.riftlabs.villagerMerchants.manager.EffectManager;
import org.riftlabs.villagerMerchants.manager.NametagManager;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.manager.ProtectionManager;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.manager.StaffManager;
import org.riftlabs.villagerMerchants.manager.TeleportManager;
import org.riftlabs.villagerMerchants.manager.TransactionManager;
import org.riftlabs.villagerMerchants.util.CurrencyToken;
import org.riftlabs.villagerMerchants.util.CustomItems;
import org.riftlabs.villagerMerchants.util.Messages;

public final class VillagerMerchants extends JavaPlugin {

    private static VillagerMerchants instance;
    private DatabaseManager databaseManager;
    private ShopManager shopManager;
    private TransactionManager transactions;
    private RatingManager ratings;
    private TeleportManager teleports;
    private NametagManager nametagManager;
    private EffectManager effectManager;
    private CustomerTradeListener customerTrades;
    private PricePromptListener prompts;
    private ChatPromptListener chatPrompts;
    private PromotionManager promotions;
    private StaffManager staff;
    private CategoryRegistry categories;
    private Economy econ;
    private Messages messages;
    private Access access;
    private AntiAbuse antiAbuse;
    private ProtectionManager protection;

    @Override
    public void onLoad() {
        // WorldGuard only accepts new flags while plugins load, so the vm-shops flag has to be registered here
        saveDefaultConfig();
        ProtectionManager.registerFlags(this);
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        messages = new Messages(this);   // lang/<language>.yml
        access = new Access(this);       // every permission check goes through this

        if (!setupEconomy()) {
            getLogger().severe("Disabled: Vault (with an economy provider) was not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        CurrencyToken.init(this);
        CustomItems.init(this);

        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        antiAbuse = new AntiAbuse(this); // blacklist, price caps, purchase cooldown, suspicious log
        protection = new ProtectionManager(this); // WorldGuard regions/flags, GriefPrevention, Lands

        databaseManager = new DatabaseManager(this);
        categories = new CategoryRegistry(this);
        promotions = new PromotionManager(this);
        staff = new StaffManager(this);
        transactions = new TransactionManager(this, databaseManager);
        ratings = new RatingManager(this, databaseManager);
        teleports = new TeleportManager(this);

        // Critical: Initialize managers BEFORE loading data
        nametagManager = new NametagManager(this);
        effectManager = new EffectManager(this);
        access.registerAll();            // after effects load, so effect.<id> nodes exist
        shopManager = new ShopManager(this, databaseManager);

        shopManager.load();
        transactions.load();
        ratings.load();
        shopManager.startTasks();
        promotions.startTasks();

        customerTrades = new CustomerTradeListener(this);
        prompts = new PricePromptListener(this);
        chatPrompts = new ChatPromptListener(this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopInteractListener(this), this);
        getServer().getPluginManager().registerEvents(customerTrades, this);
        getServer().getPluginManager().registerEvents(prompts, this);
        getServer().getPluginManager().registerEvents(chatPrompts, this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        if (getConfig().getBoolean("npc.look-at-players", true)) {
            long ticks = Math.max(1, getConfig().getLong("npc.look-update-ticks", 4));
            getServer().getScheduler().runTaskTimer(this, new NpcLookTask(this), 20L, ticks);
        }

        ShopCommand cmd = new ShopCommand(this);
        for (String name : new String[]{"vm", "shops"}) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(cmd);
                getCommand(name).setTabCompleter(cmd);
            }
        }
        getLogger().info("VillagerMerchants enabled.");
    }

    @Override
    public void onDisable() {
        if (antiAbuse != null) antiAbuse.close();
        if (nametagManager != null) nametagManager.cleanup();
        if (effectManager != null) effectManager.cleanup();
        if (shopManager != null) {
            shopManager.saveAll();
            shopManager.despawnAll(); // Deletes all physical NPCs from the world to prevent chunk saving
        }
        if (databaseManager != null) databaseManager.disconnect();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }

    /** Reloads config.yml, the language file, effects.yml and categories.yml. */
    public void reloadAll() {
        reloadConfig();
        CustomItems.init(this);
        antiAbuse.reload();
        protection.reload();
        messages.load();
        effectManager.reload();
        categories.load();
        access.registerAll();   // picks up permissions.strict and new effect / nametag ids
    }

    /** Icon material from {@code gui.materials.<key>} in config.yml, or {@code def} if unset or invalid. */
    public Material material(String key, Material def) {
        String raw = getConfig().getString("gui.materials." + key);
        if (raw == null || raw.isBlank()) return def;
        Material m = Material.matchMaterial(raw.trim());
        return (m == null || m.isAir() || !m.isItem()) ? def : m;
    }

    public void sync(Runnable r) {
        if (isEnabled()) getServer().getScheduler().runTask(this, r);
    }

    public static VillagerMerchants getInstance() { return instance; }
    public Messages msg() { return messages; }
    public Messages getMessages() { return messages; }
    public Access access() { return access; }
    public AntiAbuse getAntiAbuse() { return antiAbuse; }
    public ProtectionManager getProtection() { return protection; }
    public Economy getEconomy() { return econ; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public ShopManager getShopManager() { return shopManager; }
    public TransactionManager getTransactions() { return transactions; }
    public RatingManager getRatings() { return ratings; }
    public TeleportManager getTeleports() { return teleports; }
    public NametagManager getNametagManager() { return nametagManager; }
    public EffectManager getEffectManager() { return effectManager; }
    public CustomerTradeListener getCustomerTrades() { return customerTrades; }
    public PricePromptListener getPrompts() { return prompts; }
    public ChatPromptListener getChatPrompts() { return chatPrompts; }
    public PromotionManager getPromotions() { return promotions; }
    public StaffManager getStaff() { return staff; }
    public CategoryRegistry getCategories() { return categories; }
}