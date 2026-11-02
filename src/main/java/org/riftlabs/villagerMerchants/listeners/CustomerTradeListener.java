package org.riftlabs.villagerMerchants.listeners;

import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.TradeSelectEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.CurrencyToken;
import org.riftlabs.villagerMerchants.util.InvUtil;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.*;

/**
 * Opens the native Merchant UI and executes trades ourselves (the vanilla result click is
 * cancelled), so stock and payments move between the customer and the shop's live storage.
 *
 * Item trades pull payment items out of the customer's trade slots. Money trades have no payment
 * items: the recipe shows a display-only currency token, and the price is charged to the
 * customer's Vault balance and paid to the shop owner.
 */
public class CustomerTradeListener implements Listener {
    private static final class Session {
        final UUID shopId;
        boolean bought;
        /** Money prices (sale applied) as shown in the open merchant window, per live trade. */
        double[] prices;
        Session(UUID shopId) { this.shopId = shopId; }
    }

    private final VillagerMerchants plugin;
    private final Map<UUID, Session> sessions = new HashMap<>(); // player -> open shop

    public CustomerTradeListener(VillagerMerchants plugin) { this.plugin = plugin; }

    public void open(Player p, Shop shop) {
        ShopManager sm = plugin.getShopManager();
        List<MerchantRecipe> recipes = sm.buildRecipes(shop);
        if (recipes.isEmpty()) {
            plugin.msg().send(p, "customer.nothing-for-sale");
            return;
        }
        RatingManager.Summary rating = plugin.getRatings().summary(shop);
        String owner = sm.ownerName(shop.getOwner());
        Merchant m = Bukkit.createMerchant(rating.count() > 0
                ? plugin.msg().get("customer.title-rated", "owner", owner, "rating", Text.oneDecimal(rating.average()))
                : plugin.msg().get("customer.title", "owner", owner));
        m.setRecipes(recipes);
        p.openMerchant(m, true);
        Session session = new Session(shop.getId());
        session.prices = sm.snapshotPrices(shop);
        sessions.put(p.getUniqueId(), session);
    }

    public void closeSessions(UUID shopId) {
        for (Map.Entry<UUID, Session> e : new ArrayList<>(sessions.entrySet())) {
            if (e.getValue().shopId.equals(shopId)) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) p.closeInventory();
                sessions.remove(e.getKey());
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getType() != InventoryType.MERCHANT) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        // Money trades keep a display token in the payment slot. This event fires before vanilla hands
        // the trade slots back to the player, so remove it here or it would end up in their inventory.
        if (e.getInventory() instanceof MerchantInventory mi) clearTokens(mi);
        CurrencyToken.scrub(p);
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        if (s.bought) {
            Shop shop = plugin.getShopManager().getShop(s.shopId);
            if (shop != null) plugin.getRatings().maybePrompt(p, shop);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { sessions.remove(e.getPlayer().getUniqueId()); }

    /**
     * A money trade has no payment items, and the vanilla client recalculates the result slot from the
     * payment slots on every slot change (so a result we place there alone gets wiped). Instead we put
     * the trade's display token into the payment slot: client and server then both see a valid payment
     * and show the result themselves. The token is display-only: it can't be taken out, is never
     * consumed, and is removed again on close.
     * Runs one tick after the selection so it executes after vanilla's own selection handling.
     */
    @EventHandler
    public void onSelect(TradeSelectEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        Shop shop = plugin.getShopManager().getShop(s.shopId);
        if (shop == null) return;
        int idx = e.getIndex();
        Bukkit.getScheduler().runTask(plugin, () -> syncResultSlot(p, s, shop, idx));
    }

    /** Money trade: place its token in the payment slot. Item trade: make sure no token is left over. */
    private void syncResultSlot(Player p, Session s, Shop shop, int idx) {
        if (sessions.get(p.getUniqueId()) != s) return;
        if (!(p.getOpenInventory().getTopInventory() instanceof MerchantInventory mi)) return;
        List<Trade> trades = shop.completeTrades();
        if (idx < 0 || idx >= trades.size()) return;

        // when the selection changes vanilla moves the old payment slot content into the player's bag
        CurrencyToken.scrub(p);
        clearTokens(mi);

        if (!isEmpty(mi.getItem(0)) || !isEmpty(mi.getItem(1))) return; // real payment items: vanilla owns the result slot
        Trade t = trades.get(idx);
        if (!t.isMoney()) { mi.setItem(2, null); return; }

        Merchant merchant = mi.getMerchant();
        if (idx >= merchant.getRecipeCount()) return;
        List<ItemStack> ingredients = merchant.getRecipe(idx).getIngredients();
        if (ingredients.isEmpty()) return;
        // use the recipe's own ingredient so it matches exactly; setting it makes vanilla compute the result
        mi.setItem(0, ingredients.get(0).clone());
    }

    private static void clearTokens(MerchantInventory mi) {
        for (int i = 0; i < 2; i++) {
            if (CurrencyToken.isToken(mi.getItem(i))) mi.setItem(i, null);
        }
    }

    private static boolean isEmpty(ItemStack it) { return it == null || it.getType().isAir(); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory() instanceof MerchantInventory mi)) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Session session = sessions.get(p.getUniqueId());
        if (session == null) return;                                  // not one of our shops
        if (e.getRawSlot() < 2 && CurrencyToken.isToken(e.getCurrentItem())) { // display token: look, don't touch
            e.setCancelled(true);
            return;
        }
        if (e.getRawSlot() != 2) return;                               // only the result slot is special

