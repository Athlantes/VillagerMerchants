package org.riftlabs.villagerMerchants.analytics.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The map colour table, and gradients built on it.
 *
 * <p>A map pixel is one byte: {@code base * 4 + shade}. Only base colours 0-31 are used here on purpose: their
 * byte values (4..127) are accepted by every Bukkit/Paper map implementation from 1.21.1 up, whereas the newer
 * bases (32+) were filtered out by older {@code CraftMapView} builds. 32 hues x 4 shades is plenty for a dark UI.
 *
 * <p>This class has no Bukkit dependency, so the whole renderer can be unit-tested or previewed standalone.
 */
public final class Pal {
    private Pal() {}

    /** Vanilla MapColor RGB by base id (0 = transparent, never drawn). */
    private static final int[] BASE = {
            0x000000, 0x7FB238, 0xF7E9A3, 0xC7C7C7, 0xFF0000, 0xA0A0FF, 0xA7A7A7, 0x007C00,
            0xFFFFFF, 0xA4A8B8, 0x976D4D, 0x707070, 0x4040FF, 0x8F7748, 0xFFFCF5, 0xD87F33,
            0xB24CD8, 0x6699D8, 0xE5E533, 0x7FCC19, 0xF27FA5, 0x4C4C4C, 0x999999, 0x4C7F99,
            0x7F3FB2, 0x334CB2, 0x664C33, 0x667F33, 0x993333, 0x191919, 0xFAEE4D, 0x5CDBD5};
    /** Shade multipliers out of 255, in shade order 0..3 (the vanilla ones). */
    private static final int[] MUL = {180, 220, 255, 135};

    public static final int BLACK = 29, GRAY = 21, LGRAY = 22, SNOW = 8, GOLD = 30, ORANGE = 15, YELLOW = 18,
            BROWN = 26, CYAN = 23, DIAMOND = 31, LBLUE = 17, BLUE = 25, PLANT = 7, LGREEN = 19, GREEN = 27,
            PURPLE = 24, MAGENTA = 16, PINK = 20, RED = 28, FIRE = 4;

    public static byte idx(int base, int shade) { return (byte) (base * 4 + shade); }

    /** 0xRRGGBB of a map colour byte (used by previews). */
    public static int rgb(byte b) {
        int u = b & 0xFF;
        int c = BASE[Math.min(u >> 2, BASE.length - 1)];
        int m = MUL[u & 3];
        return ((((c >> 16) & 0xFF) * m / 255) << 16) | ((((c >> 8) & 0xFF) * m / 255) << 8) | ((c & 0xFF) * m / 255);
    }

    private static double lum(int rgb) {
        return 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
    }

    /** 4x4 Bayer matrix, normalised to (0,1): ordered dithering makes 4 shades look like a smooth gradient. */
    private static final double[] BAYER = new double[16];
    static {
        int[] m = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
        for (int i = 0; i < 16; i++) BAYER[i] = (m[i] + 0.5) / 16.0;
    }

    /** A dark-to-bright colour run. {@link #at} dithers between neighbouring entries. */
    public static final class Ramp {
        private final byte[] idx;
        private final int[] rgb;

        private Ramp(byte[] idx) {
            this.idx = idx;
            this.rgb = new int[idx.length];
            for (int i = 0; i < idx.length; i++) rgb[i] = Pal.rgb(idx[i]);
        }

        /** All four shades of each base colour, ordered by brightness (near-duplicates dropped). */
        public static Ramp of(int... bases) {
            List<byte[]> all = new ArrayList<>();
            for (int b : bases) for (int s = 0; s < 4; s++) all.add(new byte[]{idx(b, s)});
            all.sort(Comparator.comparingDouble(a -> lum(Pal.rgb(a[0]))));
            List<Byte> out = new ArrayList<>();
            double last = -100;
            for (byte[] a : all) {
                double l = lum(Pal.rgb(a[0]));
                if (l - last < 6) continue;
                out.add(a[0]);
                last = l;
            }
            byte[] r = new byte[out.size()];
            for (int i = 0; i < r.length; i++) r[i] = out.get(i);
            return new Ramp(r);
        }

        /** An explicit run, dark to bright, in the order given. */
        public static Ramp run(byte... colors) { return new Ramp(Arrays.copyOf(colors, colors.length)); }

        public int size() { return idx.length; }
        public byte dark() { return idx[0]; }
        public byte bright() { return idx[idx.length - 1]; }
        public byte mid() { return idx[idx.length / 2]; }
        public byte step(int i) { return idx[Math.max(0, Math.min(idx.length - 1, i))]; }

        /** Colour at t (0 = darkest, 1 = brightest), dithered per pixel. */
        public byte at(double t, int x, int y) {
            if (idx.length == 1) return idx[0];
            double p = Math.max(0, Math.min(1, t)) * (idx.length - 1);
            int i = (int) Math.floor(p);
            if (i >= idx.length - 1) return idx[idx.length - 1];
            double f = p - i;
            return f > BAYER[(y & 3) * 4 + (x & 3)] ? idx[i + 1] : idx[i];
        }
    }
}
