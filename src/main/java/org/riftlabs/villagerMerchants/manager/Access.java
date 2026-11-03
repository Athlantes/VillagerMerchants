package org.riftlabs.villagerMerchants.manager;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.effect.EffectDefinition;
import org.riftlabs.villagerMerchants.util.Skins;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The one place every permission check goes through, so nothing is hard-coded in listeners or menus.
 *
 * <p>Everything is a plain Bukkit permission node, which means LuckPerms (or any other permissions plugin)
 * handles groups, users, wildcards, contexts and expiry for us. Nothing in here depends on LuckPerms.
 *
 * <p>Three kinds of gates:
 * <ul>
 *   <li><b>Feature nodes</b> ({@code villagermerchants.rent}, {@code .shops.teleport}, {@code .upgrades.slots} ...).
 *       They default to <i>everybody</i>, so existing servers behave exactly as before and admins take things away.
 *       Set {@code permissions.strict: true} in config.yml to flip that: they then default to <i>ops only</i>
 *       and admins grant what they want.</li>
 *   <li><b>Item nodes</b> for skins, nametags and effects ({@code villagermerchants.skin.<id>} ...). Off until an
 *       admin turns on {@code customization.require-permission.*} or names a custom node on one item.</li>
 *   <li><b>Numeric limits</b> ({@code villagermerchants.maxshops.<n>}, {@code .maxstaff.<n>}): the highest node a
 *       player has wins; the config value is the floor for players without any.</li>
 * </ul>
 */
public final class Access {
    public static final String ROOT = "villagermerchants.";

    /** Nodes that default to everyone (or to ops only in strict mode). */
    private static final List<String> FEATURE_NODES = List.of(
            "use", "rent", "shops", "shops.teleport",
            "sales", "promote", "staff", "rate", "history", "claim",
            "upgrades.slots", "upgrades.tax", "upgrades.duration",
            "customize.skins", "customize.nametags", "customize.effects");

    /** Sub-actions of /vm that need villagermerchants.admin or villagermerchants.admin.<action>. */
    public static final List<String> ADMIN_ACTIONS = List.of("create", "remove", "reload", "effect", "log");

    private final VillagerMerchants plugin;

    public Access(VillagerMerchants plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ------------------------------------------------------------ plain feature checks

    public boolean has(CommandSender who, String suffix) { return who.hasPermission(ROOT + suffix); }

    /** Interact with shop NPCs at all (customers and owners). */
    public boolean canUse(CommandSender p) { return has(p, "use"); }
    /** Rent a new shop. */
    public boolean canRent(CommandSender p) { return has(p, "rent"); }
    /** Open the /shops directory. */
    public boolean canBrowse(CommandSender p) { return has(p, "shops"); }
    /** Teleport to shops from the directory. */
    public boolean canTeleport(CommandSender p) { return has(p, "shops.teleport"); }
    public boolean canRunSales(CommandSender p) { return has(p, "sales"); }
    public boolean canPromote(CommandSender p) { return has(p, "promote"); }
    public boolean canManageStaff(CommandSender p) { return has(p, "staff"); }
    public boolean canRate(CommandSender p) { return has(p, "rate"); }
    public boolean canViewHistory(CommandSender p) { return has(p, "history"); }
    public boolean canClaim(CommandSender p) { return has(p, "claim"); }

    // ------------------------------------------------------------ teleport bypasses

    public boolean bypassCooldown(CommandSender p) { return has(p, "shops.bypass") || has(p, "shops.bypass.cooldown"); }
    public boolean bypassWarmup(CommandSender p) { return has(p, "shops.bypass") || has(p, "shops.bypass.warmup"); }
    public boolean bypassCost(CommandSender p) { return has(p, "shops.bypass") || has(p, "shops.bypass.cost"); }

    // ------------------------------------------------------------ admin

    /** villagermerchants.admin grants every admin action; villagermerchants.admin.<action> grants just one. */
    public boolean admin(CommandSender who, String action) {
        return has(who, "admin") || has(who, "admin." + action.toLowerCase(Locale.ROOT));
    }

    public boolean anyAdmin(CommandSender who) {
        for (String a : ADMIN_ACTIONS) if (admin(who, a)) return true;
        return false;
    }

    // ------------------------------------------------------------ upgrades and customization menus

    public boolean canUpgrade(Player p, UpgradeType t) { return has(p, "upgrades." + t.key); }

    public boolean canUpgradeAny(Player p) {
        for (UpgradeType t : UpgradeType.values()) if (canUpgrade(p, t)) return true;
        return false;
    }

    /** @param tab "skins", "nametags" or "effects" */
    public boolean canCustomize(Player p, String tab) { return has(p, "customize." + tab); }

    public boolean canCustomizeAny(Player p) {
        return canCustomize(p, "skins") || canCustomize(p, "nametags") || canCustomize(p, "effects");
    }

    // ------------------------------------------------------------ per-item gates (skins / nametags / effects)

    /** The node a skin needs, or null if it needs none. Free skins are exempt from the global switch. */
    public String skinNode(Skins.Skin s) {
        String custom = cfg().getString("skins.permissions." + s.key());   // "" = explicitly open
        boolean all = cfg().getBoolean("customization.require-permission.skins", false);
        return itemNode(custom, all && !s.isFree(), "skin", s.key());
    }

    public String nametagNode(String id, String configured) {
        boolean all = cfg().getBoolean("customization.require-permission.nametags", false);
        return itemNode(blankToNull(configured), all, "nametag", id);
    }

    public String effectNode(EffectDefinition d) {
        boolean all = cfg().getBoolean("customization.require-permission.effects", false);
        return itemNode(blankToNull(d.permission()), all, "effect", d.id());
    }

    public boolean canUseSkin(Player p, Skins.Skin s) { return passes(p, skinNode(s)); }
    public boolean canUseNametag(Player p, String id, String configured) { return passes(p, nametagNode(id, configured)); }
    public boolean canUseEffect(Player p, EffectDefinition d) { return passes(p, effectNode(d)); }

    private static String itemNode(String custom, boolean requireByDefault, String kind, String id) {
        if (custom != null) return custom.isBlank() ? null : custom.trim();
        if (requireByDefault) return ROOT + kind + "." + id.toLowerCase(Locale.ROOT);
        return null;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static boolean passes(Player p, String node) { return node == null || p.hasPermission(node); }

    // ------------------------------------------------------------ numeric limits

    /**
     * Highest {@code villagermerchants.<family>.<n>} the player has (or unlimited), never below {@code base}.
     * Negated nodes are ignored, so a plain {@code false} in LuckPerms removes that rank's bonus.
     */
    public int limit(Player p, String family, int base) {
        String prefix = ROOT + family + ".";
        if (p.hasPermission(prefix + "unlimited")) return Integer.MAX_VALUE;
        int best = base;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (!node.startsWith(prefix)) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException ignored) { /* "unlimited", wildcards, typos */ }
        }
        return best;
    }

