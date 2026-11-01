package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.manager.UpgradeType;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

public class UpgradeGui implements GuiHolder, ShopBound {
    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;
    private UpgradeType confirming;

    public UpgradeGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.inv = Bukkit.createInventory(this, 27, Text.mm("<dark_gray>Upgrades"));
        render();
    }

    private int slotOf(UpgradeType t) {
        return switch (t) { case SLOTS -> 11; case TAX -> 13; case DURATION -> 15; };
    }

    private UpgradeType typeAt(int slot) {
        for (UpgradeType t : UpgradeType.values()) if (slotOf(t) == slot) return t;
        return null;
    }

    private void render() {
        ShopManager sm = plugin.getShopManager();
        PlayerProfile p = sm.getProfile(player.getUniqueId());
        for (UpgradeType t : UpgradeType.values()) {
            int lvl = sm.level(t, p), max = sm.maxLevel(t);
            boolean maxed = lvl >= max;
            Material mat = switch (t) { case SLOTS -> Material.BARREL; case TAX -> Material.GOLD_INGOT; case DURATION -> Material.CLOCK; };
            String name = switch (t) { case SLOTS -> "Trade Slots"; case TAX -> "Rent Discount"; case DURATION -> "Max Rent Duration"; };
            String now = switch (t) {
                case SLOTS -> p.getUnlockedSlots() + " slots";
                case TAX -> (int) Math.round(p.getTaxLevel() * sm.perLevel(t) * 100) + "% off rent";
                case DURATION -> p.getMaxRentDays() + " days";
            };
            String gain = switch (t) {
                case SLOTS -> "+" + (int) sm.perLevel(t) + " slot";
                case TAX -> "+" + (int) Math.round(sm.perLevel(t) * 100) + "% off";
                case DURATION -> "+" + (int) sm.perLevel(t) + " days";
            };
            ItemStack it;
            if (maxed) {
                it = Text.icon(mat, "<gold>" + name + " <green>(MAX)", "<gray>Current: <white>" + now);
            } else if (confirming == t) {
                it = Text.glow(Text.icon(mat, "<yellow>Click again to confirm",
                        "<gray>" + gain + " for <white>" + sm.money(sm.upgradeCost(t, p))));
            } else {
                it = Text.icon(mat, "<gold>" + name, "<gray>Level: <white>" + lvl + "/" + max,
                        "<gray>Current: <white>" + now, "<gray>Next: <green>" + gain,
                        "<gray>Cost: <white>" + sm.money(sm.upgradeCost(t, p)), "<yellow>Click to buy");
            }
            inv.setItem(slotOf(t), it);
        }
        inv.setItem(22, Text.icon(Material.OAK_DOOR, "<red>Back"));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        if (slot == 22) {
            Bukkit.getScheduler().runTask(plugin, () -> new OwnerMenuGui(plugin, player, shop).open());
            return;
        }
        UpgradeType t = typeAt(slot);
        if (t == null) return;
        if (confirming != t) {
            confirming = t;
        } else {
            confirming = null;
            plugin.getShopManager().purchaseUpgrade(player, t);
        }
        render();
    }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
