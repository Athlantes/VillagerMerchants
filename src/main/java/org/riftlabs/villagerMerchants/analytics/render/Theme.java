package org.riftlabs.villagerMerchants.analytics.render;

import java.util.Locale;

/** Colours of the dashboard: a near-black background, graphite panels, and one configurable accent. */
public final class Theme {
    public final Pal.Ramp bg = Pal.Ramp.run(Pal.idx(Pal.BLACK, 3), Pal.idx(Pal.BLACK, 0), Pal.idx(Pal.BLACK, 1), Pal.idx(Pal.BLACK, 2));
    public final Pal.Ramp white = Pal.Ramp.of(Pal.SNOW);
    public final Pal.Ramp mute = Pal.Ramp.of(Pal.LGRAY);
    public final Pal.Ramp accent;
    public final Pal.Ramp good = Pal.Ramp.of(Pal.PLANT, Pal.LGREEN);
    public final Pal.Ramp bad = Pal.Ramp.of(Pal.RED, Pal.FIRE);
    public final Pal.Ramp info = Pal.Ramp.of(Pal.BLUE, Pal.LBLUE);

    public final byte panel = Pal.idx(Pal.GRAY, 3);
    public final byte panelHi = Pal.idx(Pal.GRAY, 0);
    public final byte panelLine = Pal.idx(Pal.GRAY, 1);
    public final byte grid = Pal.idx(Pal.GRAY, 3);
    public final byte shadow = Pal.idx(Pal.BLACK, 3);

    public final byte text = Pal.idx(Pal.SNOW, 2);
    public final byte textDim = Pal.idx(Pal.SNOW, 0);
    public final byte textMute = Pal.idx(Pal.LGRAY, 1);
    public final byte textFaint = Pal.idx(Pal.LGRAY, 0);

    private Theme(Pal.Ramp accent) { this.accent = accent; }

    private static final java.util.Map<String, Theme> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Themes are immutable, so one instance per accent name serves every frame. */
    public static Theme of(String name) {
        return CACHE.computeIfAbsent(name == null ? "gold" : name.trim().toLowerCase(Locale.ROOT), Theme::build);
    }

    private static Theme build(String n) {
        Pal.Ramp r = switch (n) {
            case "aqua", "cyan", "teal" -> Pal.Ramp.of(Pal.CYAN, Pal.DIAMOND);
            case "green", "emerald" -> Pal.Ramp.of(Pal.PLANT, Pal.LGREEN);
            case "purple", "violet" -> Pal.Ramp.of(Pal.PURPLE, Pal.MAGENTA);
            case "pink" -> Pal.Ramp.of(Pal.PURPLE, Pal.PINK);
            case "red" -> Pal.Ramp.of(Pal.RED, Pal.FIRE);
            case "blue" -> Pal.Ramp.of(Pal.BLUE, Pal.LBLUE);
            default -> Pal.Ramp.of(Pal.BROWN, Pal.ORANGE, Pal.GOLD);
        };
        return new Theme(r);
    }
}
