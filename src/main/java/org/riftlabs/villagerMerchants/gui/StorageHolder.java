package org.riftlabs.villagerMerchants.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.model.Shop;

public class StorageHolder implements GuiHolder, ShopBound {
    private final Shop shop;

    public StorageHolder(Shop shop) { this.shop = shop; }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return shop.getStorage(); }

    // Storage is a real inventory: allow normal interaction, just flag for saving.
    @Override public void onClick(InventoryClickEvent e) { shop.setDirty(true); }
    @Override public void onDrag(InventoryDragEvent e) { shop.setDirty(true); }
    @Override public void onClose(InventoryCloseEvent e) {
        VillagerMerchants.getInstance().getShopManager().saveShop(shop);
    }
}
