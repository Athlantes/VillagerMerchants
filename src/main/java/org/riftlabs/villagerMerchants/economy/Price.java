package org.riftlabs.villagerMerchants.economy;

/** An amount in one specific currency. Build prices with {@link EconomyManager#price(String, double)}. */
public record Price(String currency, double amount) {
    public static final String VAULT = "vault";

    public static Price vault(double amount) { return new Price(VAULT, amount); }

    public boolean isFree() { return amount <= 0; }

    public boolean isVault() { return VAULT.equals(currency); }
}
