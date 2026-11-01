package org.riftlabs.villagerMerchants.model;

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
}
