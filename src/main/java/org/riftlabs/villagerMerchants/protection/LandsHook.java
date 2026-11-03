package org.riftlabs.villagerMerchants.protection;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Lands, through reflection. The Lands API has been reshaped between releases (class packages, how you obtain the
 * integration, where the flags live), so this looks the pieces up at startup instead of compiling against one
 * version. If a piece can't be found the constructor throws, ProtectionManager reports exactly what was missing,
 * and the on-error setting decides what happens to Lands-protected spots.
 *
 * Expected shape (Lands 6.x / 7.x): {@code LandsIntegration.of(plugin)}, {@code getArea(Location)},
 * {@code Area#hasRoleFlag(UUID, flag)} with the BLOCK_PLACE role flag.
 */
public final class LandsHook implements ClaimHook {
    private static final List<String> FLAG_CLASSES = List.of(
            "me.angeschossen.lands.api.flags.type.Flags",
            "me.angeschossen.lands.api.flags.Flags");

    private final Object api;
    private final Method getArea;
    private final Method hasRoleFlag;
    private final Object blockPlace;

    public LandsHook(Plugin plugin) throws Exception {
        Class<?> integration = Class.forName("me.angeschossen.lands.api.LandsIntegration");
        Object a;
        try {
            a = integration.getMethod("of", Plugin.class).invoke(null, plugin);
        } catch (NoSuchMethodException e) {
            a = integration.getConstructor(Plugin.class).newInstance(plugin);
        }
        api = a;

        Method ga = find(integration, "getArea", Location.class);
        if (ga == null) ga = find(integration, "getAreaByLoc", Location.class);
        if (ga == null) throw new NoSuchMethodException("LandsIntegration#getArea(Location) not found");
        getArea = ga;

        Class<?> area = Class.forName("me.angeschossen.lands.api.land.Area");
        Method has = null;
        for (Method m : area.getMethods()) {
            if (m.getName().equals("hasRoleFlag") && m.getParameterCount() == 2 && m.getParameterTypes()[0] == UUID.class) { has = m; break; }
        }
        if (has == null) throw new NoSuchMethodException("Area#hasRoleFlag(UUID, flag) not found");
        hasRoleFlag = has;

        Object flag = null;
        for (String cn : FLAG_CLASSES) {
            try {
                Object f = Class.forName(cn).getField("BLOCK_PLACE").get(null);
                if (f != null && has.getParameterTypes()[1].isInstance(f)) { flag = f; break; }
            } catch (ReflectiveOperationException ignored) {}
        }
        if (flag == null) throw new NoSuchFieldException("Flags.BLOCK_PLACE not found");
        blockPlace = flag;
    }

    private static Method find(Class<?> c, String name, Class<?>... params) {
        try { return c.getMethod(name, params); } catch (NoSuchMethodException e) { return null; }
    }

    private Object area(Location loc) throws Exception {
        try { return getArea.invoke(api, loc); }
        catch (InvocationTargetException e) { throw e.getCause() instanceof Exception ex ? ex : e; }
    }

    @Override
    public ClaimInfo claimAt(Location loc) throws Exception {
        Object area = area(loc);
        if (area == null) return null;
        String name = "a land";
        try {
            Object n = area.getClass().getMethod("getName").invoke(area);
            if (n != null) name = String.valueOf(n);
        } catch (ReflectiveOperationException ignored) {}
        return new ClaimInfo(name, false);
    }

    @Override
    public boolean canBuild(Player player, Location loc) throws Exception {
        Object area = area(loc);
        if (area == null) return true;
        try { return (Boolean) hasRoleFlag.invoke(area, player.getUniqueId(), blockPlace); }
        catch (InvocationTargetException e) { throw e.getCause() instanceof Exception ex ? ex : e; }
    }
}
