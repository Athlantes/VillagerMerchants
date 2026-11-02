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
        this.inv = Bukkit.createInventory(this, 27, plugin.msg().get("rent-gui.title"));
        render();
    }

    private void render() {
        var m = plugin.msg();
        ShopManager sm = plugin.getShopManager();
        PlayerProfile prof = sm.getProfile(player.getUniqueId());
        int days = Math.min(plugin.getConfig().getInt("rent.base-days", 7), prof.getMaxRentDays());
        int discount = (int) Math.round(prof.getTaxLevel() * plugin.getConfig().getDouble("upgrades.tax.per-level", 0.05) * 100);

        inv.setItem(13, m.icon(plugin.material("rent-info", Material.PAPER), "rent-gui.info",
                "cost", sm.money(sm.rentPrice(prof)), "days", days, "slots", prof.getUnlockedSlots(), "discount", discount));
        inv.setItem(11, confirming
                ? Text.glow(m.icon(plugin.material("rent-confirm", Material.YELLOW_CONCRETE), "gui.confirm"))
                : m.icon(plugin.material("rent-accept", Material.LIME_CONCRETE), "rent-gui.accept"));
        inv.setItem(15, m.icon(plugin.material("rent-cancel", Material.RED_CONCRETE), "gui.cancel"));
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
