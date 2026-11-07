package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.protection.ClaimHook;
import org.riftlabs.villagerMerchants.protection.GriefPreventionHook;
import org.riftlabs.villagerMerchants.protection.LandsHook;
import org.riftlabs.villagerMerchants.protection.WorldGuardHook;
import org.riftlabs.villagerMerchants.util.Messages;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Where shops may exist, and who may set one up there. Configured under {@code protection:} in config.yml.
 *
 * <p>Two moments are checked: an admin placing a shop NPC with {@code /vm create}, and a player renting one
 * ({@link ShopManager#rentShop}, checked before they are charged). The rules are:
 * <ul>
 *   <li><b>WorldGuard</b>: the spot must be inside one of {@code allowed-regions}; optionally the custom
 *       {@code vm-shops} region flag decides ({@code require} = needs {@code vm-shops allow}, {@code block} = refused
 *       where {@code vm-shops deny} is set); optionally the acting player must be able to build there.</li>
 *   <li><b>GriefPrevention / Lands</b>: {@code deny-claimed} (no shops inside any claim) or {@code require-trust}
 *       (inside a claim, the acting player needs build trust there).</li>
 * </ul>
 * Every configured rule must pass. Players with villagermerchants.bypass.protection skip everything.
 * Shops that already exist are not touched when rules change.
 */
public class ProtectionManager {

    public record Verdict(boolean allowed, String key, Object[] args) {
        static Verdict deny(String key, Object... args) { return new Verdict(false, key, args); }
        public void send(Messages m, CommandSender to) { if (!allowed) m.send(to, key, args); }
    }

    private static final Verdict ALLOW = new Verdict(true, null, new Object[0]);

    private enum FlagMode { OFF, REQUIRE, BLOCK }
    private enum Mode { OFF, DENY_CLAIMED, REQUIRE_TRUST }

    private record RegionRule(String world, String id, String raw) {}

    private static final class ClaimRule {
        final String label;
        final ClaimHook hook;       // null when the plugin's API couldn't be used (see failure)
        final String failure;
        final Mode mode;
        final boolean allowUnclaimed, allowAdminClaims;
        ClaimRule(String label, ClaimHook hook, String failure, Mode mode, boolean allowUnclaimed, boolean allowAdminClaims) {
            this.label = label; this.hook = hook; this.failure = failure;
            this.mode = mode; this.allowUnclaimed = allowUnclaimed; this.allowAdminClaims = allowAdminClaims;
        }
    }

    private interface HookFactory { ClaimHook make() throws Exception; }

    private final VillagerMerchants plugin;
    private final Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private boolean enabled, onErrorAllow, applyToAdminCreate, requireBuild;
    private WorldGuardHook wg;
    private String wgNote = "";
    private FlagMode flagMode = FlagMode.OFF;
    private final List<RegionRule> regions = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<ClaimRule> claimRules = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<String[]> claimNotes = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ProtectionManager(VillagerMerchants plugin) {
        this.plugin = plugin;
        reload();
    }

    // ------------------------------------------------------------ startup

    /**
     * Called from onLoad: WorldGuard only accepts new flags while plugins are loading. Does nothing if WorldGuard
     * is absent.
     */
    public static void registerFlags(VillagerMerchants plugin) {
        Plugin wgPlugin = plugin.getServer().getPluginManager().getPlugin("WorldGuard");
        if (wgPlugin == null) return;
        try {
            WorldGuardHook.registerFlag(plugin.getLogger());
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not register the WorldGuard flag '" + WorldGuardHook.FLAG_NAME + "': " + t);
        }
    }

    private static boolean present(PluginManager pm, String name) {
        Plugin p = pm.getPlugin(name);
        return p != null && p.isEnabled();
    }

    public void reload() {
        warned.clear();
        FileConfiguration c = plugin.getConfig();
        PluginManager pm = plugin.getServer().getPluginManager();
        enabled = c.getBoolean("protection.enabled", true);
        onErrorAllow = "allow".equalsIgnoreCase(c.getString("protection.on-error", "deny"));
        applyToAdminCreate = c.getBoolean("protection.apply-to-admin-create", true);

        // ---- WorldGuard
        wg = null;
        wgNote = "";
        regions.clear();
        flagMode = parseFlagMode(c.getString("protection.worldguard.flag", "off"));
        requireBuild = c.getBoolean("protection.worldguard.require-build", false);
        for (String raw : c.getStringList("protection.worldguard.allowed-regions")) {
            if (raw == null || raw.isBlank()) continue;
            String s = raw.trim();
            int colon = s.indexOf(':');
            String world = colon > 0 ? s.substring(0, colon).trim() : null;
            String id = (colon > 0 ? s.substring(colon + 1) : s).trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) regions.add(new RegionRule(world, id, s));
        }
        boolean wgUsed = !regions.isEmpty() || flagMode != FlagMode.OFF || requireBuild;
        if (!c.getBoolean("protection.worldguard.enabled", true)) {
            wgNote = "disabled in config";
        } else if (!present(pm, "WorldGuard")) {
            wgNote = "not installed";
            if (wgUsed) plugin.getLogger().warning("protection.worldguard rules are configured but WorldGuard is not installed - they are NOT being enforced.");
        } else {
            try {
                wg = new WorldGuardHook();
                if (flagMode != FlagMode.OFF && !WorldGuardHook.flagRegistered()) {
                    plugin.getLogger().warning("protection.worldguard.flag is '" + flagMode.name().toLowerCase(Locale.ROOT)
                            + "' but the " + WorldGuardHook.FLAG_NAME + " flag could not be registered; spots will be refused per on-error.");
                }
                wgNote = "active";
            } catch (Throwable t) {
                wg = null;
                wgNote = "API error: " + t;
                if (wgUsed) plugin.getLogger().warning("WorldGuard rules are configured but its API could not be used: " + t);
            }
        }

        // ---- claim plugins
        claimRules.clear();
        claimNotes.clear();
        // a lambda, not GriefPreventionHook::new: the method reference would load the hook class even when GriefPrevention is absent
        addClaimRule(c, pm, "griefprevention", "GriefPrevention", () -> new GriefPreventionHook());
        addClaimRule(c, pm, "lands", "Lands", () -> new LandsHook(plugin));
    }

    private void addClaimRule(FileConfiguration c, PluginManager pm, String key, String pluginName, HookFactory factory) {
        String base = "protection." + key;
        Mode mode = parseMode(c.getString(base + ".mode", "off"), base + ".mode");
        if (mode == Mode.OFF) { claimNotes.add(new String[]{pluginName, "off"}); return; }
        if (!present(pm, pluginName)) {
            claimNotes.add(new String[]{pluginName, mode.name().toLowerCase(Locale.ROOT).replace('_', '-') + " - plugin NOT installed, rule not enforced"});
            plugin.getLogger().warning(base + ".mode is set but " + pluginName + " is not installed - the rule is NOT being enforced.");
            return;
        }
        boolean allowUnclaimed = c.getBoolean(base + ".allow-unclaimed", true);
        boolean allowAdmin = c.getBoolean(base + ".allow-admin-claims", true);
        String modeText = mode.name().toLowerCase(Locale.ROOT).replace('_', '-');
        try {
            claimRules.add(new ClaimRule(pluginName, factory.make(), null, mode, allowUnclaimed, allowAdmin));
            claimNotes.add(new String[]{pluginName, modeText + " - active"});
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            claimRules.add(new ClaimRule(pluginName, null, why, mode, allowUnclaimed, allowAdmin));
            claimNotes.add(new String[]{pluginName, modeText + " - API NOT recognised (" + why + "); spots are handled per on-error"});
            plugin.getLogger().warning(pluginName + " is installed but its API could not be used (" + why
                    + "). Spots will be handled per protection.on-error (currently: " + (onErrorAllow ? "allow" : "deny") + ").");
        }
    }

    private FlagMode parseFlagMode(String raw) {
        String s = raw == null ? "off" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "require" -> FlagMode.REQUIRE;
            case "block" -> FlagMode.BLOCK;
            case "off", "false", "" -> FlagMode.OFF;   // an unquoted off in YAML arrives as "false"
            default -> {
                plugin.getLogger().warning("protection.worldguard.flag: unknown value '" + raw + "' (use off, require or block) - using off.");
                yield FlagMode.OFF;
            }
        };
    }

    private Mode parseMode(String raw, String path) {
        String s = raw == null ? "off" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (s) {
            case "deny-claimed" -> Mode.DENY_CLAIMED;
            case "require-trust" -> Mode.REQUIRE_TRUST;
            case "off", "false", "" -> Mode.OFF;      // an unquoted off in YAML arrives as "false"
            default -> {
                plugin.getLogger().warning(path + ": unknown value '" + raw + "' (use off, deny-claimed or require-trust) - using off.");
                yield Mode.OFF;
            }
        };
    }

    // ------------------------------------------------------------ public checks

    /** An admin is placing a shop NPC at {@code loc} ({@code /vm create}). */
    public Verdict checkCreate(Player admin, Location loc) {
        if (!applyToAdminCreate) return ALLOW;
        return evaluate(admin, loc, true);
    }

    /** A player is about to rent the shop NPC standing at {@code loc}. Call before charging them. */
    public Verdict checkRent(Player renter, Location loc) {
        return evaluate(renter, loc, true);
    }

    /** A player is opening an existing shop (customer view or owner menu). Only the vm-shops flag applies. */
    public Verdict checkUse(Player p, Location loc) {
        if (!enabled || wg == null || flagMode == FlagMode.OFF) return ALLOW;
        if (plugin.access().bypassProtection(p)) return ALLOW;
        try {
            Boolean state = wg.flagState(p, loc);
            if (flagMode == FlagMode.REQUIRE && !Boolean.TRUE.equals(state)) return Verdict.deny("protection.denied.flag");
            if (flagMode == FlagMode.BLOCK && Boolean.FALSE.equals(state)) return Verdict.deny("protection.denied.flag");
            return ALLOW;
        } catch (Throwable t) {
            return onErrorAllow ? ALLOW : Verdict.deny("protection.denied.error");
        }
    }

    /** Like checkCreate but ignores the bypass permission and apply-to-admin-create; for /vm protection. */
    public Verdict test(Player p, Location loc) {
        return evaluate(p, loc, false);
    }

    // ------------------------------------------------------------ evaluation

    private Verdict evaluate(Player p, Location loc, boolean honorBypass) {
        if (!enabled) return ALLOW;
        if (honorBypass && plugin.access().bypassProtection(p)) return ALLOW;
        try {
            if (loc == null || loc.getWorld() == null) throw new IllegalStateException("shop location is unknown");
            Verdict v = checkWorldGuard(p, loc);
            if (!v.allowed()) return v;
            for (ClaimRule r : claimRules) {
                v = checkClaim(r, p, loc);
                if (!v.allowed()) return v;
            }
            return ALLOW;
        } catch (Throwable t) {
            String id = t.getClass().getName() + ":" + t.getMessage();
            if (warned.add(id)) {
                plugin.getLogger().warning("Protection check failed (" + (onErrorAllow ? "allowing" : "refusing") + "): " + t);
            }
            return onErrorAllow ? ALLOW : Verdict.deny("protection.denied.error");
        }
    }

    private Verdict checkWorldGuard(Player p, Location loc) {
        if (wg == null) return ALLOW; // not installed / disabled: reported at startup
        if (!regions.isEmpty()) {
            Set<String> here = wg.regionIds(loc);
            String world = loc.getWorld().getName();
            boolean inside = false;
            for (RegionRule r : regions) {
                if (here.contains(r.id()) && (r.world() == null || r.world().equalsIgnoreCase(world))) { inside = true; break; }
            }
            if (!inside) {
                List<String> names = new ArrayList<>();
                for (RegionRule r : regions) names.add(r.raw());
                return Verdict.deny("protection.denied.region", "regions", String.join(", ", names));
            }
        }
        if (flagMode != FlagMode.OFF) {
            Boolean state = wg.flagState(p, loc);   // throws if the flag could not be registered -> on-error
            if (flagMode == FlagMode.REQUIRE && !Boolean.TRUE.equals(state)) return Verdict.deny("protection.denied.flag");
            if (flagMode == FlagMode.BLOCK && Boolean.FALSE.equals(state)) return Verdict.deny("protection.denied.flag");
        }
        if (requireBuild && !wg.canBuild(p, loc)) return Verdict.deny("protection.denied.build");
        return ALLOW;
    }

    private Verdict checkClaim(ClaimRule r, Player p, Location loc) throws Exception {
        if (r.hook == null) throw new IllegalStateException(r.label + " API not usable: " + r.failure);
        ClaimHook.ClaimInfo claim = r.hook.claimAt(loc);
        if (claim == null) return r.allowUnclaimed ? ALLOW : Verdict.deny("protection.denied.unclaimed", "plugin", r.label);
        if (claim.admin() && r.allowAdminClaims) return ALLOW;
        if (r.mode == Mode.DENY_CLAIMED) return Verdict.deny("protection.denied.claimed", "plugin", r.label, "owner", claim.owner());
        if (!r.hook.canBuild(p, loc)) return Verdict.deny("protection.denied.no-trust", "plugin", r.label, "owner", claim.owner());
        return ALLOW;
    }

    // ------------------------------------------------------------ /vm protection

    /** name/value rows describing what is being enforced right now. */
    public List<String[]> status() {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"Protection", enabled ? "on" : "OFF"});
        out.add(new String[]{"On error", onErrorAllow ? "allow" : "deny"});
        out.add(new String[]{"Checks /vm create", applyToAdminCreate ? "yes" : "no"});
        out.add(new String[]{"WorldGuard", wgNote.isEmpty() ? "-" : wgNote});
        if (wg != null || !regions.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (RegionRule r : regions) names.add(r.raw());
            out.add(new String[]{"  allowed regions", names.isEmpty() ? "any" : String.join(", ", names)});
            out.add(new String[]{"  flag " + WorldGuardHook.FLAG_NAME, flagMode.name().toLowerCase(Locale.ROOT)
                    + (flagMode != FlagMode.OFF && wg != null && !WorldGuardHook.flagRegistered() ? " (flag NOT registered)" : "")});
            out.add(new String[]{"  require build", requireBuild ? "yes" : "no"});
        }
        out.addAll(claimNotes);
        return out;
    }
}
