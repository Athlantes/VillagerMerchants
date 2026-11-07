package org.riftlabs.villagerMerchants.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registry of every currency VillagerMerchants can charge in, and the one place that takes money from a player.
 * <p>
 * Rule of thumb: player-to-player trades, rent, teleports and featured listings stay on Vault. Only the things an
 * admin gives a {@code currency:} option to (skins, nametags, effects, upgrades) can be paid in another currency.
 */
public final class EconomyManager {
    private final VillagerMerchants plugin;
    private final Economy vaultEconomy;
    private final Map<String, EconomyProvider> providers = new LinkedHashMap<>();

    public EconomyManager(VillagerMerchants plugin, Economy vaultEconomy) {
        this.plugin = plugin;
        this.vaultEconomy = vaultEconomy;
        reload();
    }

    /** Rebuilds the provider list from config.yml. Called on startup and by /vm reload. */
    public void reload() {
        providers.clear();
        register(new VaultEconomyProvider(vaultEconomy));
        register(new PlayerPointsProvider(plugin));
        register(new TokenManagerProvider(plugin));

        StringBuilder sb = new StringBuilder();
        for (EconomyProvider p : providers.values()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.id()).append(p.isAvailable() ? " (ready)" : " (not available)");
        }
        plugin.getLogger().info("Currencies: " + sb);
    }

    /** Add your own provider here (or from another plugin) to support one more currency. */
    public void register(EconomyProvider provider) { providers.put(provider.id(), provider); }

    public EconomyProvider vault() { return providers.get(Price.VAULT); }

    /** The provider for this id (aliases accepted), or null if unknown. It may exist but not be {@link EconomyProvider#isAvailable() available}. */
    public EconomyProvider get(String currency) { return providers.get(canonical(currency)); }

    /** "points" / "pp" -> playerpoints, "tokens" -> tokenmanager, blank / "money" -> vault. */
    public static String canonical(String raw) {
        if (raw == null) return Price.VAULT;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "", "vault", "money", "default" -> Price.VAULT;
            case "playerpoints", "points", "pp" -> "playerpoints";
            case "tokenmanager", "tokens", "tm" -> "tokenmanager";
            default -> s;
        };
    }

    /** A price in the named currency, already rounded to what that currency can charge. */
    public Price price(String currency, double amount) {
        String id = canonical(currency);
        EconomyProvider p = providers.get(id);
        return new Price(id, p == null ? Math.max(0, amount) : p.normalize(amount));
    }

    public Price vaultPrice(double amount) { return price(Price.VAULT, amount); }

    /** "$5,000.00" or "250 Points". Works even if the currency's plugin is missing, so menus never break. */
    public String format(Price price) {
        EconomyProvider p = providers.get(price.currency());
        if (p != null && p.isAvailable()) return p.format(price.amount());
        if (p == null) return String.format(Locale.US, "%,.0f %s", price.amount(), price.currency());
        return String.format(Locale.US, "%,.0f %s", price.amount(), p.displayName());
    }

    /**
     * Takes the price from the player and tells them why if it can't. Nothing is paid to anyone: a pure sink,
     * exactly like the Vault charges this plugin already had. Returns true when the player has paid.
     */
    public boolean charge(Player player, Price price) {
        if (price.isFree()) return true;
        var m = plugin.msg();
        EconomyProvider p = providers.get(price.currency());
        if (p == null || !p.isAvailable()) {
            m.send(player, "economy.currency-unavailable",
                    "currency", p == null ? price.currency() : p.displayName());
            return false;
        }
        if (!p.has(player, price.amount())) {
            m.send(player, "economy.not-enough", "amount", format(price));
            return false;
        }
        if (!p.withdraw(player, price.amount())) {
            m.send(player, "economy.payment-failed");
            return false;
        }
        return true;
    }

    /** Gives a price back (refunds). */
    public boolean refund(Player player, Price price) {
        if (price.isFree()) return true;
        EconomyProvider p = providers.get(price.currency());
        return p != null && p.isAvailable() && p.deposit(player, price.amount());
    }
}
