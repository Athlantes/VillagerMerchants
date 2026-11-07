package org.riftlabs.villagerMerchants.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Every player-facing text lives in {@code plugins/VillagerMerchants/lang/<language>.yml} (MiniMessage).
 * The active file is chosen by {@code language:} in config.yml. Keys missing from a translation fall back
 * to the English file bundled in the jar, so an outdated translation never shows blank text.
 *
 * <p>Placeholders are written as {@code <name>} in the lang file. Values passed from code are inserted as
 * plain text (safe for player-controlled names); wrap them in {@link #parsed(String)} to insert MiniMessage
 * or pass a {@link Component}. {@code <prefix>} is always available.
 */
public final class Messages {
    /** A placeholder value that is itself MiniMessage (colors, other lang fragments). */
    public record Parsed(String mm) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    /** Language files shipped inside the jar; they are copied to the data folder on first start. */
    private static final String[] BUNDLED = {"en"};

    private final JavaPlugin plugin;
    private final Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private YamlConfiguration active = new YamlConfiguration();
    private YamlConfiguration bundled = new YamlConfiguration();
    private String language = "en";

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    // ------------------------------------------------------------ loading

    public void load() {
        warned.clear();
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists()) dir.mkdirs();
        for (String lang : BUNDLED) {
            if (!new File(dir, lang + ".yml").exists()) plugin.saveResource("lang/" + lang + ".yml", false);
        }
        bundled = loadBundled();

        language = plugin.getConfig().getString("language", "en").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        if (language.isEmpty()) language = "en";
        File file = new File(dir, language + ".yml");
        if (!file.isFile()) {
            plugin.getLogger().warning("Language file lang/" + language + ".yml not found - using English.");
            language = "en";
            file = new File(dir, "en.yml");
        }
        active = YamlConfiguration.loadConfiguration(file);

        int missing = 0;
        for (String k : bundled.getKeys(true)) {
            if (!bundled.isConfigurationSection(k) && !active.contains(k)) missing++;
        }
        plugin.getLogger().info("Language: " + language + (missing > 0
                ? " (" + missing + " key(s) missing from lang/" + language + ".yml, using built-in English for them)" : ""));
    }

    private YamlConfiguration loadBundled() {
        try (var in = plugin.getResource("lang/en.yml")) {
            if (in == null) return new YamlConfiguration();
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return YamlConfiguration.loadConfiguration(r);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read the bundled lang/en.yml: " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    public String language() { return language; }

    // ------------------------------------------------------------ raw access

    private Object find(String key) {
        Object o = active.get(key);
        if (o == null || o instanceof ConfigurationSection) o = bundled.get(key);
        return o instanceof ConfigurationSection ? null : o;
    }

    public boolean has(String key) { return find(key) != null; }

    /** The unparsed MiniMessage text of a key (lists are joined with line breaks). */
    public String raw(String key) {
        Object o = find(key);
        if (o == null) return missing(key);
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).collect(Collectors.joining("<newline>"));
        return String.valueOf(o);
    }

    /** The lines of a list key (a plain string counts as one line; a missing key as none). */
    public List<String> rawList(String key) {
        Object o = find(key);
        if (o == null) return List.of();
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return List.of(String.valueOf(o));
    }

    private String missing(String key) {
        if (warned.add(key)) plugin.getLogger().warning("Missing language key: " + key);
        return "<red>[missing: " + key + "]";
    }

    /** Raw text with {@code <name>} replaced literally (no MiniMessage involved) - for plain-text templates. */
    public String str(String key, Object... kv) {
        String s = raw(key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("<" + kv[i] + ">", String.valueOf(kv[i + 1]));
        return s;
    }

    // ------------------------------------------------------------ placeholders

    public static Parsed parsed(String miniMessage) { return new Parsed(miniMessage == null ? "" : miniMessage); }

    /** Another lang entry (with optional literal replacements) usable as a placeholder value. */
    public Parsed ref(String key, Object... kv) { return new Parsed(str(key, kv)); }

    private TagResolver[] resolvers(Object[] kv) {
        List<TagResolver> out = new ArrayList<>();
        out.add(Placeholder.parsed("prefix", has("prefix") ? raw("prefix") : ""));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            Object v = kv[i + 1];
            if (v instanceof Parsed p) out.add(Placeholder.parsed(k, p.mm()));
            else if (v instanceof Component c) out.add(Placeholder.component(k, c));
            else out.add(Placeholder.unparsed(k, String.valueOf(v)));
        }
        return out.toArray(new TagResolver[0]);
    }

    // ------------------------------------------------------------ components

    /** For chat, action bars and inventory titles. */
    public Component get(String key, Object... kv) {
        return MM.deserialize(raw(key), resolvers(kv));
    }

    /** For item names and lore: like {@link #get} but without the default italic. */
    public Component line(String key, Object... kv) {
        return get(key, kv).decoration(TextDecoration.ITALIC, false);
    }

    /** Every line of a list key as lore lines. */
    public List<Component> lines(String key, Object... kv) {
        List<Component> out = new ArrayList<>();
        TagResolver[] r = resolvers(kv);
        for (String s : rawList(key)) out.add(MM.deserialize(s, r).decoration(TextDecoration.ITALIC, false));
        return out;
    }

    public void send(CommandSender to, String key, Object... kv) { to.sendMessage(get(key, kv)); }

    /** Sends every line of a list key. */
    public void sendLines(CommandSender to, String key, Object... kv) {
        TagResolver[] r = resolvers(kv);
        for (String s : rawList(key)) to.sendMessage(MM.deserialize(s, r));
    }

    // ------------------------------------------------------------ items

    /** An item whose name is {@code <key>.name} and whose lore is the optional list {@code <key>.lore}. */
    public ItemStack icon(Material m, String key, Object... kv) {
        return iconPlus(m, key, List.of(), kv);
    }

    /** Like {@link #icon} with extra lore lines appended (conditional lines built in code). */
    public ItemStack iconPlus(Material m, String key, List<Component> extra, Object... kv) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(line(key + ".name", kv));
        List<Component> lore = new ArrayList<>();
        if (has(key + ".lore")) lore.addAll(lines(key + ".lore", kv));
        lore.addAll(extra);
        if (!lore.isEmpty()) meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }
}
