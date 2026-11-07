package org.riftlabs.villagerMerchants.analytics;

import org.riftlabs.villagerMerchants.analytics.AnalyticsData.BuyerStat;
import org.riftlabs.villagerMerchants.analytics.AnalyticsData.ItemStat;
import org.riftlabs.villagerMerchants.analytics.AnalyticsData.Mode;
import org.riftlabs.villagerMerchants.analytics.AnalyticsData.ModeData;
import org.riftlabs.villagerMerchants.analytics.render.Fonts;
import org.riftlabs.villagerMerchants.analytics.render.Frame;
import org.riftlabs.villagerMerchants.analytics.render.Pal;
import org.riftlabs.villagerMerchants.analytics.render.Theme;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Draws the five dashboard pages. Pure functions of (data, mode, animation progress): no Bukkit, no state, safe to
 * call from any thread (a map renderer runs on whatever thread owns the viewing player).
 */
public final class Pages {
    private Pages() {}

    public static final int COUNT = 5;
    public static final int OVERVIEW = 0, REVENUE = 1, ITEMS = 2, CUSTOMERS = 3, RATINGS = 4;
    /** Language keys (analytics.map.*) of the page names, in page order. */
    public static final String[] NAMES = {"overview", "revenue", "best-sellers", "customers", "ratings"};

    /**
     * @param p    0..1, how far the page's entrance animation has run (1 = finished)
     * @param time seconds, for things that pulse
     * @param hint footer hint to show instead of the page name, or null
     */
    public record Ctx(Theme t, Function<String, String> lang, String symbol, String title, double p, double time, String hint) {
        String s(String key) { return Fonts.sanitize(lang.apply(key)); }

        /** Currency symbol for the 5x7 font, or "" when the font cannot draw it. */
        String symbolBig() {
            for (char ch : symbol.toCharArray()) if (!Fonts.canBig(ch)) return "";
            return symbol;
        }

        String moneyBig(double v) { return symbolBig() + Fmt.compact(v); }
        String moneySmall(double v) { return Fonts.sanitize(symbol) + Fmt.compact(v); }
        public Ctx withProgress(double np) { return new Ctx(t, lang, symbol, title, np, time, hint); }
    }

    // ------------------------------------------------------------ animation helpers

    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    static double easeOut(double v) { double x = 1 - clamp01(v); return 1 - x * x * x; }

    /** Progress of element i of n when the whole animation is at p; later elements start later. */
    static double stagger(double p, int i, int n, double spread) {
        double start = spread * i / Math.max(1, n);
        return easeOut((p - start) / (1 - spread));
    }

    // ------------------------------------------------------------ entry points

    public static Frame render(int page, AnalyticsData d, Mode mode, Ctx c) {
        Frame f = new Frame();
        ModeData md = d.mode(mode);
        chrome(f, c, mode, page, md.buckets());
        switch (page) {
            case REVENUE -> revenue(f, c, mode, md);
            case ITEMS -> items(f, c, md);
            case CUSTOMERS -> customers(f, c, mode, md);
            case RATINGS -> ratings(f, c, mode, md, d.rating());
            default -> overview(f, c, md, d.rating());
        }
        return f;
    }

    /** Shown for the split second before the first query finishes. */
    public static Frame loading(Ctx c, Mode mode, int buckets) {
        Frame f = new Frame();
        Theme t = c.t();
        chrome(f, c, mode, OVERVIEW, buckets);
        double sweep = (c.time() * 0.9) % 1.0;
        int[][] boxes = {{5, 17, 57, 30}, {66, 17, 57, 30}, {5, 50, 57, 30}, {66, 50, 57, 30}, {5, 83, 118, 31}};
        for (int[] b : boxes) {
            f.panel(b[0], b[1], b[2], b[3], t.panel);
            int hx = (int) (b[0] - 20 + sweep * (b[2] + 40));
            for (int x = Math.max(b[0], hx); x < Math.min(b[0] + b[2], hx + 14); x++)
                for (int y = b[1] + 1; y < b[1] + b[3] - 1; y++) f.set(x, y, t.panelHi);
        }
        f.textCenter(64, 63, c.s("loading"), t.textMute);
        return f;
    }

    // ------------------------------------------------------------ chrome

