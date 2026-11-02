package org.riftlabs.villagerMerchants.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * The "virtual currency item". The native trading UI can only show items as ingredients, so a
 * money-priced trade displays this token as its price. Players never receive one: the trade is
 * paid from their Vault balance. The PDC tag lets us recognise (and remove) a stray token.
 */
public final class CurrencyToken {
    private static NamespacedKey key;

    private CurrencyToken() {}

    public static void init(Plugin plugin) { key = new NamespacedKey(plugin, "currency_token"); }

    public static ItemStack create(Material material, String priceText) {
        ItemStack it = org.riftlabs.villagerMerchants.VillagerMerchants.getInstance().msg()
                .icon(material, "token", "price", priceText);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    public static boolean isToken(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Safety net: removes any token from the player's inventory and cursor. */
    public static void scrub(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (isToken(contents[i])) { inv.setItem(i, null); changed = true; }
        }
        if (isToken(p.getItemOnCursor())) { p.setItemOnCursor(null); changed = true; }
        if (changed) p.updateInventory();
    }
}
