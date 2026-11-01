package org.riftlabs.villagerMerchants.effect;

import java.util.Locale;

/** Geometry a layer draws. See effects.yml for what each one does and which options it reads. */
public enum EffectShape {
    RING, CROWN, ORBIT, HELIX, SPIRAL, SPHERE, CLOUD, RAIN, RISING, PULSE, HEART, WINGS, STAR, FOUNTAIN;

    public static EffectShape parse(String s) {
        if (s == null) return null;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; }
    }
}
