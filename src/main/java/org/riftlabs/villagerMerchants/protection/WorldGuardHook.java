package org.riftlabs.villagerMerchants.protection;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * WorldGuard 7. Only loaded when WorldGuard is installed. The {@code vm-shops} flag has to be registered while the
 * plugin is loading (WorldGuard refuses new flags once it has enabled), so {@link #registerFlag} is called from onLoad.
 */
public final class WorldGuardHook {
    public static final String FLAG_NAME = "vm-shops";
    private static StateFlag flag;

    /** Registers the {@code vm-shops} state flag. Safe to call more than once (e.g. after a plugin manager reload). */
    public static void registerFlag(Logger log) {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            StateFlag f = new StateFlag(FLAG_NAME, false);
            registry.register(f);
            flag = f;
        } catch (FlagConflictException e) {
            Flag<?> existing = registry.get(FLAG_NAME);
            if (existing instanceof StateFlag sf) flag = sf;
            else log.warning("A WorldGuard flag named '" + FLAG_NAME + "' already exists with another type; the flag rule is unavailable.");
        }
    }

    public static boolean flagRegistered() { return flag != null; }

    private static RegionQuery query() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery();
    }

    private static LocalPlayer wrap(Player p) { return WorldGuardPlugin.inst().wrapPlayer(p); }

    /** Lower-case ids of the regions covering this spot (the global region is not included). */
    public Set<String> regionIds(Location loc) {
        ApplicableRegionSet set = query().getApplicableRegions(BukkitAdapter.adapt(loc));
        Set<String> out = new HashSet<>();
        for (ProtectedRegion r : set.getRegions()) out.add(r.getId().toLowerCase(Locale.ROOT));
        return out;
    }

    /** The vm-shops flag here: TRUE = allow, FALSE = deny, null = no region sets it. */
    public Boolean flagState(Player p, Location loc) {
        if (flag == null) throw new IllegalStateException("the " + FLAG_NAME + " flag is not registered");
        StateFlag.State s = query().queryState(BukkitAdapter.adapt(loc), wrap(p), flag);
        return s == null ? null : s == StateFlag.State.ALLOW;
    }

    /** Whether WorldGuard would let this player build here (region membership, bypass permission, build flag). */
    public boolean canBuild(Player p, Location loc) {
        return query().testBuild(BukkitAdapter.adapt(loc), wrap(p));
    }
}
