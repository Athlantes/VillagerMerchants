package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.database.DatabaseManager.LbRow;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily and weekly leaderboards: top earners, best-rated shops and most-visited shops, with optional rewards
 * paid when a period resets.
 *
 * Shops are ranked by their owner (a player can only rent a limited number of shops, and shop ids change when the
 * NPC respawns, while owners are stable).
 *
 *  - Earnings: money earned from sales during the period (stored in lb_earnings, cleared on reset).
 *  - Visits:   unique players who opened the shop's trade window during the period (lb_visits, cleared on reset).
 *  - Rating:   average of the star ratings given during the period, for shops with at least leaderboards.min-ratings.
 *
 * Shop owners, staff and the owner previewing their own shop never count as visitors.
 */
public class LeaderboardManager {

    public enum Period {
        DAILY("daily", 1), WEEKLY("weekly", 7);
        public final String id;
        public final int days;
        Period(String id, int days) { this.id = id; this.days = days; }
        public static Period parse(String s) {
            if (s == null) return null;
            for (Period p : values()) if (p.id.equalsIgnoreCase(s)) return p;
            return null;
        }
    }

    public enum Category {
        EARNINGS("earners"), RATING("rated"), VISITS("visited");
        public final String id;
        Category(String id) { this.id = id; }
        public static Category parse(String s) {
            if (s == null) return null;
            for (Category c : values()) if (c.id.equalsIgnoreCase(s)) return c;
            return null;
        }
    }

    /** One ranked shop. {@code count} is the number of ratings behind a rating score (0 for the other boards). */
    public record Entry(UUID owner, String name, double value, int count) {}

    private final VillagerMerchants plugin;
    private final DatabaseManager db;

    /** When each running period began. */
    private final Map<Period, Long> started = new ConcurrentHashMap<>();
    /** Periods currently being reset (so a slow reset isn't started twice). */
    private final Set<Period> resetting = ConcurrentHashMap.newKeySet();
    /** "period|owner|visitor" already written this period, to spare the database repeat visits. */
    private final Set<String> seenVisits = ConcurrentHashMap.newKeySet();
    /** Latest ranking per board, rebuilt every few seconds. Read freely from any thread. */
    private volatile Map<String, List<Entry>> cache = Map.of();

    private BukkitTask refreshTask;
    private BukkitTask resetTask;

