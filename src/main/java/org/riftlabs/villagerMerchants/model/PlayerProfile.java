package org.riftlabs.villagerMerchants.model;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PlayerProfile {
    private final UUID uuid;
    private int unlockedSlots;
    private int taxLevel;
    private int maxRentDays;
    private int slotLevel;
    private final Set<String> unlockedSkins = new HashSet<>();
    private String skinType = "plains";
    private String skinProfession = "none";

    // Nametag Customization State
    private final Set<String> unlockedNametags = new HashSet<>();
    private String equippedNametagFormat = "<green><bold>Available Shop</bold></green>";

    // Effect (particle) Customization State
    private final Set<String> unlockedEffects = new HashSet<>();
    private String equippedEffect = "";

    public PlayerProfile(UUID uuid, int unlockedSlots, int taxLevel, int maxRentDays) {
        this.uuid = uuid;
        this.unlockedSlots = unlockedSlots;
        this.taxLevel = taxLevel;
        this.maxRentDays = maxRentDays;
    }

    public UUID getUuid() { return uuid; }
    public int getUnlockedSlots() { return unlockedSlots; }
    public void setUnlockedSlots(int v) { unlockedSlots = v; }
    public int getSlotLevel() { return slotLevel; }
    public void setSlotLevel(int v) { slotLevel = v; }
    public int getTaxLevel() { return taxLevel; }
    public void setTaxLevel(int v) { taxLevel = v; }
    public int getMaxRentDays() { return maxRentDays; }
    public void setMaxRentDays(int v) { maxRentDays = v; }

    public String getSkinType() { return skinType; }
    public void setSkinType(String v) { skinType = v; }
    public String getSkinProfession() { return skinProfession; }
    public void setSkinProfession(String v) { skinProfession = v; }
    public boolean ownsSkin(String id) { return unlockedSkins.contains(id); }
    public void unlockSkin(String id) { unlockedSkins.add(id); }

    public String serializeSkins() { return String.join(",", unlockedSkins); }

    public void loadSkins(String unlocked, String type, String profession) {
        if (unlocked != null && !unlocked.isBlank()) {
            for (String s : unlocked.split(",")) if (!s.isBlank()) unlockedSkins.add(s.trim());
        }
        if (type != null && !type.isBlank()) skinType = type;
        if (profession != null && !profession.isBlank()) skinProfession = profession;
    }

    // Nametag Methods
    public boolean ownsNametag(String formatId) { return unlockedNametags.contains(formatId); }
    public void unlockNametag(String formatId) { unlockedNametags.add(formatId); }
    public String getUnlockedNametagsString() { return String.join(",", unlockedNametags); }
    public String getEquippedNametagFormat() { return equippedNametagFormat; }
    public void setEquippedNametagFormat(String format) { this.equippedNametagFormat = format; }

    // Effect Methods
    public boolean ownsEffect(String id) { return unlockedEffects.contains(id); }
    public void unlockEffect(String id) { unlockedEffects.add(id); }
    public String getUnlockedEffectsString() { return String.join(",", unlockedEffects); }
    public String getEquippedEffect() { return equippedEffect; }
    public void setEquippedEffect(String id) { this.equippedEffect = id == null ? "" : id; }

    public void loadEffects(String unlocked, String equipped) {
        unlockedEffects.clear();
        if (unlocked != null && !unlocked.isBlank()) {
            for (String s : unlocked.split(",")) if (!s.isBlank()) unlockedEffects.add(s.trim());
        }
        equippedEffect = equipped == null ? "" : equipped.trim();
    }

    public void loadNametags(String nametagsString) {
        unlockedNametags.clear();
        if (nametagsString != null && !nametagsString.isBlank()) {
            for (String s : nametagsString.split(",")) if (!s.isBlank()) unlockedNametags.add(s.trim());
        }
    }
}