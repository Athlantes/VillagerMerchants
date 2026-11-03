package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.Text;

/**
 * 36-slot editor. Rows 0-2 = payment 1 / payment 2 / result, 9 trades per page,
 * row 3 = navigation. Items are "ghosts": hold an item on your cursor and click a slot
 * to set it - nothing is consumed from your inventory.
 *
 * A trade can instead be priced in money: shift-left-click its Payment 1 slot (empty cursor)
 * and type the price in chat. Money trades use only that one price - no payment items.
 */
public class TradeEditorGui implements GuiHolder, ShopBound {
    private static final int PER_PAGE = 9;

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final PlayerProfile profile;
    private final Inventory inv;
    private int page;

    public TradeEditorGui(VillagerMerchants plugin, Player player, Shop shop) {
        this(plugin, player, shop, 0);
    }

    public TradeEditorGui(VillagerMerchants plugin, Player player, Shop shop, int page) {
        this.plugin = plugin;
        this.page = Math.max(0, page);
        this.player = player;
        this.shop = shop;
        this.profile = plugin.getShopManager().getProfile(shop.getOwner());
        this.inv = Bukkit.createInventory(this, 36, plugin.msg().get("trade-editor.title"));
        this.page = Math.min(this.page, pages() - 1);
        render();
    }

    private int pages() { return Math.max(1, (int) Math.ceil(profile.getUnlockedSlots() / (double) PER_PAGE)); }

    private ItemStack placeholder(int row) {
        var m = plugin.msg();
        return switch (row) {
            case 0 -> m.icon(plugin.material("trade-payment1", Material.YELLOW_STAINED_GLASS_PANE),
                    moneyOn() ? "trade-editor.payment1-money" : "trade-editor.payment1");
            case 1 -> m.icon(plugin.material("trade-payment2", Material.ORANGE_STAINED_GLASS_PANE), "trade-editor.payment2");
            default -> m.icon(plugin.material("trade-result", Material.LIME_STAINED_GLASS_PANE), "trade-editor.result");
        };
    }

    private boolean moneyOn() { return plugin.getShopManager().moneyTradesEnabled(); }

