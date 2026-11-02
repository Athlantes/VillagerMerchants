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
import org.riftlabs.villagerMerchants.util.Text;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Teleports players to a shop from the /shops directory: warm-up, cooldown, optional cost, safe landing spot. */
public class TeleportManager {
    private static final Set<Material> BAD_FLOOR = EnumSet.of(Material.MAGMA_BLOCK, Material.CACTUS,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.LAVA);
    private static final Set<Material> BAD_AIR = EnumSet.of(Material.FIRE, Material.SOUL_FIRE,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.COBWEB, Material.LAVA);

    private final VillagerMerchants plugin;
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public TeleportManager(VillagerMerchants plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    public boolean enabled() { return cfg().getBoolean("directory.teleport.enabled", true); }
    public double cost() { return Math.max(0, cfg().getDouble("directory.teleport.cost", 0.0)); }

    /** Entry point from the directory GUI. */
    public void request(Player p, Shop shop) {
        ShopManager sm = plugin.getShopManager();
        if (!enabled()) { p.sendMessage(Text.mm("<red>Shop teleporting is disabled.")); return; }
        if (!p.hasPermission("villagermerchants.shops.teleport")) {
            p.sendMessage(Text.mm("<red>You don't have permission to teleport to shops."));
            return;
        }
        if (!sm.isLive(shop) || !shop.isActive(System.currentTimeMillis())) {
            p.sendMessage(Text.mm("<red>That shop is closed."));
            return;
        }
        Location target = parse(shop.getLocation());
        if (target == null) {
            p.sendMessage(Text.mm("<red>That shop's location isn't available right now."));
            return;
        }

        boolean bypass = p.hasPermission("villagermerchants.shops.bypass");
        if (!bypass) {
            long left = cooldownUntil.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
            if (left > 0) {
                p.sendMessage(Text.mm("<red>Please wait " + Math.max(1, (left + 999) / 1000) + "s before teleporting again."));
                return;
            }
            double cost = cost();
            if (cost > 0 && !plugin.getEconomy().has(p, cost)) {
                p.sendMessage(Text.mm("<red>You need " + sm.money(cost) + " to teleport."));
                return;
            }
        }

        cancelPending(p.getUniqueId());
        int warmup = bypass ? 0 : Math.max(0, cfg().getInt("directory.teleport.warmup-seconds", 3));
        if (warmup <= 0) { go(p, shop, target, bypass); return; }

        final Location start = p.getLocation();
        final int[] waited = {0};
        p.sendMessage(Text.mm("<yellow>Teleporting in " + warmup + " seconds. Don't move!"));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            @Override public void run() {
                if (!p.isOnline()) { cancelPending(p.getUniqueId()); return; }
                Location now = p.getLocation();
                if (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 0.5) {
                    cancelPending(p.getUniqueId());
                    p.sendMessage(Text.mm("<red>Teleport cancelled because you moved."));
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
                    p.sendMessage(Text.mm("<red>Couldn't load that area. Try again.")));
            return null;
        });
    }

    private void finish(Player p, Shop shop, Location target, boolean bypass) {
        if (!p.isOnline()) return;
        ShopManager sm = plugin.getShopManager();
        if (!sm.isLive(shop) || !shop.isActive(System.currentTimeMillis())) {
            p.sendMessage(Text.mm("<red>That shop closed while you were waiting."));
            return;
        }
        Location spot = findSpot(target);
        if (spot == null) {
            p.sendMessage(Text.mm("<red>Couldn't find a safe spot next to that shop."));
            return;
        }

        final double cost = bypass ? 0 : cost();
        if (cost > 0) {
            Economy econ = plugin.getEconomy();
            if (!econ.has(p, cost) || !econ.withdrawPlayer(p, cost).transactionSuccess()) {
                p.sendMessage(Text.mm("<red>You need " + sm.money(cost) + " to teleport."));
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
                p.sendMessage(Text.mm("<green>Welcome to <gold>" + Text.esc(owner) + "'s Shop</gold>!"
                        + (cost > 0 ? " <gray>(-" + sm.money(cost) + ")" : "")));
            } else {
                if (cost > 0) plugin.getEconomy().depositPlayer(p, cost); // teleport failed: give the money back
                p.sendMessage(Text.mm("<red>The teleport failed."));
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
                if (BAD_AIR.contains(feet.getType()) || BAD_AIR.contains(head.getType())) continue;
                if (!floor.getType().isSolid() || BAD_FLOOR.contains(floor.getType())) continue;

                double x = feet.getX() + 0.5, z = feet.getZ() + 0.5;
                double dx = npc.getX() - x, dz = npc.getZ() - z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                return new Location(w, x, feet.getY(), z, yaw, 0f);
            }
        }
        return null;
    }
}