    private static void chrome(Frame f, Ctx c, Mode mode, int page, int buckets) {
        Theme t = c.t();
        for (int y = 0; y < Frame.H; y++) f.hline(0, y, Frame.W, t.bg.at(1.0 - y / 127.0, 0, 0));

        // header: coin, scope name, live dot, range pill
        f.disc(8, 7, 3, t.accent.step(1));
        f.disc(8, 7, 2, t.accent.bright());
        f.set(7, 6, t.white.bright());
        f.text(15, 4, Fonts.fit(c.title(), 62), t.text);

        String unit = c.s(mode == Mode.DAY ? "unit-day" : "unit-week");
        String pill = buckets < 0 ? "" : buckets + unit;
        int pillW = Fonts.smallWidth(pill) + 7;
        int px = 123 - pillW;
        if (!pill.isEmpty()) {
            f.panel(px, 2, pillW, 9, t.panelHi);
            f.text(px + 3, 4, pill, t.accent.bright());
        }
        double pulse = 0.5 + 0.5 * Math.sin(c.time() * 4.0);
        byte live = pulse > 0.5 ? t.good.bright() : t.good.step(Math.max(0, t.good.size() - 3));
        f.rect(px - 6, 6, 3, 3, live);

        f.gradientH(5, 13, 118, 1, t.accent, 0.85, 0.0);

        // footer: page dots and page name (or a hint)
        f.hline(5, 116, 118, t.grid);
        int x = 6;
        for (int i = 0; i < COUNT; i++) {
            if (i == page) {
                f.gradientH(x, 121, 9, 2, t.accent, 0.5, 1.0);
                x += 12;
            } else {
                f.rect(x, 121, 2, 2, t.panelLine);
                x += 5;
            }
        }
        String label = c.hint() != null ? Fonts.sanitize(c.hint()) : c.s(NAMES[page]);
        f.textRight(122, 120, Fonts.fit(label, 122 - x - 3), c.hint() != null ? t.textDim : t.textMute);
    }

    // ------------------------------------------------------------ small widgets

    /** Up/down arrow plus percentage against the previous span. Returns the x after it. */
    private static int delta(Frame f, Ctx c, int x, int y, double now, double before) {
        Theme t = c.t();
        if (before <= 0) return now > 0 ? f.text(x, y, c.s("new"), t.info.bright()) : x;
        long r = Math.round((now - before) / before * 100.0);
        String body = Math.abs(r) >= 1000 ? ">999%" : Math.abs(r) + "%";
        if (r == 0) return f.text(x, y, "=" + body, t.textMute);
        byte col = r > 0 ? t.good.bright() : t.bad.bright();
        x = f.text(x, y, String.valueOf(r > 0 ? Fonts.UP : Fonts.DOWN), col);
        return f.text(x, y, body, col);
    }

    private static void tile(Frame f, Ctx c, int x, int y, String label, String value, Pal.Ramp valueRamp, Pal.Ramp accent) {
        Theme t = c.t();
        f.panel(x, y, 57, 30, t.panel);
        f.hline(x + 1, y, 55, t.panelHi);
        f.rect(x, y + 3, 1, 24, accent.bright());
        f.text(x + 5, y + 4, label, t.textMute);
        f.big(x + 5, y + 11, value, valueRamp, t.shadow);
    }

    private static void emptyNote(Frame f, Ctx c, int cy) {
        Theme t = c.t();
        f.textCenter(64, cy, c.s("no-sales"), t.textDim);
        f.textCenter(64, cy + 7, Fonts.fit(c.s("no-sales-hint"), 116), t.textFaint);
    }

    private static String range(Ctx c, Mode mode, int n) {
        return c.s(mode == Mode.DAY ? "range-day" : "range-week").replace("<N>", String.valueOf(n));
    }

    // ------------------------------------------------------------ page 0: overview

