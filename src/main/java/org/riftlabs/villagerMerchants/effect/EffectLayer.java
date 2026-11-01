package org.riftlabs.villagerMerchants.effect;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable, pre-validated settings of one particle layer (one entry under "layers:" in effects.yml). */
public final class EffectLayer {
    public enum ColorMode { STATIC, CYCLE, GRADIENT, RAINBOW, RANDOM }

    private static final Set<Class<?>> SUPPORTED = Set.of(Void.class, Particle.DustOptions.class,
            Particle.DustTransition.class, BlockData.class, ItemStack.class, Color.class, Float.class, Integer.class);

    public final EffectShape shape;
    public final Particle particle;
    public final Class<?> dataType;

    public final int count, points, interval, strands, trail;
    public final double offX, offY, offZ, extra;
    public final double radius, height, y, turns, taper, period, jitter, chance, trailSpacing, size;
    public final boolean face;

    // animation
    public final double spin, tiltX, tiltZ, tumble, bobAmp, bobSpeed, pulseAmp, pulseSpeed, flapAmp, flapSpeed;

    // colour
    public final ColorMode colorMode;
    public final List<Color> colors;
    public final Color toColor;           // nullable
    public final double colorSpeed, colorSpread;
    public final boolean noteColor;       // NOTE particles take their colour from the x-offset

    public final BlockData blockData;     // nullable
    public final ItemStack item;          // nullable

    /** @throws IllegalArgumentException with a human readable reason when the section is invalid */
    public EffectLayer(ConfigurationSection s, int maxPoints) {
        shape = EffectShape.parse(s.getString("shape", "RING"));
        if (shape == null) throw new IllegalArgumentException("unknown shape '" + s.getString("shape") + "'");

        particle = parseParticle(s.getString("particle", "END_ROD"));
        if (particle == null) throw new IllegalArgumentException("unknown particle '" + s.getString("particle") + "'");
        dataType = particle.getDataType();
        if (!SUPPORTED.contains(dataType))
            throw new IllegalArgumentException("particle " + s.getString("particle") + " needs data this plugin cannot provide");

        count = Math.max(0, s.getInt("count", 1));
        List<Double> off = s.getDoubleList("offset");
        offX = off.size() > 0 ? off.get(0) : 0;
        offY = off.size() > 1 ? off.get(1) : 0;
        offZ = off.size() > 2 ? off.get(2) : 0;
        extra = s.getDouble("speed", 0.0);

        points = Math.max(1, Math.min(maxPoints, s.getInt("points", 16)));
        interval = Math.max(1, s.getInt("interval", 2));
        int defStrands = switch (shape) { case CROWN -> 6; case FOUNTAIN -> 4; case ORBIT, PULSE, SPIRAL -> 1; default -> 2; };
        strands = Math.max(1, Math.min(64, s.getInt("strands", defStrands)));
        trail = Math.max(0, Math.min(64, s.getInt("trail", 0)));
        trailSpacing = s.getDouble("trail-spacing", shape == EffectShape.SPIRAL ? 0.02 : 8.0);

        radius = s.getDouble("radius", 0.8);
        height = s.getDouble("height", 2.0);
        y = s.getDouble("y", 0.0);
        turns = s.getDouble("turns", 2.0);
        taper = s.getDouble("taper", 0.0);
        period = Math.max(1.0, s.getDouble("period", 40.0));
        jitter = Math.max(0.0, s.getDouble("jitter", 0.0));
        chance = Math.max(0.0, Math.min(1.0, s.getDouble("chance", 1.0)));
        size = Math.max(0.1, s.getDouble("size", 1.0));
        face = s.getBoolean("face", shape == EffectShape.HEART || shape == EffectShape.WINGS);

        spin = s.getDouble("animation.spin", 0.0);
        tiltX = s.getDouble("animation.tilt-x", 0.0);
        tiltZ = s.getDouble("animation.tilt-z", 0.0);
        tumble = s.getDouble("animation.tumble", 0.0);
        bobAmp = s.getDouble("animation.bob-amplitude", 0.0);
        bobSpeed = s.getDouble("animation.bob-speed", 6.0);
        pulseAmp = s.getDouble("animation.pulse-amplitude", 0.0);
        pulseSpeed = s.getDouble("animation.pulse-speed", 6.0);
        flapAmp = s.getDouble("animation.flap-amplitude", 25.0);
        flapSpeed = s.getDouble("animation.flap-speed", 10.0);

        try {
            colorMode = ColorMode.valueOf(s.getString("color-mode", "STATIC").trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown color-mode '" + s.getString("color-mode") + "'");
        }
        List<Color> cols = new ArrayList<>();
        String single = s.getString("color");
        if (single != null) cols.add(requireColor(single));
        for (String c : s.getStringList("colors")) cols.add(requireColor(c));
        if (cols.isEmpty()) cols.add(Color.WHITE);
        colors = List.copyOf(cols);
        String to = s.getString("to-color");
        toColor = to == null ? null : requireColor(to);
        colorSpeed = s.getDouble("color-speed", 1.0);
        colorSpread = s.getDouble("color-spread", 0.0);
        noteColor = particle == Particle.NOTE && colorMode != ColorMode.STATIC;

        BlockData bd = null;
        ItemStack is = null;
        if (dataType == BlockData.class || dataType == ItemStack.class) {
            Material m = Material.matchMaterial(s.getString("material", "STONE"));
            if (m == null) throw new IllegalArgumentException("unknown material '" + s.getString("material") + "'");
            if (dataType == BlockData.class) bd = (m.isBlock() ? m : Material.STONE).createBlockData();
            else is = new ItemStack(m.isItem() ? m : Material.DIAMOND);
        }
        blockData = bd;
        item = is;
    }

    private static Color requireColor(String s) {
        Color c = parseColor(s);
        if (c == null) throw new IllegalArgumentException("bad colour '" + s + "' (use \"#RRGGBB\" or \"r,g,b\")");
        return c;
    }

    /** "#RRGGBB" or "r,g,b" (0-255). */
    public static Color parseColor(String str) {
        if (str == null) return null;
        str = str.trim();
        try {
            if (str.startsWith("#")) return Color.fromRGB(Integer.parseInt(str.substring(1), 16));
            String[] p = str.split("[,\\s]+");
            if (p.length == 3) return Color.fromRGB(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (RuntimeException ignored) {}
        return null;
    }

    /** Accepts HAPPY_VILLAGER, happy_villager or minecraft:happy_villager. */
    public static Particle parseParticle(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.startsWith("minecraft:")) n = n.substring(10);
        try { return Particle.valueOf(n.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }
}
