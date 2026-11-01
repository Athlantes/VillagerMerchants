package org.riftlabs.villagerMerchants.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** /shops - every open shop on the server, sortable, with click-to-teleport. */
public class ShopDirectoryGui implements GuiHolder {
    private static final int PER_PAGE = 45;

    private enum Sort {
        RATING("Top rated"), POPULAR("Most popular"), NAME("Owner name (A-Z)");

        final String label;
        Sort(String label) { this.label = label; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private final VillagerMerchants plugin;
    private final Player player;
    private final Inventory inv;
    private final Map<Integer, Shop> bySlot = new HashMap<>();
    private int page;
    private Sort sort = Sort.RATING;

    public ShopDirectoryGui(VillagerMerchants plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv = Bukkit.createInventory(this, 54, Text.mm("<dark_gray>Shop Directory"));
        render();
    }

    public void open() { player.openInventory(inv); }

    // ------------------------------------------------------------ data

    private List<Shop> listing() {
        ShopManager sm = plugin.getShopManager();
        RatingManager rm = plugin.getRatings();
        long now = System.currentTimeMillis();
        boolean hideEmpty = plugin.getConfig().getBoolean("directory.hide-empty-shops", true);

        List<Shop> list = new ArrayList<>();
        for (Shop s : sm.allShops()) {
            if (!s.isActive(now)) continue;
            if (hideEmpty && s.completeTrades().isEmpty()) continue;
            list.add(s);
        }
        Comparator<Shop> byName = Comparator.comparing((Shop s) -> sm.ownerName(s.getOwner()).toLowerCase(Locale.ROOT));
        switch (sort) {
            case RATING -> list.sort(Comparator.<Shop>comparingDouble(rm::score).reversed()
                    .thenComparing(Comparator.comparingLong((Shop s) -> plugin.getTransactions().salesCount(s)).reversed())
                    .thenComparing(byName));
            case POPULAR -> list.sort(Comparator.comparingLong((Shop s) -> plugin.getTransactions().salesCount(s)).reversed()
                    .thenComparing(byName));
            case NAME -> list.sort(byName);
        }
        return list;
    }

    // ------------------------------------------------------------ rendering

    private void render() {
        inv.clear();
        bySlot.clear();
        List<Shop> list = listing();
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) break;
            Shop s = list.get(idx);
            inv.setItem(i, entry(s));
            bySlot.put(i, s);
        }

        ItemStack pane = Text.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(45, Text.icon(Material.ARROW, "<yellow>Previous page"));
        inv.setItem(47, Text.icon(Material.HOPPER, "<aqua>Sort: <white>" + sort.label, "<yellow>Click to change"));
        inv.setItem(49, Text.icon(Material.PAPER, "<white>Shop Directory",
                "<gray>Open shops: <white>" + list.size(), "<gray>Page <white>" + (page + 1) + "/" + pages));
        inv.setItem(51, Text.icon(Material.BARRIER, "<red>Close"));
        if (page + 1 < pages) inv.setItem(53, Text.icon(Material.ARROW, "<yellow>Next page"));
        if (list.isEmpty()) {
            inv.setItem(22, Text.icon(Material.COBWEB, "<gray>No open shops yet", "<dark_gray>Rent a shop NPC to be the first!"));
        }
    }

    private ItemStack entry(Shop s) {
        ShopManager sm = plugin.getShopManager();
        String owner = sm.ownerName(s.getOwner());
        List<String> lore = new ArrayList<>();

        RatingManager.Summary r = plugin.getRatings().summary(s);
        lore.add(r.count() > 0
                ? "<gray>Rating: " + Text.stars(r.average()) + " <white>" + Text.oneDecimal(r.average()) + " <gray>(" + r.count() + ")"
                : "<gray>Rating: <dark_gray>not rated yet");
        lore.add("<gray>Sales: <white>" + plugin.getTransactions().salesCount(s));

        int offers = 0, money = 0;
        Set<String> names = new LinkedHashSet<>();
        for (Trade t : s.completeTrades()) {
            offers++;
            if (t.isMoney()) money++;
            names.add(Text.esc(Text.itemName(t.getResult())));
        }
        lore.add("<gray>Offers: <white>" + offers + (money > 0 ? " <gray>(" + money + " for money)" : ""));
        if (!names.isEmpty()) {
            List<String> shown = new ArrayList<>(names).subList(0, Math.min(3, names.size()));
            int more = names.size() - shown.size();
            lore.add("<gray>Sells: <white>" + String.join("<gray>, <white>", shown) + (more > 0 ? " <gray>+" + more + " more" : ""));
        }
        if (plugin.getConfig().getBoolean("directory.show-coordinates", false)) {
            String[] p = s.getLocation() == null ? new String[0] : s.getLocation().split(";");
            if (p.length == 4) lore.add("<gray>Location: <white>" + Text.esc(p[0]) + " <gray>" + p[1] + ", " + p[2] + ", " + p[3]);
        }

        lore.add("");
        var tp = plugin.getTeleports();
        if (tp.enabled() && player.hasPermission("villagermerchants.shops.teleport")) {
            lore.add("<yellow>Click to teleport");
            if (tp.cost() > 0 && !player.hasPermission("villagermerchants.shops.bypass")) {
                lore.add("<gray>Cost: <white>" + sm.money(tp.cost()));
            }
        } else {
            lore.add("<dark_gray>Teleporting is unavailable");
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(s.getOwner()));
        meta.displayName(Text.item("<gold>" + Text.esc(owner) + "'s Shop"));
        List<Component> loreC = new ArrayList<>();
        for (String l : lore) loreC.add(Text.item(l));
        meta.lore(loreC);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        head.setItemMeta(meta);
        return head;
    }

    // ------------------------------------------------------------ clicks

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        switch (slot) {
            case 45 -> { if (page > 0) { page--; render(); } }
            case 53 -> { page++; render(); }
            case 47 -> { sort = sort.next(); page = 0; render(); }
            case 51 -> later(player::closeInventory);
            default -> {
                Shop s = bySlot.get(slot);
                if (s == null) return;
                later(() -> {
                    player.closeInventory();
                    plugin.getTeleports().request(player, s);
                });
            }
        }
    }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    @Override public Inventory getInventory() { return inv; }
}
