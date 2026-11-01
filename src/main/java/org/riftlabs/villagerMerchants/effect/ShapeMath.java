package org.riftlabs.villagerMerchants.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure geometry: turns a layer + the current tick into points relative to the NPC's feet.
 * Each point is {x, y, z, u} where u in [0,1] is its position along the shape (used for colour spread).
 */
public final class ShapeMath {
    private static final double TAU = Math.PI * 2;
    private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));

    private ShapeMath() {}

    private static double frac(double v) { return v - Math.floor(v); }

    private static void add(List<double[]> out, double x, double y, double z, double u) {
        out.add(new double[]{x, y, z, u});
    }

    public static List<double[]> generate(EffectLayer l, double t, double yawDeg, ThreadLocalRandom rnd) {
        List<double[]> out = new ArrayList<>(Math.max(8, l.points));
        double spin = Math.toRadians(l.spin) * t;
        double r = l.radius * (1.0 + l.pulseAmp * Math.sin(Math.toRadians(l.pulseSpeed) * t));
        int n = l.points;

        switch (l.shape) {
            case RING -> {
                for (int i = 0; i < n; i++) {
                    double a = spin + TAU * i / n;
                    add(out, r * Math.cos(a), 0, r * Math.sin(a), (double) i / n);
                }
            }
            case CROWN -> {
                for (int i = 0; i < n; i++) {
                    double f = (double) i / n;
                    double a = spin + TAU * f;
                    double w = frac(f * l.strands);
                    double tri = 1 - Math.abs(2 * w - 1);
                    add(out, r * Math.cos(a), l.height * tri, r * Math.sin(a), f);
                }
            }
            case ORBIT -> {
                for (int o = 0; o < l.strands; o++) {
                    double base = spin + TAU * o / l.strands;
                    for (int k = 0; k <= l.trail; k++) {
                        double a = base - Math.toRadians(l.trailSpacing) * k;
                        add(out, r * Math.cos(a), 0, r * Math.sin(a), (double) o / l.strands);
                    }
                }
            }
            case HELIX -> {
                int per = Math.max(2, Math.min(n, 400 / l.strands));
                for (int st = 0; st < l.strands; st++) {
                    for (int i = 0; i < per; i++) {
                        double p = (double) i / (per - 1);
                        double rr = r * (1 - l.taper * p);
                        double a = spin + TAU * st / l.strands + p * l.turns * TAU;
                        add(out, rr * Math.cos(a), l.height * p, rr * Math.sin(a), p);
                    }
                }
            }
            case SPIRAL -> {
                double head = frac(t / l.period);
                for (int st = 0; st < l.strands; st++) {
                    for (int k = 0; k <= l.trail; k++) {
                        double p = head - k * l.trailSpacing;
                        if (p < 0) continue;
                        double rr = r * (1 - l.taper * p);
                        double a = spin + TAU * st / l.strands + p * l.turns * TAU;
                        add(out, rr * Math.cos(a), l.height * p, rr * Math.sin(a), p);
                    }
                }
            }
            case SPHERE -> {
                for (int i = 0; i < n; i++) {
                    double yy = 1 - 2 * (i + 0.5) / n;
                    double rr = Math.sqrt(Math.max(0, 1 - yy * yy));
                    double a = i * GOLDEN + spin;
                    add(out, r * rr * Math.cos(a), r * yy, r * rr * Math.sin(a), (double) i / n);
                }
            }
            case CLOUD -> {
                for (int i = 0; i < n; i++) {
                    double a = rnd.nextDouble() * TAU;
                    double d = r * Math.sqrt(rnd.nextDouble());
                    add(out, d * Math.cos(a), rnd.nextDouble() * l.height, d * Math.sin(a), rnd.nextDouble());
                }
            }
            case RAIN, RISING -> {
                boolean down = l.shape == EffectShape.RAIN;
                for (int i = 0; i < n; i++) {
                    double a = i * GOLDEN + spin;
                    double d = r * Math.sqrt((i + 0.5) / n);
                    double p = frac(t / l.period + frac(i * 0.6180339887));
                    double yy = down ? l.height * (1 - p) : l.height * p;
                    add(out, d * Math.cos(a), yy, d * Math.sin(a), (double) i / n);
                }
            }
            case PULSE -> {
                for (int w = 0; w < l.strands; w++) {
                    double p = frac(t / l.period + (double) w / l.strands);
                    double rr = r * p;
                    double yy = l.height * p;
                    for (int i = 0; i < n; i++) {
                        double a = TAU * i / n + spin;
                        add(out, rr * Math.cos(a), yy, rr * Math.sin(a), p);
                    }
                }
            }
            case HEART -> {
                double s = r / 16.0, cs = Math.cos(spin), sn = Math.sin(spin);
                for (int i = 0; i < n; i++) {
                    double th = TAU * i / n;
                    double hx = 16 * Math.pow(Math.sin(th), 3);
                    double hy = 13 * Math.cos(th) - 5 * Math.cos(2 * th) - 2 * Math.cos(3 * th) - Math.cos(4 * th);
                    add(out, hx * s * cs, (hy + 2.5) * s, hx * s * sn, (double) i / n);
                }
            }
            case WINGS -> {
                double flap = Math.toRadians(l.flapAmp) * (0.5 + 0.5 * Math.sin(Math.toRadians(l.flapSpeed) * t));
                double s = r / 4.0, cf = Math.cos(flap), sf = Math.sin(flap);
                for (int i = 0; i < n; i++) {
                    double th = 12 * Math.PI * i / n;
                    double rr = Math.exp(Math.sin(th)) - 2 * Math.cos(4 * th) + Math.pow(Math.sin((2 * th - Math.PI) / 24), 5);
                    double bx = Math.sin(th) * rr, by = Math.cos(th) * rr;
                    add(out, bx * s * cf, (by - 0.8) * s, -0.2 - Math.abs(bx) * s * sf * 0.9, (double) i / n);
                }
            }
            case STAR -> {
                int per = Math.max(1, n / 10);
                double[][] v = new double[10][2];
                for (int k = 0; k < 10; k++) {
                    double ang = spin - Math.PI / 2 + k * Math.PI / 5;
                    double rad = (k % 2 == 0) ? r : r * 0.45;
                    v[k][0] = rad * Math.cos(ang);
                    v[k][1] = rad * Math.sin(ang);
                }
                for (int e = 0; e < 10; e++) {
                    double[] a = v[e], b = v[(e + 1) % 10];
                    for (int m = 0; m < per; m++) {
                        double f = (double) m / per;
                        add(out, a[0] + (b[0] - a[0]) * f, 0, a[1] + (b[1] - a[1]) * f, (e * per + m) / (10.0 * per));
                    }
                }
            }
            case FOUNTAIN -> {
                int per = Math.max(1, Math.min(n, 400 / l.strands));
                for (int st = 0; st < l.strands; st++) {
                    double a0 = spin + TAU * st / l.strands;
                    for (int i = 0; i < per; i++) {
                        double p = frac(t / l.period + (double) i / per);
                        double d = r * p;
                        add(out, d * Math.cos(a0), l.height * 4 * p * (1 - p), d * Math.sin(a0), p);
                    }
                }
            }
        }

        // post-processing: tilt (X then Z), face the NPC's yaw, bob, base height, jitter
        double tx = Math.toRadians(l.tiltX + l.tumble * t), tz = Math.toRadians(l.tiltZ);
        double yaw = l.face ? Math.toRadians(yawDeg) : 0;
        double cx = Math.cos(tx), sx = Math.sin(tx), cz = Math.cos(tz), sz = Math.sin(tz);
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double lift = l.y + l.bobAmp * Math.sin(Math.toRadians(l.bobSpeed) * t);
        boolean jit = l.jitter > 0;

        for (double[] p : out) {
            double x = p[0], y = p[1], z = p[2];
            double y1 = y * cx - z * sx, z1 = y * sx + z * cx;
            y = y1; z = z1;
            double x1 = x * cz - y * sz;
            y1 = x * sz + y * cz;
            x = x1; y = y1;
            p[0] = x * cy - z * sy;
            p[2] = x * sy + z * cy;
            p[1] = y + lift;
            if (jit) {
                p[0] += (rnd.nextDouble() - 0.5) * 2 * l.jitter;
                p[1] += (rnd.nextDouble() - 0.5) * 2 * l.jitter;
                p[2] += (rnd.nextDouble() - 0.5) * 2 * l.jitter;
            }
        }
        return out;
    }
}
