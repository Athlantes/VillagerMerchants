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
    private static final int BACK = 22;

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;
    private UpgradeType confirming;

    public UpgradeGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.inv = Bukkit.createInventory(this, 27, plugin.msg().get("upgrade.title"));
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
        var m = plugin.msg();
        ShopManager sm = plugin.getShopManager();
        PlayerProfile p = sm.getProfile(player.getUniqueId());
        for (UpgradeType t : UpgradeType.values()) {
            int lvl = sm.level(t, p), max = sm.maxLevel(t);
            boolean maxed = lvl >= max;
            Material mat = switch (t) {
                case SLOTS -> plugin.material("upgrade-slots", Material.BARREL);
                case TAX -> plugin.material("upgrade-tax", Material.GOLD_INGOT);
                case DURATION -> plugin.material("upgrade-duration", Material.CLOCK);
            };
            String base = "upgrade.types." + t.key;
            String name = m.raw(base + ".name");
            String now = switch (t) {
                case SLOTS -> m.str(base + ".current", "slots", p.getUnlockedSlots());
                case TAX -> m.str(base + ".current", "percent", (int) Math.round(p.getTaxLevel() * sm.perLevel(t) * 100));
                case DURATION -> m.str(base + ".current", "days", p.getMaxRentDays());
            };
            String gain = switch (t) {
                case SLOTS -> m.str(base + ".gain", "amount", (int) sm.perLevel(t));
                case TAX -> m.str(base + ".gain", "percent", (int) Math.round(sm.perLevel(t) * 100));
                case DURATION -> m.str(base + ".gain", "days", (int) sm.perLevel(t));
            };
            String cost = sm.money(sm.upgradeCost(t, p));
            ItemStack it;
            if (!plugin.access().canUpgrade(player, t)) {
                it = m.icon(Material.BARRIER, "upgrade.item.locked", "name", name);
            } else if (maxed) {
                it = m.icon(mat, "upgrade.item.maxed", "name", name, "current", now);
            } else if (confirming == t) {
                it = Text.glow(m.icon(mat, "upgrade.item.confirm", "gain", gain, "cost", cost));
            } else {
                it = m.icon(mat, "upgrade.item.buy", "name", name, "level", lvl, "max", max,
                        "current", now, "gain", gain, "cost", cost);
            }
            inv.setItem(slotOf(t), it);
        }
        inv.setItem(BACK, Gui.back(plugin));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        if (slot == BACK) {
            Bukkit.getScheduler().runTask(plugin, () -> new OwnerMenuGui(plugin, player, shop).open());
            return;
        }
        UpgradeType t = typeAt(slot);
        if (t == null) return;
        if (!plugin.access().canUpgrade(player, t)) {
            plugin.msg().send(player, "upgrade.no-permission");
            return;
        }
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