    private static void overview(Frame f, Ctx c, ModeData md, AnalyticsData.RatingData rating) {
        Theme t = c.t();
        double e = easeOut(c.p());

        tile(f, c, 5, 17, c.s("revenue"), c.moneyBig(md.revenueTotal() * e), t.accent, t.accent);
        delta(f, c, 10, 39, md.revenueTotal(), md.prevRevenue());

        tile(f, c, 66, 17, c.s("sales"), Fmt.compact(Math.round(md.salesTotal() * e)), t.white, t.info);
        delta(f, c, 71, 39, md.salesTotal(), md.prevSales());

        tile(f, c, 5, 50, c.s("customers"), Fmt.compact(Math.round(md.customersTotal() * e)), t.white, t.good);
        delta(f, c, 10, 72, md.customersTotal(), md.prevCustomers());

        String avg = rating.count() == 0 ? "-" : String.format(Locale.ROOT, "%.1f", rating.average() * e);
        tile(f, c, 66, 50, c.s("rating"), avg, t.accent, t.accent);
        for (int i = 0; i < 5; i++) {
            double fill = rating.count() == 0 ? 0 : Math.max(0, Math.min(1, rating.average() - i));
            f.star(71 + i * 6, 71, fill, t.accent.bright(), t.panelHi);
        }
        if (rating.count() > 0) f.text(71 + 31, 72, "(" + rating.count() + ")", t.textMute);

        // revenue sparkline
        f.panel(5, 83, 118, 31, t.panel);
        f.hline(6, 83, 116, t.panelHi);
        f.text(9, 86, c.s("revenue"), t.textMute);
        if (md.empty()) {
            f.textRight(119, 86, c.s("no-sales"), t.textFaint);
            f.dashedH(9, 108, 110, t.panelLine, 2, 2);
            return;
        }
        int best = md.bestBucket();
        if (best >= 0) f.textRight(119, 86, c.s("peak") + " " + c.moneySmall(md.revenue()[best]), t.textDim);
        sparkline(f, c, md.revenue(), 9, 94, 110, 16, e);
    }

    private static void sparkline(Frame f, Ctx c, double[] v, int x0, int top, int w, int h, double reveal) {
        Theme t = c.t();
        double max = 0;
        for (double d : v) max = Math.max(max, d);
        if (max <= 0) max = 1;
        int base = top + h - 1;
        int shown = (int) Math.round(w * reveal);
        int lastY = base;
        for (int i = 0; i < w && i < shown; i++) {
            double u = v.length == 1 ? 0 : (double) i / (w - 1) * (v.length - 1);
            int a = (int) Math.floor(u);
            int b = Math.min(v.length - 1, a + 1);
            double fr = u - a;
            fr = fr * fr * (3 - 2 * fr);
            double val = v[a] + (v[b] - v[a]) * fr;
            int y = base - (int) Math.round(val / max * (h - 1));
            lastY = y;
            for (int yy = y; yy <= base; yy++) {
                double tt = 0.12 + 0.55 * (1.0 - (yy - y) / (double) Math.max(1, base - y + 1));
                f.set(x0 + i, yy, t.accent.at(tt, x0 + i, yy));
            }
            f.set(x0 + i, y, t.accent.bright());
            f.set(x0 + i, y + 1, t.accent.step(t.accent.size() - 2));
        }
        if (reveal >= 0.999) {
            boolean on = Math.sin(c.time() * 5.0) > -0.2;
            byte col = on ? t.white.bright() : t.accent.bright();
            f.rect(x0 + w - 2, lastY - 1, 3, 3, col);
        }
    }

    // ------------------------------------------------------------ page 1: revenue

