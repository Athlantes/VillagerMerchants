package org.riftlabs.villagerMerchants.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.analytics.DashboardManager;
import org.riftlabs.villagerMerchants.util.Sched;

/**
 * Keeps the dashboard map honest: clicks flip pages, and anything that could move or lose the map first closes the
 * dashboard, which hands the player's original hotbar item back. All of these run on the player's own thread.
 */
public final class DashboardListener implements Listener {
    private final VillagerMerchants plugin;

    public DashboardListener(VillagerMerchants plugin) { this.plugin = plugin; }

    private DashboardManager dash() { return plugin.getDashboard(); }

    private boolean viewing(Player p) {
        DashboardManager d = dash();
        return d != null && d.isViewing(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!viewing(p)) return;
        boolean forward = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (dash().click(p, forward, p.isSneaking())) {
            e.setCancelled(true);
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    /** Right-clicking an NPC, frame or animal would use the map item on it: close first and swallow the click. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        if (!viewing(p)) return;
        e.setCancelled(true);
        dash().close(p, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && viewing(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent e) {
        if (viewing(e.getPlayer())) dash().close(e.getPlayer(), true);   // scrolling away puts it back in the pocket
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (dash() != null && dash().isDashboardItem(e.getItemDrop().getItemStack())) {
            // The server has already taken the map out of the slot when this fires and puts it back once the
            // event is cancelled. Closing right now would hand the original item over first and the map would then
            // land on top of it, so close on the next tick, when the slot is settled again.
            e.setCancelled(true);
            Sched.entityNext(p, () -> dash().close(p, true));    // Q closes
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!viewing(p)) return;
        e.setCancelled(true);
        dash().close(p, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !viewing(p)) return;
        e.setCancelled(true);
        dash().close(p, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p) || !viewing(p)) return;
        e.setCancelled(true);
        dash().close(p, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p && viewing(p)) dash().close(p, false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent e) {
        if (dash() != null) dash().onDeath(e.getEntity(), e.getDrops(), e.getKeepInventory());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) {
        if (dash() != null) dash().close(e.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        if (dash() != null) dash().heal(e.getPlayer());
    }
}
