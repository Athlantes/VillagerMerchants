package org.riftlabs.villagerMerchants.model;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.riftlabs.villagerMerchants.gui.StorageHolder;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class Shop {
    private final UUID id;          // stable shop id (NOT the entity UUID: entities respawn with new UUIDs)
    private final UUID owner;
    private long rentExpiry;
    private String location;
    private String linkedSkin = "";   // equipped variant from linked-skins.yml (shops on a MythicMob / ModelEngine body)
    private final String npcId;     // the NPC slot (/vm create <shop-id>) this shop is rented at; null = legacy shop without a slot
    // Folia: the owner edits, customers buy and the autosave reads, each possibly on a different region thread.
    // A concurrent sorted map keeps the TreeMap ordering without ConcurrentModificationException.
    private final Map<Integer, Trade> trades = new java.util.concurrent.ConcurrentSkipListMap<>();
    /** Live inventory: shared by owner (view) and trade logic, so no desync/dupes. */
    private final Inventory storage;
    private volatile boolean dirty;

    // ---- staff
    private final Map<UUID, StaffRole> staff = java.util.Collections.synchronizedMap(new LinkedHashMap<>());

    // ---- sale (percent > 0 and now < saleExpiry = running; saleExpiry is kept afterwards for the cooldown)
    private int salePercent;
    private long saleExpiry;
    private boolean saleListed;

    // ---- featured listing
    private long featuredUntil;

    public Shop(UUID id, UUID owner, long rentExpiry, String location, String npcId) {
        this.id = id;
        this.owner = owner;
        this.rentExpiry = rentExpiry;
        this.location = location;
        this.npcId = npcId;
        this.storage = Bukkit.createInventory(new StorageHolder(this), 54, org.riftlabs.villagerMerchants.VillagerMerchants.getInstance().msg().get("storage.title"));
    }

    public UUID getId() { return id; }
    public UUID getOwner() { return owner; }
    public long getRentExpiry() { return rentExpiry; }
    public void setRentExpiry(long v) { rentExpiry = v; dirty = true; }
    public String getLocation() { return location; }
    public void setLocation(String loc) { if (loc != null && !loc.equals(location)) { location = loc; dirty = true; } }
    public String getNpcId() { return npcId; }
    public String getLinkedSkin() { return linkedSkin; }
    public void setLinkedSkin(String v) { linkedSkin = v == null ? "" : v; dirty = true; }
    public Inventory getStorage() { return storage; }
    public Map<Integer, Trade> getTrades() { return trades; }
    public boolean isDirty() { return dirty; }
    public void setDirty(boolean d) { dirty = d; }
    public boolean isOwner(UUID u) { return owner.equals(u); }
    public boolean isActive(long now) { return now <= rentExpiry; }

    public Trade getTrade(int idx) { return trades.get(idx); }
    public Trade getOrCreateTrade(int idx) { return trades.computeIfAbsent(idx, k -> new Trade()); }
    public void setTrade(int idx, Trade t) { trades.put(idx, t); }

    public void pruneTrade(int idx) {
        Trade t = trades.get(idx);
        if (t != null && t.isEmpty()) trades.remove(idx);
        dirty = true;
    }

    /** Only complete trades, in slot order. Recipe index == list index. */
    public List<Trade> completeTrades() {
        List<Trade> list = new ArrayList<>();
        for (Trade t : trades.values()) if (t.isComplete()) list.add(t);
        return list;
    }

    // ------------------------------------------------------------ staff

    /** Owner or any staff member. Members can't buy from or rate the shop (no wash trading). */
    public boolean isMember(UUID u) { return owner.equals(u) || staff.containsKey(u); }
    public StaffRole roleOf(UUID u) { return staff.get(u); }
    public Map<UUID, StaffRole> getStaff() { return Collections.unmodifiableMap(staff); }

    /** The owner can do everything; staff can do what their role allows. */
    public boolean can(UUID u, Perm perm) {
        if (owner.equals(u)) return true;
        StaffRole r = staff.get(u);
        return r != null && r.has(perm);
    }

    public void setStaff(UUID u, StaffRole role) { staff.put(u, role); dirty = true; }
    public boolean removeStaff(UUID u) { boolean b = staff.remove(u) != null; if (b) dirty = true; return b; }

    public String serializeStaff() {
        StringBuilder sb = new StringBuilder();
        synchronized (staff) {   // iterating a synchronized map needs its lock
            for (Map.Entry<UUID, StaffRole> e : staff.entrySet()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(e.getKey()).append(':').append(e.getValue().name());
            }
        }
        return sb.toString();
    }

    public void loadStaff(String raw) {
        staff.clear();
        if (raw == null || raw.isBlank()) return;
        for (String part : raw.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            try {
                StaffRole r = StaffRole.parse(kv[1]);
                if (r != null) staff.put(UUID.fromString(kv[0].trim()), r);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    // ------------------------------------------------------------ sale

    public boolean isOnSale(long now) { return salePercent > 0 && now < saleExpiry; }
    public int getSalePercent() { return salePercent; }
    public long getSaleExpiry() { return saleExpiry; }
    public boolean isSaleListed() { return saleListed; }

    public void startSale(int percent, long expiry, boolean listed) {
        this.salePercent = percent;
        this.saleExpiry = expiry;
        this.saleListed = listed;
        dirty = true;
    }

    /** Ends the sale now. saleExpiry is kept (set to {@code now}) so the cooldown can be measured from it. */
    public void endSale(long now) {
        this.salePercent = 0;
        this.saleExpiry = Math.min(saleExpiry, now);
        this.saleListed = false;
        dirty = true;
    }

    public void setSaleListed(boolean listed) { this.saleListed = listed; dirty = true; }

    /** Only money-priced trades can be discounted: item-for-item trades have no price to cut. */
    public double effectivePrice(Trade t, long now) {
        if (!t.isMoney()) return 0;
        if (!isOnSale(now)) return t.getPrice();
        double p = t.getPrice() * (100 - salePercent) / 100.0;
        return Math.max(0.01, Math.round(p * 100.0) / 100.0);
    }

    // ------------------------------------------------------------ featured

    public long getFeaturedUntil() { return featuredUntil; }
    public boolean isFeatured(long now) { return now < featuredUntil; }
    public void setFeaturedUntil(long v) { featuredUntil = v; dirty = true; }

    /** Copies everything that isn't trades/storage. The NPC is respawned with a new UUID on every restart. */
    public void copyExtrasFrom(Shop o) {
        staff.clear();
        staff.putAll(o.staff);
        salePercent = o.salePercent;
        saleExpiry = o.saleExpiry;
        saleListed = o.saleListed;
        featuredUntil = o.featuredUntil;
    }
}
