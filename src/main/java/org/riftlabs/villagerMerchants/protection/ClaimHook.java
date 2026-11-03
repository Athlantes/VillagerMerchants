package org.riftlabs.villagerMerchants.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/** A land-claim plugin (GriefPrevention, Lands, ...). Implementations may throw; ProtectionManager applies on-error. */
public interface ClaimHook {
    /** What claim covers a spot. {@code owner} is only used in messages. */
    record ClaimInfo(String owner, boolean admin) {}

    /** The claim at this location, or null if it is unclaimed. */
    ClaimInfo claimAt(Location loc) throws Exception;

    /** Whether the player has build trust at this location (only asked when a claim exists there). */
    boolean canBuild(Player player, Location loc) throws Exception;
}
