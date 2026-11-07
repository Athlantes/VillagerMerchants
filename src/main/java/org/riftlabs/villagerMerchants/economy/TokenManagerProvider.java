package org.riftlabs.villagerMerchants.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * TokenManager (Realized). Its API works on online players, which is always the case here: every charge comes from
 * a menu the player has open. Offline players simply can't pay or be refunded in tokens.
 */
public final class TokenManagerProvider extends PointsProvider {
    private Plugin tm;
    private Method getTokens, addTokens, removeTokens;

    public TokenManagerProvider(VillagerMerchants plugin) {
        super(plugin, "tokenmanager", "TokenManager", "Tokens");
    }

    @Override
    protected boolean bind(Plugin live) throws ReflectiveOperationException {
        tm = live;
        getTokens = live.getClass().getMethod("getTokens", Player.class);
        addTokens = live.getClass().getMethod("addTokens", Player.class, long.class);
        removeTokens = live.getClass().getMethod("removeTokens", Player.class, long.class);
        return true;
    }

    private static Player online(OfflinePlayer p) {
        return p instanceof Player pl ? pl : Bukkit.getPlayer(p.getUniqueId());
    }

    private static long read(Object v) {
        if (v instanceof OptionalLong o) return o.isPresent() ? o.getAsLong() : -1;
        if (v instanceof Optional<?> o) return o.map(x -> x instanceof Number n ? n.longValue() : -1L).orElse(-1L);
        return v instanceof Number n ? n.longValue() : -1;
    }

    @Override
    public double balance(OfflinePlayer p) {
        Player pl = online(p);
        if (pl == null || live() == null) return 0;
        return Math.max(0, read(call(getTokens, tm, pl)));
    }

    @Override
    public boolean withdraw(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        Player pl = online(p);
        if (pl == null || live() == null || !has(pl, amount)) return false;
        Object r = call(removeTokens, tm, pl, units(amount));
        return !(r instanceof Boolean b) || b;   // older builds return void, newer ones a boolean
    }

    @Override
    public boolean deposit(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        Player pl = online(p);
        if (pl == null || live() == null) return false;
        Object r = call(addTokens, tm, pl, units(amount));
        return !(r instanceof Boolean b) || b;
    }
}
