package org.riftlabs.villagerMerchants.analytics.render;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Two pixel fonts: a 3x5 caps font for labels and a 5x7 font for the big numbers.
 * Both are drawn by {@link Frame}; only characters in these tables can appear on a map, everything else becomes
 * '?' (accents are stripped first, so "Café" still reads "CAFE").
 */
public final class Fonts {
    private Fonts() {}

    /** One glyph: a row of bit masks (bit 0 = leftmost pixel) and its width in pixels. */
    record Glyph(int[] rows, int w) {}

    static final int SMALL_H = 5, BIG_H = 7;
    static final Map<Character, Glyph> SMALL = new HashMap<>();
    static final Map<Character, Glyph> BIG = new HashMap<>();

    /** Private-use characters that stand for small icons inside the 3x5 font. */
    public static final char UP = '\u0001', DOWN = '\u0002', STAR_FULL = '\u0003';

    private static void put(Map<Character, Glyph> map, char c, String... rows) {
        int[] bits = new int[rows.length];
        int w = rows[0].length();
        for (int r = 0; r < rows.length; r++) {
            for (int x = 0; x < w; x++) if (rows[r].charAt(x) == '#') bits[r] |= 1 << x;
        }
        map.put(c, new Glyph(bits, w));
    }

    private static void s(char c, String... rows) { put(SMALL, c, rows); }
    private static void b(char c, String... rows) { put(BIG, c, rows); }

