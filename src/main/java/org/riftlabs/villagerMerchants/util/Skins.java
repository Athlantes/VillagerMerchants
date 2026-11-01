package org.riftlabs.villagerMerchants.util;

import org.bukkit.Material;
import org.bukkit.entity.Villager;

import java.util.ArrayList;
import java.util.List;

/** Villager skins = biome type + profession outfit. Plains + Unemployed are free defaults. */
public final class Skins {
    public enum Kind { TYPE, PROFESSION }

    public record Skin(Kind kind, String key, String name, Material icon) {
        public String id() { return kind.name().toLowerCase() + ":" + key; }
        public boolean isFree() { return key.equals("plains") || key.equals("none"); }
    }

    public static final List<Skin> TYPES = List.of(
            new Skin(Kind.TYPE, "plains", "Plains", Material.GRASS_BLOCK),
            new Skin(Kind.TYPE, "desert", "Desert", Material.SAND),
            new Skin(Kind.TYPE, "jungle", "Jungle", Material.JUNGLE_LOG),
            new Skin(Kind.TYPE, "savanna", "Savanna", Material.ACACIA_LOG),
            new Skin(Kind.TYPE, "snow", "Snow", Material.SNOW_BLOCK),
            new Skin(Kind.TYPE, "swamp", "Swamp", Material.LILY_PAD),
            new Skin(Kind.TYPE, "taiga", "Taiga", Material.SPRUCE_LOG));

    public static final List<Skin> PROFESSIONS = List.of(
            new Skin(Kind.PROFESSION, "none", "Unemployed", Material.EMERALD),
            new Skin(Kind.PROFESSION, "armorer", "Armorer", Material.BLAST_FURNACE),
            new Skin(Kind.PROFESSION, "butcher", "Butcher", Material.SMOKER),
            new Skin(Kind.PROFESSION, "cartographer", "Cartographer", Material.CARTOGRAPHY_TABLE),
            new Skin(Kind.PROFESSION, "cleric", "Cleric", Material.BREWING_STAND),
            new Skin(Kind.PROFESSION, "farmer", "Farmer", Material.COMPOSTER),
            new Skin(Kind.PROFESSION, "fisherman", "Fisherman", Material.BARREL),
            new Skin(Kind.PROFESSION, "fletcher", "Fletcher", Material.FLETCHING_TABLE),
            new Skin(Kind.PROFESSION, "leatherworker", "Leatherworker", Material.CAULDRON),
            new Skin(Kind.PROFESSION, "librarian", "Librarian", Material.LECTERN),
            new Skin(Kind.PROFESSION, "mason", "Mason", Material.STONECUTTER),
            new Skin(Kind.PROFESSION, "shepherd", "Shepherd", Material.LOOM),
            new Skin(Kind.PROFESSION, "toolsmith", "Toolsmith", Material.SMITHING_TABLE),
            new Skin(Kind.PROFESSION, "weaponsmith", "Weaponsmith", Material.GRINDSTONE));

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
