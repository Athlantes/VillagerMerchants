package org.riftlabs.villagerMerchants.papi;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.Locale;
import java.util.UUID;

/**
 * %vm_<placeholder>% expansion.
 *
 * Server-wide:
 *   active_shops         shops with paid-up rent
 *   rented_shops         every rented shop, including ones in their grace period
 *   expired_shops        rented shops whose rent ran out (still in the grace period)
 *   featured_shops       shops currently in the featured listing
 *   shops_on_sale        shops running a sale right now
 *   total_sales          trades ever completed on the server
 *   total_revenue        money ever earned by all shops, formatted by your economy plugin
 *   total_revenue_raw    the same as a plain number (1234.5)
 *
 * Per player:
 *   rent_status          Active / Expiring / Expired / None (wording from placeholders.status in config.yml)
 *   rent_time_left       time until the player's soonest rent expires, e.g. "2d 3h" (or "expired" / "-" with no shop)
 *   rent_days_left       the same in whole days (0 when expired, empty with no shop)
 *   owned_shops          how many shops the player has rented
 *   my_sales             trades the player has ever made through their shops
 */
public final class VmExpansion extends PlaceholderExpansion {

    private static final long DAY_MS = 86_400_000L;

    private final VillagerMerchants plugin;
    private final PapiHook hook;

    public VmExpansion(VillagerMerchants plugin, PapiHook hook) {
        this.plugin = plugin;
        this.hook = hook;
    }

    @Override public @NotNull String getIdentifier() { return "vm"; }
    @Override public @NotNull String getAuthor() { return String.join(", ", plugin.getPluginMeta().getAuthors()); }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public String getRequiredPlugin() { return plugin.getName(); }
    @Override public boolean persist() { return true; }   // survive /papi reload

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        PapiHook.Snapshot s = hook.snapshot();
        switch (params.toLowerCase(Locale.ROOT)) {
            // ---- server-wide
            case "active_shops":       return String.valueOf(s.active());
            case "rented_shops":       return String.valueOf(s.rented());
            case "expired_shops":      return String.valueOf(s.expired());
            case "featured_shops":     return String.valueOf(s.featured());
            case "shops_on_sale":      return String.valueOf(s.onSale());
            case "total_sales":        return String.valueOf(plugin.getTransactions().totalSales());
            case "total_revenue":      return plugin.getShopManager().money(plugin.getTransactions().totalMoney());
            case "total_revenue_raw":  return String.format(Locale.ROOT, "%.2f", plugin.getTransactions().totalMoney());
            default: break;
        }

        // ---- per player
        String key = params.toLowerCase(Locale.ROOT);
        if (!key.equals("rent_status") && !key.equals("rent_time_left") && !key.equals("rent_days_left")
                && !key.equals("owned_shops") && !key.equals("my_sales")) {
            return null;   // unknown placeholder: PlaceholderAPI leaves the raw text so typos are easy to spot
        }
        if (player == null) return "";

        UUID id = player.getUniqueId();
        PapiHook.OwnerRent rent = s.owners().get(id);
        long now = System.currentTimeMillis();

        switch (key) {
            case "owned_shops": return String.valueOf(rent == null ? 0 : rent.shops());
            case "my_sales":    return String.valueOf(s.salesByOwner().getOrDefault(id, 0L));
            case "rent_status": return status(rent, now);
            case "rent_time_left":
                return rent == null ? "-" : Text.duration(rent.soonestExpiry() - now);
            case "rent_days_left":
                return rent == null ? "" : String.valueOf(Math.max(0, (rent.soonestExpiry() - now) / DAY_MS));
            default: return null;
        }
    }

    private String status(PapiHook.OwnerRent rent, long now) {
        String k;
        if (rent == null) {
            k = "none";
        } else {
            long left = rent.soonestExpiry() - now;
            long warn = Math.max(0, plugin.getConfig().getLong("placeholders.expiring-hours", 24)) * 3_600_000L;
            k = left <= 0 ? "expired" : left <= warn ? "expiring" : "active";
        }
        String def = switch (k) {
            case "expired" -> "&cExpired";
            case "expiring" -> "&eExpiring";
            case "none" -> "&7None";
            default -> "&aActive";
        };
        return ChatColor.translateAlternateColorCodes('&', plugin.getConfig().getString("placeholders.status." + k, def));
    }
}
