package org.riftlabs.villagerMerchants.hook;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Talks to MythicMobs through reflection, so the plugin compiles and runs without it.
 * Every call is guarded: if the API differs from what is expected, the hook simply reports "not a MythicMob".
 */
public final class MythicHook {
    private final VillagerMerchants plugin;
    private Object mobManager;
    private Method getActiveMob;
    private Method spawnMob;
    private boolean ready;

    public MythicHook(VillagerMerchants plugin) {
        this.plugin = plugin;
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicMobs")) return;
        try {
            Object inst = Class.forName("io.lumine.mythic.bukkit.MythicBukkit").getMethod("inst").invoke(null);
            mobManager = inst.getClass().getMethod("getMobManager").invoke(inst);
            getActiveMob = mobManager.getClass().getMethod("getActiveMob", UUID.class);
            spawnMob = mobManager.getClass().getMethod("spawnMob", String.class, Location.class);
            ready = true;
            plugin.getLogger().info("MythicMobs found: shops can be linked to MythicMobs.");
        } catch (Throwable t) {
            plugin.getLogger().warning("MythicMobs is installed but its API could not be used (" + t + "); /vm link will not see MythicMobs.");
        }
    }

    public boolean available() { return ready; }

    /** Internal name of the MythicMob type this entity is, or null if it is not a MythicMob. */
    public String typeOf(Entity e) {
        if (!ready) return null;
        try {
            Object opt = getActiveMob.invoke(mobManager, e.getUniqueId());
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) return null;
            Object mob = o.get();
            try {
                Object t = mob.getClass().getMethod("getMobType").invoke(mob);
                if (t != null) return t.toString();
            } catch (NoSuchMethodException ignored) { /* older API: fall through */ }
            Object type = mob.getClass().getMethod("getType").invoke(mob);
            return String.valueOf(type.getClass().getMethod("getInternalName").invoke(type));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Spawns a MythicMob of the given type (internal name); null if that fails. */
    public Entity spawn(String type, Location at) {
        if (!ready || type == null) return null;
        try {
            Object mob = spawnMob.invoke(mobManager, type, at);
            if (mob == null) return null;
            Object abstractEntity = mob.getClass().getMethod("getEntity").invoke(mob);
            Object bukkit = abstractEntity.getClass().getMethod("getBukkitEntity").invoke(abstractEntity);
            return bukkit instanceof Entity en ? en : null;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not spawn MythicMob '" + type + "': " + t);
            return null;
        }
    }
}
