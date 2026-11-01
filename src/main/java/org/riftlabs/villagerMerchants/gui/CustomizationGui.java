package org.riftlabs.villagerMerchants.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
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

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomizationGui implements GuiHolder, ShopBound {
    private enum Tab { SKINS, NAMETAGS }
    private record NametagPreset(String id, String format, String name, double price) {}

    // Fixed layout slots for Skins tab
    private static final int[] TYPE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] PROF_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    // Paginated grid slots for Nametags tab
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int PAGE_SIZE = CONTENT_SLOTS.length;

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;

    private final List<NametagPreset> allNametags = new ArrayList<>();
    private final Map<Integer, Skins.Skin> skinBySlot = new HashMap<>();

    private final boolean skinsEnabled;
    private final boolean tagsEnabled;

    private Tab currentTab = Tab.SKINS;
    private int page = 0;
    private String confirming;

    public CustomizationGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;

        this.skinsEnabled = plugin.getConfig().getBoolean("customization.skins-enabled", true);
        this.tagsEnabled = plugin.getConfig().getBoolean("customization.nametags-enabled", true);

        if (!skinsEnabled && tagsEnabled) this.currentTab = Tab.NAMETAGS;

        loadSkinsMapping();
        loadNametagsFromFile();

        this.inv = Bukkit.createInventory(this, 54, Text.mm("<dark_gray>Shop Customization"));
        render();
    }

    private void loadSkinsMapping() {
        skinBySlot.clear();
        for (int i = 0; i < Skins.TYPES.size() && i < TYPE_SLOTS.length; i++) {
            skinBySlot.put(TYPE_SLOTS[i], Skins.TYPES.get(i));
        }
        for (int i = 0; i < Skins.PROFESSIONS.size() && i < PROF_SLOTS.length; i++) {
            skinBySlot.put(PROF_SLOTS[i], Skins.PROFESSIONS.get(i));
        }
    }

    private void loadNametagsFromFile() {
        File file = new File(plugin.getDataFolder(), "nametags.yml");
        if (!file.exists()) {
            plugin.saveResource("nametags.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            String name = cfg.getString(key + ".name", "Unknown");
            String format = cfg.getString(key + ".format", "<white><bold>Shop</bold></white>");
            double price = cfg.getDouble(key + ".price", 0.0);
            allNametags.add(new NametagPreset(key, format, name, price));
        }
    }

    private void render() {
        ShopManager sm = plugin.getShopManager();
        PlayerProfile p = sm.getProfile(player.getUniqueId());
        inv.clear();

        // Render Tabs
        if (skinsEnabled) {
            inv.setItem(3, Text.icon(Material.LEATHER_CHESTPLATE,
                    currentTab == Tab.SKINS ? "<green>▶ Skins" : "<gray>Skins", "<yellow>Click to view outfit upgrades"));
        }
        if (tagsEnabled) {
            inv.setItem(5, Text.icon(Material.NAME_TAG,
                    currentTab == Tab.NAMETAGS ? "<green>▶ Nametags" : "<gray>Nametags", "<yellow>Click to view hologram upgrades"));
        }

        if (currentTab == Tab.SKINS && skinsEnabled) {
            // Biome and Outfit description items have been removed here.
            for (Map.Entry<Integer, Skins.Skin> entry : skinBySlot.entrySet()) {
                inv.setItem(entry.getKey(), renderSkinItem(sm, p, entry.getValue()));
            }
        } else if (currentTab == Tab.NAMETAGS && tagsEnabled) {
            int maxPages = Math.max(1, (int) Math.ceil((double) allNametags.size() / PAGE_SIZE));
            if (page >= maxPages) page = maxPages - 1;

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, allNametags.size());

            for (int i = start; i < end; i++) {
                NametagPreset data = allNametags.get(i);
                int slot = CONTENT_SLOTS[i - start];
                ItemStack it = new ItemStack(Material.NAME_TAG);
                org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();

                // Safe formatting for inventory items (turn animations into a static rainbow or color)
                String playerName = player.getName();
                String parsedFormat = data.format()
                        .replace("<anim_rainbow>", "<rainbow>") // MiniMessage static rainbow
                        .replace("</anim_rainbow>", "</rainbow>")
                        .replace("<bold>", "<bold>" + playerName + "'s ")
                        .replace("Available Shop", "Shop");

                meta.displayName(Text.mm(parsedFormat));

                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(Text.mm("<gray>Price: <white>$" + data.price()));
                lore.add(net.kyori.adventure.text.Component.empty());

                if (p.getEquippedNametagFormat().equals(data.format())) {
                    lore.add(Text.mm("<green>✔ Currently Equipped"));
                    it = Text.glow(it);
                } else if (p.ownsNametag(data.id())) {
                    lore.add(Text.mm("<yellow>Click to equip!"));
                } else if (data.id().equals(confirming)) {
                    lore.add(Text.mm("<yellow>Click again to confirm purchase!"));
                } else {
                    lore.add(Text.mm("<yellow>Click to purchase."));
                }

                meta.lore(lore);
                it.setItemMeta(meta);
                inv.setItem(slot, it);
            }

            // Pagination Controls
            if (page > 0) {
                inv.setItem(48, Text.icon(Material.ARROW, "<yellow>Previous Page", "<gray>Page " + page + " / " + maxPages));
            }
            if (page < maxPages - 1) {
                inv.setItem(50, Text.icon(Material.ARROW, "<yellow>Next Page", "<gray>Page " + (page + 2) + " / " + maxPages));
            }
        }

        inv.setItem(49, Text.icon(Material.OAK_DOOR, "<red>Back"));
        fillEmptySlots();
    }

    private ItemStack renderSkinItem(ShopManager sm, PlayerProfile p, Skins.Skin s) {
        if (sm.isEquipped(p, s)) {
            return Text.glow(Text.icon(s.icon(), "<green>" + s.name() + " <gray>(equipped)"));
        } else if (sm.owns(p, s)) {
            return Text.icon(s.icon(), "<white>" + s.name(), "<yellow>Click to equip");
        } else if (s.id().equals(confirming)) {
            return Text.glow(Text.icon(s.icon(), "<yellow>Click again to confirm",
                    "<gray>" + s.name() + " for <white>" + sm.money(sm.skinPrice(s))));
        } else {
            return Text.icon(s.icon(), "<red>" + s.name() + " <gray>(locked)",
                    "<gray>Price: <white>" + sm.money(sm.skinPrice(s)), "<yellow>Click to buy");
        }
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

        if (slot == 3 && skinsEnabled) { currentTab = Tab.SKINS; page = 0; confirming = null; render(); return; }
        if (slot == 5 && tagsEnabled) { currentTab = Tab.NAMETAGS; page = 0; confirming = null; render(); return; }

        // Pagination Clicks
        if (currentTab == Tab.NAMETAGS && tagsEnabled) {
            if (slot == 48 && page > 0) { page--; confirming = null; render(); return; }
            if (slot == 50) {
                int maxPages = (int) Math.ceil((double) allNametags.size() / PAGE_SIZE);
                if (page < maxPages - 1) { page++; confirming = null; render(); return; }
            }
        }

        if (currentTab == Tab.NAMETAGS && tagsEnabled) {
            int contentIndex = -1;
            for (int i = 0; i < CONTENT_SLOTS.length; i++) {
                if (CONTENT_SLOTS[i] == slot) {
                    contentIndex = (page * PAGE_SIZE) + i;
                    break;
                }
            }

            if (contentIndex >= 0 && contentIndex < allNametags.size()) {
                NametagPreset data = allNametags.get(contentIndex);
                ShopManager sm = plugin.getShopManager();
                PlayerProfile p = sm.getProfile(player.getUniqueId());

                if (p.getEquippedNametagFormat().equals(data.format())) {
                    return;
                } else if (p.ownsNametag(data.id())) {
                    p.setEquippedNametagFormat(data.format());
                    plugin.getNametagManager().updateShopNametag(shop.getId(), data.format());
                    plugin.getDatabaseManager().async(() ->
                            plugin.getDatabaseManager().updateNametagData(player.getUniqueId().toString(), p.getUnlockedNametagsString(), p.getEquippedNametagFormat())
                    );
                    confirming = null;
                    render();
                } else if (data.id().equals(confirming)) {
                    if (plugin.getEconomy().has(player, data.price())) {
                        plugin.getEconomy().withdrawPlayer(player, data.price());
                        p.unlockNametag(data.id());
                        p.setEquippedNametagFormat(data.format());
                        plugin.getNametagManager().updateShopNametag(shop.getId(), data.format());
                        plugin.getDatabaseManager().async(() ->
                                plugin.getDatabaseManager().updateNametagData(player.getUniqueId().toString(), p.getUnlockedNametagsString(), p.getEquippedNametagFormat())
                        );
                        player.sendMessage(Text.mm("<green>Purchased and applied: " + data.name()));
                    } else {
                        player.sendMessage(Text.mm("<red>You don't have enough money!"));
                    }
                    confirming = null;
                    render();
                } else {
                    confirming = data.id();
                    render();
                }
            }
        } else if (currentTab == Tab.SKINS && skinsEnabled) {
            Skins.Skin clickedSkin = skinBySlot.get(slot);
            if (clickedSkin == null) return;

            ShopManager sm = plugin.getShopManager();
            PlayerProfile p = sm.getProfile(player.getUniqueId());

            if (sm.owns(p, clickedSkin)) {
                sm.equipSkin(player, clickedSkin);
                confirming = null;
            } else if (clickedSkin.id().equals(confirming)) {
                confirming = null;
                sm.buySkin(player, clickedSkin);
            } else {
                confirming = clickedSkin.id();
            }
            render();
        }
    }

    private void fillEmptySlots() {
        ItemStack pane = Text.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, pane);
            }
        }
    }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}