    private void render() {
        var m = plugin.msg();
        int unlocked = profile.getUnlockedSlots();
        for (int col = 0; col < PER_PAGE; col++) {
            int idx = page * PER_PAGE + col;
            for (int row = 0; row < 3; row++) {
                int slot = row * 9 + col;
                if (idx >= unlocked) {
                    inv.setItem(slot, m.icon(plugin.material("trade-locked", Material.BARRIER), "trade-editor.locked"));
                    continue;
                }
                Trade t = shop.getTrade(idx);
                if (t != null && t.isMoney() && row == 0) {
                    inv.setItem(slot, Text.glow(m.icon(plugin.getShopManager().tokenMaterial(), "trade-editor.price",
                            "price", plugin.getShopManager().money(t.getPrice()))));
                    continue;
                }
                if (t != null && t.isMoney() && row == 1) {
                    inv.setItem(slot, m.icon(plugin.material("trade-unused", Material.GRAY_STAINED_GLASS_PANE), "trade-editor.unused"));
                    continue;
                }
                ItemStack it = t == null ? null : t.get(row);
                inv.setItem(slot, it != null ? it.clone() : placeholder(row));
            }
        }
        ItemStack pane = Gui.filler(plugin);
        for (int i = 27; i < 36; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(27, Gui.previousPage(plugin));
        inv.setItem(29, Gui.back(plugin));
        inv.setItem(31, m.icon(plugin.material("trade-page", Material.PAPER), "trade-editor.page", "page", page + 1, "pages", pages()));
        if (shop.isOwner(player.getUniqueId())) inv.setItem(33, m.icon(plugin.material("trade-upgrades", Material.NETHER_STAR), "trade-editor.upgrades"));
        if (page + 1 < pages()) inv.setItem(35, Gui.nextPage(plugin));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) return;
        if (clicked != inv) { // own inventory: allow picking items up, block anything that could pull from/push to the GUI
            if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK
                    || e.getAction() == InventoryAction.COLLECT_TO_CURSOR) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        int slot = e.getSlot();

        if (slot >= 27) {
            switch (slot) {
                case 27 -> { if (page > 0) { page--; render(); } }
                case 35 -> { if (page + 1 < pages()) { page++; render(); } }
                case 29 -> Bukkit.getScheduler().runTask(plugin, () -> new OwnerMenuGui(plugin, player, shop).open());
                case 33 -> { if (shop.isOwner(player.getUniqueId())) Bukkit.getScheduler().runTask(plugin, () -> new UpgradeGui(plugin, player, shop).open()); }
                default -> {}
            }
            return;
        }

        int row = slot / 9, col = slot % 9;
        int idx = page * PER_PAGE + col;
        if (idx >= profile.getUnlockedSlots()) return;

        ItemStack cursor = e.getCursor();
        Trade t = shop.getOrCreateTrade(idx);
        ItemStack current = t.get(row);

        if (t.isMoney() && row == 1) {
            // money trades have no second payment
        } else if (cursor != null && !cursor.getType().isAir()) {
            var aa = plugin.getAntiAbuse();
            if (aa.isBannedItem(cursor)) {
                aa.logBlockedEdit(player, shop, cursor, idx, row);
                plugin.msg().send(player, "anti-abuse.banned-item");
                shop.pruneTrade(idx);
                render();
                return;
            }
            // Selling something new at an existing money price: the price must still fit the new item's cap.
            if (row == 2 && t.isMoney() && t.getPrice() > aa.maxTotal(cursor) + 0.0001) {
                aa.logBlockedPrice(player, shop, cursor, t.getPrice(), aa.maxTotal(cursor));
                plugin.msg().send(player, "anti-abuse.price-too-high-editor", "price", plugin.getShopManager().money(t.getPrice()),
                        "max", plugin.getShopManager().money(aa.maxTotal(cursor)));
                shop.pruneTrade(idx);
                render();
                return;
            }
            if (row != 2) t.setPrice(0); // setting a payment item switches the trade back to item pricing
            t.set(row, cursor.clone());
        } else if (row == 0 && moneyOn() && e.getClick() == ClickType.SHIFT_LEFT) {
            final int tradeIdx = idx, tradePage = page;
            shop.pruneTrade(idx); // don't leave an empty trade behind while the owner types
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                plugin.getPrompts().begin(player, shop, tradeIdx, tradePage);
            });
            return;
        } else if (row == 0 && t.isMoney()) {
            t.setPrice(0);
        } else if (current != null) {
            int max = current.getMaxStackSize();
            int before = current.getAmount();
            switch (e.getClick()) {
                case RIGHT -> current.setAmount(current.getAmount() + 1 > max ? 1 : current.getAmount() + 1);
                case SHIFT_RIGHT -> current.setAmount(current.getAmount() - 1 < 1 ? max : current.getAmount() - 1);
                default -> t.set(row, null);
            }
            // Shrinking the stack being sold lowers its price cap (cap x amount): don't allow it to drop below the price.
            if (row == 2 && t.getResult() != null && t.isMoney()) {
                double cap = plugin.getAntiAbuse().maxTotal(t.getResult());
                if (t.getPrice() > cap + 0.0001) {
                    t.getResult().setAmount(before);
                    plugin.msg().send(player, "anti-abuse.price-too-high-editor", "price", plugin.getShopManager().money(t.getPrice()),
                            "max", plugin.getShopManager().money(cap));
                }
            }
        }
        shop.pruneTrade(idx);
        render();
    }

    @Override
    public void onClose(InventoryCloseEvent e) {
        plugin.getShopManager().saveShop(shop);
    }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
