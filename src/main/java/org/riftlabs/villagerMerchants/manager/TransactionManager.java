package org.riftlabs.villagerMerchants.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.database.DatabaseManager.TxMode;
import org.riftlabs.villagerMerchants.database.DatabaseManager.TxRow;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.Messages;
import org.riftlabs.villagerMerchants.util.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Transaction log + everything built on it: live sale notifications, the "while you were away"
 * summary on join, /vm sales and /vm purchases, and per-shop stats. Every sale is one row in the
 * `transactions` table; the same rows feed the web dashboard.
 */
public class TransactionManager {
    private static final long DAY_MS = 86_400_000L;

    public record ShopStats(long sales7d, double money7d, long salesTotal, double moneyTotal) {}

    private final VillagerMerchants plugin;
    private final DatabaseManager db;
    /** "shopId|ownerId" -> lifetime trade executions. Keyed by owner so a new renter starts at zero. */
    private final Map<String, Long> salesCounts = new HashMap<>();

    public TransactionManager(VillagerMerchants plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    /** Rows per page of /vm sales, /vm purchases and /vm log. */
    private int pageSize() { return Math.max(1, cfg().getInt("log.page-size", 8)); }
    /** Days covered by the "recent" numbers in the owner menu. */
    public int statsDays() { return Math.max(1, cfg().getInt("log.stats-days", 7)); }

    private DateTimeFormatter timeFormat() {
        try { return DateTimeFormatter.ofPattern(cfg().getString("log.time-format", "MM-dd HH:mm")); }
        catch (IllegalArgumentException e) { return DateTimeFormatter.ofPattern("MM-dd HH:mm"); }
    }

    private static String key(String shopId, String owner) { return shopId + "|" + owner; }
    private static String key(Shop s) { return key(s.getId().toString(), s.getOwner().toString()); }

    public void load() {
        for (DatabaseManager.SalesCount sc : db.loadSalesCounts()) {
            salesCounts.put(key(sc.shopId(), sc.seller()), sc.sales());
        }
        int days = cfg().getInt("log.retention-days", 0);
        if (days > 0) {
            long cutoff = System.currentTimeMillis() - days * 86_400_000L;
            db.async(() -> {
                int n = db.purgeTransactions(cutoff);
                if (n > 0) plugin.getLogger().info("Purged " + n + " transactions older than " + days + " days.");
            });
        }
    }

    public long salesCount(Shop s) { return salesCounts.getOrDefault(key(s), 0L); }

    // ------------------------------------------------------------ recording

    /** Logs a completed purchase (one click = one row, even when shift-click bought many) and notifies the seller. */
    public void recordSale(Player buyer, Shop shop, Trade trade, int times) {
        recordSale(buyer, shop, trade, times, trade.getPrice());
    }

    /** @param unitPrice what the buyer actually paid per unit (the sale price while a sale runs) */
    public void recordSale(Player buyer, Shop shop, Trade trade, int times, double unitPrice) {
        ItemStack res = trade.getResult();
        boolean money = trade.isMoney();
        double total = money ? Text.round2(unitPrice * times) : 0.0;
        String paid = money ? "" : paySummary(trade, times);
        UUID seller = shop.getOwner();
        Player sellerOnline = Bukkit.getPlayer(seller);
        boolean notifyOn = cfg().getBoolean("notifications.enabled", true);

        TxRow row = new TxRow(0L, System.currentTimeMillis(), shop.getId().toString(), seller.toString(),
                buyer.getUniqueId().toString(), buyer.getName(), money ? "MONEY" : "ITEM",
                res.getType().name(), Text.itemName(res), res.getAmount() * times, times, total, paid);

        // Already seen if they were online for it (or notifications are off entirely).
        boolean seen = sellerOnline != null || !notifyOn;
        db.async(() -> db.insertTransaction(row, seen));
        salesCounts.merge(key(shop), (long) times, Long::sum);

        if (sellerOnline != null && notifyOn) notifySale(sellerOnline, row);
    }

    private String paySummary(Trade t, int times) {
        List<String> parts = new ArrayList<>();
        if (t.getPay1() != null) parts.add(t.getPay1().getAmount() * times + "x " + Text.itemName(t.getPay1()));
        if (t.getPay2() != null) parts.add(t.getPay2().getAmount() * times + "x " + Text.itemName(t.getPay2()));
        return String.join(" + ", parts);
    }

    // ------------------------------------------------------------ notifications

    private TagResolver[] resolvers(TxRow r) {
        return new TagResolver[]{
                Placeholder.unparsed("buyer", r.buyerName() == null ? plugin.msg().raw("misc.someone") : r.buyerName()),
                Placeholder.unparsed("item", r.itemName() == null ? plugin.msg().raw("misc.item") : r.itemName()),
                Placeholder.unparsed("qty", String.valueOf(r.itemQty())),
                Placeholder.unparsed("price", plugin.getShopManager().money(r.money())),
                Placeholder.unparsed("paid", r.paySummary() == null ? "" : r.paySummary())
        };
    }

    private void notifySale(Player seller, TxRow r) {
        boolean money = "MONEY".equals(r.kind());
        Component msg = Text.mm(plugin.msg().raw(money ? "notify.sale-money" : "notify.sale-item"), resolvers(r));
        if (cfg().getBoolean("notifications.chat", true)) seller.sendMessage(msg);
        if (cfg().getBoolean("notifications.actionbar", true)) seller.sendActionBar(msg);
        String sound = cfg().getString("notifications.sound", "entity.experience_orb.pickup");
        if (sound != null && !sound.isBlank()) {
            try { seller.playSound(seller.getLocation(), sound, 1f, 1.2f); } catch (Exception ignored) {}
        }
    }

    /** Called shortly after a player joins: shows what they earned while offline. */
    public void onJoin(Player p) {
        if (!cfg().getBoolean("notifications.enabled", true)) return;
        if (!cfg().getBoolean("notifications.join-summary", true)) return;
        int maxLines = Math.max(0, cfg().getInt("notifications.join-max-lines", 5));
        String seller = p.getUniqueId().toString();
        long delay = Math.max(0, cfg().getLong("notifications.join-delay-ticks", 60L));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            db.async(() -> {
                DatabaseManager.Unread un = db.takeUnread(seller, maxLines);
                if (un.maxId() == 0) return;
                plugin.sync(() -> { if (p.isOnline()) showJoinSummary(p, un); });
            });
        }, delay);
    }

    private void showJoinSummary(Player p, DatabaseManager.Unread un) {
        var m = plugin.msg();
        m.send(p, "join.header");
        String money = plugin.getShopManager().money(un.money());
        m.send(p, "join.summary", "sales", un.sales(),
                "earned", un.money() > 0 ? Messages.parsed(m.str("join.earned", "money", Text.esc(money))) : Messages.parsed(""));
        for (TxRow r : un.recent()) p.sendMessage(line(r, TxMode.SALES));
        if (un.rows() > un.recent().size()) m.send(p, "join.more");
        String sound = cfg().getString("notifications.sound", "entity.experience_orb.pickup");
        if (sound != null && !sound.isBlank()) {
            try { p.playSound(p.getLocation(), sound, 1f, 1.2f); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------ history (/vm sales, /vm purchases, /vm log)

    private Component line(TxRow r, TxMode mode) {
        String time = timeFormat().format(Instant.ofEpochMilli(r.ts()).atZone(ZoneId.systemDefault()));
        boolean money = "MONEY".equals(r.kind());
        String fmt = plugin.msg().raw(money ? "history.line-money" : "history.line-item");
        if (mode != TxMode.SALES) fmt += plugin.msg().raw("history.from-suffix");
        String sellerName = plugin.msg().raw("misc.unknown-short");
        try { sellerName = plugin.getShopManager().ownerName(UUID.fromString(r.seller())); } catch (IllegalArgumentException ignored) {}
        return Text.mm(fmt, TagResolver.resolver(resolvers(r)),
                Placeholder.unparsed("time", time), Placeholder.unparsed("seller", sellerName));
    }

    /**
     * Shows one page of a player's log. Safe to call from the main thread: the query runs on the
     * database thread and the result is sent back on the main thread.
     */
    public void showHistory(CommandSender viewer, TxMode mode, UUID subject, String subjectName, int page) {
        final int want = Math.max(1, page);
        final String sid = subject.toString();
        db.async(() -> {
            DatabaseManager.Totals tot = db.totals(mode, sid);
            final int size = pageSize();
            int pages = Math.max(1, (int) Math.ceil(tot.rows() / (double) size));
            int p = Math.min(want, pages);
            List<TxRow> rows = db.queryTransactions(mode, sid, size, (p - 1) * size);
            plugin.sync(() -> renderHistory(viewer, mode, subjectName, tot, rows, p, pages));
        });
    }

    private void renderHistory(CommandSender viewer, TxMode mode, String subjectName,
                               DatabaseManager.Totals tot, List<TxRow> rows, int page, int pages) {
        if (viewer instanceof Player pl && !pl.isOnline()) return;
        if (tot.rows() == 0) {
            plugin.msg().send(viewer, "history.empty");
            return;
        }
        var m = plugin.msg();
        String title = switch (mode) {
            case SALES -> m.raw("history.title-sales");
            case PURCHASES -> m.raw("history.title-purchases");
            case BOTH -> m.str("history.title-both", "player", subjectName);
        };
        m.send(viewer, "history.header", "title", title, "page", page, "pages", pages);
        for (TxRow r : rows) viewer.sendMessage(line(r, mode));

        String summaryKey = switch (mode) {
            case SALES -> "history.summary-sales";
            case PURCHASES -> "history.summary-purchases";
            case BOTH -> "history.summary-both";
        };
        m.send(viewer, summaryKey, "sales", tot.sales(), "money", plugin.getShopManager().money(tot.money()));

        String base = switch (mode) {
            case SALES -> "/vm sales ";
            case PURCHASES -> "/vm purchases ";
            case BOTH -> "/vm log " + subjectName + " ";
        };
        StringBuilder nav = new StringBuilder();
        if (page > 1) nav.append(m.str("history.nav-prev", "command", base + (page - 1))).append(' ');
        if (page < pages) nav.append(m.str("history.nav-next", "command", base + (page + 1)));
        if (nav.length() > 0) viewer.sendMessage(Text.mm(nav.toString()));
    }

    // ------------------------------------------------------------ per-shop stats (owner menu)

    public void loadStats(Shop shop, Consumer<ShopStats> callback) {
        final String sid = shop.getId().toString();
        final String owner = shop.getOwner().toString();
        final long since = System.currentTimeMillis() - statsDays() * DAY_MS;
        db.async(() -> {
            DatabaseManager.Totals week = db.shopTotals(sid, owner, since);
            DatabaseManager.Totals all = db.shopTotals(sid, owner, 0L);
            ShopStats st = new ShopStats(week.sales(), week.money(), all.sales(), all.money());
            plugin.sync(() -> callback.accept(st));
        });
    }
}