    private static void revenue(Frame f, Ctx c, Mode mode, ModeData md) {
        Theme t = c.t();
        double e = easeOut(c.p());
        int n = md.buckets();
        int x = f.big(6, 18, c.moneyBig(md.revenueTotal() * e), t.accent, t.shadow);
        delta(f, c, x + 4, 20, md.revenueTotal(), md.prevRevenue());
        String unit = c.s(mode == Mode.DAY ? "unit-day" : "unit-week");
        f.textRight(122, 18, c.s("avg") + " " + c.moneySmall(md.revenueTotal() / n) + "/" + unit, t.textMute);
        f.textRight(122, 25, c.s("sales") + " " + Fmt.compact(md.salesTotal()), t.textMute);

        int plotX = 6, plotW = 116, base = 98, plotH = 54;
        double max = 0;
        for (double d : md.revenue()) max = Math.max(max, d);
        double nice = Fmt.niceCeil(max);
        f.text(plotX, base - plotH - 7, c.moneySmall(nice), t.textFaint);
        for (int k = 1; k <= 4; k++) f.dashedH(plotX, base - plotH * k / 4, plotW, t.grid, 1, 2);
        f.hline(plotX, base, plotW, t.panelLine);

        if (md.empty()) {
            emptyNote(f, c, 66);
            f.textCenter(64, 109, range(c, mode, n), t.textFaint);
            return;
        }

        int pitch = Math.max(3, plotW / n);
        int bw = Math.max(2, pitch - (pitch >= 8 ? 2 : 1));
        int x0 = plotX + (plotW - pitch * n) / 2;
        String[] labels = md.labels();
        int labelStep = 1;
        for (int i = 0; i < n; i++) labelStep = Math.max(labelStep, (int) Math.ceil((Fonts.smallWidth(Fonts.sanitize(labels[i])) + 2.0) / pitch));

        int best = md.bestBucket();
        for (int i = 0; i < n; i++) {
            int bx = x0 + i * pitch + (pitch - bw) / 2;
            double g = stagger(c.p(), i, n, 0.55);
            int h = (int) Math.round(md.revenue()[i] / nice * plotH * g);
            boolean now = i == n - 1;
            if (md.revenue()[i] > 0 && h < 1) h = 1;
            if (h > 0) {
                f.gradient(bx, base - h, bw, h, t.accent, now ? 1.0 : 0.9, 0.3);
                f.hline(bx, base - h, bw, now ? t.white.bright() : t.accent.bright());
            } else {
                f.rect(bx, base - 1, bw, 1, t.panelLine);
            }
            if ((n - 1 - i) % labelStep == 0) {
                f.textCenter(bx + bw / 2, base + 3, Fonts.sanitize(labels[i]), now ? t.accent.bright() : t.textMute);
            }
        }
        if (c.p() > 0.6) {
            double avg = md.revenueTotal() / n;
            int ay = base - (int) Math.round(avg / nice * plotH);
            f.dashedH(plotX, ay, plotW, t.textFaint, 2, 3);
        }
        if (best >= 0 && c.p() > 0.85) {
            String s = c.moneySmall(md.revenue()[best]);
            int bx = x0 + best * pitch + pitch / 2;
            int ty = base - (int) Math.round(md.revenue()[best] / nice * plotH) - 7;
            int tx = Math.max(2, Math.min(126 - Fonts.smallWidth(s), bx - Fonts.smallWidth(s) / 2));
            f.text(tx, Math.max(33, ty), s, t.text);
        }
        f.textCenter(64, 109, range(c, mode, n), t.textFaint);
    }

    // ------------------------------------------------------------ page 2: best sellers

    private static void items(Frame f, Ctx c, ModeData md) {
        Theme t = c.t();
        List<ItemStat> items = md.items();
        f.text(6, 18, c.s("units-sold"), t.text);
        if (md.itemKinds() > 0) {
            f.textRight(122, 18, c.s("top") + " " + Math.min(5, items.size()) + "/" + md.itemKinds(), t.textMute);
        }
        if (items.isEmpty()) {
            emptyNote(f, c, 62);
            return;
        }
        long max = 1;
        for (ItemStat it : items) max = Math.max(max, it.units());
        for (int i = 0; i < Math.min(5, items.size()); i++) {
            ItemStat it = items.get(i);
            int y = 27 + i * 17;
            double g = stagger(c.p(), i, 5, 0.5);
            int off = (int) Math.round((1 - g) * 18);   // rows glide in from the right
            // rank badge
            f.panel(6 + off, y, 9, 9, i == 0 ? t.accent.step(2) : t.panelHi);
            f.textCenter(10 + off, y + 2, String.valueOf(i + 1), i == 0 ? t.shadow : t.textDim);
            if (g <= 0.02) continue;
            // line 1: name (left) and revenue (right)
            int nameMax = 122 - 19 - 30;
            f.text(19 + off, y, Fonts.fit(Fonts.sanitize(it.name()), nameMax), t.text);
            String rev = it.revenue() > 0 ? c.moneySmall(it.revenue() * g) : c.s("swap");
            f.textRight(122 + off, y, rev, it.revenue() > 0 ? t.accent.bright() : t.textMute);
            // line 2: bar (left) and units sold (right)
            int trackW = 84;
            f.rect(19 + off, y + 7, trackW, 3, t.panel);
            int w = Math.max(1, (int) Math.round(trackW * ((double) it.units() / max) * g));
            f.gradientH(19 + off, y + 7, w, 3, t.accent, 0.35, 1.0);
            f.textRight(122 + off, y + 6, Fmt.compact(Math.round(it.units() * g)), t.textDim);
        }
    }

