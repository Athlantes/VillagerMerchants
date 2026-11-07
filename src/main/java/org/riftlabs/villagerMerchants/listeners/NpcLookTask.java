package org.riftlabs.villagerMerchants.listeners;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.util.Sched;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a shop villager turn to face the nearest player and return to its facing afterwards.
 * <p>
 * Folia: there is no global loop over every NPC any more. Each villager gets its own timer that runs on the thread of
 * the region it stands in ({@link #attach}), so a busy region never delays a quiet one.
 */
public final class NpcLookTask {
    private static final Set<UUID> ATTACHED = ConcurrentHashMap.newKeySet();

    private final VillagerMerchants plugin;
    private final Villager villager;
    private final double radius;
    private float originalYaw;
    private boolean turned;

    private NpcLookTask(VillagerMerchants plugin, Villager villager, double radius) {
        this.plugin = plugin;
        this.villager = villager;
        this.radius = radius;
    }

    /** Starts the look timer for a vanilla shop villager. Safe to call again for the same entity. Call on the entity's thread. */
    public static void attach(VillagerMerchants plugin, Villager v) {
        if (!plugin.getConfig().getBoolean("npc.look-at-players", true)) return;
        if (!ATTACHED.add(v.getUniqueId())) return;
        long ticks = Math.max(1, plugin.getConfig().getLong("npc.look-update-ticks", 4));
        double r = plugin.getConfig().getDouble("npc.look-radius", 6.0);
        NpcLookTask task = new NpcLookTask(plugin, v, r);
        UUID id = v.getUniqueId();
        Sched.entityTimer(v, h -> task.run(h, id), () -> ATTACHED.remove(id), 20L, ticks);
    }

    private void run(Sched.Handle self, UUID id) {
        // body replaced, linked to another plugin, or removed: stop (a new body gets its own timer)
        if (!villager.isValid() || !plugin.getNpcs().isVanilla(villager)) {
            ATTACHED.remove(id);
            self.cancel();
            return;
        }
        Location here = villager.getLocation();
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player p : villager.getWorld().getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            Location pl = p.getLocation();
            double dx = pl.getX() - here.getX(), dy = pl.getY() - here.getY(), dz = pl.getZ() - here.getZ();
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) { best = d; nearest = p; }
        }

        if (nearest == null) {                      // nobody near any more: face the original direction again
            if (turned) { villager.setRotation(originalYaw, 0f); turned = false; }
            return;
        }
        if (!turned) { originalYaw = here.getYaw(); turned = true; }
        Location from = villager.getEyeLocation(), to = nearest.getEyeLocation();
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        if (Math.abs(angleDiff(yaw, here.getYaw())) > 2f || Math.abs(pitch - here.getPitch()) > 2f) {
            villager.setRotation(yaw, pitch);
        }
    }

    private static float angleDiff(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
