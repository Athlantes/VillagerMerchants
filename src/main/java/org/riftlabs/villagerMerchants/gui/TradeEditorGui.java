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
        this.inv = Bukkit.createInventory(this, 36, Text.mm("<dark_gray>Edit Trades"));
        this.page = Math.min(this.page, pages() - 1);
        render();
    }

    private int pages() { return Math.max(1, (int) Math.ceil(profile.getUnlockedSlots() / (double) PER_PAGE)); }

    private ItemStack placeholder(int row) {
        return switch (row) {
            case 0 -> moneyOn()
                    ? Text.icon(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Payment 1", "<gray>Hold an item and click to set.",
                            "<gray>Or <white>shift-left-click</white> with an empty hand", "<gray>to charge money instead.")
                    : Text.icon(Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Payment 1", "<gray>Hold an item and click to set.");
            case 1 -> Text.icon(Material.ORANGE_STAINED_GLASS_PANE, "<gold>Payment 2 <gray>(optional)", "<gray>Hold an item and click to set.");
            default -> Text.icon(Material.LIME_STAINED_GLASS_PANE, "<green>Result", "<gray>What the customer receives.");
        };
    }

    private boolean moneyOn() { return plugin.getShopManager().moneyTradesEnabled(); }

    private void render() {
        int unlocked = profile.getUnlockedSlots();
        for (int col = 0; col < PER_PAGE; col++) {
            int idx = page * PER_PAGE + col;
            for (int row = 0; row < 3; row++) {
                int slot = row * 9 + col;
                if (idx >= unlocked) {
                    inv.setItem(slot, Text.icon(Material.BARRIER, "<red>Locked slot", "<gray>Buy more in Upgrades."));
                    continue;
                }
                Trade t = shop.getTrade(idx);
                if (t != null && t.isMoney() && row == 0) {
                    inv.setItem(slot, Text.glow(Text.icon(plugin.getShopManager().tokenMaterial(),
                            "<gold>Price: " + plugin.getShopManager().money(t.getPrice()),
                            "<gray>Customers pay this from their balance.",
                            "<yellow>Shift-left-click: change price", "<yellow>Left-click: remove price")));
                    continue;
                }
                if (t != null && t.isMoney() && row == 1) {
                    inv.setItem(slot, Text.icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>Not used",
                            "<dark_gray>Money trades have a single price.",
                            "<dark_gray>Remove the price to use items again."));
                    continue;
                }
                ItemStack it = t == null ? null : t.get(row);
                inv.setItem(slot, it != null ? it.clone() : placeholder(row));
            }
        }
        ItemStack pane = Text.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 27; i < 36; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(27, Text.icon(Material.ARROW, "<yellow>Previous page"));
        inv.setItem(29, Text.icon(Material.OAK_DOOR, "<red>Back"));
        inv.setItem(31, Text.icon(Material.PAPER, "<white>Page " + (page + 1) + "/" + pages(),
                "<gray>Left-click: clear slot", "<gray>Right-click: amount +1", "<gray>Shift-right: amount -1",
                "<gray>Only trades with a payment (items or a money", "<gray>price) and a Result are live."));
        if (shop.isOwner(player.getUniqueId())) inv.setItem(33, Text.icon(Material.NETHER_STAR, "<light_purple>Upgrades"));
        if (page + 1 < pages()) inv.setItem(35, Text.icon(Material.ARROW, "<yellow>Next page"));
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
            switch (e.getClick()) {
                case RIGHT -> current.setAmount(current.getAmount() + 1 > max ? 1 : current.getAmount() + 1);
                case SHIFT_RIGHT -> current.setAmount(current.getAmount() - 1 < 1 ? max : current.getAmount() - 1);
                default -> t.set(row, null);
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
