package org.riftlabs.villagerMerchants.util;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** Reads the optional "locked-lore" option: a single MiniMessage line or a list of lines. */
public final class Lore {
    private Lore() {}

    public static List<String> read(ConfigurationSection sec, String path) {
        List<String> out = new ArrayList<>();
        if (sec == null || !sec.contains(path)) return out;
        if (sec.isList(path)) {
            for (String s : sec.getStringList(path)) if (s != null && !s.isBlank()) out.add(s);
        } else {
            String s = sec.getString(path, "");
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }
}
