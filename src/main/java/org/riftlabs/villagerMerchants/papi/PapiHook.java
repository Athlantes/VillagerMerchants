package org.riftlabs.villagerMerchants.papi;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.model.Shop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PlaceholderAPI integration. This is the only class the main plugin touches, so nothing from the PlaceholderAPI
 * jar is loaded unless PlaceholderAPI is actually installed.
 *
 * Placeholders are asked for constantly (scoreboards, holograms, tab lists - sometimes from async threads), so they
 * never touch the shop map or the database directly. Instead a snapshot of the shop data is rebuilt on the main
 * thread every few seconds and the expansion only reads that immutable snapshot.
 */
public final class PapiHook {

    /** What one owner has rented right now. {@code soonestExpiry} is the earliest rent expiry among their shops. */
    public record OwnerRent(int shops, long soonestExpiry) {}

    /** Immutable view of the shop data the placeholders read. */
    public record Snapshot(int rented, int active, int expired, int featured, int onSale,
                           Map<UUID, OwnerRent> owners, Map<UUID, Long> salesByOwner) {
        static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, Map.of(), Map.of());
    }

    private final VillagerMerchants plugin;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private VmExpansion expansion;
    private BukkitTask task;

    private PapiHook(VillagerMerchants plugin) {
        this.plugin = plugin;
    }

    /** Registers the %vm_...% expansion. Call only when PlaceholderAPI is installed and enabled. */
    public static PapiHook register(VillagerMerchants plugin) {
        PapiHook hook = new PapiHook(plugin);
        hook.refresh();
        hook.startTask();
        hook.expansion = new VmExpansion(plugin, hook);
        hook.expansion.register();
        plugin.getLogger().info("PlaceholderAPI found: registered %vm_...% placeholders.");
        return hook;
    }

    public void unregister() {
        if (task != null) task.cancel();
        task = null;
        if (expansion != null) expansion.unregister();
        expansion = null;
    }

    /** Re-reads placeholders.refresh-seconds (called from /vm reload). */
    public void reload() {
        if (task != null) task.cancel();
        refresh();
        startTask();
    }

    public Snapshot snapshot() { return snapshot; }

    private void startTask() {
        long ticks = Math.max(1, plugin.getConfig().getLong("placeholders.refresh-seconds", 5)) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, ticks, ticks);
    }

    /** Rebuilds the snapshot. Main thread only: it reads the live shop map. */
    private void refresh() {
        var shops = plugin.getShopManager();
        var tx = plugin.getTransactions();
        if (shops == null || tx == null) return;

        long now = System.currentTimeMillis();
        int rented = 0, active = 0, expired = 0, featured = 0, onSale = 0;
        Map<UUID, long[]> perOwner = new HashMap<>();   // {shop count, soonest expiry}

        for (Shop s : shops.allShops()) {
            rented++;
            if (s.isActive(now)) active++; else expired++;
            if (s.isFeatured(now)) featured++;
            if (s.isOnSale(now)) onSale++;
            long[] o = perOwner.computeIfAbsent(s.getOwner(), k -> new long[]{0, Long.MAX_VALUE});
            o[0]++;
            o[1] = Math.min(o[1], s.getRentExpiry());
        }

        Map<UUID, OwnerRent> owners = new HashMap<>();
        perOwner.forEach((id, o) -> owners.put(id, new OwnerRent((int) o[0], o[1])));
        snapshot = new Snapshot(rented, active, expired, featured, onSale,
                Map.copyOf(owners), Map.copyOf(tx.salesByOwner()));
    }
}
