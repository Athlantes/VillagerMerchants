package org.riftlabs.villagerMerchants.model;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.riftlabs.villagerMerchants.gui.StorageHolder;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class Shop {
    private final UUID id;          // the villager NPC's UUID
    private final UUID owner;
    private long rentExpiry;
    private final String location;
    private final Map<Integer, Trade> trades = new TreeMap<>();
    /** Live inventory: shared by owner (view) and trade logic, so no desync/dupes. */
    private final Inventory storage;
    private boolean dirty;

    public Shop(UUID id, UUID owner, long rentExpiry, String location) {
        this.id = id;
        this.owner = owner;
        this.rentExpiry = rentExpiry;
        this.location = location;
        this.storage = Bukkit.createInventory(new StorageHolder(this), 54, Text.mm("<dark_gray>Shop Storage"));
    }

    public UUID getId() { return id; }
    public UUID getOwner() { return owner; }
    public long getRentExpiry() { return rentExpiry; }
    public void setRentExpiry(long v) { rentExpiry = v; dirty = true; }
    public String getLocation() { return location; }
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
}
