package org.riftlabs.villagerMerchants.protection;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** GriefPrevention 16.18+. Only loaded when the plugin is installed. */
public final class GriefPreventionHook implements ClaimHook {

    private static Claim at(Location loc) {
        return GriefPrevention.instance.dataStore.getClaimAt(loc, true, null);
    }

    @Override
    public ClaimInfo claimAt(Location loc) {
        Claim c = at(loc);
        if (c == null) return null;
        return new ClaimInfo(c.isAdminClaim() ? "admin" : c.getOwnerName(), c.isAdminClaim());
    }

    @Override
    public boolean canBuild(Player player, Location loc) {
        Claim c = at(loc);
        // checkPermission returns null when the player is allowed
        return c == null || c.checkPermission(player, ClaimPermission.Build, null) == null;
    }
}
