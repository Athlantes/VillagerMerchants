package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.effect.EffectDefinition;
import org.riftlabs.villagerMerchants.effect.EffectLayer;
import org.riftlabs.villagerMerchants.effect.EffectLayer.ColorMode;
import org.riftlabs.villagerMerchants.effect.EffectRegistry;
import org.riftlabs.villagerMerchants.effect.ShapeMath;
import org.riftlabs.villagerMerchants.util.Sched;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plays the particle effects configured in effects.yml around shop NPCs.
 * Every NPC with an effect has its own timer on its own region thread; particles are only sent to players within
 * view-distance, and NPCs nobody can see cost almost nothing.
 */
public class EffectManager {

    private static final class Active {
        final Entity npc;
        String effectId;
        long age;
        long[] next = new long[0];
        List<Player> viewers = List.of();
        long viewerRefresh;
        long previewUntil;     // 0 = not previewing
        String restoreId;      // effect to return to when the preview ends
        volatile Sched.Handle handle;   // this NPC's timer

        Active(Entity npc) { this.npc = npc; }
    }

    private final VillagerMerchants plugin;
    private final EffectRegistry registry;
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    public EffectManager(VillagerMerchants plugin) {
        this.plugin = plugin;
        this.registry = new EffectRegistry(plugin);
        registry.load();
    }

    public EffectRegistry getRegistry() { return registry; }

    /**
     * Folia: there is no single loop over every NPC. Each NPC with an effect gets its own timer on the thread of the
     * region it stands in (see {@link #startTimer}), which also means a busy region never slows down the others.
     */
    private void startTimer(Active a) {
        if (a.handle != null) a.handle.cancel();
        UUID id = a.npc.getUniqueId();
        a.handle = Sched.entityTimer(a.npc, h -> tickOne(a, h), () -> active.remove(id, a), 20L, registry.updateTicks());
    }

    /** Re-reads effects.yml. Running effects that no longer exist stop on the next tick. */
    public void reload() {
        registry.load();
        for (Active a : active.values()) {
            Sched.entity(a.npc, () -> {
                a.next = new long[0];
                startTimer(a);   // the update interval may have changed
            });
        }
    }

    public void cleanup() {
        for (Active a : active.values()) if (a.handle != null) a.handle.cancel();
        active.clear();
    }

    // ------------------------------------------------------------------ control (safe from any thread)

    /** Sets (or with a null/blank/unknown id, clears) the effect on a shop NPC. */
    public void set(UUID npcId, String effectId) {
        Entity e = npcId == null ? null : Bukkit.getEntity(npcId);
        if (e != null) set(e, effectId); else if (npcId != null) clear(npcId);
    }

    public void set(Entity v, String effectId) {
        if (effectId == null || effectId.isBlank() || registry.get(effectId) == null) {
            clear(v.getUniqueId());
            return;
        }
        Sched.entity(v, () -> {
            Active a = active.get(v.getUniqueId());
            boolean fresh = a == null;
            if (fresh) { a = new Active(v); active.put(v.getUniqueId(), a); }
            a.effectId = effectId;
            a.previewUntil = 0;
            a.restoreId = null;
            a.next = new long[0];
            if (fresh) startTimer(a);
        });
    }

    public void clear(UUID npcId) {
        if (npcId == null) return;
        Active a = active.remove(npcId);
        if (a != null && a.handle != null) a.handle.cancel();
    }

    /** Plays an effect for a few seconds, then goes back to restoreId (may be null/blank for "nothing"). */
    public boolean preview(UUID npcId, String effectId, int seconds, String restoreId) {
        if (registry.get(effectId) == null) return false;
        Entity v = npcId == null ? null : Bukkit.getEntity(npcId);
        if (v == null) return false;
        Sched.entity(v, () -> {
            Active a = active.get(npcId);
            boolean fresh = a == null;
            if (fresh) { a = new Active(v); active.put(npcId, a); }
            // keep the original restore target if a preview is already running
            if (a.previewUntil == 0) a.restoreId = restoreId;
            a.effectId = effectId;
            a.previewUntil = System.currentTimeMillis() + seconds * 1000L;
            a.next = new long[0];
            if (fresh) startTimer(a);
        });
        return true;
    }

    // ------------------------------------------------------------------ ticking

    /** One update of one NPC's effect. Runs on the thread that owns the NPC. */
    private void tickOne(Active a, Sched.Handle self) {
        UUID id = a.npc.getUniqueId();
        if (active.get(id) != a) { self.cancel(); return; }            // cleared or replaced
        Entity v = a.npc;
        if (!v.isValid()) { stop(id, a, self); return; }

        long now = System.currentTimeMillis();
        int step = registry.updateTicks();
        double vd = registry.viewDistance();
        double vd2 = vd * vd;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        if (a.previewUntil > 0 && now >= a.previewUntil) {
            a.previewUntil = 0;
            String back = a.restoreId;
            a.restoreId = null;
            if (back == null || back.isBlank() || registry.get(back) == null) { stop(id, a, self); return; }
            a.effectId = back;
            a.next = new long[0];
        }

        EffectDefinition def = registry.get(a.effectId);
        if (def == null) { stop(id, a, self); return; }

        Location base = v.getLocation();
        if (now >= a.viewerRefresh) {
            List<Player> near = new ArrayList<>();
            for (Player p : base.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(base) <= vd2) near.add(p);
            }
            a.viewers = near;
            a.viewerRefresh = now + 500;
        }
        a.age += step;
        if (a.viewers.isEmpty()) return;

