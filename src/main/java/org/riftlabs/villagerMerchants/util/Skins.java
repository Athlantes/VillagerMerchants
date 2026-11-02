package org.riftlabs.villagerMerchants.util;

import org.bukkit.Material;
import org.bukkit.entity.Villager;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.util.ArrayList;
import java.util.List;

/** Villager skins = biome type + profession outfit. Plains + Unemployed are free defaults. */
public final class Skins {
    public enum Kind { TYPE, PROFESSION }

    /**
     * @param defaultIcon icon used unless config.yml overrides it in {@code skins.icons.<key>}.
     *                    The display name comes from the language file ({@code skins.type.<key>} / {@code skins.profession.<key>}).
     */
    public record Skin(Kind kind, String key, Material defaultIcon) {
        public String id() { return kind.name().toLowerCase() + ":" + key; }

        public String name() {
            return VillagerMerchants.getInstance().msg().raw("skins." + kind.name().toLowerCase() + "." + key);
        }

        public Material icon() {
            String raw = VillagerMerchants.getInstance().getConfig().getString("skins.icons." + key);
            Material m = raw == null || raw.isBlank() ? null : Material.matchMaterial(raw.trim());
            return (m == null || m.isAir() || !m.isItem()) ? defaultIcon : m;
        }

        /** Free skins are listed in {@code skins.free} (default: plains, none). */
        public boolean isFree() {
            var cfg = VillagerMerchants.getInstance().getConfig();
            List<String> free = cfg.contains("skins.free") ? cfg.getStringList("skins.free") : List.of("plains", "none");
            for (String f : free) if (f.equalsIgnoreCase(key)) return true;
            return false;
        }
    }

    public static final List<Skin> TYPES = List.of(
            new Skin(Kind.TYPE, "plains", Material.GRASS_BLOCK),
            new Skin(Kind.TYPE, "desert", Material.SAND),
            new Skin(Kind.TYPE, "jungle", Material.JUNGLE_LOG),
            new Skin(Kind.TYPE, "savanna", Material.ACACIA_LOG),
            new Skin(Kind.TYPE, "snow", Material.SNOW_BLOCK),
            new Skin(Kind.TYPE, "swamp", Material.LILY_PAD),
            new Skin(Kind.TYPE, "taiga", Material.SPRUCE_LOG));

    public static final List<Skin> PROFESSIONS = List.of(
            new Skin(Kind.PROFESSION, "none", Material.EMERALD),
            new Skin(Kind.PROFESSION, "armorer", Material.BLAST_FURNACE),
            new Skin(Kind.PROFESSION, "butcher", Material.SMOKER),
            new Skin(Kind.PROFESSION, "cartographer", Material.CARTOGRAPHY_TABLE),
            new Skin(Kind.PROFESSION, "cleric", Material.BREWING_STAND),
            new Skin(Kind.PROFESSION, "farmer", Material.COMPOSTER),
            new Skin(Kind.PROFESSION, "fisherman", Material.BARREL),
            new Skin(Kind.PROFESSION, "fletcher", Material.FLETCHING_TABLE),
            new Skin(Kind.PROFESSION, "leatherworker", Material.CAULDRON),
            new Skin(Kind.PROFESSION, "librarian", Material.LECTERN),
            new Skin(Kind.PROFESSION, "mason", Material.STONECUTTER),
            new Skin(Kind.PROFESSION, "shepherd", Material.LOOM),
            new Skin(Kind.PROFESSION, "toolsmith", Material.SMITHING_TABLE),
            new Skin(Kind.PROFESSION, "weaponsmith", Material.GRINDSTONE));

    private Skins() {}

    public static List<Skin> all() {
        List<Skin> l = new ArrayList<>(TYPES);
        l.addAll(PROFESSIONS);
        return l;
    }

    public static Skin find(String id) {
        for (Skin s : all()) if (s.id().equals(id)) return s;
        return null;
    }

    public static Villager.Type type(String key) {
        return switch (key) {
            case "desert" -> Villager.Type.DESERT;
            case "jungle" -> Villager.Type.JUNGLE;
            case "savanna" -> Villager.Type.SAVANNA;
            case "snow" -> Villager.Type.SNOW;
            case "swamp" -> Villager.Type.SWAMP;
            case "taiga" -> Villager.Type.TAIGA;
            default -> Villager.Type.PLAINS;
        };
    }

    public static Villager.Profession profession(String key) {
        return switch (key) {
            case "armorer" -> Villager.Profession.ARMORER;
            case "butcher" -> Villager.Profession.BUTCHER;
            case "cartographer" -> Villager.Profession.CARTOGRAPHER;
            case "cleric" -> Villager.Profession.CLERIC;
            case "farmer" -> Villager.Profession.FARMER;
            case "fisherman" -> Villager.Profession.FISHERMAN;
            case "fletcher" -> Villager.Profession.FLETCHER;
            case "leatherworker" -> Villager.Profession.LEATHERWORKER;
            case "librarian" -> Villager.Profession.LIBRARIAN;
            case "mason" -> Villager.Profession.MASON;
            case "shepherd" -> Villager.Profession.SHEPHERD;
            case "toolsmith" -> Villager.Profession.TOOLSMITH;
            case "weaponsmith" -> Villager.Profession.WEAPONSMITH;
            default -> Villager.Profession.NONE;
        };
    }

    public static void apply(Villager v, String typeKey, String professionKey) {
        Villager.Type t = type(typeKey);
        Villager.Profession p = profession(professionKey);
        if (v.getVillagerType() != t) v.setVillagerType(t);
        if (v.getProfession() != p) v.setProfession(p);
    }
}
