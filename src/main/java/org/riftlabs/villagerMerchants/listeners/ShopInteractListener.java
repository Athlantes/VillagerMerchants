package org.riftlabs.villagerMerchants.listeners;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.gui.OwnerMenuGui;
import org.riftlabs.villagerMerchants.gui.RentGui;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

public class ShopInteractListener implements Listener {
    private final VillagerMerchants plugin;

    public ShopInteractListener(VillagerMerchants plugin) { this.plugin = plugin; }

    private boolean isShopNpc(Entity e) {
        return e instanceof Villager v
                && v.getPersistentDataContainer().has(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onShopInteract(PlayerInteractEntityEvent event) {
        if (!isShopNpc(event.getRightClicked())) return;
        event.setCancelled(true); // never open vanilla trading, for either hand
        if (event.getHand() != EquipmentSlot.HAND) return;

        Villager villager = (Villager) event.getRightClicked();
        Player player = event.getPlayer();
        var sm = plugin.getShopManager();
        Shop shop = sm.getShop(villager.getUniqueId());
        sm.refreshNameTag(villager);

        if (!player.hasPermission("villagermerchants.use")) {
            player.sendMessage(Text.mm("<red>No permission."));
            return;
        }

        if (shop == null) {
            new RentGui(plugin, player, villager.getUniqueId()).open();
        } else if (shop.isOwner(player.getUniqueId()) && !player.isSneaking()) {
            new OwnerMenuGui(plugin, player, shop).open();   // sneak = preview as a customer
        } else if (!shop.isActive(System.currentTimeMillis())) {
            player.sendMessage(Text.mm("<gray>This shop is closed."));
        } else {
            plugin.getCustomerTrades().open(player, shop);
        }
    }

    /** Keep name tags in sync after chunk loads (e.g. rent expired while unloaded). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity ent : e.getEntities()) {
            if (isShopNpc(ent)) plugin.getShopManager().refreshNameTag((Villager) ent);
        }
    }
}
