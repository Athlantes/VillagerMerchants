package org.riftlabs.villagerMerchants.analytics.render;

import java.util.Arrays;

/**
 * One 128x128 map image as raw palette bytes, plus the drawing primitives the dashboard needs.
 * Pure Java: the Bukkit side only ever copies {@link #px} onto a MapCanvas.
 */
public final class Frame {
    public static final int W = 128, H = 128;

    public final byte[] px = new byte[W * H];

    public Frame() {}

    public Frame copy() {
        Frame f = new Frame();
        System.arraycopy(px, 0, f.px, 0, px.length);
        return f;
    }

    // ------------------------------------------------------------ pixels and shapes

    public void set(int x, int y, byte c) {
        if (x >= 0 && y >= 0 && x < W && y < H) px[y * W + x] = c;
    }

    public byte get(int x, int y) { return (x >= 0 && y >= 0 && x < W && y < H) ? px[y * W + x] : 0; }

    public void fill(byte c) { Arrays.fill(px, c); }

    public void rect(int x, int y, int w, int h, byte c) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y), x1 = Math.min(W, x + w), y1 = Math.min(H, y + h);
        for (int yy = y0; yy < y1; yy++) Arrays.fill(px, yy * W + x0, yy * W + Math.max(x0, x1), c);
    }

    public void hline(int x, int y, int len, byte c) { rect(x, y, len, 1, c); }
    public void vline(int x, int y, int len, byte c) { rect(x, y, 1, len, c); }

    public void dashedH(int x, int y, int len, byte c, int on, int off) {
        for (int i = 0, k = 0; i < len; i++, k = (k + 1) % (on + off)) if (k < on) set(x + i, y, c);
    }

    public void outline(int x, int y, int w, int h, byte c) {
        hline(x, y, w, c);
        hline(x, y + h - 1, w, c);
        vline(x, y, h, c);
        vline(x + w - 1, y, h, c);
    }

    /** Filled rectangle with the four corner pixels cut: the cheapest believable rounded corner. */
    public void panel(int x, int y, int w, int h, byte c) {
        rect(x + 1, y, w - 2, h, c);
        rect(x, y + 1, 1, h - 2, c);
        rect(x + w - 1, y + 1, 1, h - 2, c);
    }

    /** Vertical gradient fill from ramp position t0 (top) to t1 (bottom), dithered. */
    public void gradient(int x, int y, int w, int h, Pal.Ramp r, double t0, double t1) {
        for (int yy = 0; yy < h; yy++) {
            double t = h <= 1 ? t0 : t0 + (t1 - t0) * yy / (h - 1.0);
            for (int xx = 0; xx < w; xx++) set(x + xx, y + yy, r.at(t, x + xx, y + yy));
        }
    }

    /** Horizontal gradient from t0 (left) to t1 (right). */
    public void gradientH(int x, int y, int w, int h, Pal.Ramp r, double t0, double t1) {
        for (int xx = 0; xx < w; xx++) {
            double t = w <= 1 ? t0 : t0 + (t1 - t0) * xx / (w - 1.0);
            for (int yy = 0; yy < h; yy++) set(x + xx, y + yy, r.at(t, x + xx, y + yy));
        }
    }

    public void line(int x0, int y0, int x1, int y1, byte c) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx + dy;
        while (true) {
            set(x0, y0, c);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** Filled disc (r in pixels). */
    public void disc(int cx, int cy, int r, byte c) {
        for (int y = -r; y <= r; y++)
            for (int x = -r; x <= r; x++) if (x * x + y * y <= r * r + r / 2) set(cx + x, cy + y, c);
    }

    // ------------------------------------------------------------ text

    private int glyph(Fonts.Glyph g, int x, int y, java.util.function.IntFunction<Byte> colorOfRow) {
        for (int r = 0; r < g.rows().length; r++) {
            byte c = colorOfRow.apply(r);
            for (int cx = 0; cx < g.w(); cx++) if ((g.rows()[r] & (1 << cx)) != 0) set(x + cx, y + r, c);
        }
        return g.w();
    }

    /** 3x5 text (already sanitised), left edge at x. Returns the x just after the last glyph. */
    public int text(int x, int y, String s, byte c) {
        for (int i = 0; i < s.length(); i++) {
            Fonts.Glyph g = Fonts.SMALL.getOrDefault(s.charAt(i), Fonts.SMALL.get('?'));
            x += glyph(g, x, y, r -> c) + 1;
        }
        return x;
    }

    public void textRight(int rightX, int y, String s, byte c) { text(rightX - Fonts.smallWidth(s), y, s, c); }

    public void textCenter(int cx, int y, String s, byte c) { text(cx - Fonts.smallWidth(s) / 2, y, s, c); }

    /** 5x7 number text with a per-row colour ramp (top row brightest) and an optional drop shadow. */
    public int big(int x, int y, String s, Pal.Ramp ramp, byte shadow) {
        int start = x;
        if (shadow != 0) bigPass(start + 1, y + 1, s, r -> shadow);
        return bigPass(start, y, s, r -> ramp.at(1.0 - r / 8.0, 0, 0));
    }

    private int bigPass(int x, int y, String s, java.util.function.IntFunction<Byte> rowColor) {
        for (int i = 0; i < s.length(); i++) {
            Fonts.Glyph g = Fonts.BIG.get(s.charAt(i));
            if (g == null) { x += 4; continue; }
            x += glyph(g, x, y, rowColor) + 1;
        }
        return x;
    }

    // ------------------------------------------------------------ shapes that appear more than once

    /** 5x5 star; {@code fill} 0..1 lights it from the left (half stars for 4.5 averages). */
    public void star(int x, int y, double fill, byte on, byte off) {
        String[] shape = {"..#..", "#####", ".###.", ".###.", "#...#"};
        int lit = (int) Math.round(Math.max(0, Math.min(1, fill)) * 5);
        for (int r = 0; r < 5; r++)
            for (int c = 0; c < 5; c++) if (shape[r].charAt(c) == '#') set(x + c, y + r, c < lit ? on : off);
    }

    // ------------------------------------------------------------ compositing

    /** Slide transition: {@code from} moves out by {@code offset} px while {@code to} moves in from the right (or left when negative). */
    public static Frame slide(Frame from, Frame to, int offset) {
        Frame out = new Frame();
        int o = Math.max(-W, Math.min(W, offset));
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int sx = x + o;
                byte c;
                if (sx >= 0 && sx < W) c = from.px[y * W + sx];
                else {
                    int tx = o > 0 ? sx - W : sx + W;
                    c = (tx >= 0 && tx < W) ? to.px[y * W + tx] : 0;
                }
                out.px[y * W + x] = c;
            }
        }
        return out;
    }
}
