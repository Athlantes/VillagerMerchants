package org.riftlabs.villagerMerchants.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.gui.TradeEditorGui;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Captures the next chat message from a shop owner as the money price of a trade ("1500", "2.5k", "cancel"). */
public class PricePromptListener implements Listener {
    private long timeoutMs() { return Math.max(5, plugin.getConfig().getLong("prompts.timeout-seconds", 60)) * 1000L; }

    public record Prompt(Shop shop, int idx, int page, long expires) {}

    private final VillagerMerchants plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public PricePromptListener(VillagerMerchants plugin) { this.plugin = plugin; }

    public void begin(Player p, Shop shop, int idx, int page) {
        prompts.put(p.getUniqueId(), new Prompt(shop, idx, page, System.currentTimeMillis() + timeoutMs()));
        double min = plugin.getConfig().getDouble("money.min-price", 0.01);
        plugin.msg().send(p, "price-prompt.ask", "min", plugin.getShopManager().money(min));
        plugin.msg().send(p, "price-prompt.cancel-hint", "cancel", plugin.msg().raw("prompts.cancel-word"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Prompt pr = prompts.get(p.getUniqueId());
        if (pr == null) return;
        e.setCancelled(true); // never broadcast the price
        String text = Text.plain(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, pr, text));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); }

    private void handle(Player p, Prompt pr, String text) {
        if (!prompts.remove(p.getUniqueId(), pr)) return; // superseded or already handled
        if (!p.isOnline()) return;
        ShopManager sm = plugin.getShopManager();
        if (!sm.isLive(pr.shop()) || !pr.shop().can(p.getUniqueId(), Perm.TRADES)) {
            plugin.msg().send(p, "price-prompt.no-longer-allowed");
            return;
        }
        if (System.currentTimeMillis() > pr.expires()) {
            plugin.msg().send(p, "price-prompt.timed-out");
            reopen(p, pr);
            return;
        }
        if (text.equalsIgnoreCase(plugin.msg().raw("prompts.cancel-word"))) {
            reopen(p, pr);
            return;
        }

        double min = plugin.getConfig().getDouble("money.min-price", 0.01);
        double max = plugin.getConfig().getDouble("money.max-price", 1_000_000_000.0);
        OptionalDouble parsed = parse(text);
        if (parsed.isEmpty()) {
            plugin.msg().send(p, "price-prompt.not-a-number", "cancel", plugin.msg().raw("prompts.cancel-word"));
            rearm(p, pr);
            return;
        }
        double price = Text.round2(parsed.getAsDouble());
        if (price < min || price > max) {
            plugin.msg().send(p, "price-prompt.out-of-range", "min", sm.money(min), "max", sm.money(max));
            rearm(p, pr);
            return;
        }

        // Anti-abuse: price cap for the item being sold (if the result is already set; otherwise the editor and the
        // purchase check enforce it as soon as it is).
        Trade existing = pr.shop().getTrade(pr.idx());
        ItemStack result = existing == null ? null : existing.getResult();
        if (result != null) {
            double cap = plugin.getAntiAbuse().maxTotal(result);
            if (price > cap + 0.0001) {
                plugin.getAntiAbuse().logBlockedPrice(p, pr.shop(), result, price, cap);
                plugin.msg().send(p, "anti-abuse.price-too-high", "max", sm.money(cap),
                        "unit", sm.money(plugin.getAntiAbuse().unitCap(result)), "cancel", plugin.msg().raw("prompts.cancel-word"));
                rearm(p, pr);
                return;
            }
        }

        Trade t = pr.shop().getOrCreateTrade(pr.idx());
        t.set(0, null);
        t.set(1, null);
        t.setPrice(price); // a money trade has no payment items
        pr.shop().setDirty(true);
        sm.saveShop(pr.shop());
        plugin.msg().send(p, "price-prompt.set", "price", sm.money(price));
        reopen(p, pr);
    }

    private void rearm(Player p, Prompt pr) {
        prompts.put(p.getUniqueId(), new Prompt(pr.shop(), pr.idx(), pr.page(), System.currentTimeMillis() + timeoutMs()));
    }

    private void reopen(Player p, Prompt pr) {
        if (plugin.getShopManager().isLive(pr.shop())) new TradeEditorGui(plugin, p, pr.shop(), pr.page()).open();
    }

    /** "1500", "1,500", "$2.5k", "3m" -> 1500, 1500, 2500, 3000000. */
    static OptionalDouble parse(String raw) {
        String s = raw.replace(",", "").replace("$", "").trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return OptionalDouble.empty();
        double mult = 1;
        String last = s.substring(s.length() - 1);
        var shorthand = VillagerMerchants.getInstance().getConfig().getConfigurationSection("money.shorthand");
        if (shorthand != null) {
            for (String suffix : shorthand.getKeys(false)) {
                if (suffix.equalsIgnoreCase(last)) { mult = shorthand.getDouble(suffix, 1); break; }
            }
        } else if (last.equals("k")) mult = 1_000;
        else if (last.equals("m")) mult = 1_000_000;
        else if (last.equals("b")) mult = 1_000_000_000;
        if (mult != 1) s = s.substring(0, s.length() - 1);
        if (!s.matches("\\d+(\\.\\d+)?|\\.\\d+")) return OptionalDouble.empty();
        try {
            double v = Double.parseDouble(s) * mult;
            return (Double.isNaN(v) || Double.isInfinite(v)) ? OptionalDouble.empty() : OptionalDouble.of(v);
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }
}
