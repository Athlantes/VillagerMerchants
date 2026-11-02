package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.StaffRole;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * The shop's management menu. Owners see everything; staff only see what their role allows
 * (see {@link StaffRole}). Cancelling, upgrades, NPC customization and staff management stay owner-only.
 */
public class OwnerMenuGui implements GuiHolder, ShopBound {
    private static final int TRADES = 10, STORAGE = 12, UPGRADES = 14, RENT = 16;
    private static final int SALE = 19, FEATURED = 21, STAFF = 23, CUSTOM = 25;
    private static final int STATS = 29, CANCEL = 33, INFO = 4;

    private final VillagerMerchants plugin;
    private final Shop shop;
    private final Player player;
    private final Inventory inv;
    private final boolean owner;
    private boolean confirmingRent;
    private boolean confirmingCancel;
    private List<String> statsLines = List.of("<gray>Sales (7 days): <dark_gray>loading...");

    public OwnerMenuGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.owner = shop.isOwner(player.getUniqueId());
        this.inv = Bukkit.createInventory(this, 36, Text.mm("<dark_gray>" + (owner ? "Manage your shop" : "Manage shop (staff)")));
        render();
        if (can(Perm.STATS)) loadStats();
    }

    private boolean can(Perm p) { return shop.can(player.getUniqueId(), p); }

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
        inv.clear();
        ShopManager sm = plugin.getShopManager();
        PromotionManager promo = plugin.getPromotions();
        PlayerProfile prof = sm.getProfile(shop.getOwner());   // slots, rent price and max duration belong to the shop
        long now = System.currentTimeMillis();
        long left = shop.getRentExpiry() - now;
        int complete = shop.completeTrades().size();

        // ---- who's looking
        StaffRole role = shop.roleOf(player.getUniqueId());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sk = (SkullMeta) head.getItemMeta();
        sk.setOwningPlayer(Bukkit.getOfflinePlayer(shop.getOwner()));
        sk.displayName(Text.item("<gold>" + Text.esc(sm.ownerName(shop.getOwner())) + "'s Shop"));
        List<net.kyori.adventure.text.Component> hl = new ArrayList<>();
        hl.add(Text.item("<gray>Your role: <white>" + (owner ? "Owner" : role == null ? "?" : role.label())));
        hl.add(Text.item("<gray>Staff: <white>" + shop.getStaff().size() + "/" + plugin.getStaff().maxStaff()));
        sk.lore(hl);
        head.setItemMeta(sk);
        inv.setItem(INFO, head);

        // ---- row 1
        if (can(Perm.TRADES)) {
            inv.setItem(TRADES, Text.icon(Material.WRITABLE_BOOK, "<green>Edit Trades",
                    "<gray>Active trades: <white>" + complete + "/" + prof.getUnlockedSlots(),
                    "<yellow>Click to open"));
        }
        if (can(Perm.STORAGE)) {
            inv.setItem(STORAGE, Text.icon(Material.CHEST, "<gold>Storage",
                    "<gray>Stock the items you sell here.", "<gray>Customer payments land here too.",
                    "<yellow>Click to open"));
        }
        if (owner) {
            inv.setItem(UPGRADES, Text.icon(Material.NETHER_STAR, "<light_purple>Upgrades",
                    "<gray>Permanent: they stay with you", "<gray>between rentals.", "<yellow>Click to open"));
        }
        if (can(Perm.BILLING)) {
            String status = left > 0 ? "<green>" + Text.duration(left) + " left" : "<red>EXPIRED - renew now!";
            inv.setItem(RENT, confirmingRent
                    ? Text.glow(Text.icon(Material.CLOCK, "<yellow>Click again to confirm",
                            "<gray>Cost: <white>" + sm.money(sm.rentPrice(prof)), "<gray>Paid from your own balance."))
                    : Text.icon(Material.CLOCK, "<aqua>Extend Rent", "<gray>Status: " + status,
                            "<gray>Cost: <white>" + sm.money(sm.rentPrice(prof)), "<yellow>Click to extend"));
        }

        // ---- row 2
        if (can(Perm.SALES)) {
            if (shop.isOnSale(now)) {
                inv.setItem(SALE, Text.glow(Text.icon(Material.GOLDEN_CARROT, "<red>Sale running: <yellow>" + shop.getSalePercent() + "% off",
                        "<gray>Ends in: <white>" + Text.duration(shop.getSaleExpiry() - now),
                        "<gray>Listed in /shops: " + (shop.isSaleListed() ? "<green>yes" : "<red>no"),
                        "<yellow>Click to manage")));
            } else {
                inv.setItem(SALE, Text.icon(Material.GOLDEN_CARROT, "<gold>Run a Sale",
                        "<gray>Discount your money-priced trades", "<gray>for a limited time.",
                        "<yellow>Click to set up"));
            }
        }
        if (can(Perm.BILLING)) {
            if (shop.isFeatured(now)) {
                inv.setItem(FEATURED, Text.glow(Text.icon(Material.NETHER_STAR, "<gold>Featured",
                        "<gray>Pinned in /shops for: <white>" + Text.duration(shop.getFeaturedUntil() - now),
                        "<yellow>Click to extend")));
            } else {
                inv.setItem(FEATURED, Text.icon(Material.NETHER_STAR, "<gold>Get Featured",
                        "<gray>Pin your shop at the top of /shops.",
                        "<gray>Slots in use: <white>" + promo.featuredInUse() + "/" + promo.maxFeaturedSlots(),
                        "<yellow>Click to see prices"));
            }
        }
        if (owner) {
            inv.setItem(STAFF, Text.icon(Material.PLAYER_HEAD, "<green>Staff & Co-owners",
                    "<gray>Let friends restock or manage", "<gray>this shop.",
                    "<gray>Members: <white>" + shop.getStaff().size() + "/" + plugin.getStaff().maxStaff(),
                    "<yellow>Click to manage"));
            inv.setItem(CUSTOM, Text.icon(Material.VILLAGER_SPAWN_EGG,
                    "<aqua><bold>Customization</bold></aqua>",
                    "<gray>Biome: <white>" + (prof.getSkinType() != null ? prof.getSkinType() : "none"),
                    "<gray>Outfit: <white>" + (prof.getSkinProfession() != null ? prof.getSkinProfession() : "none"),
                    "",
                    "<yellow>Click to customize NPC"));
        }

        // ---- row 3
        if (can(Perm.STATS)) {
            RatingManager.Summary rating = plugin.getRatings().summary(shop);
            List<String> salesLore = new ArrayList<>();
            salesLore.add(rating.count() > 0
                    ? "<gray>Rating: " + Text.stars(rating.average()) + " <white>" + Text.oneDecimal(rating.average())
                            + " <gray>(" + rating.count() + ")"
                    : "<gray>Rating: <dark_gray>no ratings yet");
            salesLore.addAll(statsLines);
            if (owner) salesLore.add("<yellow>Click to view your sales log");
            inv.setItem(STATS, Text.icon(Material.BOOK, "<aqua>Sales & Ratings", salesLore.toArray(new String[0])));
        }
        if (owner) {
            double refund = sm.cancelRefund(shop);
            inv.setItem(CANCEL, confirmingCancel
                    ? Text.glow(Text.icon(Material.TNT, "<red>Click again to CANCEL the shop",
                            "<gray>Refund: <white>" + sm.money(refund), "<gray>Items go to /vm claim."))
                    : Text.icon(Material.BARRIER, "<red>Cancel Shop",
                            "<gray>Refund (50% of remaining time):", "<white>" + sm.money(refund),
                            "<gray>Your stock is returned to you.",
                            "<gray>Featured time and staff are lost.", "<yellow>Click to cancel"));
        }
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        if (slot != RENT) confirmingRent = false;
        if (slot != CANCEL) confirmingCancel = false;
        switch (slot) {
            case TRADES -> { if (can(Perm.TRADES)) later(() -> new TradeEditorGui(plugin, player, shop).open()); }
            case STORAGE -> { if (can(Perm.STORAGE)) later(() -> player.openInventory(shop.getStorage())); }
            case UPGRADES -> { if (owner) later(() -> new UpgradeGui(plugin, player, shop).open()); }
            case CUSTOM -> { if (owner) later(() -> new CustomizationGui(plugin, player, shop).open()); }
            case SALE -> { if (can(Perm.SALES)) later(() -> new SaleGui(plugin, player, shop).open()); }
            case FEATURED -> { if (can(Perm.BILLING)) later(() -> new FeaturedGui(plugin, player, shop).open()); }
            case STAFF -> { if (owner) later(() -> new StaffGui(plugin, player, shop).open()); }
            case STATS -> {
                if (owner) later(() -> {
                    player.closeInventory();
                    plugin.getTransactions().showHistory(player, DatabaseManager.TxMode.SALES, player.getUniqueId(), player.getName(), 1);
                });
            }
            case RENT -> {
                if (!can(Perm.BILLING)) return;
                if (!confirmingRent) confirmingRent = true;
                else { confirmingRent = false; plugin.getShopManager().extendRent(player, shop); }
                render();
            }
            case CANCEL -> {
                if (!owner) return;
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