    // ------------------------------------------------------------ page 3: customers

    private static void customers(Frame f, Ctx c, Mode mode, ModeData md) {
        Theme t = c.t();
        double e = easeOut(c.p());
        int x = f.big(6, 18, Fmt.compact(Math.round(md.customersTotal() * e)), t.white, t.shadow);
        delta(f, c, x + 4, 20, md.customersTotal(), md.prevCustomers());
        f.textRight(122, 18, c.s("unique"), t.textMute);
        f.textRight(122, 25, c.s("customers"), t.textMute);

        if (md.empty()) {
            emptyNote(f, c, 66);
            return;
        }
        // new vs returning
        int total = Math.max(1, md.customersTotal());
        int newW = (int) Math.round(116.0 * md.customersNew() / total * e);
        int backW = (int) Math.round(116.0 * md.customersReturning() / total * e);
        f.panel(6, 35, 116, 6, t.panel);
        if (newW > 0) f.gradient(6, 35, Math.max(1, newW), 6, t.accent, 0.3, 1.0);
        if (backW > 0) f.gradient(6 + 116 - backW, 35, backW, 6, t.info, 0.3, 1.0);
        f.text(6, 44, c.s("new") + " " + md.customersNew(), t.accent.bright());
        f.textRight(122, 44, c.s("returning") + " " + md.customersReturning(), t.info.bright());

        // buyers per bucket
        int n = md.buckets();
        f.text(6, 52, c.s(mode == Mode.DAY ? "buyers-per-day" : "buyers-per-week"), t.textFaint);
        int plotX = 6, plotW = 116, base = 79, plotH = 18;
        int max = 1;
        for (int v : md.customers()) max = Math.max(max, v);
        f.hline(plotX, base, plotW, t.panelLine);
        int pitch = Math.max(3, plotW / n);
        int bw = Math.max(2, pitch - (pitch >= 8 ? 2 : 1));
        int x0 = plotX + (plotW - pitch * n) / 2;
        for (int i = 0; i < n; i++) {
            int bx = x0 + i * pitch + (pitch - bw) / 2;
            double g = stagger(c.p(), i, n, 0.55);
            int h = (int) Math.round((double) md.customers()[i] / max * plotH * g);
            if (md.customers()[i] > 0 && h < 1) h = 1;
            if (h > 0) {
                f.gradient(bx, base - h, bw, h, t.info, 1.0, 0.3);
                f.hline(bx, base - h, bw, i == n - 1 ? t.white.bright() : t.info.bright());
            } else f.rect(bx, base - 1, bw, 1, t.panelLine);
        }

        // top buyers
        f.text(6, 84, c.s("top-buyers"), t.textMute);
        List<BuyerStat> buyers = md.buyers();
        for (int i = 0; i < 3; i++) {
            int y = 91 + i * 8;
            if (i % 2 == 0) f.rect(5, y - 1, 118, 7, t.panel);
            if (i >= buyers.size()) continue;
            BuyerStat b = buyers.get(i);
            f.text(8, y, String.valueOf(i + 1), t.accent.bright());
            f.text(16, y, Fonts.fit(Fonts.sanitize(b.name()), 66), t.text);
            f.textRight(120, y, b.spent() > 0 ? c.moneySmall(b.spent()) : b.trades() + "X", t.textDim);
        }
    }

    // ------------------------------------------------------------ page 4: ratings

