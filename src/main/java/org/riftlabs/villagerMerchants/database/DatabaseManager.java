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

public class DatabaseManager {
    public record ShopRow(String id, String owner, String location, long expiry, String trades, String stock, String prices,
                          int salePercent, long saleExpiry, boolean saleListed, long featuredUntil, String staff,
                          String npcId, String linkedSkin) {}
    /** One shop NPC slot: a stable, admin-chosen id plus what is standing there (vanilla villager or a linked custom entity). */
    public record NpcRow(String id, String kind, String location, String ref, boolean models) {}
    public record ClaimRow(int id, String data) {}
    public record TxRow(long id, long ts, String shopId, String seller, String buyer, String buyerName,
                        String kind, String itemType, String itemName, int itemQty, int trades,
                        double money, String paySummary) {}
    public record Totals(long rows, long sales, double money) {}
    public record Unread(long rows, long sales, double money, long maxId, List<TxRow> recent) {}
    public record RatingAgg(String shopId, String owner, double sum, int count) {}
    public record SalesCount(String shopId, String seller, long sales) {}
    /** One leaderboard row: the shop owner, the score, and (for ratings) how many ratings it is based on. */
    public record LbRow(String owner, double value, int count) {}

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
                + "max_rent_duration INTEGER DEFAULT 7, "
                + "unlocked_nametags TEXT, "
                + "nametag_format VARCHAR(255) DEFAULT '<green><bold>Available Shop</bold></green>', "
                + "unlocked_effects TEXT, "
                + "equipped_effect VARCHAR(64) DEFAULT ''"
                + ");";
        String shops = "CREATE TABLE IF NOT EXISTS shops ("
                + "shop_id VARCHAR(36) PRIMARY KEY, "
                + "owner_uuid VARCHAR(36), "
                + "location VARCHAR(255), "
                + "rent_expiry TIMESTAMP, "
                + "active_trades TEXT, "
                + "backend_stock TEXT);";
        String npcs = "CREATE TABLE IF NOT EXISTS shop_npcs ("
                + "npc_id VARCHAR(32) PRIMARY KEY, "
                + "kind VARCHAR(16) NOT NULL, "
                + "location VARCHAR(255) NOT NULL, "
                + "link_ref TEXT, "
                + "has_model INTEGER DEFAULT 0);";
        String claims = "CREATE TABLE IF NOT EXISTS claims ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "owner_uuid VARCHAR(36), "
                + "data TEXT);";
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
        // Lifetime sales per shop and owner. Kept apart from the transaction log so log.retention-days purges
        // never change the totals shown by the placeholders, the shop directory and the owner menu.
        String salesTotals = "CREATE TABLE IF NOT EXISTS sales_totals ("
                + "shop_id VARCHAR(36) NOT NULL, "
                + "seller_uuid VARCHAR(36) NOT NULL, "
                + "trades INTEGER NOT NULL DEFAULT 0, "
                + "money REAL NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (shop_id, seller_uuid));";
        // Leaderboards: stats of the CURRENT daily/weekly period only; rows are cleared when a period resets.
        String lbState = "CREATE TABLE IF NOT EXISTS lb_state (period VARCHAR(16) PRIMARY KEY, start_ts INTEGER NOT NULL);";
        String lbEarnings = "CREATE TABLE IF NOT EXISTS lb_earnings ("
                + "period VARCHAR(16) NOT NULL, owner_uuid VARCHAR(36) NOT NULL, money REAL NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (period, owner_uuid));";
        String lbVisits = "CREATE TABLE IF NOT EXISTS lb_visits ("
                + "period VARCHAR(16) NOT NULL, owner_uuid VARCHAR(36) NOT NULL, visitor_uuid VARCHAR(36) NOT NULL, "
                + "PRIMARY KEY (period, owner_uuid, visitor_uuid));";
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL;");
            s.execute(profiles);
            s.execute(shops);
            s.execute(npcs);
            s.execute(claims);
            s.execute(transactions);
            s.execute(ratings);
            s.execute(salesTotals);
            s.execute(lbState);
            s.execute(lbEarnings);
            s.execute(lbVisits);
            s.execute("CREATE INDEX IF NOT EXISTS idx_rating_ts ON shop_ratings (ts);");
            // One-time backfill for a database that has a log but no totals yet (the table was just created).
            s.execute("INSERT INTO sales_totals (shop_id, seller_uuid, trades, money) "
                    + "SELECT shop_id, seller_uuid, COALESCE(SUM(trades), 0), COALESCE(SUM(money), 0) "
                    + "FROM transactions WHERE NOT EXISTS (SELECT 1 FROM sales_totals) GROUP BY shop_id, seller_uuid");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_seller ON transactions (seller_uuid, ts);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_buyer ON transactions (buyer_uuid, ts);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_shop ON transactions (shop_id, seller_uuid, ts);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_unread ON transactions (seller_uuid, notified);");

