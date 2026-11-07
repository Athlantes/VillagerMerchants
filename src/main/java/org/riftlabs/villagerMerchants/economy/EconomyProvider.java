package org.riftlabs.villagerMerchants.economy;

import org.bukkit.OfflinePlayer;

/**
 * One currency a player can pay with. Vault is the default; secondary currencies (PlayerPoints, TokenManager)
 * plug in behind the same interface, so the purchase code never needs to know which one it is charging.
 * <p>
 * Every method is called on the main server thread.
 */
public interface EconomyProvider {

    /** Stable lower-case id used in config files ("vault", "playerpoints", "tokenmanager"). */
    String id();

    /** Name shown to players ("Points", "Tokens"). */
    String displayName();

    /** True when the backing plugin is installed, enabled and its API could be reached. */
    boolean isAvailable();

    /** True for currencies that only exist in whole units (PlayerPoints, TokenManager). Prices are rounded up. */
    boolean isWholeNumbers();

    /** Current balance, or 0 if it can't be read. */
    double balance(OfflinePlayer player);

    default boolean has(OfflinePlayer player, double amount) {
        return amount <= 0 || balance(player) + 1e-9 >= amount;
    }

    /** Takes {@code amount} from the player. Returns false (and takes nothing) if it could not be taken. */
    boolean withdraw(OfflinePlayer player, double amount);

    /** Gives {@code amount} to the player (refunds). Returns false if it could not be given. */
    boolean deposit(OfflinePlayer player, double amount);

    /** "1,250 Points", "$1,250.00" ... */
    String format(double amount);

    /** Rounds a configured price to what this currency can actually charge. */
    default double normalize(double amount) {
        if (amount <= 0) return 0;
        return isWholeNumbers() ? Math.ceil(Math.round(amount * 100.0) / 100.0) : Math.round(amount * 100.0) / 100.0;
    }
}
