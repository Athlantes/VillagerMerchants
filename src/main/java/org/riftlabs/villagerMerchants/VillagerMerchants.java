package org.riftlabs.villagerMerchants;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.riftlabs.villagerMerchants.commands.ShopCommand;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.economy.EconomyManager;
import org.riftlabs.villagerMerchants.listeners.ChatPromptListener;
import org.riftlabs.villagerMerchants.listeners.CustomerTradeListener;
import org.riftlabs.villagerMerchants.listeners.GuiListener;
import org.riftlabs.villagerMerchants.listeners.JoinListener;
import org.riftlabs.villagerMerchants.listeners.PricePromptListener;
import org.riftlabs.villagerMerchants.listeners.ShopInteractListener;
import org.riftlabs.villagerMerchants.manager.Access;
import org.riftlabs.villagerMerchants.manager.AntiAbuse;
import org.riftlabs.villagerMerchants.manager.CategoryRegistry;
import org.riftlabs.villagerMerchants.manager.EffectManager;
import org.riftlabs.villagerMerchants.manager.LeaderboardManager;
import org.riftlabs.villagerMerchants.manager.NametagManager;
import org.riftlabs.villagerMerchants.manager.NpcManager;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.manager.ProtectionManager;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.manager.StaffManager;
import org.riftlabs.villagerMerchants.manager.TeleportManager;
import org.riftlabs.villagerMerchants.manager.TransactionManager;
import org.riftlabs.villagerMerchants.papi.PapiHook;
import org.riftlabs.villagerMerchants.util.CurrencyToken;
import org.riftlabs.villagerMerchants.util.CustomItems;
import org.riftlabs.villagerMerchants.util.Sched;
import org.riftlabs.villagerMerchants.util.Messages;

public final class VillagerMerchants extends JavaPlugin {

    private static VillagerMerchants instance;
    private DatabaseManager databaseManager;
    private ShopManager shopManager;
    private NpcManager npcManager;
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
    private EconomyManager economies;
    private Messages messages;
    private Access access;
    private AntiAbuse antiAbuse;
    private org.riftlabs.villagerMerchants.analytics.DashboardManager dashboard;
    private ProtectionManager protection;
    private PapiHook papi;
    private LeaderboardManager leaderboards;

    @Override
    public void onLoad() {
        // WorldGuard only accepts new flags while plugins load, so the vm-shops flag has to be registered here
        saveDefaultConfig();
        ProtectionManager.registerFlags(this);
    }

