package org.riftlabs.villagerMerchants.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {}

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** MiniMessage with placeholders. Use Placeholder.unparsed(...) for anything a player controls. */
    public static Component mm(String s, TagResolver... resolvers) {
        return MM.deserialize(s, resolvers);
    }

    /** Escapes MiniMessage tags in player-controlled text (item names etc.). */
    public static String esc(String s) {
        return MM.escapeTags(s == null ? "" : s);
    }

    public static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** MiniMessage without the default italic (for item names / lore). */
    public static Component item(String s) {
        return MM.deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    public static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(item(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(item(s));
            meta.lore(l);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack glow(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        it.setItemMeta(meta);
        return it;
    }

    private static Messages msg() { return VillagerMerchants.getInstance().msg(); }

    /** "2d 3h", "1h 20m", "5m" or "expired"; the wording comes from the "time.*" keys of the language file. */
    public static String duration(long ms) {
        Messages m = msg();
        if (ms <= 0) return m.raw("time.expired");
        long mins = ms / 60_000L;
        long d = mins / 1440, h = (mins % 1440) / 60, min = mins % 60;
        if (d > 0) return m.str("time.days-hours", "days", d, "hours", h);
        if (h > 0) return m.str("time.hours-minutes", "hours", h, "minutes", min);
        return m.str("time.minutes", "minutes", Math.max(1, min));
    }

    /** Plain-text name of an item: custom name if it has one, otherwise the prettified material. */
    public static String itemName(ItemStack it) {
        if (it == null) return msg().raw("misc.nothing");
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String n = plain(meta.displayName());
            if (!n.isBlank()) return n;
        }
        return pretty(it.getType().name());
    }

    /** DIAMOND_SWORD -> Diamond Sword */
    public static String pretty(String enumName) {
        StringBuilder sb = new StringBuilder();
        for (String part : enumName.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }

    /** MiniMessage fragment: filled/empty stars for an average rating. */
    public static String stars(double average) {
        int full = (int) Math.max(0, Math.min(5, Math.round(average)));
        Messages m = msg();
        return m.str("stars.format", "filled", m.raw("stars.filled").repeat(full), "empty", m.raw("stars.empty").repeat(5 - full));
    }

    public static String oneDecimal(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
