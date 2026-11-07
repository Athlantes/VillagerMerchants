package org.riftlabs.villagerMerchants.analytics;

import java.util.Locale;

/** Number formatting for a 128 pixel wide screen: 1.2K, 34K, 5.6M. No locale, no grouping separators. */
public final class Fmt {
    private Fmt() {}

    public static String compact(double v) {
        double a = Math.abs(v);
        String sign = v < 0 ? "-" : "";
        if (a < 1000) {
            if (a >= 100 || a == Math.rint(a)) return sign + Math.round(a);
            return sign + trim(String.format(Locale.ROOT, "%.1f", a));
        }
        String[] suffix = {"K", "M", "B", "T"};
        double x = a;
        int i = -1;
        while (x >= 1000 && i < suffix.length - 1) { x /= 1000; i++; }
        String body = x >= 100 ? String.valueOf(Math.round(x)) : trim(String.format(Locale.ROOT, x >= 10 ? "%.0f" : "%.1f", x));
        if (body.equals("1000") && i < suffix.length - 1) { body = "1"; i++; }
        return sign + body + suffix[i];
    }

    private static String trim(String s) { return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s; }

    /** "+12%", "-5%"; null when there is nothing to compare against. */
    public static String percent(double now, double before) {
        if (before <= 0) return null;
        double p = (now - before) / before * 100.0;
        if (Math.abs(p) >= 1000) return p > 0 ? ">999%" : "<-999%";
        return (p >= 0 ? "+" : "") + Math.round(p) + "%";
    }

    /** Smallest 1/2/5 x 10^k that is at least {@code v}: gives chart axes round numbers. */
    public static double niceCeil(double v) {
        if (v <= 0) return 1;
        double p = Math.pow(10, Math.floor(Math.log10(v)));
        double m = v / p;
        double n = m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10;
        return n * p;
    }
}
