package org.riftlabs.villagerMerchants.util;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Item matching for shops. Two stacks are "the same offer" if Bukkit says they are similar, or if both are the
 * same ItemsAdder / Oraxen / MMOItems item with the same vanilla damage and enchantments (so rolled stats or lore
 * do not split one product into many).
 * It never rejects a pair that isSimilar accepts, so vanilla behaviour is unchanged.
 *
 * Because matching is looser than isSimilar, goods are moved as the ACTUAL stacks that were in the inventory
 * (take), never as a copy of the listing template. That is what keeps each item's own NBT intact when it changes hands.
 */
public final class ItemMatch {
    private ItemMatch() {}

    public static boolean same(ItemStack a, ItemStack b) {
        if (a == null || b == null) return a == b;
        if (a.isSimilar(b)) return true;
        if (a.getType() != b.getType()) return false;          // cheap filter before any plugin lookup
        String ka = CustomItems.keyOf(a);
        // same custom item id, but a worn or differently enchanted copy is NOT the same product
        return ka != null && ka.equals(CustomItems.keyOf(b)) && sameWear(a, b);
    }

    /** Vanilla damage and enchantments must agree; anything else (rolled stats, lore) may differ. */
    private static boolean sameWear(ItemStack a, ItemStack b) {
        ItemMeta ma = a.getItemMeta(), mb = b.getItemMeta();
        if (ma == null || mb == null) return false;
        int da = ma instanceof Damageable d ? d.getDamage() : 0;
        int db = mb instanceof Damageable d ? d.getDamage() : 0;
        return da == db && ma.getEnchants().equals(mb.getEnchants());
    }

    public static int count(Inventory inv, ItemStack template) {
        int n = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s != null && same(s, template)) n += s.getAmount();
        }
        return n;
    }

    /** Removes up to {@code amount} matching items and returns the actual stacks removed (clones, exact NBT). */
    public static List<ItemStack> take(Inventory inv, ItemStack template, int amount) {
        List<ItemStack> out = new ArrayList<>();
        int left = amount;
        for (int i = 0; i < inv.getSize() && left > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (s == null || !same(s, template)) continue;
            int n = Math.min(left, s.getAmount());
            ItemStack part = s.clone();
            part.setAmount(n);
            out.add(part);
            if (n == s.getAmount()) {
                inv.setItem(i, null);
            } else {
                s.setAmount(s.getAmount() - n);
                inv.setItem(i, s);
            }
            left -= n;
        }
        return out;
    }

    /** What to record as the item type in the transaction log: the custom item id, else the material name. */
    public static String logType(ItemStack item) {
        String k = CustomItems.keyOf(item);
        return k != null ? k : item.getType().name();
    }
}
