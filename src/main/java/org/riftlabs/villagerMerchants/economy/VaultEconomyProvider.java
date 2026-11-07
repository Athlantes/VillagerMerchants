package org.riftlabs.villagerMerchants.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

/** The server's main money (any Vault economy). Everything that is not explicitly premium uses this. */
public final class VaultEconomyProvider implements EconomyProvider {
    private final Economy econ;

    public VaultEconomyProvider(Economy econ) { this.econ = econ; }

    @Override public String id() { return Price.VAULT; }
    @Override public String displayName() { return "Money"; }
    @Override public boolean isAvailable() { return econ != null; }
    @Override public boolean isWholeNumbers() { return false; }

    @Override public double balance(OfflinePlayer p) { return econ.getBalance(p); }
    @Override public boolean has(OfflinePlayer p, double amount) { return econ.has(p, amount); }

    @Override public boolean withdraw(OfflinePlayer p, double amount) {
        return amount <= 0 || econ.withdrawPlayer(p, amount).transactionSuccess();
    }

    @Override public boolean deposit(OfflinePlayer p, double amount) {
        return amount <= 0 || econ.depositPlayer(p, amount).transactionSuccess();
    }

    @Override public String format(double amount) { return econ.format(amount); }
}
