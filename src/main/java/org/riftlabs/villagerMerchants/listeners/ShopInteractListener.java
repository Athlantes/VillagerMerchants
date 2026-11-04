package org.riftlabs.villagerMerchants.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.gui.OwnerMenuGui;
import org.riftlabs.villagerMerchants.gui.RentGui;
import org.riftlabs.villagerMerchants.model.Shop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShopInteractListener implements Listener {
    private final VillagerMerchants plugin;
    /** Last tick each player opened a shop: a click can reach us twice (vanilla event + ModelEngine hitbox event). */
    private final Map<UUID, Integer> lastOpen = new HashMap<>();

    public ShopInteractListener(VillagerMerchants plugin) { this.plugin = plugin; }

    public boolean isShopNpc(Entity e) { return plugin.getNpcs().isManaged(e); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onShopInteract(PlayerInteractEntityEvent event) {
        if (!isShopNpc(event.getRightClicked())) return;
        event.setCancelled(true); // never open vanilla trading / mounting / Mythic click skills, for either hand
        if (event.getHand() != EquipmentSlot.HAND) return;
        handleClick(event.getPlayer(), event.getRightClicked());
    }

    /** Opens the right menu for a click on a shop NPC. Also called by the ModelEngine hitbox bridge. */
    public void handleClick(Player player, Entity npc) {
        int now = Bukkit.getCurrentTick();
        Integer last = lastOpen.put(player.getUniqueId(), now);
        if (last != null && now - last < 3) return;

        var sm = plugin.getShopManager();
        Shop shop = sm.getShopByEntity(npc.getUniqueId());
        sm.refreshNameTag(npc);

        if (!plugin.access().canUse(player)) {
            plugin.msg().send(player, "misc.no-permission");
            return;
        }

        if (shop != null) {
            var use = plugin.getProtection().checkUse(player, npc.getLocation());
            if (!use.allowed()) { use.send(plugin.msg(), player); return; }
        }

        if (shop == null) {
            if (!plugin.access().canRent(player)) {
                plugin.msg().send(player, "shop.rent.no-rent-permission");
                return;
            }
            var verdict = plugin.getProtection().checkRent(player, npc.getLocation());
            if (!verdict.allowed()) { verdict.send(plugin.msg(), player); return; }
            new RentGui(plugin, player, npc.getUniqueId()).open();
        } else if (shop.isMember(player.getUniqueId()) && !player.isSneaking()) {
            new OwnerMenuGui(plugin, player, shop).open();   // owner or staff; sneak = preview as a customer
        } else if (!shop.isActive(System.currentTimeMillis())) {
            plugin.msg().send(player, "customer.shop-closed");
        } else {
            plugin.getLeaderboards().recordVisit(player, shop);
            plugin.getCustomerTrades().open(player, shop);
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        plugin.getNpcs().onWorldLoad(e.getWorld());
    }

    /** Binds bodies that persisted through a restart, and re-creates the ones whose chunk loads without them. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        plugin.getNpcs().onEntitiesLoad(e.getChunk(), e.getEntities());
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        plugin.getNpcs().onEntitiesUnload(e.getEntities());
    }

    /** A linked MythicMob that gets killed anyway (void, /kill ...) comes back after a moment. */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (isShopNpc(e.getEntity())) plugin.getNpcs().onBodyGone(e.getEntity());
    }
}
