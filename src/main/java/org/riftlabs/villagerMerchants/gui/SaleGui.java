package org.riftlabs.villagerMerchants.gui;

import org.riftlabs.villagerMerchants.util.Sched;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.List;

/** Pick a discount and a duration, then start (or stop) a timed sale. */
public class SaleGui implements GuiHolder, ShopBound {
    private static final int INFO = 4, BACK = 27, LISTED = 29, ACTION = 31;
    private static final int PERCENT_START = 10, DURATION_START = 19;   // up to 7 buttons each

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;
    private final List<Integer> percents;
    private final List<Long> durations;
    private int percent;
    private long duration;
    private boolean listed = true;

    public SaleGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        PromotionManager promo = plugin.getPromotions();
        this.percents = promo.percentOptions().subList(0, Math.min(7, promo.percentOptions().size()));
        this.durations = promo.durationOptions().subList(0, Math.min(7, promo.durationOptions().size()));
        this.percent = percents.get(Math.min(1, percents.size() - 1));
        this.duration = durations.get(Math.min(1, durations.size() - 1));
        this.inv = Bukkit.createInventory(this, 36, plugin.msg().get("sale-gui.title"));
        render();
    }

    private void render() {
        inv.clear();
        var m = plugin.msg();
        PromotionManager promo = plugin.getPromotions();
        long now = System.currentTimeMillis();
        boolean running = shop.isOnSale(now);

        int discountable = promo.discountableTrades(shop);
        inv.setItem(INFO, m.icon(plugin.material("sale-info", Material.GOLDEN_CARROT),
                running ? "sale-gui.info-running" : "sale-gui.info",
                "affected", discountable, "total", shop.completeTrades().size()));

        if (running) {
            inv.setItem(PERCENT_START + 2, Text.glow(m.icon(plugin.material("sale-percent", Material.GOLD_INGOT), "sale-gui.current",
                    "percent", shop.getSalePercent(), "time", Text.duration(shop.getSaleExpiry() - now))));
            inv.setItem(ACTION, m.icon(plugin.material("sale-stop", Material.RED_CONCRETE), "sale-gui.stop"));
        } else {
            for (int i = 0; i < percents.size(); i++) {
                int pct = percents.get(i);
                ItemStack it = m.icon(plugin.material("sale-percent", Material.GOLD_INGOT),
                        pct == percent ? "sale-gui.percent-selected" : "sale-gui.percent", "percent", pct);
                it.setAmount(Math.max(1, Math.min(64, pct)));
                inv.setItem(PERCENT_START + i, pct == percent ? Text.glow(it) : it);
            }
            for (int i = 0; i < durations.size(); i++) {
                long d = durations.get(i);
                ItemStack it = m.icon(plugin.material("sale-duration", Material.CLOCK),
                        d == duration ? "sale-gui.duration-selected" : "sale-gui.duration", "time", Text.duration(d));
                inv.setItem(DURATION_START + i, d == duration ? Text.glow(it) : it);
            }
            Component blocker = promo.saleBlocker(shop);
            double fee = promo.saleFee();
            if (blocker != null) {
                inv.setItem(ACTION, m.iconPlus(plugin.material("sale-blocked", Material.GRAY_CONCRETE), "sale-gui.blocked", List.of(blocker)));
            } else {
                inv.setItem(ACTION, m.icon(plugin.material("sale-start", Material.LIME_CONCRETE), "sale-gui.start",
                        "percent", percent, "time", Text.duration(duration),
                        "fee", fee > 0 ? m.ref("sale-gui.fee", "fee", Text.esc(plugin.getShopManager().money(fee))) : m.ref("sale-gui.no-fee")));
            }
        }

        boolean on = listedNow(running);
        inv.setItem(LISTED, m.icon(on ? plugin.material("sale-listed-on", Material.LIME_DYE) : plugin.material("sale-listed-off", Material.GRAY_DYE),
                "sale-gui.listed", "state", m.ref(on ? "sale-gui.listed-on" : "sale-gui.listed-off")));
        inv.setItem(BACK, Gui.back(plugin));
    }

    private boolean listedNow(boolean running) { return running ? shop.isSaleListed() : listed; }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        if (!shop.can(player.getUniqueId(), Perm.SALES)) { player.closeInventory(); return; }
        PromotionManager promo = plugin.getPromotions();
        int slot = e.getSlot();
        boolean running = shop.isOnSale(System.currentTimeMillis());

        if (slot == BACK) { later(() -> new OwnerMenuGui(plugin, player, shop).open()); return; }
        if (slot == LISTED) {
            if (running) promo.toggleListed(player, shop); else listed = !listed;
            render();
            return;
        }
        if (slot == ACTION) {
            if (running) promo.stopSale(player, shop);
            else if (promo.startSale(player, shop, percent, duration, listed)) {
                later(() -> new OwnerMenuGui(plugin, player, shop).open());
                return;
            }
            render();
            return;
        }
        if (!running) {
            int pi = slot - PERCENT_START, di = slot - DURATION_START;
            if (pi >= 0 && pi < percents.size()) percent = percents.get(pi);
            else if (di >= 0 && di < durations.size()) duration = durations.get(di);
        }
        render();
    }

    private void later(Runnable r) { Sched.entityNext(player, r); }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