    public LeaderboardManager(VillagerMerchants plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ------------------------------------------------------------ config

    public boolean enabled() { return cfg().getBoolean("leaderboards.enabled", true); }
    public boolean periodEnabled(Period p) { return enabled() && cfg().getBoolean("leaderboards.periods." + p.id, true); }
    public int size() { return Math.max(1, cfg().getInt("leaderboards.size", 10)); }
    public int chatLines() { return Math.max(1, cfg().getInt("leaderboards.chat-lines", 5)); }
    private int minRatings() { return Math.max(1, cfg().getInt("leaderboards.min-ratings", 3)); }

    public List<Period> enabledPeriods() {
        List<Period> out = new ArrayList<>();
        for (Period p : Period.values()) if (periodEnabled(p)) out.add(p);
        return out;
    }

    private ZoneId zone() {
        String z = cfg().getString("leaderboards.timezone", "");
        try { return z == null || z.isBlank() ? ZoneId.systemDefault() : ZoneId.of(z.trim()); }
        catch (Exception e) { return ZoneId.systemDefault(); }
    }

    private LocalTime resetTime() {
        try { return LocalTime.parse(cfg().getString("leaderboards.reset-time", "00:00").trim()); }
        catch (Exception e) { return LocalTime.MIDNIGHT; }
    }

    private DayOfWeek resetDay() {
        try { return DayOfWeek.valueOf(cfg().getString("leaderboards.weekly-reset-day", "MONDAY").trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { return DayOfWeek.MONDAY; }
    }

    // ------------------------------------------------------------ period boundaries

    /** The most recent reset moment at or before {@code now}: the start of the period that is running. */
    long latestBoundary(Period p, long now) {
        ZonedDateTime t = at(now);
        ZonedDateTime b = t.toLocalDate().atTime(resetTime()).atZone(zone());
        if (p == Period.WEEKLY) {
            b = t.toLocalDate().with(TemporalAdjusters.previousOrSame(resetDay())).atTime(resetTime()).atZone(zone());
            if (b.isAfter(t)) b = b.minusDays(7);
        } else if (b.isAfter(t)) {
            b = b.minusDays(1);
        }
        return b.toInstant().toEpochMilli();
    }

    private ZonedDateTime at(long ms) { return java.time.Instant.ofEpochMilli(ms).atZone(zone()); }

    /** Milliseconds until the running period resets. */
    public long millisUntilReset(Period p) {
        long now = System.currentTimeMillis();
        long start = latestBoundary(p, now);
        long next = at(start).plusDays(p.days).toInstant().toEpochMilli();
        return Math.max(0, next - now);
    }

    // ------------------------------------------------------------ lifecycle

    public void load() {
        Map<String, Long> saved = db.lbLoadState();
        long now = System.currentTimeMillis();
        for (Period p : Period.values()) {
            Long s = saved.get(p.id);
            if (s == null) {
                s = latestBoundary(p, now);
                final long first = s;
                db.async(() -> db.lbSetState(p.id, first));
            }
            started.put(p, s);
        }
    }

    public void startTasks() {
        cancelTasks();
        long refresh = Math.max(5, cfg().getLong("leaderboards.refresh-seconds", 30)) * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, refresh);
        resetTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkResets, 100L, 20L * 30);
    }

    public void cancelTasks() {
        if (refreshTask != null) refreshTask.cancel();
        if (resetTask != null) resetTask.cancel();
        refreshTask = null;
        resetTask = null;
    }

    public void reload() {
        startTasks();
        refresh();
    }

    // ------------------------------------------------------------ recording

    /** Called for every money sale; money earned counts towards each running period. */
    public void recordEarning(UUID owner, double money) {
        if (!enabled() || money <= 0) return;
        String o = owner.toString();
        for (Period p : enabledPeriods()) db.async(() -> db.lbAddEarning(p.id, o, money));
    }

    /** A customer opened the shop's trade window. Owner and staff don't count; each player counts once per period. */
    public void recordVisit(Player visitor, Shop shop) {
        if (!enabled() || shop.isMember(visitor.getUniqueId())) return;
        String owner = shop.getOwner().toString();
        String vis = visitor.getUniqueId().toString();
        for (Period p : enabledPeriods()) {
            if (seenVisits.add(p.id + "|" + owner + "|" + vis)) db.async(() -> db.lbAddVisit(p.id, owner, vis));
        }
    }

    // ------------------------------------------------------------ reading

    public List<Entry> top(Period p, Category c) {
        return cache.getOrDefault(p.id + ":" + c.id, List.of());
    }

    /** Score as shown to players, e.g. "$1,250.00", "4.8★ (12)" or "37 visitors". */
    public String format(Category c, Entry e) {
        var m = plugin.msg();
        return switch (c) {
            case EARNINGS -> plugin.getShopManager().money(e.value());
            case RATING -> m.str("leaderboards.value.rating", "avg", Text.oneDecimal(e.value()), "count", e.count());
            case VISITS -> m.str("leaderboards.value.visits", "count", (long) e.value());
        };
    }

    // ------------------------------------------------------------ ranking

    private record Raw(List<LbRow> earnings, List<LbRow> ratings, List<LbRow> visits) {}

    /**
     * Reads the three boards for a period off the main thread (clearing them too when {@code resetTo} is set), then
     * back on the main thread drops shops that no longer exist from the rating/visit boards, resolves names and
     * hands the result over.
     */
    private void compute(Period p, long since, Long resetTo, java.util.function.Consumer<Map<Category, List<Entry>>> done) {
        final int size = size();
        final int fetch = size * 3;   // headroom: owners without a shop any more are filtered out afterwards
        final int minRatings = minRatings();
        db.async(() -> {
            Raw raw = new Raw(db.lbEarnings(p.id, size), db.lbRatings(since, minRatings, fetch), db.lbVisits(p.id, fetch));
            // On a reset the stats are cleared in this same database task, so nothing recorded in between is lost.
            if (resetTo != null) db.lbReset(p.id, resetTo);
            plugin.sync(() -> {
                Set<String> owners = new HashSet<>();
                for (Shop s : plugin.getShopManager().allShops()) owners.add(s.getOwner().toString());
                Map<Category, List<Entry>> out = new EnumMap<>(Category.class);
                out.put(Category.EARNINGS, entries(raw.earnings(), null, size));
                out.put(Category.RATING, entries(raw.ratings(), owners, size));
                out.put(Category.VISITS, entries(raw.visits(), owners, size));
                done.accept(out);
            });
        });
    }

    private List<Entry> entries(List<LbRow> rows, Set<String> mustOwnShop, int size) {
        List<Entry> out = new ArrayList<>();
        for (LbRow r : rows) {
            if (out.size() >= size) break;
            if (mustOwnShop != null && !mustOwnShop.contains(r.owner())) continue;
            try {
                UUID id = UUID.fromString(r.owner());
                out.add(new Entry(id, plugin.getShopManager().ownerName(id), r.value(), r.count()));
            } catch (IllegalArgumentException ignored) {}
        }
        return out;
    }

    /** Rebuilds the cached boards for every enabled period. */
    public void refresh() {
        if (!enabled()) { cache = Map.of(); return; }
        for (Period p : enabledPeriods()) {
            long since = started.getOrDefault(p, latestBoundary(p, System.currentTimeMillis()));
            compute(p, since, null, boards -> {
                Map<String, List<Entry>> next = new HashMap<>(cache);
                for (Map.Entry<Category, List<Entry>> e : boards.entrySet()) next.put(p.id + ":" + e.getKey().id, e.getValue());
                cache = Map.copyOf(next);
            });
        }
    }

    // ------------------------------------------------------------ reset + rewards

    private void checkResets() {
        if (!enabled()) return;
        long now = System.currentTimeMillis();
        for (Period p : enabledPeriods()) {
            long boundary = latestBoundary(p, now);
            if (boundary > started.getOrDefault(p, boundary)) reset(p, boundary);
        }
    }

    private void reset(Period p, long newStart) {
        if (!resetting.add(p)) return;
        long oldStart = started.getOrDefault(p, newStart);
        compute(p, oldStart, newStart, boards -> {
            started.put(p, newStart);
            seenVisits.removeIf(k -> k.startsWith(p.id + "|"));
            payRewards(p, boards);
            announce(p, boards);
            resetting.remove(p);
            refresh();
        });
    }

    private void payRewards(Period p, Map<Category, List<Entry>> boards) {
        for (Category c : Category.values()) {
            List<Entry> list = boards.getOrDefault(c, List.of());
            for (int i = 0; i < list.size(); i++) {
                int rank = i + 1;
                ConfigurationSection sec = cfg().getConfigurationSection("leaderboards.rewards." + p.id + "." + c.id + "." + rank);
                if (sec == null) continue;
                Entry e = list.get(i);
                OfflinePlayer op = Bukkit.getOfflinePlayer(e.owner());
                double money = sec.getDouble("money", 0);
                if (money > 0 && plugin.getEconomy() != null) plugin.getEconomy().depositPlayer(op, money);
                String value = format(c, e);
                for (String cmd : sec.getStringList("commands")) {
                    String line = cmd.replace("%player%", e.name()).replace("%uuid%", e.owner().toString())
                            .replace("%rank%", String.valueOf(rank)).replace("%period%", p.id)
                            .replace("%board%", c.id).replace("%value%", value);
                    try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line); }
                    catch (Exception ex) { plugin.getLogger().warning("Leaderboard reward command failed: " + line + " (" + ex.getMessage() + ")"); }
                }
                Player online = op.getPlayer();
                if (online != null) {
                    String rewardText = money > 0
                            ? plugin.msg().str("leaderboards.reward-money", "money", Text.esc(plugin.getShopManager().money(money))) : "";
                    plugin.msg().send(online, "leaderboards.reward-message", "rank", rank,
                            "board", plugin.msg().raw("leaderboards.title." + c.id),
                            "period", plugin.msg().raw("leaderboards.period." + p.id),
                            "reward", org.riftlabs.villagerMerchants.util.Messages.parsed(rewardText));
                }
            }
        }
    }

    private void announce(Period p, Map<Category, List<Entry>> boards) {
        if (!cfg().getBoolean("leaderboards.announce", true)) return;
        var m = plugin.msg();
        boolean any = boards.values().stream().anyMatch(l -> !l.isEmpty());
        if (!any) return;
        Bukkit.broadcast(m.get("leaderboards.announce-header", "period", m.raw("leaderboards.period." + p.id)));
        for (Category c : Category.values()) {
            List<Entry> list = boards.getOrDefault(c, List.of());
            if (list.isEmpty()) continue;
            Entry w = list.get(0);
            Bukkit.broadcast(m.get("leaderboards.announce-line", "board", m.raw("leaderboards.title." + c.id),
                    "name", w.name(), "value", format(c, w)));
        }
    }
}
