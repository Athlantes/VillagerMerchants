package org.riftlabs.villagerMerchants.analytics;

import java.time.ZoneId;
import java.util.List;

/**
 * Everything the dashboard shows, computed once off the main thread and then only read (never mutated), so the
 * map renderer can use it from any thread.
 */
public record AnalyticsData(long generatedAt, ZoneId zone, String scopeLabel, long version,
                            ModeData day, ModeData week, RatingData rating) {

    /** The two ranges the owner can flip between. */
    public enum Mode {
        DAY, WEEK;
        public Mode other() { return this == DAY ? WEEK : DAY; }
    }

    public ModeData mode(Mode m) { return m == Mode.DAY ? day : week; }

    public record ItemStat(String name, long units, long trades, double revenue) {}

    public record BuyerStat(String name, double spent, long trades) {}

    /**
     * One range (14 days or 8 weeks): a value per bucket, oldest first, the last bucket being "now".
     * {@code prev*} cover the equal span right before, for the up/down arrows.
     */
    public record ModeData(String[] labels, double[] revenue, long[] sales, int[] customers,
                           double revenueTotal, long salesTotal, int customersTotal, int customersNew,
                           double prevRevenue, long prevSales, int prevCustomers,
                           List<ItemStat> items, int itemKinds, List<BuyerStat> buyers,
                           double[] ratingAvg, int[] ratingNew) {
        public int buckets() { return revenue.length; }
        public boolean empty() { return salesTotal == 0; }
        public int customersReturning() { return Math.max(0, customersTotal - customersNew); }

        public int bestBucket() {
            int best = -1;
            double v = 0;
            for (int i = 0; i < revenue.length; i++) if (revenue[i] > v) { v = revenue[i]; best = i; }
            return best;
        }
    }

    /** All-time rating picture of the shop(s): average, how many, and the 1-5 star spread (index 0 = one star). */
    public record RatingData(double average, int count, int[] spread) {
        public static final RatingData NONE = new RatingData(0, 0, new int[5]);
    }
}
