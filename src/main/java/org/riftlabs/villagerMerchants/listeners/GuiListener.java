package org.riftlabs.villagerMerchants.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.gui.GuiHolder;
import org.riftlabs.villagerMerchants.gui.ShopBound;

/** Single dispatcher for every GuiHolder-based inventory. */
public class GuiListener implements Listener {
    private final VillagerMerchants plugin;

    public GuiListener(VillagerMerchants plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GuiHolder h)) return;
        if (h instanceof ShopBound sb && !plugin.getShopManager().isLive(sb.shop())) {
            e.setCancelled(true);
            e.getWhoClicked().closeInventory();
            return;
        }
        h.onClick(e);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof GuiHolder h) h.onDrag(e);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof GuiHolder h) h.onClose(e);
    }
}
