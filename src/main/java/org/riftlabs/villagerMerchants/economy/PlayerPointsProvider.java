package org.riftlabs.villagerMerchants.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.lang.reflect.Method;
import java.util.UUID;

/** PlayerPoints (rosewood): {@code PlayerPoints#getAPI()} with look / take / give on a UUID. */
public final class PlayerPointsProvider extends PointsProvider {
    private Object api;
    private Method look, take, give;

    public PlayerPointsProvider(VillagerMerchants plugin) {
        super(plugin, "playerpoints", "PlayerPoints", "Points");
    }

    @Override
    protected boolean bind(Plugin live) throws ReflectiveOperationException {
        api = live.getClass().getMethod("getAPI").invoke(live);
        if (api == null) return false;
        look = api.getClass().getMethod("look", UUID.class);
        take = api.getClass().getMethod("take", UUID.class, int.class);
        give = api.getClass().getMethod("give", UUID.class, int.class);
        return true;
    }

    @Override
    public double balance(OfflinePlayer p) {
        if (live() == null) return 0;
        Object v = call(look, api, p.getUniqueId());
        return v instanceof Number n ? n.doubleValue() : 0;
    }

    @Override
    public boolean withdraw(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        if (live() == null || !has(p, amount)) return false;
        long u = units(amount);
        if (u > Integer.MAX_VALUE) return false;
        return Boolean.TRUE.equals(call(take, api, p.getUniqueId(), (int) u));
    }

    @Override
    public boolean deposit(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        if (live() == null) return false;
        long u = units(amount);
        if (u > Integer.MAX_VALUE) return false;
        return Boolean.TRUE.equals(call(give, api, p.getUniqueId(), (int) u));
    }
}
