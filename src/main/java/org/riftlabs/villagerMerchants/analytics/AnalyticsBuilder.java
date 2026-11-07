package org.riftlabs.villagerMerchants.analytics;

import org.riftlabs.villagerMerchants.analytics.AnalyticsData.BuyerStat;
import org.riftlabs.villagerMerchants.analytics.AnalyticsData.ItemStat;
import org.riftlabs.villagerMerchants.analytics.AnalyticsData.ModeData;
import org.riftlabs.villagerMerchants.analytics.AnalyticsData.RatingData;
import org.riftlabs.villagerMerchants.analytics.AnalyticsRepository.BuyerRow;
import org.riftlabs.villagerMerchants.analytics.AnalyticsRepository.ItemRow;
import org.riftlabs.villagerMerchants.analytics.AnalyticsRepository.RatingRow;
import org.riftlabs.villagerMerchants.analytics.AnalyticsRepository.SlotRow;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/** Turns query rows into {@link AnalyticsData}. Pure: no database, no Bukkit. */
public final class AnalyticsBuilder {
    private AnalyticsBuilder() {}

    /** Start of the oldest bucket of the day view and of the week view, and the earliest sale worth fetching. */
    public record Window(LocalDate dayStart, LocalDate weekStart, long fetchSince, long dayStartMs, long weekStartMs) {}

    public static Window window(ZoneId zone, int days, int weeks, long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate dayStart = today.minusDays(days - 1L);
        LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1L);
        long dayMs = dayStart.atStartOfDay(zone).toInstant().toEpochMilli();
        long weekMs = weekStart.atStartOfDay(zone).toInstant().toEpochMilli();
        long since = Math.min(dayStart.minusDays(days).atStartOfDay(zone).toInstant().toEpochMilli(),
                weekStart.minusWeeks(weeks).atStartOfDay(zone).toInstant().toEpochMilli());
        return new Window(dayStart, weekStart, since, dayMs, weekMs);
    }

    public static AnalyticsData build(String label, long version, long now, ZoneId zone, Locale locale, String weekPrefix,
                                      int days, int weeks, Window w,
                                      List<SlotRow> slots, List<ItemRow> dayItems, List<ItemRow> weekItems,
                                      List<BuyerRow> dayBuyers, List<BuyerRow> weekBuyers, List<RatingRow> ratings) {
        ModeData day = mode(false, days, w.dayStart(), w.dayStartMs(), zone, locale, weekPrefix, slots, dayItems, dayBuyers, ratings);
        ModeData week = mode(true, weeks, w.weekStart(), w.weekStartMs(), zone, locale, weekPrefix, slots, weekItems, weekBuyers, ratings);
        return new AnalyticsData(now, zone, label, version, day, week, ratingData(ratings));
    }

    private static RatingData ratingData(List<RatingRow> ratings) {
        if (ratings.isEmpty()) return RatingData.NONE;
        int[] spread = new int[5];
        double sum = 0;
        for (RatingRow r : ratings) {
            int s = Math.max(1, Math.min(5, r.stars()));
            spread[s - 1]++;
            sum += s;
        }
        return new RatingData(sum / ratings.size(), ratings.size(), spread);
    }

    private static ModeData mode(boolean weekly, int n, LocalDate first, long firstMs, ZoneId zone, Locale locale, String weekPrefix,
                                 List<SlotRow> slots, List<ItemRow> items, List<BuyerRow> buyers, List<RatingRow> ratings) {
        double[] rev = new double[n];
        long[] sales = new long[n];
        List<Set<String>> perBucket = new ArrayList<>();
        for (int i = 0; i < n; i++) perBucket.add(new HashSet<>());
        Set<String> window = new HashSet<>(), prevWindow = new HashSet<>();
        double prevRev = 0;
        long prevSales = 0;

        for (SlotRow r : slots) {
            LocalDate date = Instant.ofEpochMilli(r.slot() * AnalyticsRepository.SLOT_MS).atZone(zone).toLocalDate();
            long idx = weekly ? ChronoUnit.WEEKS.between(first, date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
                              : ChronoUnit.DAYS.between(first, date);
            if (idx >= n) continue;
            if (idx >= 0) {
                rev[(int) idx] += r.money();
                sales[(int) idx] += r.trades();
                perBucket.get((int) idx).add(r.buyer());
                window.add(r.buyer());
            } else if (idx >= -n) {
                prevRev += r.money();
                prevSales += r.trades();
                prevWindow.add(r.buyer());
            }
        }

        double revTotal = 0;
        long salesTotal = 0;
        int[] cust = new int[n];
        String[] labels = new String[n];
        for (int i = 0; i < n; i++) {
            revTotal += rev[i];
            salesTotal += sales[i];
            cust[i] = perBucket.get(i).size();
            LocalDate d = weekly ? first.plusWeeks(i) : first.plusDays(i);
            labels[i] = weekly ? weekPrefix + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) : d.getDayOfWeek().getDisplayName(TextStyle.NARROW, locale);
        }

        int fresh = 0;
        for (BuyerRow b : buyers) if (b.firstTs() >= firstMs) fresh++;
        int uniques = window.size();
        fresh = Math.min(fresh, uniques);

        List<ItemStat> top = new ArrayList<>();
        for (ItemRow it : items) {
            if (top.size() >= 5) break;
            String name = it.name() == null || it.name().isBlank() ? String.valueOf(it.type()) : it.name();
            top.add(new ItemStat(name, it.units(), it.trades(), it.money()));
        }
        List<BuyerStat> topBuyers = new ArrayList<>();
        buyers.stream().filter(b -> b.trades() > 0)
                .sorted(Comparator.comparingDouble(BuyerRow::money).reversed().thenComparing(Comparator.comparingLong(BuyerRow::trades).reversed()))
                .limit(3)
                .forEach(b -> topBuyers.add(new BuyerStat(b.name() == null ? "?" : b.name(), b.money(), b.trades())));

        // rating history: running average at the end of each bucket, and how many ratings landed in it
        double[] avg = new double[n];
        int[] newRatings = new int[n];
        double sum = 0;
        int count = 0, ri = 0;
        for (int i = 0; i < n; i++) {
            LocalDate next = weekly ? first.plusWeeks(i + 1L) : first.plusDays(i + 1L);
            long endMs = next.atStartOfDay(zone).toInstant().toEpochMilli();
            long startMs = (weekly ? first.plusWeeks(i) : first.plusDays(i)).atStartOfDay(zone).toInstant().toEpochMilli();
            while (ri < ratings.size() && ratings.get(ri).ts() < endMs) {
                RatingRow r = ratings.get(ri++);
                sum += r.stars();
                count++;
                if (r.ts() >= startMs) newRatings[i]++;
            }
            avg[i] = count == 0 ? Double.NaN : sum / count;
        }

        return new ModeData(labels, rev, sales, cust, revTotal, salesTotal, uniques, fresh,
                prevRev, prevSales, prevWindow.size(), top, items.size(), topBuyers, avg, newRatings);
    }

    /** The calendar date a timestamp falls on in {@code zone} (the dashboard uses it to notice midnight passing). */
    public static LocalDate dateOf(long ms, ZoneId zone) { return Instant.ofEpochMilli(ms).atZone(zone).toLocalDate(); }
}
