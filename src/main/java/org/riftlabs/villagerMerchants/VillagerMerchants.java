package org.riftlabs.villagerMerchants;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.riftlabs.villagerMerchants.commands.ShopCommand;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.listeners.CustomerTradeListener;
import org.riftlabs.villagerMerchants.listeners.GuiListener;
import org.riftlabs.villagerMerchants.listeners.JoinListener;
import org.riftlabs.villagerMerchants.listeners.NpcLookTask;
import org.riftlabs.villagerMerchants.listeners.PricePromptListener;
import org.riftlabs.villagerMerchants.listeners.ShopInteractListener;
import org.riftlabs.villagerMerchants.manager.NametagManager;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.manager.TeleportManager;
import org.riftlabs.villagerMerchants.manager.TransactionManager;
import org.riftlabs.villagerMerchants.util.CurrencyToken;

public final class VillagerMerchants extends JavaPlugin {

    private static VillagerMerchants instance;
    private DatabaseManager databaseManager;
    private ShopManager shopManager;
    private TransactionManager transactions;
    private RatingManager ratings;
    private TeleportManager teleports;
    private NametagManager nametagManager;
    private CustomerTradeListener customerTrades;
    private PricePromptListener prompts;
    private Economy econ;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!setupEconomy()) {
            getLogger().severe("Disabled: Vault (with an economy provider) was not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        CurrencyToken.init(this);
        registerPermissions();

        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        databaseManager = new DatabaseManager(this);
        transactions = new TransactionManager(this, databaseManager);
        ratings = new RatingManager(this, databaseManager);
        teleports = new TeleportManager(this);

        // Critical: Initialize managers BEFORE loading data
        nametagManager = new NametagManager(this);
        shopManager = new ShopManager(this, databaseManager);

        shopManager.load();
        transactions.load();
        ratings.load();
        shopManager.startTasks();

        customerTrades = new CustomerTradeListener(this);
        prompts = new PricePromptListener(this);
        getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        getServer().getPluginManager().registerEvents(new ShopInteractListener(this), this);
        getServer().getPluginManager().registerEvents(customerTrades, this);
        getServer().getPluginManager().registerEvents(prompts, this);
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
        if (nametagManager != null) nametagManager.cleanup();
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

    private void registerPermissions() {
        PluginManager pm = getServer().getPluginManager();
        addPermission(pm, "villagermerchants.shops", PermissionDefault.TRUE);
        addPermission(pm, "villagermerchants.shops.teleport", PermissionDefault.TRUE);
        addPermission(pm, "villagermerchants.shops.bypass", PermissionDefault.OP);
    }

    private void addPermission(PluginManager pm, String node, PermissionDefault def) {
        if (pm.getPermission(node) == null) pm.addPermission(new Permission(node, def));
    }

    public void sync(Runnable r) {
        if (isEnabled()) getServer().getScheduler().runTask(this, r);
    }

    public static VillagerMerchants getInstance() { return instance; }
    public Economy getEconomy() { return econ; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public ShopManager getShopManager() { return shopManager; }
    public TransactionManager getTransactions() { return transactions; }
    public RatingManager getRatings() { return ratings; }
    public TeleportManager getTeleports() { return teleports; }
    public NametagManager getNametagManager() { return nametagManager; }
    public CustomerTradeListener getCustomerTrades() { return customerTrades; }
    public PricePromptListener getPrompts() { return prompts; }
}