package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Skins;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.HashMap;
import java.util.Map;

public class SkinGui implements GuiHolder, ShopBound {
    private static final int[] TYPE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] PROF_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;
    private final Map<Integer, Skins.Skin> bySlot = new HashMap<>();
    private String confirming;

    public SkinGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.inv = Bukkit.createInventory(this, 54, Text.mm("<dark_gray>Villager Skins"));
        for (int i = 0; i < Skins.TYPES.size(); i++) bySlot.put(TYPE_SLOTS[i], Skins.TYPES.get(i));
        for (int i = 0; i < Skins.PROFESSIONS.size(); i++) bySlot.put(PROF_SLOTS[i], Skins.PROFESSIONS.get(i));
        render();
    }

    private void render() {
        ShopManager sm = plugin.getShopManager();
        PlayerProfile p = sm.getProfile(player.getUniqueId());
        inv.clear();
        inv.setItem(4, Text.icon(Material.PAPER, "<white>Skins are permanent unlocks",
                "<gray>Your equipped skin is applied to all", "<gray>your shops, including future ones."));
        inv.setItem(9, Text.icon(Material.GRASS_BLOCK, "<aqua>Biome", "<gray>Changes the base look."));
        inv.setItem(27, Text.icon(Material.LECTERN, "<aqua>Outfit", "<gray>Changes the profession clothing."));

        for (Map.Entry<Integer, Skins.Skin> e : bySlot.entrySet()) {
            Skins.Skin s = e.getValue();
            ItemStack it;
            if (sm.isEquipped(p, s)) {
                it = Text.glow(Text.icon(s.icon(), "<green>" + s.name() + " <gray>(equipped)"));
            } else if (sm.owns(p, s)) {
                it = Text.icon(s.icon(), "<white>" + s.name(), "<yellow>Click to equip");
            } else if (s.id().equals(confirming)) {
                it = Text.glow(Text.icon(s.icon(), "<yellow>Click again to confirm",
                        "<gray>" + s.name() + " for <white>" + sm.money(sm.skinPrice(s))));
            } else {
                it = Text.icon(s.icon(), "<red>" + s.name() + " <gray>(locked)",
                        "<gray>Price: <white>" + sm.money(sm.skinPrice(s)), "<yellow>Click to buy");
            }
            inv.setItem(e.getKey(), it);
        }
        inv.setItem(49, Text.icon(Material.OAK_DOOR, "<red>Back"));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        if (slot == 49) {
            Bukkit.getScheduler().runTask(plugin, () -> new OwnerMenuGui(plugin, player, shop).open());
            return;
        }
        Skins.Skin s = bySlot.get(slot);
        if (s == null) return;
        ShopManager sm = plugin.getShopManager();
        PlayerProfile p = sm.getProfile(player.getUniqueId());
        if (sm.owns(p, s)) {
            sm.equipSkin(player, s);
            confirming = null;
        } else if (s.id().equals(confirming)) {
            confirming = null;
            sm.buySkin(player, s);
        } else {
            confirming = s.id();
        }
        render();
    }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
