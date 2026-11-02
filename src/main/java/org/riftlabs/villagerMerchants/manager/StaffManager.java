package org.riftlabs.villagerMerchants.manager;

import net.kyori.adventure.text.Component;
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

    /** @return null on success, otherwise the error to show (ready for chat). */
    public Component add(Player actor, Shop shop, OfflinePlayer target, StaffRole role) {
        var m = plugin.msg();
        if (!enabled()) return m.get("staff.error.disabled");
        if (!shop.isOwner(actor.getUniqueId())) return m.get("staff.error.owner-only");
        if (!actor.hasPermission("villagermerchants.staff")) return m.get("staff.error.no-permission");
        UUID id = target.getUniqueId();
        if (shop.isOwner(id)) return m.get("staff.error.self");
        if (shop.getStaff().containsKey(id)) return m.get("staff.error.already-staff", "player", nameOf(target));
        if (shop.getStaff().size() >= maxStaff()) return m.get("staff.error.full", "max", maxStaff());

        shop.setStaff(id, role);
        plugin.getShopManager().saveShop(shop);
        Player online = target.getPlayer();
        if (online != null) {
            m.send(online, "staff.notice.added", "actor", actor.getName(), "role", role.label());
        }
        return null;
    }

    public Component remove(Player actor, Shop shop, UUID target) {
        var m = plugin.msg();
        if (!shop.isOwner(actor.getUniqueId())) return m.get("staff.error.owner-only");
        if (!shop.removeStaff(target)) return m.get("staff.error.not-staff");
        plugin.getShopManager().saveShop(shop);
        kickFromMenus(shop, target);
        Player online = Bukkit.getPlayer(target);
        if (online != null) m.send(online, "staff.notice.removed", "owner", plugin.getShopManager().ownerName(shop.getOwner()));
        return null;
    }

    public Component setRole(Player actor, Shop shop, UUID target, StaffRole role) {
        var m = plugin.msg();
        if (!shop.isOwner(actor.getUniqueId())) return m.get("staff.error.owner-only");
        if (!shop.getStaff().containsKey(target)) return m.get("staff.error.not-staff");
        shop.setStaff(target, role);
        plugin.getShopManager().saveShop(shop);
        kickFromMenus(shop, target);   // reopening shows the menu for the new role
        Player online = Bukkit.getPlayer(target);
        if (online != null) m.send(online, "staff.notice.role-changed", "owner", plugin.getShopManager().ownerName(shop.getOwner()), "role", role.label());
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

    public static String nameOf(OfflinePlayer p) {
        return p.getName() == null ? VillagerMerchants.getInstance().msg().raw("misc.unknown") : p.getName();
    }
}