    private static void ratings(Frame f, Ctx c, Mode mode, ModeData md, AnalyticsData.RatingData r) {
        Theme t = c.t();
        double e = easeOut(c.p());
        if (r.count() == 0) {
            f.big(6, 18, "-", t.white, t.shadow);
            for (int i = 0; i < 5; i++) f.star(6 + i * 6, 28, 0, t.accent.bright(), t.panelHi);
            f.textCenter(64, 72, c.s("no-ratings"), t.textDim);
            f.textCenter(64, 79, Fonts.fit(c.s("no-ratings-hint"), 116), t.textFaint);
            return;
        }
        f.big(6, 18, String.format(Locale.ROOT, "%.1f", r.average() * e), t.accent, t.shadow);
        for (int i = 0; i < 5; i++) {
            double fill = Math.max(0, Math.min(1, r.average() * e - i));
            f.star(6 + i * 6, 28, fill, t.accent.bright(), t.panelHi);
        }
        f.text(6, 36, r.count() + " " + c.s("ratings"), t.textMute);

        int maxSpread = 1;
        for (int v : r.spread()) maxSpread = Math.max(maxSpread, v);
        for (int k = 0; k < 5; k++) {
            int stars = 5 - k;
            int y = 18 + k * 7;
            f.text(66, y, String.valueOf(stars), t.textDim);
            f.rect(73, y + 1, 36, 3, t.panel);
            double g = stagger(c.p(), k, 5, 0.5);
            int w = (int) Math.round(36.0 * r.spread()[stars - 1] / maxSpread * g);
            if (w > 0) f.gradientH(73, y + 1, w, 3, t.accent, 0.3, 1.0);
            f.textRight(122, y, String.valueOf(r.spread()[stars - 1]), t.textMute);
        }

        // history: rolling average per bucket, new ratings as small bars underneath
        int n = md.buckets();
        double[] avg = md.ratingAvg();
        int[] fresh = md.ratingNew();
        f.text(6, 56, c.s("trend"), t.textMute);
        f.textRight(122, 56, range(c, mode, n), t.textFaint);

        double lo = 5, hi = 1;
        boolean any = false;
        for (double v : avg) if (!Double.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); any = true; }
        if (!any) { lo = r.average(); hi = r.average(); }
        double ymin = Math.max(1, Math.floor((lo - 0.3) * 2) / 2.0);
        double ymax = Math.min(5, Math.ceil((hi + 0.3) * 2) / 2.0);
        if (ymax - ymin < 1) { ymax = Math.min(5, ymin + 1); ymin = Math.max(1, ymax - 1); }

        int plotX = 12, plotW = 110, top = 66, base = 106, lineBase = 97;
        f.text(6, top - 1, String.valueOf((int) Math.round(ymax)), t.textFaint);
        f.text(6, lineBase - 5, String.valueOf((int) Math.round(ymin)), t.textFaint);
        f.dashedH(plotX, top + 2, plotW, t.grid, 1, 2);
        f.dashedH(plotX, (top + 2 + lineBase) / 2, plotW, t.grid, 1, 2);
        f.hline(plotX, base, plotW, t.panelLine);

        int pitch = Math.max(3, plotW / n);
        int x0 = plotX + (plotW - pitch * n) / 2;
        int maxNew = 1;
        for (int v : fresh) maxNew = Math.max(maxNew, v);
        int[] ys = new int[n];
        int[] xs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x0 + i * pitch + pitch / 2;
            ys[i] = Double.isNaN(avg[i]) ? Integer.MIN_VALUE
                    : lineBase - (int) Math.round((avg[i] - ymin) / (ymax - ymin) * (lineBase - top - 2));
            int h = (int) Math.round(6.0 * fresh[i] / maxNew * stagger(c.p(), i, n, 0.5));
            if (fresh[i] > 0 && h < 1) h = 1;
            if (h > 0) f.rect(xs[i] - 1, base - h, 3, h, t.info.step(t.info.size() - 2));
        }
        int upto = (int) Math.round((n - 1) * e);
        int prev = -1;
        for (int i = 0; i <= upto; i++) {
            if (ys[i] == Integer.MIN_VALUE) continue;
            if (prev >= 0) {
                // area under the segment, then the line itself
                for (int x = xs[prev]; x <= xs[i]; x++) {
                    double u = xs[i] == xs[prev] ? 0 : (double) (x - xs[prev]) / (xs[i] - xs[prev]);
                    int y = (int) Math.round(ys[prev] + (ys[i] - ys[prev]) * u);
                    for (int yy = y; yy <= lineBase; yy++) {
                        double tt = 0.08 + 0.4 * (1.0 - (yy - y) / (double) Math.max(1, lineBase - y + 1));
                        f.set(x, yy, t.accent.at(tt, x, yy));
                    }
                }
                f.line(xs[prev], ys[prev], xs[i], ys[i], t.accent.bright());
            }
            prev = i;
        }
        if (prev >= 0) {
            f.disc(xs[prev], ys[prev], 1, t.white.bright());
        }
    }
}
