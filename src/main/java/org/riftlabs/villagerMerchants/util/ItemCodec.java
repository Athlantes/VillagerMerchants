package org.riftlabs.villagerMerchants.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** ItemStack[] <-> Base64 (Paper's native, version-safe item bytes). */
public final class ItemCodec {
    private ItemCodec() {}

    public static String encode(ItemStack[] items) {
        if (items == null || items.length == 0) return "";
        ItemStack[] safe = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            safe[i] = (items[i] == null) ? new ItemStack(Material.AIR) : items[i];
        }
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(safe));
    }

    public static ItemStack[] decode(String b64) {
        if (b64 == null || b64.isEmpty()) return new ItemStack[0];
        ItemStack[] arr = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(b64));
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null || arr[i].getType().isAir()) arr[i] = null;
        }
        return arr;
    }
}
