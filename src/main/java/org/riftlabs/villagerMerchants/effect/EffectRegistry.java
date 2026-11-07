package org.riftlabs.villagerMerchants.effect;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads and validates effects.yml. Bad layers/effects are skipped with a console warning, never a crash. */
public final class EffectRegistry {
    private final VillagerMerchants plugin;
    // Folia: read from every region's effect timers while /vm reload runs. Reload builds a new map and swaps it in whole.
    private volatile Map<String, EffectDefinition> effects = new LinkedHashMap<>();

    private double viewDistance = 24;
    private int updateTicks = 1;
    private int maxPoints = 200;
    private int previewSeconds = 8;
    private double yOffset = 0;

    public EffectRegistry(VillagerMerchants plugin) { this.plugin = plugin; }

    public void load() {
        File file = new File(plugin.getDataFolder(), "effects.yml");
        if (!file.exists()) plugin.saveResource("effects.yml", false);
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        viewDistance = Math.max(4, Math.min(64, cfg.getDouble("settings.view-distance", 24)));
        updateTicks = Math.max(1, Math.min(10, cfg.getInt("settings.update-ticks", 1)));
        maxPoints = Math.max(8, Math.min(2000, cfg.getInt("settings.max-points-per-emission", 200)));
        previewSeconds = Math.max(1, cfg.getInt("settings.preview-seconds", 8));
        yOffset = cfg.getDouble("settings.y-offset", 0.0);

        Map<String, EffectDefinition> effects = new LinkedHashMap<>();
        ConfigurationSection root = cfg.getConfigurationSection("effects");
        if (root == null) { this.effects = effects; plugin.getLogger().warning("effects.yml has no 'effects:' section."); return; }

        for (String id : root.getKeys(false)) {
            ConfigurationSection e = root.getConfigurationSection(id);
            if (e == null || !e.getBoolean("enabled", true)) continue;

            List<EffectLayer> layers = new ArrayList<>();
            ConfigurationSection ls = e.getConfigurationSection("layers");
            if (ls != null) {
                for (String lid : ls.getKeys(false)) {
                    ConfigurationSection l = ls.getConfigurationSection(lid);
                    if (l == null) continue;
                    try {
                        layers.add(new EffectLayer(l, maxPoints));
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("effects.yml: effect '" + id + "' layer '" + lid + "': " + ex.getMessage());
                    }
                }
            }
            if (layers.isEmpty()) {
                plugin.getLogger().warning("effects.yml: effect '" + id + "' has no valid layers and was skipped.");
                continue;
            }
            Material icon = Material.matchMaterial(e.getString("icon", "BLAZE_POWDER"));
            if (icon == null || !icon.isItem()) icon = Material.BLAZE_POWDER;

            effects.put(id, new EffectDefinition(id, e.getString("name", id), icon,
                    Math.max(0, e.getDouble("price", 0.0)), e.getString("currency", "vault"), e.getString("permission", ""),
                    e.getStringList("description"), List.copyOf(layers),
                    org.riftlabs.villagerMerchants.util.Lore.read(e, "locked-lore")));
        }
        this.effects = effects;
        plugin.getLogger().info("Loaded " + effects.size() + " shop effects.");
    }

    public EffectDefinition get(String id) { return id == null ? null : effects.get(id); }
    public Collection<EffectDefinition> all() { return effects.values(); }
    public double viewDistance() { return viewDistance; }
    public int updateTicks() { return updateTicks; }
    public int maxPoints() { return maxPoints; }
    public int previewSeconds() { return previewSeconds; }
    public double yOffset() { return yOffset; }
}
