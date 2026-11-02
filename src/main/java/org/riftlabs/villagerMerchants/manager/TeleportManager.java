package org.riftlabs.villagerMerchants.manager;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Messages;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.EnumSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Teleports players to a shop from the /shops directory: warm-up, cooldown, optional cost, safe landing spot. */
public class TeleportManager {
    // Built-in lists, used when directory.teleport.unsafe-floor / unsafe-air are missing from config.yml.
    private static final List<String> DEFAULT_BAD_FLOOR = List.of("MAGMA_BLOCK", "CACTUS", "CAMPFIRE", "SOUL_CAMPFIRE", "LAVA");
    private static final List<String> DEFAULT_BAD_AIR = List.of("FIRE", "SOUL_FIRE", "SWEET_BERRY_BUSH", "WITHER_ROSE",
            "POWDER_SNOW", "COBWEB", "LAVA");

    private final VillagerMerchants plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public TeleportManager(VillagerMerchants plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    public boolean enabled() { return cfg().getBoolean("directory.teleport.enabled", true); }
    public double cost() { return Math.max(0, cfg().getDouble("directory.teleport.cost", 0.0)); }

    private Set<Material> materials(String path, List<String> defaults) {
        List<String> names = cfg().isList(path) ? cfg().getStringList(path) : defaults;
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String n : names) {
            Material m = Material.matchMaterial(n.trim());
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Entry point from the directory GUI. */
    public void request(Player p, Shop shop) {
        ShopManager sm = plugin.getShopManager();
        var m = plugin.msg();
        if (!enabled()) { m.send(p, "teleport.disabled"); return; }
        if (!p.hasPermission("villagermerchants.shops.teleport")) {
            m.send(p, "teleport.no-permission");
            return;
        }
        if (!sm.isLive(shop) || !shop.isActive(System.currentTimeMillis())) {
            m.send(p, "teleport.shop-closed");
            return;
        }
        Location target = parse(shop.getLocation());
        if (target == null) {
            m.send(p, "teleport.no-location");
            return;
        }

        boolean bypass = p.hasPermission("villagermerchants.shops.bypass");
        if (!bypass) {
            long left = cooldownUntil.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                m.send(p, "teleport.cooldown", "seconds", Math.max(1, (left + 999) / 1000));
                return;
            }
            double cost = cost();
            if (cost > 0 && !plugin.getEconomy().has(p, cost)) {
                m.send(p, "teleport.need-money", "amount", sm.money(cost));
                return;
            }
        }

        cancelPending(p.getUniqueId());
        int warmup = bypass ? 0 : Math.max(0, cfg().getInt("directory.teleport.warmup-seconds", 3));
        if (warmup <= 0) { go(p, shop, target, bypass); return; }

        final Location start = p.getLocation();
        final int[] waited = {0};
        final double tolerance = Math.max(0.0, cfg().getDouble("directory.teleport.move-tolerance", 0.7));
        m.send(p, "teleport.warmup", "seconds", warmup);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) { cancelPending(p.getUniqueId()); return; }
                Location now = p.getLocation();
                if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > tolerance * tolerance) {
                    cancelPending(p.getUniqueId());
                    m.send(p, "teleport.cancelled-moved");
                    return;
                }
                waited[0] += 5;
                if (waited[0] >= warmup * 20) {
                    cancelPending(p.getUniqueId());
                    go(p, shop, target, bypass);
                }
            }
        }, 5L, 5L);
        pending.put(p.getUniqueId(), task);
    }

    public void cancelPending(UUID player) {
        BukkitTask t = pending.remove(player);
        if (t != null) t.cancel();
    }

    /** Loads the destination chunk, then (back on the main thread) validates, charges and teleports. */
    private void go(Player p, Shop shop, Location target, boolean bypass) {
        target.getWorld().getChunkAtAsync(target).thenAccept(chunk ->
                Bukkit.getScheduler().runTask(plugin, () -> finish(p, shop, target, bypass))
        ).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () ->
                    plugin.msg().send(p, "teleport.load-failed"));
            return null;
        });
    }

    private void finish(Player p, Shop shop, Location target, boolean bypass) {
        if (!p.isOnline()) return;
        ShopManager sm = plugin.getShopManager();
        if (!sm.isLive(shop) || !shop.isActive(System.currentTimeMillis())) {
            plugin.msg().send(p, "teleport.closed-while-waiting");
            return;
        }
        Location spot = findSpot(target);
        if (spot == null) {
            plugin.msg().send(p, "teleport.no-safe-spot");
            return;
        }

        final double cost = bypass ? 0 : cost();
        if (cost > 0) {
            Economy econ = plugin.getEconomy();
            if (!econ.has(p, cost) || !econ.withdrawPlayer(p, cost).transactionSuccess()) {
                plugin.msg().send(p, "teleport.need-money", "amount", sm.money(cost));
                return;
            }
        }
        if (!bypass) {
            long cd = Math.max(0, cfg().getLong("directory.teleport.cooldown-seconds", 60)) * 1000L;
            cooldownUntil.put(p.getUniqueId(), System.currentTimeMillis() + cd);
        }

        final String owner = sm.ownerName(shop.getOwner());
        p.teleportAsync(spot).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ok) {
                var m = plugin.msg();
                m.send(p, "teleport.welcome", "owner", owner,
                        "cost", cost > 0 ? m.ref("teleport.welcome-cost", "cost", Text.esc(sm.money(cost))) : Messages.parsed(""));
            } else {
                if (cost > 0) plugin.getEconomy().depositPlayer(p, cost); // teleport failed: give the money back
                plugin.msg().send(p, "teleport.failed");
            }
        }));
    }

    /** "world;x;y;z" (as stored on the shop) -> Location, or null if unknown / world not loaded. */
    private Location parse(String raw) {
        if (raw == null) return null;
        String[] p = raw.split(";");
        if (p.length < 4) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        try {
            return new Location(w,
                    Double.parseDouble(p[1]),
                    Double.parseDouble(p[2]),
                    Double.parseDouble(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Finds a standable spot a block or two away from the NPC and turns the player to face it. */
    private Location findSpot(Location npc) {
        Set<Material> badFloor = materials("directory.teleport.unsafe-floor", DEFAULT_BAD_FLOOR);
        Set<Material> badAir = materials("directory.teleport.unsafe-air", DEFAULT_BAD_AIR);
        World w = npc.getWorld();
        int bx = npc.getBlockX(), by = npc.getBlockY(), bz = npc.getBlockZ();
        int[][] offsets = {{0, 2}, {2, 0}, {0, -2}, {-2, 0}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2},
                {0, 1}, {1, 0}, {0, -1}, {-1, 0}, {0, 3}, {3, 0}, {0, -3}, {-3, 0}};
        for (int[] o : offsets) {
            for (int dy : new int[]{0, 1, -1}) {
                Block feet = w.getBlockAt(bx + o[0], by + dy, bz + o[1]);
                Block head = feet.getRelative(0, 1, 0);
                Block floor = feet.getRelative(0, -1, 0);
                if (!feet.isPassable() || !head.isPassable()) continue;
                if (feet.isLiquid() || head.isLiquid()) continue;
                if (badAir.contains(feet.getType()) || badAir.contains(head.getType())) continue;
                if (!floor.getType().isSolid() || badFloor.contains(floor.getType())) continue;

                double x = feet.getX() + 0.5, z = feet.getZ() + 0.5;
                double dx = npc.getX() - x, dz = npc.getZ() - z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                return new Location(w, x, feet.getY(), z, yaw, 0f);
            }
        }
        return null;
    }
}