    static {
        s('0', "###", "#.#", "#.#", "#.#", "###");
        s('1', ".#.", "##.", ".#.", ".#.", "###");
        s('2', "###", "..#", "###", "#..", "###");
        s('3', "###", "..#", "###", "..#", "###");
        s('4', "#.#", "#.#", "###", "..#", "..#");
        s('5', "###", "#..", "###", "..#", "###");
        s('6', "###", "#..", "###", "#.#", "###");
        s('7', "###", "..#", "..#", ".#.", ".#.");
        s('8', "###", "#.#", "###", "#.#", "###");
        s('9', "###", "#.#", "###", "..#", "###");
        s('A', ".#.", "#.#", "###", "#.#", "#.#");
        s('B', "##.", "#.#", "##.", "#.#", "##.");
        s('C', ".##", "#..", "#..", "#..", ".##");
        s('D', "##.", "#.#", "#.#", "#.#", "##.");
        s('E', "###", "#..", "##.", "#..", "###");
        s('F', "###", "#..", "##.", "#..", "#..");
        s('G', ".##", "#..", "#.#", "#.#", ".##");
        s('H', "#.#", "#.#", "###", "#.#", "#.#");
        s('I', "###", ".#.", ".#.", ".#.", "###");
        s('J', "..#", "..#", "..#", "#.#", ".#.");
        s('K', "#.#", "#.#", "##.", "#.#", "#.#");
        s('L', "#..", "#..", "#..", "#..", "###");
        s('M', "#.#", "###", "###", "#.#", "#.#");
        s('N', "##.", "#.#", "#.#", "#.#", "#.#");
        s('O', ".#.", "#.#", "#.#", "#.#", ".#.");
        s('P', "##.", "#.#", "##.", "#..", "#..");
        s('Q', ".#.", "#.#", "#.#", "##.", ".##");
        s('R', "##.", "#.#", "##.", "#.#", "#.#");
        s('S', ".##", "#..", ".#.", "..#", "##.");
        s('T', "###", ".#.", ".#.", ".#.", ".#.");
        s('U', "#.#", "#.#", "#.#", "#.#", "###");
        s('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        s('W', "#.#", "#.#", "###", "###", "#.#");
        s('X', "#.#", "#.#", ".#.", "#.#", "#.#");
        s('Y', "#.#", "#.#", ".#.", ".#.", ".#.");
        s('Z', "###", "..#", ".#.", "#..", "###");
        s(' ', "..", "..", "..", "..", "..");
        s('.', ".", ".", ".", ".", "#");
        s(',', "..", "..", "..", ".#", "#.");
        s(':', ".", "#", ".", "#", ".");
        s('!', "#", "#", "#", ".", "#");
        s('\'', "#", "#", ".", ".", ".");
        s('-', "...", "...", "###", "...", "...");
        s('+', "...", ".#.", "###", ".#.", "...");
        s('/', "..#", "..#", ".#.", "#..", "#..");
        s('%', "#.#", "..#", ".#.", "#..", "#.#");
        s('(', ".#", "#.", "#.", "#.", ".#");
        s(')', "#.", ".#", ".#", ".#", "#.");
        s('$', ".##", "##.", ".#.", ".##", "##.");
        s('#', "#.#", "###", "#.#", "###", "#.#");
        s('*', "#.#", ".#.", "###", ".#.", "#.#");
        s('?', "##.", "..#", ".#.", "...", ".#.");
        s('<', "..#", ".#.", "#..", ".#.", "..#");
        s('>', "#..", ".#.", "..#", ".#.", "#..");
        s('=', "...", "###", "...", "###", "...");
        s('_', "...", "...", "...", "...", "###");
        s('~', "...", ".##", "##.", "...", "...");
        s('|', "#", "#", "#", "#", "#");
        s(UP, "...", ".#.", "###", "...", "...");
        s(DOWN, "...", "###", ".#.", "...", "...");

        b('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.");
        b('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
        b('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
        b('3', ".###.", "#...#", "....#", "..##.", "....#", "#...#", ".###.");
        b('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
        b('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
        b('6', ".###.", "#....", "#....", "####.", "#...#", "#...#", ".###.");
        b('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
        b('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
        b('9', ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".###.");
        b('.', "..", "..", "..", "..", "..", "##", "##");
        b(',', "..", "..", "..", "..", "##", ".#", "#.");
        b('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
        b('M', "#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#");
        b('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
        b('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
        b('$', "..#..", ".####", "#.#..", ".###.", "..#.#", "####.", "..#..");
        b('-', "....", "....", "....", "####", "....", "....", "....");
        b('+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....");
        b(' ', "...", "...", "...", "...", "...", "...", "...");
    }

    /** True when the 5x7 number font can draw this character. */
    public static boolean canBig(char c) { return BIG.containsKey(c); }

    /** Upper-cases, strips accents and swaps anything the font lacks for '?'. */
    private static final Map<String, String> MEMO = new java.util.concurrent.ConcurrentHashMap<>();

    public static String sanitize(String in) {
        if (in == null) return "";
        String hit = MEMO.get(in);
        if (hit != null) return hit;
        String out = sanitizeUncached(in);
        if (MEMO.size() > 2048) MEMO.clear();   // item and player names are unbounded: keep the memo small
        MEMO.put(in, out);
        return out;
    }

    private static String sanitizeUncached(String in) {
        String n = Normalizer.normalize(in, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (c == '_') c = ' ';
            sb.append(SMALL.containsKey(c) ? c : '?');
        }
        return sb.toString();
    }

    /** Pixel width of a string in the 3x5 font (1px between glyphs). */
    public static int smallWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            Glyph g = SMALL.getOrDefault(s.charAt(i), SMALL.get('?'));
            w += g.w() + 1;
        }
        return Math.max(0, w - 1);
    }

    /** Pixel width of a string in the 5x7 font (1px between glyphs). */
    public static int bigWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            Glyph g = BIG.get(s.charAt(i));
            w += (g == null ? 3 : g.w()) + 1;
        }
        return Math.max(0, w - 1);
    }

    /** Cuts a string to fit {@code maxPx}, ending in "." if it had to be shortened. */
    public static String fit(String s, int maxPx) {
        if (smallWidth(s) <= maxPx) return s;
        String t = s;
        while (t.length() > 1 && smallWidth(t + ".") > maxPx) t = t.substring(0, t.length() - 1);
        return t.stripTrailing() + ".";
    }
}