            // Migrations for existing databases
            for (String col : new String[]{
                    "unlocked_skins TEXT", "skin_type TEXT", "skin_profession TEXT", "slots_level INTEGER",
                    "unlocked_nametags TEXT", "nametag_format VARCHAR(255) DEFAULT '<green><bold>Available Shop</bold></green>'",
                    "unlocked_effects TEXT", "equipped_effect VARCHAR(64) DEFAULT ''"}) {
                try { s.execute("ALTER TABLE player_profiles ADD COLUMN " + col); } catch (SQLException ignored) {}
            }
            try { s.execute("ALTER TABLE shops ADD COLUMN trade_prices TEXT"); } catch (SQLException ignored) {}
            try { s.execute("ALTER TABLE shop_npcs ADD COLUMN has_model INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            for (String col : new String[]{
                    "sale_percent INTEGER DEFAULT 0", "sale_expiry INTEGER DEFAULT 0", "sale_listed INTEGER DEFAULT 0",
                    "featured_until INTEGER DEFAULT 0", "staff TEXT", "npc_id VARCHAR(32)", "linked_skin VARCHAR(64)"}) {
                try { s.execute("ALTER TABLE shops ADD COLUMN " + col); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not initialize database tables: " + e.getMessage());
        }
    }

    public void async(Runnable r) {
        Runnable safe = () -> {
            try { r.run(); } catch (Throwable t) {
                plugin.getLogger().severe("Database task failed: " + t.getMessage());
            }
        };
        try { executor.execute(safe); } catch (RejectedExecutionException ex) { safe.run(); }
    }

