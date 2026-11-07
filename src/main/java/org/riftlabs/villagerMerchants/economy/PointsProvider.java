package org.riftlabs.villagerMerchants.economy;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.ConfigurationSection;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Base for whole-number currencies that live in another plugin. The other plugin's API is called by reflection so
 * VillagerMerchants compiles and runs without it: nothing to add to build.gradle.kts, and a plugin update that
 * moves its API package cannot break the build. The plugin is looked up again on every call, so /plugman style
 * reloads of PlayerPoints / TokenManager are picked up without restarting.
 */
abstract class PointsProvider implements EconomyProvider {
    protected final VillagerMerchants plugin;
    private final String id;
    private final String pluginName;
    private final String defaultName;
    private final ConfigurationSection cfg;

    private Plugin cachedPlugin;
    private boolean broken;      // the API did not look like we expected: stop trying, log once

    PointsProvider(VillagerMerchants plugin, String id, String pluginName, String defaultName) {
        this.plugin = plugin;
        this.id = id;
        this.pluginName = pluginName;
        this.defaultName = defaultName;
        this.cfg = plugin.getConfig().getConfigurationSection("currencies." + id);
    }

    /** Looks up the methods on the live plugin object. Returns false if this plugin version has a different API. */
    protected abstract boolean bind(Plugin live) throws ReflectiveOperationException;

    @Override public final String id() { return id; }
    @Override public boolean isWholeNumbers() { return true; }

    @Override public String displayName() {
        return cfg == null ? defaultName : cfg.getString("name", defaultName);
    }

    /** The live plugin, bound and ready, or null. */
    protected final Plugin live() {
        if (broken) return null;
        if (cfg != null && !cfg.getBoolean("enabled", true)) return null;
        Plugin live = Bukkit.getPluginManager().getPlugin(pluginName);
        if (live == null || !live.isEnabled()) return null;
        if (live != cachedPlugin) {
            try {
                if (!bind(live)) { markBroken(null); return null; }
                cachedPlugin = live;
            } catch (ReflectiveOperationException | RuntimeException ex) {
                markBroken(ex);
                return null;
            }
        }
        return live;
    }

    private void markBroken(Throwable ex) {
        broken = true;
        plugin.getLogger().log(Level.WARNING, pluginName + " is installed but its API is not what VillagerMerchants "
                + "expects, so the '" + id + "' currency is disabled. Please report this with your " + pluginName + " version.", ex);
    }

    @Override public final boolean isAvailable() { return live() != null; }

    @Override public String format(double amount) {
        String template = cfg == null ? "{amount} " + defaultName : cfg.getString("format", "{amount} " + displayName());
        return template.replace("{amount}", String.format(Locale.US, "%,d", Math.round(amount)));
    }

    /** Whole units to charge for a price. Never rounds down: a 0.5 price still costs 1. */
    protected static long units(double amount) { return (long) Math.ceil(amount - 1e-9); }

    protected static Object call(Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override public boolean has(OfflinePlayer p, double amount) {
        return amount <= 0 || balance(p) >= units(amount);
    }
}
