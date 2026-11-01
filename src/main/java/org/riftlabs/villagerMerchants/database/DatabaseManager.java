package org.riftlabs.villagerMerchants.database;

import org.bukkit.plugin.java.JavaPlugin;
import org.riftlabs.villagerMerchants.model.PlayerProfile;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * SQLite storage. Reads happen once at startup (and for claims / history); all work that
 * touches the database after startup goes through a single-thread executor so it stays
 * ordered and never blocks the main thread.
 */
public class DatabaseManager {
    public record ShopRow(String id, String owner, String location, long expiry, String trades, String stock, String prices) {}
    public record ClaimRow(int id, String data) {}

    /** One logged sale. A shift-click that buys several times is one row with trades = N. */
    public record TxRow(long id, long ts, String shopId, String seller, String buyer, String buyerName,
                        String kind, String itemType, String itemName, int itemQty, int trades,
                        double money, String paySummary) {}
    /** rows = log entries, sales = individual trade executions, money = total paid. */
    public record Totals(long rows, long sales, double money) {}
    public record Unread(long rows, long sales, double money, long maxId, List<TxRow> recent) {}
    public record RatingAgg(String shopId, String owner, double sum, int count) {}
    public record SalesCount(String shopId, String seller, long sales) {}

    public enum TxMode { SALES, PURCHASES, BOTH }

    private static final String TX_COLS =
            "id, ts, shop_id, seller_uuid, buyer_uuid, buyer_name, kind, item_type, item_name, item_qty, trades, money, pay_summary";

