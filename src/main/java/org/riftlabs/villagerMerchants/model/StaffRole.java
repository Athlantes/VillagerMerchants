package org.riftlabs.villagerMerchants.model;

import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Permission levels for shop staff. The owner always has everything, plus the things that stay
 * owner-only: cancelling the shop, upgrades, NPC customization and managing staff.
 *
 * <p>What each role may do is configurable in config.yml ({@code staff.roles.<role>}); the built-in
 * sets below are used when that list is missing. Role names shown to players come from the language file.
 */
public enum StaffRole {
    STOCKER(EnumSet.of(Perm.STORAGE)),
    MANAGER(EnumSet.of(Perm.STORAGE, Perm.TRADES, Perm.SALES, Perm.STATS)),
    CO_OWNER(EnumSet.allOf(Perm.class));

    private final Set<Perm> defaults;

    StaffRole(Set<Perm> defaults) { this.defaults = defaults; }

    /** Config / language key: "stocker", "manager", "co_owner". */
    public String key() { return name().toLowerCase(Locale.ROOT); }

    /** Display name from the language file ({@code roles.<key>}). */
    public String label() { return VillagerMerchants.getInstance().msg().raw("roles." + key()); }

    public boolean has(Perm p) {
        var cfg = VillagerMerchants.getInstance().getConfig();
        String path = "staff.roles." + key();
        if (!cfg.isList(path)) return defaults.contains(p);
        for (String s : cfg.getStringList(path)) {
            if (s.equalsIgnoreCase(p.name()) || s.equalsIgnoreCase("ALL")) return true;
        }
        return false;
    }

    /** Next level up, wrapping around (used by the staff menu to cycle a member's role). */
    public StaffRole next() { return values()[(ordinal() + 1) % values().length]; }

    /** Accepts "stocker", "manager", "co-owner", "co_owner", "coowner" - or the role's name in the active language. */
    public static StaffRole parse(String s) {
        if (s == null) return null;
        String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (n.equals("COOWNER")) n = "CO_OWNER";
        try { return valueOf(n); } catch (IllegalArgumentException ignored) {}
        String plain = s.trim().toLowerCase(Locale.ROOT);
        for (StaffRole r : values()) if (r.label().toLowerCase(Locale.ROOT).equals(plain)) return r;
        return null;
    }
}
