package org.riftlabs.villagerMerchants.util;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Looks players can buy for shops linked to a MythicMob / ModelEngine entity (linked-skins.yml).
 * Villager biome / profession skins make no sense there, so those are replaced by these admin-defined variants.
 */
public final class LinkedSkins {
    /**
     * @param model  ModelEngine model id swapped onto the entity (works for ModelEngine and MythicMobs bodies), or null
     * @param mythic MythicMob type the body is re-spawned as (MythicMobs bodies only), or null
     */
    public record Variant(String group, String id, String name, Material icon, double price, String permission,
                          String model, String mythic) {
        /** Id stored in the player's unlocked list. */
        public String key() { return "linked:" + group + ":" + id; }
    }

    private final VillagerMerchants plugin;
    private final org.bukkit.configuration.file.FileConfiguration cfg;

    public LinkedSkins(VillagerMerchants plugin) {
        this.plugin = plugin;
        File f = new File(plugin.getDataFolder(), "linked-skins.yml");
        if (!f.exists()) plugin.saveResource("linked-skins.yml", false);
        this.cfg = YamlConfiguration.loadConfiguration(f);
    }

    /** Variants for this shop-id: its own section, else the "default" section, else none. */
    public List<Variant> forShop(String shopId) {
        ConfigurationSection sec = shopId == null ? null : cfg.getConfigurationSection(shopId.toLowerCase(Locale.ROOT));
        String group = shopId == null ? "default" : shopId.toLowerCase(Locale.ROOT);
        if (sec == null) { sec = cfg.getConfigurationSection("default"); group = "default"; }
        List<Variant> out = new ArrayList<>();
        if (sec == null) return out;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection v = sec.getConfigurationSection(id);
            if (v == null) continue;
            String model = blank(v.getString("model"));
            String mythic = blank(v.getString("mythic"));
            if (model == null && mythic == null) continue;   // nothing to apply
            Material icon = Material.matchMaterial(v.getString("icon", "PLAYER_HEAD"));
            out.add(new Variant(group, id.toLowerCase(Locale.ROOT), v.getString("name", id),
                    icon == null || icon.isAir() ? Material.PLAYER_HEAD : icon,
                    Math.max(0, v.getDouble("price", 0)), v.getString("permission", ""), model, mythic));
        }
        return out;
    }

    public Variant find(String shopId, String variantId) {
        if (variantId == null || variantId.isBlank()) return null;
        for (Variant v : forShop(shopId)) if (v.id().equals(variantId)) return v;
        return null;
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
