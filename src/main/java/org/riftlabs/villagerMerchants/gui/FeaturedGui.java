package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.manager.PromotionManager.FeaturedPackage;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.List;

/** Buy time at the top of the /shops directory. Click a package twice to confirm. */
public class FeaturedGui implements GuiHolder, ShopBound {
    private static final int INFO = 4, BACK = 22, FIRST = 10;   // up to 7 packages in row 1

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;
    private final List<FeaturedPackage> packages;
    private int confirming = -1;

    public FeaturedGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        List<FeaturedPackage> all = plugin.getPromotions().packages();
        this.packages = all.subList(0, Math.min(7, all.size()));
        this.inv = Bukkit.createInventory(this, 27, Text.mm("<dark_gray>Featured Listing"));
        render();
    }

    private void render() {
        inv.clear();
        PromotionManager promo = plugin.getPromotions();
        long now = System.currentTimeMillis();

        inv.setItem(INFO, Text.icon(Material.NETHER_STAR, shop.isFeatured(now) ? "<gold>Featured" : "<gold>Not featured",
                shop.isFeatured(now) ? "<gray>Time left: <white>" + Text.duration(shop.getFeaturedUntil() - now)
                        : "<gray>Pin your shop above all others in /shops.",
                "<gray>Slots in use: <white>" + promo.featuredInUse() + "/" + promo.maxFeaturedSlots(),
                "<dark_gray>Time keeps running while your rent is expired."));

        for (int i = 0; i < packages.size(); i++) {
            FeaturedPackage pkg = packages.get(i);
            String blocker = promo.featuredBlocker(shop, pkg);
            String name = "<yellow>" + pkg.hours() + " hours";
            if (blocker != null) {
                inv.setItem(FIRST + i, Text.icon(Material.GRAY_DYE, name, "<gray>Price: <white>" + plugin.getShopManager().money(pkg.price()), blocker));
            } else if (confirming == i) {
                inv.setItem(FIRST + i, Text.glow(Text.icon(Material.EMERALD, "<green>Click again to buy", "<gray>" + pkg.hours() + "h for <white>"
                        + plugin.getShopManager().money(pkg.price()), "<gray>Paid from your own balance.")));
            } else {
                inv.setItem(FIRST + i, Text.icon(Material.EMERALD, name, "<gray>Price: <white>" + plugin.getShopManager().money(pkg.price()),
                        shop.isFeatured(now) ? "<gray>Adds to your remaining time." : "<gray>Starts right away.", "<yellow>Click to buy"));
            }
        }
        inv.setItem(BACK, Text.icon(Material.OAK_DOOR, "<red>Back"));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        if (!shop.can(player.getUniqueId(), Perm.BILLING)) { player.closeInventory(); return; }
        int slot = e.getSlot();
        if (slot == BACK) { Bukkit.getScheduler().runTask(plugin, () -> new OwnerMenuGui(plugin, player, shop).open()); return; }
        int i = slot - FIRST;
        if (i < 0 || i >= packages.size()) { confirming = -1; render(); return; }
        if (confirming != i) {
            confirming = i;
        } else {
            confirming = -1;
            plugin.getPromotions().buyFeatured(player, shop, packages.get(i));
        }
        render();
    }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