    /** Shops this player may own at once. */
    public int maxShops(Player p) { return limit(p, "maxshops", cfg().getInt("rent.max-shops-per-player", 3)); }

    /** Staff members this player may hire per shop. */
    public int maxStaff(Player p) { return Math.max(0, limit(p, "maxstaff", cfg().getInt("staff.max-per-shop", 5))); }

    /** For menus: Integer.MAX_VALUE shows as an infinity sign instead of 2147483647. */
    public static String cap(int n) { return n >= Integer.MAX_VALUE ? "\u221E" : String.valueOf(n); }

    // ------------------------------------------------------------ registration

    /**
     * Registers every node with Bukkit (so permission plugins can list and tab-complete them) and applies the
     * default for feature nodes. Safe to call again on /vm reload.
     */
    public void registerAll() {
        PluginManager pm = plugin.getServer().getPluginManager();

        PermissionDefault feature = cfg().getBoolean("permissions.strict", false)
                ? PermissionDefault.OP : PermissionDefault.TRUE;
        for (String n : FEATURE_NODES) setDefault(pm, ROOT + n, feature);

        setDefault(pm, ROOT + "maxshops.unlimited", PermissionDefault.OP);
        setDefault(pm, ROOT + "maxstaff.unlimited", PermissionDefault.OP);

        List<String> skins = new ArrayList<>();
        for (Skins.Skin s : Skins.all()) skins.add(s.key());
        registerGroup(pm, "skin", skins);
        registerGroup(pm, "nametag", nametagIds());
        registerGroup(pm, "effect", effectIds());
    }

    private List<String> nametagIds() {
        File file = new File(plugin.getDataFolder(), "nametags.yml");
        if (!file.exists()) plugin.saveResource("nametags.yml", false);
        return new ArrayList<>(YamlConfiguration.loadConfiguration(file).getKeys(false));
    }

    private List<String> effectIds() {
        List<String> out = new ArrayList<>();
        if (plugin.getEffectManager() == null) return out;
        Collection<EffectDefinition> all = plugin.getEffectManager().getRegistry().all();
        for (EffectDefinition d : all) out.add(d.id());
        return out;
    }

    /** villagermerchants.<kind>.* with one child per id. Always ops-only by default: they only matter once gating is on. */
    private void registerGroup(PluginManager pm, String kind, Collection<String> ids) {
        Permission parent = getOrCreate(pm, ROOT + kind + ".*", PermissionDefault.OP);
        for (String id : ids) {
            String node = ROOT + kind + "." + id.toLowerCase(Locale.ROOT);
            getOrCreate(pm, node, PermissionDefault.OP);
            parent.getChildren().putIfAbsent(node, true);
        }
        parent.recalculatePermissibles();
    }

    private static Permission getOrCreate(PluginManager pm, String node, PermissionDefault def) {
        Permission p = pm.getPermission(node);
        if (p == null) {
            p = new Permission(node, def);
            pm.addPermission(p);
        }
        return p;
    }

    private static void setDefault(PluginManager pm, String node, PermissionDefault def) {
        Permission p = pm.getPermission(node);
        if (p == null) pm.addPermission(new Permission(node, def));
        else if (p.getDefault() != def) p.setDefault(def);   // recalculates every online player
    }
}
