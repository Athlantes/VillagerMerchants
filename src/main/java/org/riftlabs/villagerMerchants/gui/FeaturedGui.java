package org.riftlabs.villagerMerchants.gui;

import net.kyori.adventure.text.Component;
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
        this.inv = Bukkit.createInventory(this, 27, plugin.msg().get("featured-gui.title"));
        render();
    }

    private void render() {
        inv.clear();
        var m = plugin.msg();
        PromotionManager promo = plugin.getPromotions();
        long now = System.currentTimeMillis();

        inv.setItem(INFO, m.icon(plugin.material("featured-info", Material.NETHER_STAR),
                shop.isFeatured(now) ? "featured-gui.info-active" : "featured-gui.info",
                "time", Text.duration(shop.getFeaturedUntil() - now),
                "used", promo.featuredInUse(), "max", promo.maxFeaturedSlots()));

        for (int i = 0; i < packages.size(); i++) {
            FeaturedPackage pkg = packages.get(i);
            Component blocker = promo.featuredBlocker(shop, pkg);
            String price = plugin.getShopManager().money(pkg.price());
            if (blocker != null) {
                inv.setItem(FIRST + i, m.iconPlus(plugin.material("featured-blocked", Material.GRAY_DYE), "featured-gui.package-blocked",
                        List.of(blocker), "hours", pkg.hours(), "price", price));
            } else if (confirming == i) {
                inv.setItem(FIRST + i, Text.glow(m.icon(plugin.material("featured-package", Material.EMERALD), "featured-gui.package-confirm",
                        "hours", pkg.hours(), "price", price)));
            } else {
                inv.setItem(FIRST + i, m.icon(plugin.material("featured-package", Material.EMERALD), "featured-gui.package",
                        "hours", pkg.hours(), "price", price,
                        "note", m.ref(shop.isFeatured(now) ? "featured-gui.note-extend" : "featured-gui.note-start")));
            }
        }
        inv.setItem(BACK, Gui.back(plugin));
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
