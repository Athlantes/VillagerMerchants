package org.riftlabs.villagerMerchants.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/** Every plugin GUI is an InventoryHolder, so no title-string matching is needed. */
public interface GuiHolder extends InventoryHolder {
    default void onClick(InventoryClickEvent e) { e.setCancelled(true); }
    default void onDrag(InventoryDragEvent e) { e.setCancelled(true); }
    default void onClose(InventoryCloseEvent e) {}
}