    @Override
    public void onEnable() {
        instance = this;
        Sched.init(this);   // Folia / Paper scheduling, before anything schedules
        saveDefaultConfig();
        messages = new Messages(this);   // lang/<language>.yml
        access = new Access(this);       // every permission check goes through this

        if (!setupEconomy()) {
            getLogger().severe("Disabled: Vault (with an economy provider) was not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economies = new EconomyManager(this, econ);   // Vault + optional PlayerPoints / TokenManager

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
        npcManager = new NpcManager(this, databaseManager);
        npcManager.initHooks();          // MythicMobs / ModelEngine (soft dependencies, optional)
        shopManager = new ShopManager(this, databaseManager);

        shopManager.load();
        transactions.load();
        ratings.load();
        shopManager.startTasks();
        promotions.startTasks();
        leaderboards = new LeaderboardManager(this, databaseManager);
        leaderboards.load();
        leaderboards.startTasks();

        customerTrades = new CustomerTradeListener(this);
        prompts = new PricePromptListener(this);
        chatPrompts = new ChatPromptListener(this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        ShopInteractListener interact = new ShopInteractListener(this);
        getServer().getPluginManager().registerEvents(interact, this);
        // clicks on a ModelEngine hitbox open the shop too
        npcManager.modelEngine().registerInteractBridge(interact::handleClick, interact::isShopNpc);
        getServer().getPluginManager().registerEvents(customerTrades, this);
        getServer().getPluginManager().registerEvents(prompts, this);
        getServer().getPluginManager().registerEvents(chatPrompts, this);
        getServer().getPluginManager().registerEvents(new JoinListener(this), this);

        dashboard = new org.riftlabs.villagerMerchants.analytics.DashboardManager(this);   // map analytics dashboard
        getServer().getPluginManager().registerEvents(new org.riftlabs.villagerMerchants.listeners.DashboardListener(this), this);

        // NPCs turning to face players is done per NPC on its own region thread: see NpcLookTask.attach(), called when a body is bound.

        ShopCommand cmd = new ShopCommand(this);
        for (String name : new String[]{"vm", "shops"}) {
            if (getCommand(name) != null) {
                getCommand(name).setExecutor(cmd);
                getCommand(name).setTabCompleter(cmd);
            }
        }
        // %vm_...% placeholders. PapiHook is only loaded when PlaceholderAPI is present.
        if (getConfig().getBoolean("placeholders.enabled", true)
                && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            papi = PapiHook.register(this);
        }
        if (Sched.isFolia()) {
            getLogger().info("Folia detected: shops, NPCs, effects and menus run on their own region threads.");
            for (String other : new String[]{"MythicMobs", "ModelEngine"}) {
                if (getServer().getPluginManager().getPlugin(other) != null) {
                    getLogger().warning(other + " is installed. Linked shop bodies only work if that plugin itself supports Folia; "
                            + "vanilla villager shops are not affected.");
                }
            }
        }
        getLogger().info("VillagerMerchants enabled.");
    }

    @Override
    public void onDisable() {
        if (papi != null) papi.unregister();
        if (dashboard != null) dashboard.shutdown();   // hands hotbar items back before the server saves players
        Sched.cancelAll();   // global + async timers; entity / region tasks end with the plugin
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
        economies.reload();
        effectManager.reload();
        categories.load();
        npcManager.reloadSkins();
        access.registerAll();   // picks up permissions.strict and new effect / nametag ids
        if (leaderboards != null) leaderboards.reload();
        if (papi != null) papi.reload();
    }

    /** Icon material from {@code gui.materials.<key>} in config.yml, or {@code def} if unset or invalid. */
    public Material material(String key, Material def) {
        String raw = getConfig().getString("gui.materials." + key);
        if (raw == null || raw.isBlank()) return def;
        Material m = Material.matchMaterial(raw.trim());
        return (m == null || m.isAir() || !m.isItem()) ? def : m;
    }

    /** Next tick on the global region (not tied to any player or position). From a player context use {@link #syncFor}. */
    public void sync(Runnable r) { Sched.global(r); }

    /** Runs on the thread that owns {@code e} (a player, usually): inline if already there, otherwise on its next tick. */
    public void syncFor(org.bukkit.entity.Entity e, Runnable r) { Sched.entity(e, r); }

    /** For a command sender: a player's own thread, or the global region for the console / command blocks. */
    public void syncFor(org.bukkit.command.CommandSender s, Runnable r) {
        if (s instanceof org.bukkit.entity.Entity e) Sched.entity(e, r); else Sched.global(r);
    }

    public static VillagerMerchants getInstance() { return instance; }
    public Messages msg() { return messages; }
    public Messages getMessages() { return messages; }
    public Access access() { return access; }
    public AntiAbuse getAntiAbuse() { return antiAbuse; }
    public ProtectionManager getProtection() { return protection; }
    public Economy getEconomy() { return econ; }
    /** Every currency (Vault, PlayerPoints, TokenManager). Use this for anything that can be paid in a premium currency. */
    public EconomyManager getEconomies() { return economies; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public ShopManager getShopManager() { return shopManager; }
    public NpcManager getNpcs() { return npcManager; }
    public TransactionManager getTransactions() { return transactions; }
    public RatingManager getRatings() { return ratings; }
    public TeleportManager getTeleports() { return teleports; }
    public NametagManager getNametagManager() { return nametagManager; }
    public EffectManager getEffectManager() { return effectManager; }
    public CustomerTradeListener getCustomerTrades() { return customerTrades; }
    public PricePromptListener getPrompts() { return prompts; }
    public ChatPromptListener getChatPrompts() { return chatPrompts; }
    public PromotionManager getPromotions() { return promotions; }
    public org.riftlabs.villagerMerchants.analytics.DashboardManager getDashboard() { return dashboard; }
    public LeaderboardManager getLeaderboards() { return leaderboards; }
    public StaffManager getStaff() { return staff; }
    public CategoryRegistry getCategories() { return categories; }
}