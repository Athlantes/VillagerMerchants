package org.riftlabs.villagerMerchants.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Identity of custom items from ItemsAdder, Oraxen and MMOItems, e.g. "itemsadder:ns:ruby_sword".
 *
 * The plugins are reached through reflection, so there is no compile-time dependency and the plugin keeps
 * working when none of them is installed. A hook that cannot be resolved (plugin missing, API renamed) is
 * skipped with one warning; matching then falls back to plain ItemStack#isSimilar for those items.
 *
 * The item data itself (textures, abilities, NBT/components, PDC) is never read here: it survives in the
 * database because ItemCodec stores Paper's complete item bytes. This class only answers "which custom item is
 * this?", so that two copies of the same item with different rolled stats or durability count as the same offer.
 */
public final class CustomItems {
    private interface Resolver { String key(ItemStack item) throws Exception; }

    private static VillagerMerchants plugin;
    private static boolean matchById = true;
    private static List<Resolver> resolvers = List.of();
    private static boolean resolved;
    private static final Set<String> warned = new HashSet<>();

    private CustomItems() {}

    /** Call from onEnable and again after a config reload. Hooks are resolved lazily on first use. */
    public static void init(VillagerMerchants p) {
        plugin = p;
        matchById = p.getConfig().getBoolean("custom-items.match-by-id", true);
        resolvers = List.of();
        resolved = false;
        warned.clear();
    }

    /** @return e.g. "oraxen:ruby_sword", or null for vanilla items (or when matching by id is switched off). */
    public static String keyOf(ItemStack item) {
        if (!matchById || item == null || item.getType().isAir() || !item.hasItemMeta()) return null;
        if (!resolved) resolve();
        for (Resolver r : resolvers) {
            try {
                String k = r.key(item);
                if (k != null) return k;
            } catch (Exception e) {
                warnOnce("call", "A custom item lookup failed (" + e + "); falling back to exact matching for it.");
            }
        }
        return null;
    }

    // ------------------------------------------------------------ hook setup

    private static void resolve() {
        resolved = true;
        List<Resolver> found = new ArrayList<>();
        try {
            Class<?> cs = load("ItemsAdder", "dev.lone.itemsadder.api.CustomStack");
            if (cs != null) {
                Method by = cs.getMethod("byItemStack", ItemStack.class);
                Method id = cs.getMethod("getNamespacedID");
                found.add(it -> {
                    Object c = by.invoke(null, it);
                    return c == null ? null : "itemsadder:" + id.invoke(c);
                });
            }
        } catch (Exception | LinkageError e) { warnOnce("ia", "ItemsAdder found but its API did not match: " + e); }
        try {
            Class<?> ox = load("Oraxen", "io.th0rgal.oraxen.api.OraxenItems");
            if (ox != null) {
                Method m = ox.getMethod("getIdByItem", ItemStack.class);
                found.add(it -> {
                    Object id = m.invoke(null, it);
                    return id == null ? null : "oraxen:" + id;
                });
            }
        } catch (Exception | LinkageError e) { warnOnce("ox", "Oraxen found but its API did not match: " + e); }
        try {
            Class<?> mi = load("MMOItems", "net.Indyuce.mmoitems.MMOItems");
            if (mi != null) {
                Method id = mi.getMethod("getID", ItemStack.class);
                Method type = mi.getMethod("getTypeName", ItemStack.class);
                found.add(it -> {
                    Object i = id.invoke(null, it);
                    return i == null ? null : "mmoitems:" + type.invoke(null, it) + ":" + i;
                });
            }
        } catch (Exception | LinkageError e) { warnOnce("mmo", "MMOItems found but its API did not match: " + e); }
        resolvers = found;
        if (plugin != null && !found.isEmpty()) {
            plugin.getLogger().info("Custom item support active (" + found.size() + " hook(s)).");
        }
    }

    private static Class<?> load(String pluginName, String className) throws ClassNotFoundException {
        Plugin pl = Bukkit.getPluginManager().getPlugin(pluginName);
        if (pl == null || !pl.isEnabled()) return null;
        return Class.forName(className, true, pl.getClass().getClassLoader());
    }

    private static void warnOnce(String id, String msg) {
        if (plugin != null && warned.add(id)) plugin.getLogger().warning(msg);
    }
}