    private final JavaPlugin plugin;
    private final String url;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VillagerMerchants-DB");
        t.setDaemon(true);
        return t;
    });

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File dbFile = new File(plugin.getDataFolder(), "database.db");
        this.url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}
        initDatabase();
    }

    public Connection getConnection() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout = 5000;"); }
        return c;
    }

    private void initDatabase() {
        String profiles = "CREATE TABLE IF NOT EXISTS player_profiles ("
                + "uuid VARCHAR(36) PRIMARY KEY, "
                + "unlocked_slots INTEGER DEFAULT 1, "
                + "tax_reduction_level INTEGER DEFAULT 0, "
                + "max_rent_duration INTEGER DEFAULT 7);";
        String shops = "CREATE TABLE IF NOT EXISTS shops ("
                + "shop_id VARCHAR(36) PRIMARY KEY, "
                + "owner_uuid VARCHAR(36), "
                + "location VARCHAR(255), "
                + "rent_expiry TIMESTAMP, "
                + "active_trades TEXT, "
                + "backend_stock TEXT);";
        String claims = "CREATE TABLE IF NOT EXISTS claims ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "owner_uuid VARCHAR(36), "
                + "data TEXT);";
        // Transaction log. Also the data source for sale notifications ("notified") and the web dashboard.
        String transactions = "CREATE TABLE IF NOT EXISTS transactions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ts INTEGER NOT NULL, "
                + "shop_id VARCHAR(36) NOT NULL, "
                + "seller_uuid VARCHAR(36) NOT NULL, "
                + "buyer_uuid VARCHAR(36) NOT NULL, "
                + "buyer_name VARCHAR(32), "
                + "kind VARCHAR(8) NOT NULL, "
                + "item_type VARCHAR(64), "
                + "item_name TEXT, "
                + "item_qty INTEGER NOT NULL DEFAULT 0, "
                + "trades INTEGER NOT NULL DEFAULT 1, "
                + "money REAL NOT NULL DEFAULT 0, "
                + "pay_summary TEXT, "
                + "notified INTEGER NOT NULL DEFAULT 0);";
        String ratings = "CREATE TABLE IF NOT EXISTS shop_ratings ("
                + "shop_id VARCHAR(36) NOT NULL, "
                + "owner_uuid VARCHAR(36) NOT NULL, "
                + "rater_uuid VARCHAR(36) NOT NULL, "
                + "stars INTEGER NOT NULL, "
                + "ts INTEGER NOT NULL, "
                + "PRIMARY KEY (shop_id, owner_uuid, rater_uuid));";
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL;");
            s.execute(profiles);
            s.execute(shops);
            s.execute(claims);
            s.execute(transactions);
            s.execute(ratings);
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_seller ON transactions (seller_uuid, ts);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_buyer ON transactions (buyer_uuid, ts);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_shop ON transactions (shop_id, seller_uuid, ts);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_unread ON transactions (seller_uuid, notified);");
            // migrations for databases created by older versions
            for (String col : new String[]{"unlocked_skins TEXT", "skin_type TEXT", "skin_profession TEXT", "slots_level INTEGER"}) {
                try { s.execute("ALTER TABLE player_profiles ADD COLUMN " + col); } catch (SQLException ignored) {}
            }
            try { s.execute("ALTER TABLE shops ADD COLUMN trade_prices TEXT"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not initialize database tables: " + e.getMessage());
        }
    }

    /** Queue work. Falls back to running inline if the executor is already shut down. */
    public void async(Runnable r) {
        Runnable safe = () -> {
            try { r.run(); } catch (Throwable t) {
                plugin.getLogger().severe("Database task failed: " + t.getMessage());
            }
        };
        try { executor.execute(safe); } catch (RejectedExecutionException ex) { safe.run(); }
    }

    // ---- loading ----

    public List<ShopRow> loadShops() {
        List<ShopRow> rows = new ArrayList<>();
        String q = "SELECT shop_id, owner_uuid, location, rent_expiry, active_trades, backend_stock, trade_prices FROM shops";
        try (Connection c = getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) {
                if (rs.getString(2) == null) continue;
                rows.add(new ShopRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7)));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load shops: " + e.getMessage());
        }
        return rows;
    }

    public Map<UUID, PlayerProfile> loadProfiles() {
        Map<UUID, PlayerProfile> map = new HashMap<>();
        String q = "SELECT uuid, unlocked_slots, tax_reduction_level, max_rent_duration, unlocked_skins, skin_type, skin_profession, slots_level FROM player_profiles";
        try (Connection c = getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) {
                try {
                    UUID u = UUID.fromString(rs.getString(1));
                    PlayerProfile pp = new PlayerProfile(u, rs.getInt(2), rs.getInt(3), rs.getInt(4));
                    pp.loadSkins(rs.getString(5), rs.getString(6), rs.getString(7));
                    int sl = rs.getInt(8);
                    // legacy rows (no level stored): the old default was 1 slot, so extra slots = bought levels
                    if (rs.wasNull()) sl = Math.max(0, rs.getInt(2) - 1);
                    pp.setSlotLevel(sl);
                    map.put(u, pp);
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load profiles: " + e.getMessage());
        }
        return map;
    }

    public List<ClaimRow> loadClaims(String owner) {
        List<ClaimRow> rows = new ArrayList<>();
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT id, data FROM claims WHERE owner_uuid = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) rows.add(new ClaimRow(rs.getInt(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load claims: " + e.getMessage());
        }
        return rows;
    }

    /** Startup: lifetime trade executions per (shop, seller), for the directory's popularity sort. */
    public List<SalesCount> loadSalesCounts() {
        List<SalesCount> out = new ArrayList<>();
        String q = "SELECT shop_id, seller_uuid, COALESCE(SUM(trades), 0) FROM transactions GROUP BY shop_id, seller_uuid";
        try (Connection c = getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) out.add(new SalesCount(rs.getString(1), rs.getString(2), rs.getLong(3)));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load sales counts: " + e.getMessage());
        }
        return out;
    }

    /** Startup: rating sums per (shop, owner). */
    public List<RatingAgg> loadRatingAggs() {
        List<RatingAgg> out = new ArrayList<>();
        String q = "SELECT shop_id, owner_uuid, SUM(stars), COUNT(*) FROM shop_ratings GROUP BY shop_id, owner_uuid";
        try (Connection c = getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) out.add(new RatingAgg(rs.getString(1), rs.getString(2), rs.getDouble(3), rs.getInt(4)));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load ratings: " + e.getMessage());
        }
        return out;
    }

    // ---- writes (call inside async()) ----

    public void upsertShop(ShopRow r) {
        String q = "INSERT OR REPLACE INTO shops (shop_id, owner_uuid, location, rent_expiry, active_trades, backend_stock, trade_prices) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, r.id());
            ps.setString(2, r.owner());
            ps.setString(3, r.location());
            ps.setLong(4, r.expiry());
            ps.setString(5, r.trades());
            ps.setString(6, r.stock());
            ps.setString(7, r.prices());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save shop " + r.id() + ": " + e.getMessage());
        }
    }

    public void deleteShop(String id) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM shops WHERE shop_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete shop " + id + ": " + e.getMessage());
        }
    }

    public void upsertProfile(String uuid, int slots, int tax, int days, String skins, String skinType, String skinProf, int slotLevel) {
        String q = "INSERT OR REPLACE INTO player_profiles (uuid, unlocked_slots, tax_reduction_level, max_rent_duration, unlocked_skins, skin_type, skin_profession, slots_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, uuid);
            ps.setInt(2, slots);
            ps.setInt(3, tax);
            ps.setInt(4, days);
            ps.setString(5, skins);
            ps.setString(6, skinType);
            ps.setString(7, skinProf);
            ps.setInt(8, slotLevel);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save profile " + uuid + ": " + e.getMessage());
        }
    }

    public void addClaim(String owner, String data) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement("INSERT INTO claims (owner_uuid, data) VALUES (?, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, data);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to store claim for " + owner + ": " + e.getMessage());
        }
    }

    public void deleteClaim(int id) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM claims WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete claim " + id + ": " + e.getMessage());
        }
    }

    // ---- transaction log (call inside async()) ----

    /** @param notified true when the seller was online and already saw the sale message. */
    public void insertTransaction(TxRow r, boolean notified) {
        String q = "INSERT INTO transactions (ts, shop_id, seller_uuid, buyer_uuid, buyer_name, kind, item_type, "
                + "item_name, item_qty, trades, money, pay_summary, notified) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setLong(1, r.ts());
            ps.setString(2, r.shopId());
            ps.setString(3, r.seller());
            ps.setString(4, r.buyer());
            ps.setString(5, r.buyerName());
            ps.setString(6, r.kind());
            ps.setString(7, r.itemType());
            ps.setString(8, r.itemName());
            ps.setInt(9, r.itemQty());
            ps.setInt(10, r.trades());
            ps.setDouble(11, r.money());
            ps.setString(12, r.paySummary());
            ps.setInt(13, notified ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to log transaction: " + e.getMessage());
        }
    }

    private static String where(TxMode m) {
        return switch (m) {
            case SALES -> "seller_uuid = ?";
            case PURCHASES -> "buyer_uuid = ?";
            case BOTH -> "(seller_uuid = ? OR buyer_uuid = ?)";
        };
    }

    /** Binds the subject uuid for where(mode); returns the last parameter index used. */
    private static int bind(PreparedStatement ps, TxMode m, String uuid) throws SQLException {
        ps.setString(1, uuid);
        if (m == TxMode.BOTH) { ps.setString(2, uuid); return 2; }
        return 1;
    }

    private static TxRow readTx(ResultSet rs) throws SQLException {
        return new TxRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getInt(11), rs.getDouble(12), rs.getString(13));
    }

    public List<TxRow> queryTransactions(TxMode mode, String uuid, int limit, int offset) {
        List<TxRow> out = new ArrayList<>();
        String q = "SELECT " + TX_COLS + " FROM transactions WHERE " + where(mode) + " ORDER BY id DESC LIMIT ? OFFSET ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            int i = bind(ps, mode, uuid);
            ps.setInt(++i, limit);
            ps.setInt(++i, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(readTx(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read transactions: " + e.getMessage());
        }
        return out;
    }

    public Totals totals(TxMode mode, String uuid) {
        String q = "SELECT COUNT(*), COALESCE(SUM(trades), 0), COALESCE(SUM(money), 0) FROM transactions WHERE " + where(mode);
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            bind(ps, mode, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Totals(rs.getLong(1), rs.getLong(2), rs.getDouble(3));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to total transactions: " + e.getMessage());
        }
        return new Totals(0, 0, 0);
    }

    /** Sales of one shop (while owned by this seller) since the given epoch-millis (0 = all time). */
    public Totals shopTotals(String shopId, String seller, long since) {
        String q = "SELECT COUNT(*), COALESCE(SUM(trades), 0), COALESCE(SUM(money), 0) FROM transactions "
                + "WHERE shop_id = ? AND seller_uuid = ? AND ts >= ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, shopId);
            ps.setString(2, seller);
            ps.setLong(3, since);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new Totals(rs.getLong(1), rs.getLong(2), rs.getDouble(3));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to total shop sales: " + e.getMessage());
        }
        return new Totals(0, 0, 0);
    }

    /** Sales the seller has not been told about yet. Reads them AND marks them as seen. */
    public Unread takeUnread(String seller, int maxLines) {
        Unread none = new Unread(0, 0, 0, 0, List.of());
        try (Connection c = getConnection()) {
            long rows, sales, maxId;
            double money;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(trades), 0), "
                    + "COALESCE(SUM(money), 0), COALESCE(MAX(id), 0) FROM transactions "
                    + "WHERE seller_uuid = ? AND notified = 0")) {
                ps.setString(1, seller);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return none;
                    rows = rs.getLong(1);
                    sales = rs.getLong(2);
                    money = rs.getDouble(3);
                    maxId = rs.getLong(4);
                }
            }
            if (maxId == 0) return none;

            List<TxRow> recent = new ArrayList<>();
            if (maxLines > 0) {
                try (PreparedStatement ps = c.prepareStatement("SELECT " + TX_COLS + " FROM transactions "
                        + "WHERE seller_uuid = ? AND notified = 0 AND id <= ? ORDER BY id DESC LIMIT ?")) {
                    ps.setString(1, seller);
                    ps.setLong(2, maxId);
                    ps.setInt(3, maxLines);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) recent.add(readTx(rs));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE transactions SET notified = 1 "
                    + "WHERE seller_uuid = ? AND notified = 0 AND id <= ?")) {
                ps.setString(1, seller);
                ps.setLong(2, maxId);
                ps.executeUpdate();
            }
            return new Unread(rows, sales, money, maxId, recent);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read unseen sales for " + seller + ": " + e.getMessage());
            return none;
        }
    }

    public int purgeTransactions(long olderThan) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM transactions WHERE ts < ?")) {
            ps.setLong(1, olderThan);
            return ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to purge old transactions: " + e.getMessage());
            return 0;
        }
    }

    // ---- ratings (call inside async()) ----

    /** True if this buyer has bought from this shop while it was owned by this seller. */
    public boolean hasPurchased(String shopId, String seller, String buyer) {
        String q = "SELECT 1 FROM transactions WHERE shop_id = ? AND seller_uuid = ? AND buyer_uuid = ? LIMIT 1";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, shopId);
            ps.setString(2, seller);
            ps.setString(3, buyer);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to check purchase history: " + e.getMessage());
            return false;
        }
    }

    /** @return the rater's current stars for this shop/owner, or 0 if they haven't rated. */
    public int getRating(String shopId, String owner, String rater) {
        String q = "SELECT stars FROM shop_ratings WHERE shop_id = ? AND owner_uuid = ? AND rater_uuid = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, shopId);
            ps.setString(2, owner);
            ps.setString(3, rater);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read rating: " + e.getMessage());
            return 0;
        }
    }

    public void upsertRating(String shopId, String owner, String rater, int stars, long ts) {
        String q = "INSERT OR REPLACE INTO shop_ratings (shop_id, owner_uuid, rater_uuid, stars, ts) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, shopId);
            ps.setString(2, owner);
            ps.setString(3, rater);
            ps.setInt(4, stars);
            ps.setLong(5, ts);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save rating: " + e.getMessage());
        }
    }

    public RatingAgg ratingAgg(String shopId, String owner) {
        String q = "SELECT COALESCE(SUM(stars), 0), COUNT(*) FROM shop_ratings WHERE shop_id = ? AND owner_uuid = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, shopId);
            ps.setString(2, owner);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new RatingAgg(shopId, owner, rs.getDouble(1), rs.getInt(2));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to total ratings: " + e.getMessage());
        }
        return new RatingAgg(shopId, owner, 0, 0);
    }

    /** Flushes queued writes. Call from onDisable. */
    public void disconnect() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Database writes did not finish in time!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
