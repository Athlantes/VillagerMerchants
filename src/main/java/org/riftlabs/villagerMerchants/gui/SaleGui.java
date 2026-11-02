package org.riftlabs.villagerMerchants.gui;

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
        this.inv = Bukkit.createInventory(this, 36, Text.mm("<dark_gray>Run a Sale"));
        render();
    }

    private void render() {
        inv.clear();
        PromotionManager promo = plugin.getPromotions();
        long now = System.currentTimeMillis();
        boolean running = shop.isOnSale(now);

        int discountable = promo.discountableTrades(shop);
        inv.setItem(INFO, Text.icon(Material.GOLDEN_CARROT, running ? "<red>Sale running" : "<gold>Run a Sale",
                "<gray>Discounts apply to money-priced trades.",
                "<gray>Affected trades: <white>" + discountable + "<gray>/" + shop.completeTrades().size(),
                "<gray>A name tag banner shows while it runs."));

        if (running) {
            inv.setItem(PERCENT_START + 2, Text.glow(Text.icon(Material.GOLD_INGOT, "<yellow>" + shop.getSalePercent() + "% off",
                    "<gray>Ends in: <white>" + Text.duration(shop.getSaleExpiry() - now))));
            inv.setItem(ACTION, Text.icon(Material.RED_CONCRETE, "<red>Stop the sale", "<gray>Prices go back to normal now.",
                    "<yellow>Click to stop"));
        } else {
            for (int i = 0; i < percents.size(); i++) {
                int pct = percents.get(i);
                ItemStack it = Text.icon(Material.GOLD_INGOT, "<yellow>" + pct + "% off",
                        pct == percent ? "<green>Selected" : "<gray>Click to select");
                it.setAmount(Math.max(1, Math.min(64, pct)));
                inv.setItem(PERCENT_START + i, pct == percent ? Text.glow(it) : it);
            }
            for (int i = 0; i < durations.size(); i++) {
                long d = durations.get(i);
                ItemStack it = Text.icon(Material.CLOCK, "<aqua>" + Text.duration(d),
                        d == duration ? "<green>Selected" : "<gray>Click to select");
                inv.setItem(DURATION_START + i, d == duration ? Text.glow(it) : it);
            }
            String blocker = promo.saleBlocker(shop);
            double fee = promo.saleFee();
            if (blocker != null) {
                inv.setItem(ACTION, Text.icon(Material.GRAY_CONCRETE, "<gray>Can't start a sale", blocker));
            } else {
                inv.setItem(ACTION, Text.icon(Material.LIME_CONCRETE, "<green>Start: <yellow>" + percent + "% off</yellow> for <white>" + Text.duration(duration),
                        fee > 0 ? "<gray>Fee: <white>" + plugin.getShopManager().money(fee) : "<gray>No fee.",
                        "<yellow>Click to start"));
            }
        }

        inv.setItem(LISTED, Text.icon(listedNow(running) ? Material.LIME_DYE : Material.GRAY_DYE,
                "<white>Show in /shops: " + (listedNow(running) ? "<green>ON SALE tag" : "<red>off"),
                "<gray>Adds an ON SALE tag to your entry", "<gray>and lets players filter for sales.", "<yellow>Click to toggle"));
        inv.setItem(BACK, Text.icon(Material.OAK_DOOR, "<red>Back"));
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

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
