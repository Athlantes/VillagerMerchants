package org.riftlabs.villagerMerchants.model;

import org.bukkit.inventory.ItemStack;

/**
 * One offer. Either item-priced (pay1 [+ pay2]) or money-priced (price > 0, no payment items).
 */
public class Trade {
    private ItemStack pay1, pay2, result;
    /** &gt; 0 means the customer pays this amount from their Vault balance instead of items. */
    private double price;

    public ItemStack getPay1() { return pay1; }
    public ItemStack getPay2() { return pay2; }
    public ItemStack getResult() { return result; }
    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = Math.max(0, price); }
    public boolean isMoney() { return price > 0; }

    /** row 0 = payment 1, row 1 = payment 2, row 2 = result (matches the editor GUI). */
    public ItemStack get(int row) {
        return switch (row) { case 0 -> pay1; case 1 -> pay2; default -> result; };
    }

    public void set(int row, ItemStack item) {
        switch (row) {
            case 0 -> pay1 = item;
            case 1 -> pay2 = item;
            default -> result = item;
        }
    }

    public boolean isEmpty() { return pay1 == null && pay2 == null && result == null && price <= 0; }
    public boolean isComplete() { return result != null && (price > 0 || pay1 != null); }
}
