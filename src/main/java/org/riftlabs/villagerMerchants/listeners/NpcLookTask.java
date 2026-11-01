package org.riftlabs.villagerMerchants.listeners;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Makes shop NPCs turn to face the nearest player and return to their facing afterwards. */
public class NpcLookTask implements Runnable {
    private final VillagerMerchants plugin;
    private final Map<UUID, Float> originalYaw = new HashMap<>();

    public NpcLookTask(VillagerMerchants plugin) { this.plugin = plugin; }

    private boolean isShopNpc(Entity e) {
        return e instanceof Villager v
                && v.getPersistentDataContainer().has(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE);
    }

    @Override
    public void run() {
        double r = plugin.getConfig().getDouble("npc.look-radius", 6.0);
        Map<Villager, Player> nearest = new HashMap<>();
        Map<Villager, Double> dist = new HashMap<>();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            for (Entity e : p.getNearbyEntities(r, r, r)) {
                if (!isShopNpc(e)) continue;
                Villager v = (Villager) e;
                double d = v.getLocation().distanceSquared(p.getLocation());
                if (d < dist.getOrDefault(v, Double.MAX_VALUE)) { dist.put(v, d); nearest.put(v, p); }
            }
        }

        for (Map.Entry<Villager, Player> en : nearest.entrySet()) {
            Villager v = en.getKey();
            originalYaw.putIfAbsent(v.getUniqueId(), v.getLocation().getYaw());
            Location from = v.getEyeLocation(), to = en.getValue().getEyeLocation();
            double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            if (Math.abs(angleDiff(yaw, v.getLocation().getYaw())) > 2f
                    || Math.abs(pitch - v.getLocation().getPitch()) > 2f) {
                v.setRotation(yaw, pitch);
            }
        }

        // NPCs nobody is near anymore: face their original direction again
        originalYaw.entrySet().removeIf(en -> {
            Entity ent = Bukkit.getEntity(en.getKey());
            if (!(ent instanceof Villager v)) return true;
            if (nearest.containsKey(v)) return false;
            v.setRotation(en.getValue(), 0f);
            return true;
        });
    }

    private float angleDiff(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