        e.setCancelled(true);
        ClickType c = e.getClick();
        if (c != ClickType.LEFT && c != ClickType.RIGHT && c != ClickType.SHIFT_LEFT && c != ClickType.SHIFT_RIGHT) return;

        ShopManager sm = plugin.getShopManager();
        Shop shop = sm.getShop(session.shopId);
        if (shop == null || !shop.isActive(System.currentTimeMillis())) {
            p.closeInventory();
            plugin.msg().send(p, "customer.shop-closed");
            return;
        }
        if (shop.isMember(p.getUniqueId())) {
            plugin.msg().send(p, "customer.preview-mode");
            return;
        }
        List<Trade> trades = shop.completeTrades();
        int idx = mi.getSelectedRecipeIndex();
        if (idx < 0 || idx >= trades.size()) return;
        Trade trade = trades.get(idx);
        // NOTE: mi.getSelectedRecipe() is the *active offer* and is null while the payment slots are empty
        // (always the case for money trades), so check the recipe list we gave the merchant instead.
        Merchant merchant = mi.getMerchant();
        MerchantRecipe sel = idx < merchant.getRecipeCount() ? merchant.getRecipe(idx) : null;
        if (sel == null || !sel.getResult().isSimilar(trade.getResult())) {
            p.closeInventory();
            plugin.msg().send(p, "customer.shop-updated");
            return;
        }

        // The price on screen must be the price we charge: a sale may have started/ended or the owner edited
        // a price while this window was open.
        double unit = sm.effectivePrice(shop, trade);
        if (trade.isMoney() && (session.prices == null || idx >= session.prices.length
                || Math.abs(session.prices[idx] - unit) > 0.0001)) {
            p.closeInventory();
            plugin.msg().send(p, "customer.prices-changed");
            return;
        }

        int times = e.isShiftClick() ? Math.max(1, plugin.getConfig().getInt("trading.shift-click-max", 64)) : 1;
        int done = 0;
        Component err = null;
        if (trade.isMoney()) {
            Component[] holder = new Component[1];
            done = tradeMoney(p, shop, trade, unit, times, holder);
            err = holder[0];
        } else {
            while (done < times && (err = tryTrade(p, shop, trade, mi)) == null) done++;
        }

