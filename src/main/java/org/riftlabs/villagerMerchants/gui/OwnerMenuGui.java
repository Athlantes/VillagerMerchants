package org.riftlabs.villagerMerchants.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.manager.Access;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.StaffRole;
import org.riftlabs.villagerMerchants.util.Messages;
import org.riftlabs.villagerMerchants.util.Skins;
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
    private List<Component> statsLines;

    public OwnerMenuGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.owner = shop.isOwner(player.getUniqueId());
        this.statsLines = plugin.msg().lines("owner-menu.stats.loading");
        this.inv = Bukkit.createInventory(this, 36, plugin.msg().get(owner ? "owner-menu.title-owner" : "owner-menu.title-staff"));
        render();
        if (can(Perm.STATS)) loadStats();
    }

    private boolean can(Perm p) { return shop.can(player.getUniqueId(), p); }

    // Role says what a member MAY do in this shop; these add the server's permission nodes on top.
    private boolean showSales()     { return can(Perm.SALES) && plugin.access().canRunSales(player); }
    private boolean showFeatured()  { return can(Perm.BILLING) && plugin.access().canPromote(player); }
    private boolean showUpgrades()  { return owner && plugin.access().canUpgradeAny(player); }
    private boolean showStaff()     { return owner && plugin.access().canManageStaff(player); }
    private boolean showCustomize() { return owner && plugin.access().canCustomizeAny(player); }
    private String staffMax() {
        return Access.cap(owner ? plugin.getStaff().maxStaff(player) : plugin.getStaff().maxStaff());
    }

    /** Sales numbers come from the database thread; the item is refreshed once they arrive. */
    private void loadStats() {
        plugin.getTransactions().loadStats(shop, st -> {
            var m = plugin.msg();
            int days = plugin.getTransactions().statsDays();
            statsLines = List.of(
                    m.line("owner-menu.stats.recent", "days", days, "sales", st.sales7d(), "money", plugin.getShopManager().money(st.money7d())),
                    m.line("owner-menu.stats.total", "sales", st.salesTotal(), "money", plugin.getShopManager().money(st.moneyTotal())));
            if (!inv.getViewers().isEmpty()) render();
        });
    }

    private static String skinName(Skins.Kind kind, String key) {
        Skins.Skin s = key == null ? null : Skins.find(kind.name().toLowerCase() + ":" + key);
        return s != null ? s.name() : key;
    }

    private void render() {
        inv.clear();
        var m = plugin.msg();
        ShopManager sm = plugin.getShopManager();
        PromotionManager promo = plugin.getPromotions();
        PlayerProfile prof = sm.getProfile(shop.getOwner());   // slots, rent price and max duration belong to the shop
        long now = System.currentTimeMillis();
        long left = shop.getRentExpiry() - now;
        int complete = shop.completeTrades().size();
        String staffCount = shop.getStaff().size() + "/" + staffMax();

        // ---- who's looking
        StaffRole role = shop.roleOf(player.getUniqueId());
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sk = (SkullMeta) head.getItemMeta();
        sk.setOwningPlayer(Bukkit.getOfflinePlayer(shop.getOwner()));
        String roleLabel = owner ? m.raw("roles.owner") : role == null ? m.raw("roles.unknown") : role.label();
        sk.displayName(m.line("owner-menu.head.name", "owner", sm.ownerName(shop.getOwner())));
        sk.lore(m.lines("owner-menu.head.lore", "role", roleLabel, "staff", staffCount));
        head.setItemMeta(sk);
        inv.setItem(INFO, head);

        // ---- row 1
        if (can(Perm.TRADES)) {
            inv.setItem(TRADES, m.icon(plugin.material("owner-trades", Material.WRITABLE_BOOK), "owner-menu.trades",
                    "active", complete, "slots", prof.getUnlockedSlots()));
        }
        if (can(Perm.STORAGE)) {
            inv.setItem(STORAGE, m.icon(plugin.material("owner-storage", Material.CHEST), "owner-menu.storage"));
        }
        if (showUpgrades()) {
            inv.setItem(UPGRADES, m.icon(plugin.material("owner-upgrades", Material.NETHER_STAR), "owner-menu.upgrades"));
        }
        if (can(Perm.BILLING)) {
            String cost = sm.money(sm.rentPrice(prof));
            Material clock = plugin.material("owner-rent", Material.CLOCK);
            if (confirmingRent) {
                inv.setItem(RENT, Text.glow(m.icon(clock, "owner-menu.rent-confirm", "cost", cost)));
            } else {
                Messages.Parsed status = left > 0
                        ? m.ref("owner-menu.rent-status-active", "time", Text.duration(left))
                        : m.ref("owner-menu.rent-status-expired");
                inv.setItem(RENT, m.icon(clock, "owner-menu.rent", "status", status, "cost", cost));
            }
        }

        // ---- row 2
        if (showSales()) {
            Material carrot = plugin.material("owner-sale", Material.GOLDEN_CARROT);
            if (shop.isOnSale(now)) {
                inv.setItem(SALE, Text.glow(m.icon(carrot, "owner-menu.sale-running",
                        "percent", shop.getSalePercent(), "time", Text.duration(shop.getSaleExpiry() - now),
                        "listed", m.ref(shop.isSaleListed() ? "misc.yes" : "misc.no"))));
            } else {
                inv.setItem(SALE, m.icon(carrot, "owner-menu.sale"));
            }
        }
        if (showFeatured()) {
            Material star = plugin.material("owner-featured", Material.NETHER_STAR);
            if (shop.isFeatured(now)) {
                inv.setItem(FEATURED, Text.glow(m.icon(star, "owner-menu.featured-active",
                        "time", Text.duration(shop.getFeaturedUntil() - now))));
            } else {
                inv.setItem(FEATURED, m.icon(star, "owner-menu.featured",
                        "used", promo.featuredInUse(), "max", promo.maxFeaturedSlots()));
            }
        }
        if (showStaff()) {
            inv.setItem(STAFF, m.icon(plugin.material("owner-staff", Material.PLAYER_HEAD), "owner-menu.staff",
                    "count", shop.getStaff().size(), "max", staffMax()));
        }
        if (showCustomize()) {
            String none = m.raw("misc.none");
            String biome = prof.getSkinType() != null ? skinName(Skins.Kind.TYPE, prof.getSkinType()) : none;
            String outfit = prof.getSkinProfession() != null ? skinName(Skins.Kind.PROFESSION, prof.getSkinProfession()) : none;
            inv.setItem(CUSTOM, m.icon(plugin.material("owner-customize", Material.VILLAGER_SPAWN_EGG), "owner-menu.customize",
                    "biome", biome, "outfit", outfit));
        }

        // ---- row 3
        if (can(Perm.STATS)) {
            RatingManager.Summary rating = plugin.getRatings().summary(shop);
            List<Component> lore = new ArrayList<>();
            lore.add(rating.count() > 0
                    ? m.line("owner-menu.stats.rating", "stars", Messages.parsed(Text.stars(rating.average())),
                            "avg", Text.oneDecimal(rating.average()), "count", rating.count())
                    : m.line("owner-menu.stats.rating-none"));
            lore.addAll(statsLines);
            if (owner) lore.add(m.line("owner-menu.stats.click-log"));
            inv.setItem(STATS, m.iconPlus(plugin.material("owner-stats", Material.BOOK), "owner-menu.stats", lore));
        }
        if (owner) {
            String refund = sm.money(sm.cancelRefund(shop));
            if (confirmingCancel) {
                inv.setItem(CANCEL, Text.glow(m.icon(plugin.material("owner-cancel-confirm", Material.TNT),
                        "owner-menu.cancel-confirm", "refund", refund)));
            } else {
                int pct = (int) Math.round(plugin.getConfig().getDouble("rent.cancel-refund", 0.5) * 100);
                inv.setItem(CANCEL, m.icon(plugin.material("owner-cancel", Material.BARRIER), "owner-menu.cancel",
                        "refund", refund, "percent", pct));
            }
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
            case UPGRADES -> { if (showUpgrades()) later(() -> new UpgradeGui(plugin, player, shop).open()); }
            case CUSTOM -> { if (showCustomize()) later(() -> new CustomizationGui(plugin, player, shop).open()); }
            case SALE -> { if (showSales()) later(() -> new SaleGui(plugin, player, shop).open()); }
            case FEATURED -> { if (showFeatured()) later(() -> new FeaturedGui(plugin, player, shop).open()); }
            case STAFF -> { if (showStaff()) later(() -> new StaffGui(plugin, player, shop).open()); }
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
