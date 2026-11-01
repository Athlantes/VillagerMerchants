package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.List;

public class OwnerMenuGui implements GuiHolder, ShopBound {
    private final VillagerMerchants plugin;
    private final Shop shop;
    private final Player player;
    private final Inventory inv;
    private boolean confirmingRent;
    private boolean confirmingCancel;
    private List<String> statsLines = List.of("<gray>Sales (7 days): <dark_gray>loading...");

    public OwnerMenuGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.inv = Bukkit.createInventory(this, 27, Text.mm("<dark_gray>Manage your shop"));
        render();
        loadStats();
    }

    /** Sales numbers come from the database thread; the item is refreshed once they arrive. */
    private void loadStats() {
        plugin.getTransactions().loadStats(shop, st -> {
            statsLines = List.of(
                    "<gray>Last 7 days: <white>" + st.sales7d() + " <gray>sale(s), <gold>" + plugin.getShopManager().money(st.money7d()),
                    "<gray>All time: <white>" + st.salesTotal() + " <gray>sale(s), <gold>" + plugin.getShopManager().money(st.moneyTotal()));
            if (!inv.getViewers().isEmpty()) render();
        });
    }

    private void render() {
        ShopManager sm = plugin.getShopManager();
        PlayerProfile prof = sm.getProfile(player.getUniqueId());
        long left = shop.getRentExpiry() - System.currentTimeMillis();
        int complete = shop.completeTrades().size();

        inv.setItem(10, Text.icon(Material.WRITABLE_BOOK, "<green>Edit Trades",
                "<gray>Active trades: <white>" + complete + "/" + prof.getUnlockedSlots(),
                "<yellow>Click to open"));
        inv.setItem(12, Text.icon(Material.CHEST, "<gold>Storage",
                "<gray>Stock the items you sell here.", "<gray>Customer payments land here too.",
                "<yellow>Click to open"));
        inv.setItem(14, Text.icon(Material.NETHER_STAR, "<light_purple>Upgrades",
                "<gray>Permanent: they stay with you", "<gray>between rentals.", "<yellow>Click to open"));

        String status = left > 0 ? "<green>" + Text.duration(left) + " left" : "<red>EXPIRED - renew now!";
        inv.setItem(16, confirmingRent
                ? Text.glow(Text.icon(Material.CLOCK, "<yellow>Click again to confirm",
                        "<gray>Cost: <white>" + sm.money(sm.rentPrice(prof))))
                : Text.icon(Material.CLOCK, "<aqua>Extend Rent", "<gray>Status: " + status,
                        "<gray>Cost: <white>" + sm.money(sm.rentPrice(prof)), "<yellow>Click to extend"));

        inv.setItem(20, Text.icon(Material.VILLAGER_SPAWN_EGG, "<aqua>Villager Skins",
                "<gray>Biome: <white>" + prof.getSkinType(), "<gray>Outfit: <white>" + prof.getSkinProfession(),
                "<yellow>Click to change"));

        RatingManager.Summary rating = plugin.getRatings().summary(shop);
        List<String> salesLore = new ArrayList<>();
        salesLore.add(rating.count() > 0
                ? "<gray>Rating: " + Text.stars(rating.average()) + " <white>" + Text.oneDecimal(rating.average())
                        + " <gray>(" + rating.count() + ")"
                : "<gray>Rating: <dark_gray>no ratings yet");
        salesLore.addAll(statsLines);
        salesLore.add("<yellow>Click to view your sales log");
        inv.setItem(22, Text.icon(Material.BOOK, "<aqua>Sales & Ratings", salesLore.toArray(new String[0])));

        double refund = sm.cancelRefund(shop);
        inv.setItem(24, confirmingCancel
                ? Text.glow(Text.icon(Material.TNT, "<red>Click again to CANCEL the shop",
                        "<gray>Refund: <white>" + sm.money(refund), "<gray>Items go to /vm claim."))
                : Text.icon(Material.BARRIER, "<red>Cancel Shop",
                        "<gray>Refund (50% of remaining time):", "<white>" + sm.money(refund),
                        "<gray>Your stock is returned to you.", "<yellow>Click to cancel"));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        if (slot != 16) confirmingRent = false;
        if (slot != 24) confirmingCancel = false;
        switch (slot) {
            case 10 -> later(() -> new TradeEditorGui(plugin, player, shop).open());
            case 12 -> later(() -> player.openInventory(shop.getStorage()));
            case 14 -> later(() -> new UpgradeGui(plugin, player, shop).open());
            case 20 -> later(() -> new SkinGui(plugin, player, shop).open());
            case 22 -> later(() -> {
                player.closeInventory();
                plugin.getTransactions().showHistory(player, DatabaseManager.TxMode.SALES, player.getUniqueId(), player.getName(), 1);
            });
            case 16 -> {
                if (!confirmingRent) confirmingRent = true;
                else { confirmingRent = false; plugin.getShopManager().extendRent(player, shop); }
                render();
            }
            case 24 -> {
                if (!confirmingCancel) { confirmingCancel = true; render(); }
                else later(() -> { player.closeInventory(); plugin.getShopManager().cancelShop(player, shop); });
            }
            default -> render();
        }
    }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
