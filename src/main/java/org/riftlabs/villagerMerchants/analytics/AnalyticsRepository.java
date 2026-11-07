package org.riftlabs.villagerMerchants.analytics;

import org.riftlabs.villagerMerchants.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Read-only queries over the existing {@code transactions} and {@code shop_ratings} tables (no schema changes).
 * Everything here blocks on SQLite: call it from the database thread only ({@code db.async}).
 *
 * <p>Sales are fetched pre-grouped by 15 minute slot and buyer. Every real time zone's offset is a multiple of 15
 * minutes, so the slots can be assigned to local days and weeks in Java without caring about DST, and a very busy
 * shop still returns a bounded number of rows.
 */
public final class AnalyticsRepository {
    public static final long SLOT_MS = 900_000L;

    /** Sales of one buyer in one 15 minute slot. */
    public record SlotRow(long slot, String buyer, double money, long trades) {}
    public record ItemRow(String type, String name, long units, long trades, double money) {}
    public record BuyerRow(String uuid, String name, long firstTs, double money, long trades) {}
    public record RatingRow(int stars, long ts) {}

    private final DatabaseManager db;
    private final Logger log;

    public AnalyticsRepository(DatabaseManager db, Logger log) {
        this.db = db;
        this.log = log;
    }

    private static String scopeSql(Scope s, String tsCol) {
        return " seller_uuid = ?" + (s.singleShop() ? " AND shop_id = ?" : "") + " AND " + tsCol + " >= ?";
    }

    private static int bind(PreparedStatement ps, Scope s, int i) throws SQLException {
        ps.setString(i++, s.owner().toString());
        if (s.singleShop()) ps.setString(i++, s.shopId().toString());
        return i;
    }

    /** Cheap change detector: moves whenever a sale or a rating is added. */
    public long version(Scope s) {
        String sales = "SELECT COALESCE(MAX(id), 0) FROM transactions WHERE seller_uuid = ?" + (s.singleShop() ? " AND shop_id = ?" : "");
        String rates = "SELECT COUNT(*), COALESCE(MAX(ts), 0) FROM shop_ratings WHERE owner_uuid = ?" + (s.singleShop() ? " AND shop_id = ?" : "");
        try (Connection c = db.getConnection()) {
            long lastSale = 0, ratingCount = 0, lastRating = 0;
            try (PreparedStatement ps = c.prepareStatement(sales)) {
                bind(ps, s, 1);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) lastSale = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(rates)) {
                bind(ps, s, 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) { ratingCount = rs.getLong(1); lastRating = rs.getLong(2); }
                }
            }
            return lastSale * 31 + ratingCount * 17 + lastRating;
        } catch (SQLException e) {
            log.warning("Analytics version check failed: " + e.getMessage());
            return -1;
        }
    }

    public List<SlotRow> slots(Scope s, long sinceMs) {
        String q = "SELECT ts / " + SLOT_MS + ", buyer_uuid, SUM(money), SUM(trades) FROM transactions WHERE"
                + scopeSql(s, "ts") + " GROUP BY ts / " + SLOT_MS + ", buyer_uuid";
        List<SlotRow> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            int i = bind(ps, s, 1);
            ps.setLong(i, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SlotRow(rs.getLong(1), rs.getString(2), rs.getDouble(3), rs.getLong(4)));
            }
        } catch (SQLException e) {
            log.warning("Analytics sales query failed: " + e.getMessage());
        }
        return out;
    }

    public List<ItemRow> items(Scope s, long sinceMs) {
        String q = "SELECT item_type, item_name, SUM(item_qty), SUM(trades), SUM(money) FROM transactions WHERE"
                + scopeSql(s, "ts") + " GROUP BY item_type, item_name ORDER BY SUM(item_qty) DESC, SUM(money) DESC LIMIT 500";
        List<ItemRow> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            int i = bind(ps, s, 1);
            ps.setLong(i, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new ItemRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getDouble(5)));
            }
        } catch (SQLException e) {
            log.warning("Analytics items query failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Buyers who bought since {@code sinceMs}, with their first-ever purchase from this scope (to tell new from
     * returning customers) and what they spent inside the window.
     */
    public List<BuyerRow> buyers(Scope s, long sinceMs) {
        String q = "SELECT buyer_uuid, buyer_name, MAX(ts), MIN(ts), "
                + "SUM(CASE WHEN ts >= ? THEN money ELSE 0 END), SUM(CASE WHEN ts >= ? THEN trades ELSE 0 END) "
                + "FROM transactions WHERE seller_uuid = ?" + (s.singleShop() ? " AND shop_id = ?" : "")
                + " GROUP BY buyer_uuid HAVING MAX(ts) >= ?";
        List<BuyerRow> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            ps.setLong(1, sinceMs);
            ps.setLong(2, sinceMs);
            int i = bind(ps, s, 3);
            ps.setLong(i, sinceMs);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new BuyerRow(rs.getString(1), rs.getString(2), rs.getLong(4), rs.getDouble(5), rs.getLong(6)));
            }
        } catch (SQLException e) {
            log.warning("Analytics buyers query failed: " + e.getMessage());
        }
        return out;
    }

    /** Every rating of the scope, oldest first. A rater who changed their mind only appears with their latest vote. */
    public List<RatingRow> ratings(Scope s) {
        String q = "SELECT stars, ts FROM shop_ratings WHERE owner_uuid = ?" + (s.singleShop() ? " AND shop_id = ?" : "") + " ORDER BY ts";
        List<RatingRow> out = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            bind(ps, s, 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new RatingRow(rs.getInt(1), rs.getLong(2)));
            }
        } catch (SQLException e) {
            log.warning("Analytics ratings query failed: " + e.getMessage());
        }
        return out;
    }
}