    public List<ShopRow> loadShops() {
        List<ShopRow> rows = new ArrayList<>();
        String q = "SELECT shop_id, owner_uuid, location, rent_expiry, active_trades, backend_stock, trade_prices, "
                + "sale_percent, sale_expiry, sale_listed, featured_until, staff, npc_id, linked_skin FROM shops";
        try (Connection c = getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) {
                if (rs.getString(2) == null) continue;
                rows.add(new ShopRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getInt(8), rs.getLong(9), rs.getInt(10) != 0, rs.getLong(11), rs.getString(12),
                        rs.getString(13), rs.getString(14)));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load shops: " + e.getMessage());
        }
        return rows;
    }

    // ------------------------------------------------------------ shop NPC slots

    public List<NpcRow> loadNpcs() {
        List<NpcRow> rows = new ArrayList<>();
        try (Connection c = getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT npc_id, kind, location, link_ref, has_model FROM shop_npcs")) {
            while (rs.next()) rows.add(new NpcRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5) != 0));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load shop NPCs: " + e.getMessage());
        }
        return rows;
    }

    public void upsertNpc(NpcRow r) {
        String q = "INSERT OR REPLACE INTO shop_npcs (npc_id, kind, location, link_ref, has_model) VALUES (?, ?, ?, ?, ?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, r.id());
            ps.setString(2, r.kind());
            ps.setString(3, r.location());
            ps.setString(4, r.ref());
            ps.setInt(5, r.models() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save shop NPC " + r.id() + ": " + e.getMessage());
        }
    }

    public void deleteNpc(String id) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM shop_npcs WHERE npc_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to delete shop NPC " + id + ": " + e.getMessage());
        }
    }

    public Map<UUID, PlayerProfile> loadProfiles() {
        Map<UUID, PlayerProfile> map = new HashMap<>();
        String q = "SELECT uuid, unlocked_slots, tax_reduction_level, max_rent_duration, unlocked_skins, skin_type, skin_profession, slots_level, unlocked_nametags, nametag_format, unlocked_effects, equipped_effect FROM player_profiles";
        try (Connection c = getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) {
                try {
                    UUID u = UUID.fromString(rs.getString(1));
                    PlayerProfile pp = new PlayerProfile(u, rs.getInt(2), rs.getInt(3), rs.getInt(4));
                    pp.loadSkins(rs.getString(5), rs.getString(6), rs.getString(7));
                    int sl = rs.getInt(8);
                    if (rs.wasNull()) sl = Math.max(0, rs.getInt(2) - 1);
                    pp.setSlotLevel(sl);

                    // Load Nametags
                    pp.loadNametags(rs.getString(9));
                    if (rs.getString(10) != null) pp.setEquippedNametagFormat(rs.getString(10));

                    // Load Effects
                    pp.loadEffects(rs.getString(11), rs.getString(12));

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

    public List<SalesCount> loadSalesCounts() {
        List<SalesCount> out = new ArrayList<>();
        String q = "SELECT shop_id, seller_uuid, trades FROM sales_totals";
        try (Connection c = getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(q)) {
            while (rs.next()) out.add(new SalesCount(rs.getString(1), rs.getString(2), rs.getLong(3)));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load sales counts: " + e.getMessage());
        }
        return out;
    }

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

    public void upsertShop(ShopRow r) {
        String q = "INSERT OR REPLACE INTO shops (shop_id, owner_uuid, location, rent_expiry, active_trades, backend_stock, trade_prices, "
                + "sale_percent, sale_expiry, sale_listed, featured_until, staff, npc_id, linked_skin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, r.id());
            ps.setString(2, r.owner());
            ps.setString(3, r.location());
            ps.setLong(4, r.expiry());
            ps.setString(5, r.trades());
            ps.setString(6, r.stock());
            ps.setString(7, r.prices());
            ps.setInt(8, r.salePercent());
            ps.setLong(9, r.saleExpiry());
            ps.setInt(10, r.saleListed() ? 1 : 0);
            ps.setLong(11, r.featuredUntil());
            ps.setString(12, r.staff());
            ps.setString(13, r.npcId());
            ps.setString(14, r.linkedSkin());
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

    // Keeps backwards compatibility with your existing ShopManager
    public void upsertProfile(String uuid, int slots, int tax, int days, String skins, String skinType, String skinProf, int slotLevel) {
        String q = "UPDATE player_profiles SET unlocked_slots=?, tax_reduction_level=?, max_rent_duration=?, unlocked_skins=?, skin_type=?, skin_profession=?, slots_level=? WHERE uuid=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setInt(1, slots);
            ps.setInt(2, tax);
            ps.setInt(3, days);
            ps.setString(4, skins);
            ps.setString(5, skinType);
            ps.setString(6, skinProf);
            ps.setInt(7, slotLevel);
            ps.setString(8, uuid);
            if (ps.executeUpdate() == 0) {
                // Insert new row if it didn't exist
                String iq = "INSERT INTO player_profiles (uuid, unlocked_slots, tax_reduction_level, max_rent_duration, unlocked_skins, skin_type, skin_profession, slots_level) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ips = c.prepareStatement(iq)) {
                    ips.setString(1, uuid);
                    ips.setInt(2, slots);
                    ips.setInt(3, tax);
                    ips.setInt(4, days);
                    ips.setString(5, skins);
                    ips.setString(6, skinType);
                    ips.setString(7, skinProf);
                    ips.setInt(8, slotLevel);
                    ips.executeUpdate();
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save profile " + uuid + ": " + e.getMessage());
        }
    }

    public void updateNametagData(String uuid, String unlockedNametags, String format) {
        String q = "UPDATE player_profiles SET unlocked_nametags = ?, nametag_format = ? WHERE uuid = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, unlockedNametags);
            ps.setString(2, format);
            ps.setString(3, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save nametag data for " + uuid + ": " + e.getMessage());
        }
    }

    public void updateEffectData(String uuid, String unlockedEffects, String equipped) {
        String q = "UPDATE player_profiles SET unlocked_effects = ?, equipped_effect = ? WHERE uuid = ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setString(1, unlockedEffects);
            ps.setString(2, equipped);
            ps.setString(3, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save effect data for " + uuid + ": " + e.getMessage());
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

    public void insertTransaction(TxRow r, boolean notified) {
        String q = "INSERT INTO transactions (ts, shop_id, seller_uuid, buyer_uuid, buyer_name, kind, item_type, "
                + "item_name, item_qty, trades, money, pay_summary, notified) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String totals = "INSERT INTO sales_totals (shop_id, seller_uuid, trades, money) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(shop_id, seller_uuid) DO UPDATE SET trades = trades + excluded.trades, money = money + excluded.money";
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(q); PreparedStatement pt = c.prepareStatement(totals)) {
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

                pt.setString(1, r.shopId());
                pt.setString(2, r.seller());
                pt.setInt(3, r.trades());
                pt.setDouble(4, r.money());
                pt.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
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

    /** Every sale ever made, across all shops, unaffected by log purges (feeds %vm_total_sales% / %vm_total_revenue%). */
    public Totals allTotals() {
        String q = "SELECT COUNT(*), COALESCE(SUM(trades), 0), COALESCE(SUM(money), 0) FROM sales_totals";
        try (Connection c = getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q)) {
            if (rs.next()) return new Totals(rs.getLong(1), rs.getLong(2), rs.getDouble(3));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to total all transactions: " + e.getMessage());
        }
        return new Totals(0, 0, 0);
    }

    // ------------------------------------------------------------ leaderboards

    public Map<String, Long> lbLoadState() {
        Map<String, Long> out = new HashMap<>();
        try (Connection c = getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT period, start_ts FROM lb_state")) {
            while (rs.next()) out.put(rs.getString(1), rs.getLong(2));
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load leaderboard state: " + e.getMessage());
        }
        return out;
    }

    public void lbSetState(String period, long start) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lb_state (period, start_ts) VALUES (?, ?) ON CONFLICT(period) DO UPDATE SET start_ts = excluded.start_ts")) {
            ps.setString(1, period);
            ps.setLong(2, start);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to save leaderboard state: " + e.getMessage());
        }
    }

    public void lbAddEarning(String period, String owner, double money) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO lb_earnings (period, owner_uuid, money) VALUES (?, ?, ?) "
                        + "ON CONFLICT(period, owner_uuid) DO UPDATE SET money = money + excluded.money")) {
            ps.setString(1, period);
            ps.setString(2, owner);
            ps.setDouble(3, money);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record leaderboard earnings: " + e.getMessage());
        }
    }

    /** One row per (period, shop owner, visitor): repeat visits by the same player don't count again. */
    public void lbAddVisit(String period, String owner, String visitor) {
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT OR IGNORE INTO lb_visits (period, owner_uuid, visitor_uuid) VALUES (?, ?, ?)")) {
            ps.setString(1, period);
            ps.setString(2, owner);
            ps.setString(3, visitor);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to record leaderboard visit: " + e.getMessage());
        }
    }

    public List<LbRow> lbEarnings(String period, int limit) {
        return lbQuery("SELECT owner_uuid, money, 0 FROM lb_earnings WHERE period = ? AND money > 0 "
                + "ORDER BY money DESC LIMIT ?", ps -> { ps.setString(1, period); ps.setInt(2, limit); });
    }

    public List<LbRow> lbVisits(String period, int limit) {
        return lbQuery("SELECT owner_uuid, COUNT(*), 0 FROM lb_visits WHERE period = ? GROUP BY owner_uuid "
                + "ORDER BY COUNT(*) DESC LIMIT ?", ps -> { ps.setString(1, period); ps.setInt(2, limit); });
    }

    /** Average of the ratings given since {@code since}, for owners with at least {@code minCount} of them. */
    public List<LbRow> lbRatings(long since, int minCount, int limit) {
        return lbQuery("SELECT owner_uuid, AVG(stars), COUNT(*) FROM shop_ratings WHERE ts >= ? GROUP BY owner_uuid "
                + "HAVING COUNT(*) >= ? ORDER BY AVG(stars) DESC, COUNT(*) DESC LIMIT ?",
                ps -> { ps.setLong(1, since); ps.setInt(2, minCount); ps.setInt(3, limit); });
    }

    private interface Binder { void bind(PreparedStatement ps) throws SQLException; }

    private List<LbRow> lbQuery(String q, Binder b) {
        List<LbRow> out = new ArrayList<>();
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            b.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new LbRow(rs.getString(1), rs.getDouble(2), rs.getInt(3)));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to read leaderboard: " + e.getMessage());
        }
        return out;
    }

    /** Clears a period's stats and records when its new period began. */
    public void lbReset(String period, long newStart) {
        try (Connection c = getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement a = c.prepareStatement("DELETE FROM lb_earnings WHERE period = ?");
                 PreparedStatement v = c.prepareStatement("DELETE FROM lb_visits WHERE period = ?");
                 PreparedStatement st = c.prepareStatement(
                         "INSERT INTO lb_state (period, start_ts) VALUES (?, ?) ON CONFLICT(period) DO UPDATE SET start_ts = excluded.start_ts")) {
                a.setString(1, period); a.executeUpdate();
                v.setString(1, period); v.executeUpdate();
                st.setString(1, period); st.setLong(2, newStart); st.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to reset leaderboard: " + e.getMessage());
        }
    }

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