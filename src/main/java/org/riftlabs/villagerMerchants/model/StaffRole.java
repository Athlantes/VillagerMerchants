package org.riftlabs.villagerMerchants.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Permission levels for shop staff. The owner always has everything, plus the things that stay
 * owner-only: cancelling the shop, upgrades, NPC customization and managing staff.
 */
public enum StaffRole {
    STOCKER("Stocker", EnumSet.of(Perm.STORAGE)),
    MANAGER("Manager", EnumSet.of(Perm.STORAGE, Perm.TRADES, Perm.SALES, Perm.STATS)),
    CO_OWNER("Co-owner", EnumSet.allOf(Perm.class));

    private final String label;
    private final Set<Perm> perms;

    StaffRole(String label, Set<Perm> perms) {
        this.label = label;
        this.perms = perms;
    }

    public String label() { return label; }
    public boolean has(Perm p) { return perms.contains(p); }

    /** Next level up, wrapping around (used by the staff menu to cycle a member's role). */
    public StaffRole next() { return values()[(ordinal() + 1) % values().length]; }

    /** Accepts "stocker", "manager", "co-owner", "co_owner", "coowner". */
    public static StaffRole parse(String s) {
        if (s == null) return null;
        String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (n.equals("COOWNER")) n = "CO_OWNER";
        try { return valueOf(n); } catch (IllegalArgumentException e) { return null; }
    }
}