        if (done == 0 && err != null) p.sendMessage(err);
        if (done > 0) {
            shop.setDirty(true);
            session.bought = true;
            plugin.getTransactions().recordSale(p, shop, trade, done, unit);
            plugin.getRatings().recordPurchase(p, shop);
            merchant.setRecipes(sm.buildRecipes(shop)); // re-lock sold-out trades
            session.prices = sm.snapshotPrices(shop);
        }
        final int selected = idx;
        Bukkit.getScheduler().runTask(plugin, () -> syncResultSlot(p, session, shop, selected));
        p.updateInventory();
    }

    // ------------------------------------------------------------ money trades

    /**
     * Buys up to {@code times} units of a money trade. Stock and inventory room are simulated first, so the
     * customer is charged exactly once for exactly what they receive, whatever the shift-click count.
     *
     * @return how many times the trade executed; if 0, err[0] holds the reason
     */
    private int tradeMoney(Player p, Shop shop, Trade t, double price, int times, Component[] err) {
        var m = plugin.msg();
        ShopManager sm = plugin.getShopManager();
        Economy econ = plugin.getEconomy();
        ItemStack res = t.getResult();
        Inventory st = shop.getStorage();

        // 1) how many can the shop supply and the customer carry?
        Inventory stockSim = InvUtil.sim(st.getContents(), 54);
        Inventory bagSim = InvUtil.sim(p.getInventory().getStorageContents(), 36);
        int n = 0;
        Component limit = null;
        while (n < times) {
            if (!stockSim.containsAtLeast(res, res.getAmount())) { limit = m.get("customer.error.out-of-stock"); break; }
            if (!bagSim.addItem(res.clone()).isEmpty()) { limit = m.get("customer.error.inventory-full"); break; }
            stockSim.removeItem(res.clone());
            n++;
        }

        // 2) how many can they afford?
        double balance = econ.getBalance(p);
        int affordable = (int) Math.floor(balance / price + 1e-9);
        if (affordable < n) {
            n = affordable;
            limit = m.get("economy.not-enough", "amount", sm.money(price));
        }
        if (n <= 0) { err[0] = limit != null ? limit : m.get("customer.error.cannot-buy"); return 0; }

        // 3) charge the customer, pay the owner (refund the customer if the owner can't be paid)
        double total = Text.round2(price * n);
        EconomyResponse taken = econ.withdrawPlayer(p, total);
        if (!taken.transactionSuccess()) { err[0] = m.get("economy.payment-failed"); return 0; }
        OfflinePlayer owner = Bukkit.getOfflinePlayer(shop.getOwner());
        EconomyResponse paid = econ.depositPlayer(owner, total);
        if (!paid.transactionSuccess()) {
            if (!econ.depositPlayer(p, total).transactionSuccess()) {
                plugin.getLogger().severe("Could not refund " + total + " to " + p.getName()
                        + " after the shop owner's payment failed - please check their balance!");
            }
            err[0] = m.get("customer.error.seller-cannot-receive");
            return 0;
        }

        // 4) hand over the goods
        for (int i = 0; i < n; i++) {
            st.removeItem(res.clone());
            p.getInventory().addItem(res.clone());
        }
        return n;
    }

    // ------------------------------------------------------------ item trades

    /** @return null on success, otherwise the error to show. */
    private Component tryTrade(Player p, Shop shop, Trade t, MerchantInventory mi) {
        var m = plugin.msg();
        int[] slots = matchSlots(t, mi);
        if (slots == null) return m.get("customer.error.missing-items");

        ItemStack res = t.getResult();
        Inventory st = shop.getStorage();
        if (!st.containsAtLeast(res, res.getAmount())) return m.get("customer.error.out-of-stock");

        ItemStack[] pays = t.getPay2() == null ? new ItemStack[]{t.getPay1()} : new ItemStack[]{t.getPay1(), t.getPay2()};
        if (!InvUtil.canFit(st.getContents(), 54, res, pays)) return m.get("customer.error.storage-full");
        if (!InvUtil.canFit(p.getInventory().getStorageContents(), 36, null, res)) return m.get("customer.error.inventory-full");

        st.removeItem(res.clone());
        for (ItemStack pay : pays) st.addItem(pay.clone());
        consume(mi, slots[0], t.getPay1().getAmount());
        if (t.getPay2() != null) consume(mi, slots[1], t.getPay2().getAmount());
        p.getInventory().addItem(res.clone());
        return null;
    }

    private int[] matchSlots(Trade t, MerchantInventory mi) {
        for (int[] o : new int[][]{{0, 1}, {1, 0}}) {
            if (!satisfies(mi.getItem(o[0]), t.getPay1())) continue;
            if (t.getPay2() != null && !satisfies(mi.getItem(o[1]), t.getPay2())) continue;
            return o;
        }
        return null;
    }

    private boolean satisfies(ItemStack have, ItemStack need) {
        return have != null && have.isSimilar(need) && have.getAmount() >= need.getAmount();
    }

    private void consume(MerchantInventory mi, int slot, int amount) {
        ItemStack s = mi.getItem(slot);
        if (s == null) return;
        int left = s.getAmount() - amount;
        if (left <= 0) { mi.setItem(slot, null); }
        else { s.setAmount(left); mi.setItem(slot, s); }
    }
}