package org.riftlabs.villagerMerchants.util;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public final class InvUtil {
    private InvUtil() {}

    /** A detached copy of the given contents, for "would this fit?" simulations. */
    public static Inventory sim(ItemStack[] contents, int size) {
        Inventory tmp = Bukkit.createInventory(null, size);
        ItemStack[] copy = new ItemStack[size];
        for (int i = 0; i < Math.min(size, contents.length); i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        tmp.setContents(copy);
        return tmp;
    }

    /** Simulates removing 'remove' then adding all 'add' items to a copy of the contents. */
    public static boolean canFit(ItemStack[] contents, int size, ItemStack remove, ItemStack... add) {
        Inventory tmp = sim(contents, size);
        if (remove != null) ItemMatch.take(tmp, remove, remove.getAmount());
        for (ItemStack a : add) {
            if (a != null && !tmp.addItem(a.clone()).isEmpty()) return false;
        }
        return true;
    }
}