        List<EffectLayer> layers = def.layers();
        if (a.next.length != layers.size()) a.next = new long[layers.size()];
        for (int i = 0; i < layers.size(); i++) {
            if (a.age < a.next[i]) continue;
            EffectLayer l = layers.get(i);
            a.next[i] = a.age + l.interval;
            emit(l, a, base, rnd);
        }
    }

    private void stop(UUID id, Active a, Sched.Handle self) {
        active.remove(id, a);
        self.cancel();
    }

    private void emit(EffectLayer l, Active a, Location base, ThreadLocalRandom rnd) {
        List<double[]> pts = ShapeMath.generate(l, a.age, base.getYaw(), rnd);
        boolean perPoint = l.colorMode != ColorMode.STATIC && (l.colorSpread != 0 || l.colorMode == ColorMode.RANDOM);
        Object shared = perPoint ? null : buildData(l, a.age, 0f, rnd);
        double bx = base.getX(), by = base.getY() + registry.yOffset(), bz = base.getZ();
        int cap = registry.maxPoints();
        int sent = 0;

        for (double[] p : pts) {
            if (sent >= cap) break;
            if (l.chance < 1.0 && rnd.nextDouble() > l.chance) continue;
            float u = (float) p[3];
            Object data = perPoint ? buildData(l, a.age, u, rnd) : shared;

            int count = l.count;
            double ox = l.offX, extra = l.extra;
            if (l.noteColor) {                       // NOTE reads its colour from the x offset (0..1 in 24 steps)
                count = 0;
                ox = Math.floor(cycle(l, a.age, u) * 24) / 24.0;
                extra = 1.0;
            }
            double x = bx + p[0], y = by + p[1], z = bz + p[2];
            for (Player viewer : a.viewers) {
                viewer.spawnParticle(l.particle, x, y, z, count, ox, l.offY, l.offZ, extra, data);
            }
            sent++;
        }
    }

    // ------------------------------------------------------------------ colour

    private Object buildData(EffectLayer l, long t, float u, ThreadLocalRandom rnd) {
        Class<?> type = l.dataType;
        if (type == Void.class) return null;
        if (type == Particle.DustOptions.class) return new Particle.DustOptions(colorAt(l, cycle(l, t, u), 0, rnd), (float) l.size);
        if (type == Particle.DustTransition.class) {
            double c = cycle(l, t, u);
            Color from = colorAt(l, c, 0, rnd);
            Color to = l.toColor != null ? l.toColor : colorAt(l, c, 1, rnd);
            return new Particle.DustTransition(from, to, (float) l.size);
        }
        if (type == Color.class) return colorAt(l, cycle(l, t, u), 0, rnd);
        if (type == org.bukkit.block.data.BlockData.class) return l.blockData;
        if (type == org.bukkit.inventory.ItemStack.class) return l.item;
        if (type == Float.class) return 0f;
        if (type == Integer.class) return 0;
        return null;
    }

    /** Position in the colour loop, 0..1. colorSpeed 1.0 = one full loop every 5 seconds. */
    private double cycle(EffectLayer l, long t, float u) {
        double c = t * l.colorSpeed / 100.0 + u * l.colorSpread;
        return c - Math.floor(c);
    }

    private Color colorAt(EffectLayer l, double c, int shift, ThreadLocalRandom rnd) {
        List<Color> cols = l.colors;
        int n = cols.size();
        switch (l.colorMode) {
            case CYCLE:
                return cols.get(((int) (c * n) + shift) % n);
            case GRADIENT: {
                double pos = c * n;
                int i = (int) pos;
                double f = pos - i;
                Color a = cols.get((i + shift) % n), b = cols.get((i + 1 + shift) % n);
                return Color.fromRGB(lerp(a.getRed(), b.getRed(), f), lerp(a.getGreen(), b.getGreen(), f),
                        lerp(a.getBlue(), b.getBlue(), f));
            }
            case RAINBOW:
                return hsv(c + shift * 0.2);
            case RANDOM:
                return n > 1 ? cols.get(rnd.nextInt(n)) : hsv(rnd.nextDouble());
            default:
                return cols.get(shift % n);
        }
    }

    private static int lerp(int a, int b, double f) {
        return Math.max(0, Math.min(255, (int) Math.round(a + (b - a) * f)));
    }

    private static Color hsv(double h) {
        h = h - Math.floor(h);
        double f6 = h * 6;
        int i = ((int) f6) % 6;
        double f = f6 - Math.floor(f6);
        double q = 1 - f, t = f;
        double r, g, b;
        switch (i) {
            case 0 -> { r = 1; g = t; b = 0; }
            case 1 -> { r = q; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = t; }
            case 3 -> { r = 0; g = q; b = 1; }
            case 4 -> { r = t; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = q; }
        }
        return Color.fromRGB((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }
}
