package org.riftlabs.villagerMerchants.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The one place that schedules work, so the plugin runs the same on Folia and on Paper.
 * <p>
 * Folia has no single main thread: every chunk belongs to a region with its own thread, and an entity may only be
 * touched from the region that owns it. The rule used all over this plugin:
 * <ul>
 *   <li>touching an entity (player, villager, display) -> {@link #entity}</li>
 *   <li>touching blocks / spawning at a position       -> {@link #at}</li>
 *   <li>global state (console commands, timers over all shops) -> {@link #global}</li>
 *   <li>waiting on the database or doing heavy maths    -> {@link #async}</li>
 * </ul>
 * On Paper the same calls run on the main thread, so nothing here is Folia-only.
 */
public final class Sched {
    private Sched() {}

    private static VillagerMerchants plugin;
    private static boolean folia;

    public static void init(VillagerMerchants p) {
        plugin = p;
        folia = hasClass("io.papermc.paper.threadedregions.RegionizedServer");
    }

    public static boolean isFolia() { return folia; }

    private static boolean hasClass(String name) {
        try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
    }

    /** Handle to cancel a repeating / delayed task. */
    public interface Handle {
        void cancel();
        Handle NONE = () -> {};
    }

    private static Handle wrap(ScheduledTask t) { return t == null ? Handle.NONE : t::cancel; }

    private static boolean usable() { return plugin != null && plugin.isEnabled(); }

    private static Runnable guard(Runnable r) {
        return () -> {
            try { r.run(); }
            catch (Throwable ex) { plugin.getLogger().log(Level.SEVERE, "Scheduled task failed", ex); }
        };
    }

    private static long ticks(long t) { return Math.max(1L, t); }

    // ------------------------------------------------------------ global region (console, whole-server work)

    /** Next tick on the global region. Replaces {@code Bukkit.getScheduler().runTask}. */
    public static Handle global(Runnable r) {
        if (!usable()) return Handle.NONE;
        Runnable g = guard(r);
        return wrap(Bukkit.getGlobalRegionScheduler().run(plugin, t -> g.run()));
    }

    public static Handle globalLater(Runnable r, long delayTicks) {
        if (!usable()) return Handle.NONE;
        Runnable g = guard(r);
        return wrap(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> g.run(), ticks(delayTicks)));
    }

    public static Handle globalTimer(Runnable r, long delayTicks, long periodTicks) {
        if (!usable()) return Handle.NONE;
        Runnable g = guard(r);
        return wrap(Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> g.run(), ticks(delayTicks), ticks(periodTicks)));
    }

    // ------------------------------------------------------------ async (database, heavy work: never touch the world here)

    public static Handle async(Runnable r) {
        if (!usable()) return Handle.NONE;
        Runnable g = guard(r);
        return wrap(Bukkit.getAsyncScheduler().runNow(plugin, t -> g.run()));
    }

    public static Handle asyncTimer(Runnable r, long delayTicks, long periodTicks) {
        if (!usable()) return Handle.NONE;
        Runnable g = guard(r);
        return wrap(Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> g.run(),
                ticks(delayTicks) * 50L, ticks(periodTicks) * 50L, TimeUnit.MILLISECONDS));
    }

    // ------------------------------------------------------------ entities (players, villagers, displays)

    /**
     * Runs {@code r} on the thread that owns {@code e}: right now if this already is that thread, otherwise on its
     * next tick. If the entity is gone by then the task is dropped.
     */
    public static void entity(Entity e, Runnable r) { entity(e, r, null); }

    /** Same, and {@code retired} runs instead if the entity is removed (a player logs out) before the task could run. */
    public static void entity(Entity e, Runnable r, Runnable retired) {
        if (e == null || !usable()) return;
        if (Bukkit.isOwnedByCurrentRegion(e)) { guard(r).run(); return; }
        Runnable g = guard(r);
        e.getScheduler().run(plugin, t -> g.run(), retired == null ? null : guard(retired));
    }

    /** Always next tick (or later) on the entity's thread. Use this when running inline would be unsafe, e.g. opening an inventory from inside a click event. */
    public static Handle entityLater(Entity e, Runnable r, long delayTicks) {
        if (e == null || !usable()) return Handle.NONE;
        Runnable g = guard(r);
        return wrap(e.getScheduler().runDelayed(plugin, t -> g.run(), null, ticks(delayTicks)));
    }

    /** Next tick on the entity's thread. */
    public static Handle entityNext(Entity e, Runnable r) { return entityLater(e, r, 1L); }

    /**
     * Repeats on the entity's own thread. Stops by itself when the entity is removed ({@code retired} runs then).
     * The task gets its handle so it can cancel itself.
     */
    public static Handle entityTimer(Entity e, Consumer<Handle> body, Runnable retired, long delayTicks, long periodTicks) {
        if (e == null || !usable()) return Handle.NONE;
        Runnable gone = retired == null ? null : guard(retired);
        ScheduledTask st = e.getScheduler().runAtFixedRate(plugin, t -> {
            try { body.accept(t::cancel); }
            catch (Throwable ex) { plugin.getLogger().log(Level.SEVERE, "Entity task failed", ex); }
        }, gone, ticks(delayTicks), ticks(periodTicks));
        return wrap(st);
    }

    // ------------------------------------------------------------ positions (blocks, spawning)

    /** On the region that owns {@code loc}: inline if this already is that region, otherwise next tick. */
    public static void at(Location loc, Runnable r) {
        if (loc == null || loc.getWorld() == null || !usable()) return;
        if (Bukkit.isOwnedByCurrentRegion(loc)) { guard(r).run(); return; }
        Runnable g = guard(r);
        Bukkit.getRegionScheduler().run(plugin, loc, t -> g.run());
    }

    /** Same, for a chunk. */
    public static void chunk(World w, int cx, int cz, Runnable r) {
        if (w == null || !usable()) return;
        if (Bukkit.isOwnedByCurrentRegion(w, cx, cz)) { guard(r).run(); return; }
        Runnable g = guard(r);
        Bukkit.getRegionScheduler().run(plugin, w, cx, cz, t -> g.run());
    }

    public static Handle chunkLater(World w, int cx, int cz, Runnable r, long delayTicks) {
        if (w == null || !usable()) return Handle.NONE;
        Runnable g = guard(r);
        return wrap(Bukkit.getRegionScheduler().runDelayed(plugin, w, cx, cz, t -> g.run(), ticks(delayTicks)));
    }

    // ------------------------------------------------------------ shutdown

    /** Cancels everything this plugin scheduled (global + async; entity / region tasks die with the plugin). */
    public static void cancelAll() {
        if (plugin == null) return;
        try { Bukkit.getGlobalRegionScheduler().cancelTasks(plugin); } catch (Throwable ignored) {}
        try { Bukkit.getAsyncScheduler().cancelTasks(plugin); } catch (Throwable ignored) {}
    }
}
