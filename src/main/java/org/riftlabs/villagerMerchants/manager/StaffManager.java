package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.gui.ShopBound;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.StaffRole;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Staff and co-owners. Only the shop's owner can change the staff list. Staff keep working with the owner's
 * upgrades (slots, rent discount) because those belong to the shop, not to whoever happens to be editing it.
 */
public class StaffManager {
    private final VillagerMerchants plugin;

    public StaffManager(VillagerMerchants plugin) { this.plugin = plugin; }

    public int maxStaff() { return Math.max(0, plugin.getConfig().getInt("staff.max-per-shop", 5)); }
    public boolean enabled() { return plugin.getConfig().getBoolean("staff.enabled", true); }

    /** Finds an online player or one the server has seen before; null if unknown. */
    public OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return cached;
    }

    /** @return null on success, otherwise an error message (MiniMessage). */
    public String add(Player actor, Shop shop, OfflinePlayer target, StaffRole role) {
        if (!enabled()) return "<red>Shop staff is disabled on this server.";
        if (!shop.isOwner(actor.getUniqueId())) return "<red>Only the shop owner can manage staff.";
        if (!actor.hasPermission("villagermerchants.staff")) return "<red>You don't have permission to hire staff.";
        UUID id = target.getUniqueId();
        if (shop.isOwner(id)) return "<red>That's you - you already own this shop.";
        if (shop.getStaff().containsKey(id)) return "<red>" + Text.esc(nameOf(target)) + " is already on your staff. Change their role instead.";
        if (shop.getStaff().size() >= maxStaff()) return "<red>You can have at most " + maxStaff() + " staff members per shop.";

        shop.setStaff(id, role);
        plugin.getShopManager().saveShop(shop);
        Player online = target.getPlayer();
        if (online != null) {
            online.sendMessage(Text.mm("<green>" + Text.esc(actor.getName()) + " made you <yellow>" + role.label()
                    + "</yellow> of their shop. Right-click the shop villager to work there."));
        }
        return null;
    }

    public String remove(Player actor, Shop shop, UUID target) {
        if (!shop.isOwner(actor.getUniqueId())) return "<red>Only the shop owner can manage staff.";
        if (!shop.removeStaff(target)) return "<red>That player isn't on your staff.";
        plugin.getShopManager().saveShop(shop);
        kickFromMenus(shop, target);
        Player online = Bukkit.getPlayer(target);
        if (online != null) online.sendMessage(Text.mm("<yellow>You are no longer staff at " + Text.esc(plugin.getShopManager().ownerName(shop.getOwner())) + "'s shop."));
        return null;
    }

    public String setRole(Player actor, Shop shop, UUID target, StaffRole role) {
        if (!shop.isOwner(actor.getUniqueId())) return "<red>Only the shop owner can manage staff.";
        if (!shop.getStaff().containsKey(target)) return "<red>That player isn't on your staff.";
        shop.setStaff(target, role);
        plugin.getShopManager().saveShop(shop);
        kickFromMenus(shop, target);   // reopening shows the menu for the new role
        Player online = Bukkit.getPlayer(target);
        if (online != null) online.sendMessage(Text.mm("<yellow>Your role at " + Text.esc(plugin.getShopManager().ownerName(shop.getOwner())) + "'s shop is now <gold>" + role.label() + "</gold>."));
        return null;
    }

    /** Closes any of this shop's menus the player has open, so a demotion or removal takes effect immediately. */
    private void kickFromMenus(Shop shop, UUID target) {
        Player p = Bukkit.getPlayer(target);
        if (p == null) return;
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof ShopBound sb && sb.shop() == shop) p.closeInventory();
    }

    /** Shops where this player works as staff (not as owner). */
    public java.util.List<Shop> shopsStaffedBy(UUID player) {
        java.util.List<Shop> out = new ArrayList<>();
        for (Shop s : plugin.getShopManager().allShops()) if (s.getStaff().containsKey(player)) out.add(s);
        return out;
    }

    public static String nameOf(OfflinePlayer p) { return p.getName() == null ? "Unknown" : p.getName(); }
}
