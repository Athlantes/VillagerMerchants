package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.CustomItems;
import org.riftlabs.villagerMerchants.util.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a shop sells. Categories are defined in categories.yml and worked out from the result items of a
 * shop's live offers, so owners never have to tag anything. Also owns the text matching used by search.
 */
public class CategoryRegistry {
    public static final String OTHER_ID = "other";

    public record Category(String id, String name, Material icon, Set<String> materials, List<String> suffixes,
                           List<String> prefixes, List<String> contains,
                           boolean edible, boolean enchanted, boolean block,
                           List<Pattern> customIds, boolean anyCustom) {

        public boolean matches(ItemStack it) {
            if (it == null || it.getType().isAir()) return false;
            if (anyCustom || !customIds.isEmpty()) {
                String key = CustomItems.keyOf(it);
                if (key != null) {
                    if (anyCustom) return true;
                    for (Pattern p : customIds) if (p.matcher(key).matches()) return true;
                }
            }
            Material m = it.getType();
            String n = m.name();
            if (materials.contains(n)) return true;
            for (String s : suffixes) if (n.endsWith(s)) return true;
            for (String s : prefixes) if (n.startsWith(s)) return true;
            for (String s : contains) if (n.contains(s)) return true;
            if (edible && m.isEdible()) return true;
            if (block && m.isBlock()) return true;
            return enchanted && isEnchanted(it);
        }
    }

    private final VillagerMerchants plugin;
    // Folia: swapped in whole on reload so other regions never see a half-filled map
    private volatile Map<String, Category> categories = new LinkedHashMap<>();
    private final Category other = new Category(OTHER_ID, "Other", Material.CHEST, Set.of(), List.of(), List.of(),
            List.of(), false, false, false, List.of(), false);

    public CategoryRegistry(VillagerMerchants plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        Map<String, Category> categories = new LinkedHashMap<>();
        File f = new File(plugin.getDataFolder(), "categories.yml");
        if (!f.exists()) plugin.saveResource("categories.yml", false);
        ConfigurationSection root = YamlConfiguration.loadConfiguration(f).getConfigurationSection("categories");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                ConfigurationSection c = root.getConfigurationSection(id);
                if (c == null) continue;
                Material icon = Material.matchMaterial(c.getString("icon", "CHEST"));
                if (icon == null || icon.isAir() || !icon.isItem()) icon = Material.CHEST;
                ConfigurationSection m = c.getConfigurationSection("match");
                Set<String> flags = new LinkedHashSet<>();
                if (m != null) for (String fl : m.getStringList("flags")) flags.add(fl.toUpperCase(Locale.ROOT));
                categories.put(id.toLowerCase(Locale.ROOT), new Category(
                        id.toLowerCase(Locale.ROOT), c.getString("name", Text.pretty(id)), icon,
                        m == null ? Set.of() : upper(m.getStringList("materials")),
                        m == null ? List.of() : new ArrayList<>(upper(m.getStringList("suffixes"))),
                        m == null ? List.of() : new ArrayList<>(upper(m.getStringList("prefixes"))),
                        m == null ? List.of() : new ArrayList<>(upper(m.getStringList("contains"))),
                        flags.contains("EDIBLE"), flags.contains("ENCHANTED"), flags.contains("BLOCK"),
                        m == null ? List.of() : globs(m.getStringList("custom-ids")), flags.contains("CUSTOM")));
            }
        }
        categories.put(OTHER_ID, other);
        this.categories = categories;
    }

    /** "oraxen:*scythe*" style patterns (only * is special, case-insensitive) matched against the custom item id. */
    private static List<Pattern> globs(List<String> in) {
        List<Pattern> out = new ArrayList<>();
        for (String g : in) {
            StringBuilder re = new StringBuilder();
            for (String part : g.trim().split("\\*", -1)) {
                if (re.length() > 0 || g.trim().startsWith("*")) re.append(".*");
                re.append(Pattern.quote(part));
            }
            out.add(Pattern.compile(re.toString(), Pattern.CASE_INSENSITIVE));
        }
        return out;
    }

    private static Set<String> upper(List<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) out.add(s.toUpperCase(Locale.ROOT));
        return out;
    }

    /** In display order, "Other" last. */
    public List<Category> all() { return new ArrayList<>(categories.values()); }
    public Category get(String id) { return id == null ? null : categories.get(id.toLowerCase(Locale.ROOT)); }

    /** Looks a category up by id or display name (for /shops category &lt;name&gt;). */
    public Category find(String input) {
        if (input == null) return null;
        String in = input.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        Category c = categories.get(in);
        if (c != null) return c;
        for (Category cat : categories.values()) {
            if (Text.plain(Text.mm(cat.name())).toLowerCase(Locale.ROOT).replace(' ', '_').equals(in)) return cat;
        }
        return null;
    }

    // ------------------------------------------------------------ classification

    public Set<String> categoriesOf(ItemStack it) {
        Set<String> out = new LinkedHashSet<>();
        for (Category c : categories.values()) if (c != other && c.matches(it)) out.add(c.id());
        if (out.isEmpty()) out.add(OTHER_ID);
        return out;
    }

    public Set<String> categoriesOf(Shop shop) {
        Set<String> out = new LinkedHashSet<>();
        for (Trade t : shop.completeTrades()) out.addAll(categoriesOf(t.getResult()));
        return out;
    }

    public boolean inCategory(ItemStack it, String categoryId) {
        if (categoryId == null) return true;
        return categoriesOf(it).contains(categoryId);
    }

    // ------------------------------------------------------------ search

    /** Everything a player might type to find this item: name, material, enchantments, potion type. Lower case. */
    public static String searchText(ItemStack it) {
        if (it == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(Text.itemName(it)).append(' ').append(Text.pretty(it.getType().name()));
        String customKey = CustomItems.keyOf(it);   // e.g. oraxen:ruby_scythe -> "oraxen ruby scythe"
        if (customKey != null) sb.append(' ').append(customKey.replace(':', ' '));
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            for (Enchantment e : meta.getEnchants().keySet()) sb.append(' ').append(e.getKey().getKey());
            if (meta instanceof EnchantmentStorageMeta esm) {
                for (Enchantment e : esm.getStoredEnchants().keySet()) sb.append(' ').append(e.getKey().getKey());
            }
            if (meta instanceof PotionMeta pm && pm.getBasePotionType() != null) {
                sb.append(' ').append(pm.getBasePotionType().getKey().getKey());
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** All whitespace-separated words of the query must appear in the item's search text. */
    public static boolean matchesQuery(ItemStack it, String query) {
        if (query == null || query.isBlank()) return true;
        String hay = searchText(it);
        for (String word : query.toLowerCase(Locale.ROOT).replace('_', ' ').trim().split("\\s+")) {
            if (!hay.contains(word)) return false;
        }
        return true;
    }

    /** Distinct item names currently on sale in open shops (tab completion for /shops search). */
    public List<String> sellableNames(Iterable<Shop> shops, long now) {
        Set<String> names = new LinkedHashSet<>();
        for (Shop s : shops) {
            if (!s.isActive(now)) continue;
            for (Trade t : s.completeTrades()) names.add(Text.itemName(t.getResult()));
        }
        List<String> list = new ArrayList<>(names);
        Collections.sort(list, String.CASE_INSENSITIVE_ORDER);
        return list;
    }

    private static boolean isEnchanted(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return false;
        if (!meta.getEnchants().isEmpty()) return true;
        return meta instanceof EnchantmentStorageMeta esm && !esm.getStoredEnchants().isEmpty();
    }
}
