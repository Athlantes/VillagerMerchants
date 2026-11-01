package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.UUID;

public class RentGui implements GuiHolder {
    private final VillagerMerchants plugin;
    private final UUID npcId;
    private final Inventory inv;
    private final Player player;
    private boolean confirming;

    public RentGui(VillagerMerchants plugin, Player player, UUID npcId) {
        this.plugin = plugin;
        this.player = player;
        this.npcId = npcId;
        this.inv = Bukkit.createInventory(this, 27, Text.mm("<dark_gray>Rent this shop?"));
        render();
    }

    private void render() {
        ShopManager sm = plugin.getShopManager();
        PlayerProfile prof = sm.getProfile(player.getUniqueId());
        int days = Math.min(plugin.getConfig().getInt("rent.base-days", 7), prof.getMaxRentDays());

        inv.setItem(13, Text.icon(Material.PAPER, "<gold>Rent Cost: " + sm.money(sm.rentPrice(prof)),
                "<gray>Duration: <white>" + days + " days",
                "<gray>Trade slots: <white>" + prof.getUnlockedSlots(),
                "<gray>Rent discount: <white>" + (int) Math.round(prof.getTaxLevel()
                        * plugin.getConfig().getDouble("upgrades.tax.per-level", 0.05) * 100) + "%"));
        inv.setItem(11, confirming
                ? Text.glow(Text.icon(Material.YELLOW_CONCRETE, "<yellow>Click again to confirm"))
                : Text.icon(Material.LIME_CONCRETE, "<green>Rent Shop"));
        inv.setItem(15, Text.icon(Material.RED_CONCRETE, "<red>Cancel"));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        if (slot == 15) {
            player.closeInventory();
        } else if (slot == 11) {
            if (!confirming) {
                confirming = true;
                render();
            } else {
                player.closeInventory();
                sm().rentShop(player, npcId);
            }
        }
    }

    private ShopManager sm() { return plugin.getShopManager(); }

    @Override public Inventory getInventory() { return inv; }
}
