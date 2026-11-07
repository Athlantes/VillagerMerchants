package org.riftlabs.villagerMerchants.listeners;

import org.riftlabs.villagerMerchants.util.Sched;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.UUID;

public class RentMenuListener implements Listener {

    private final VillagerMerchants plugin;
    private final double rentPrice = 5000.0; // Pull this from config.yml later

    // Tracks which player is looking at which NPC
    private final java.util.Map<UUID, UUID> activeRentSessions = new java.util.concurrent.ConcurrentHashMap<>();

    public RentMenuListener(VillagerMerchants plugin) {
        this.plugin = plugin;
    }

    public void openRentMenu(Player player, UUID npcUuid) {
        activeRentSessions.put(player.getUniqueId(), npcUuid);
        Inventory gui = Bukkit.createInventory(null, 27, Component.text("Rent Shop?"));

        // Slot 13: Info Item
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.displayName(Component.text("Rent Cost: $" + rentPrice).color(NamedTextColor.GOLD));
        info.setItemMeta(infoMeta);
        gui.setItem(13, info);

        // Slot 11: Accept Button
        ItemStack accept = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta acceptMeta = accept.getItemMeta();
        acceptMeta.displayName(Component.text("Rent Shop").color(NamedTextColor.GREEN));
        accept.setItemMeta(acceptMeta);
        gui.setItem(11, accept);

        // Slot 15: Decline Button
        ItemStack decline = new ItemStack(Material.RED_CONCRETE);
        ItemMeta declineMeta = decline.getItemMeta();
        declineMeta.displayName(Component.text("Cancel").color(NamedTextColor.RED));
        decline.setItemMeta(declineMeta);
        gui.setItem(15, decline);

        player.openInventory(gui);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!event.getView().title().equals(Component.text("Rent Shop?"))) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getCurrentItem() == null) return;

        int slot = event.getRawSlot();

        if (slot == 15) { // Decline
            player.closeInventory();
        } else if (slot == 11) { // Accept
            ItemStack clicked = event.getCurrentItem();
            ItemMeta meta = clicked.getItemMeta();

            // First Click: Add enchant to confirm
            if (!meta.hasEnchant(Enchantment.UNBREAKING)) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                meta.displayName(Component.text("Click again to confirm").color(NamedTextColor.YELLOW));
                clicked.setItemMeta(meta);
            }
            // Second Click: Process Transaction
            else {
                UUID npcUuid = activeRentSessions.get(player.getUniqueId());
                if (npcUuid != null) {
                    processRentTransaction(player, npcUuid);
                }
                player.closeInventory();
            }
        }
    }

    @EventHandler
    public void onMenuClose(InventoryCloseEvent event) {
        activeRentSessions.remove(event.getPlayer().getUniqueId());
    }

    // 3. Vault & Database Transaction
    private void processRentTransaction(Player player, UUID npcUuid) {
        if (!plugin.getEconomy().has(player, rentPrice)) {
            player.sendMessage(Component.text("You do not have enough money!").color(NamedTextColor.RED));
            return;
        }

        // Deduct money
        plugin.getEconomy().withdrawPlayer(player, rentPrice);

        // Execute SQL Async
        Sched.async(() -> {
            // INSERT OR IGNORE creates the profile only if it doesn't exist (SQLite syntax)
            String profileQuery = "INSERT OR IGNORE INTO player_profiles (uuid) VALUES (?);";

            // Generate a 7-day expiry timestamp (in milliseconds)
            long expiryTime = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000);
            String shopQuery = "INSERT INTO shops (shop_id, owner_uuid, rent_expiry) VALUES (?, ?, ?);";

            try (Connection conn = plugin.getDatabaseManager().getConnection();
                 PreparedStatement profileStmt = conn.prepareStatement(profileQuery);
                 PreparedStatement shopStmt = conn.prepareStatement(shopQuery)) {

                // Initialize profile
                profileStmt.setString(1, player.getUniqueId().toString());
                profileStmt.executeUpdate();

                // Claim shop
                shopStmt.setString(1, npcUuid.toString());
                shopStmt.setString(2, player.getUniqueId().toString());
                shopStmt.setLong(3, expiryTime);
                shopStmt.executeUpdate();

                Sched.entity(player, () -> {
                    player.sendMessage(Component.text("Shop successfully rented for 7 days!").color(NamedTextColor.GREEN));
                    // Optional: Update NPC nametag here
                });

            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to rent shop in database!");
                Sched.entity(player, () -> {   // refund and message on the player's own thread, not the async one
                    plugin.getEconomy().depositPlayer(player, rentPrice); // Refund on failure
                    player.sendMessage(Component.text("An error occurred. You have been refunded.").color(NamedTextColor.RED));
                });
            }
        });
    }
}