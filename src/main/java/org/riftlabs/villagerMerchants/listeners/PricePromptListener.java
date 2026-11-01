package org.riftlabs.villagerMerchants.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.gui.TradeEditorGui;
import org.riftlabs.villagerMerchants.manager.ShopManager;
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
    private static final long TIMEOUT_MS = 60_000L;

    private record Prompt(Shop shop, int idx, int page, long expires) {}

    private final VillagerMerchants plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public PricePromptListener(VillagerMerchants plugin) { this.plugin = plugin; }

    public void begin(Player p, Shop shop, int idx, int page) {
        prompts.put(p.getUniqueId(), new Prompt(shop, idx, page, System.currentTimeMillis() + TIMEOUT_MS));
        double min = plugin.getConfig().getDouble("money.min-price", 0.01);
        p.sendMessage(Text.mm("<yellow>Type the <gold>price</gold> for this trade in chat <gray>(e.g. <white>250</white>, <white>1.5k</white>, <white>2m</white>; minimum "
                + plugin.getShopManager().money(min) + ")."));
        p.sendMessage(Text.mm("<gray>Type <red>cancel</red> to go back without changing anything."));
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
        if (!sm.isLive(pr.shop()) || !pr.shop().isOwner(p.getUniqueId())) {
            p.sendMessage(Text.mm("<red>That shop is no longer yours."));
            return;
        }
        if (System.currentTimeMillis() > pr.expires()) {
            p.sendMessage(Text.mm("<gray>Price entry timed out."));
            reopen(p, pr);
            return;
        }
        if (text.equalsIgnoreCase("cancel")) {
            reopen(p, pr);
            return;
        }

        double min = plugin.getConfig().getDouble("money.min-price", 0.01);
        double max = plugin.getConfig().getDouble("money.max-price", 1_000_000_000.0);
        OptionalDouble parsed = parse(text);
        if (parsed.isEmpty()) {
            p.sendMessage(Text.mm("<red>That isn't a number. Try again, or type <white>cancel</white>."));
            rearm(p, pr);
            return;
        }
        double price = Text.round2(parsed.getAsDouble());
        if (price < min || price > max) {
            p.sendMessage(Text.mm("<red>Price must be between " + sm.money(min) + " and " + sm.money(max) + ". Try again."));
            rearm(p, pr);
            return;
        }

        Trade t = pr.shop().getOrCreateTrade(pr.idx());
        t.set(0, null);
        t.set(1, null);
        t.setPrice(price); // a money trade has no payment items
        pr.shop().setDirty(true);
        sm.saveShop(pr.shop());
        p.sendMessage(Text.mm("<green>Price set to <gold>" + sm.money(price) + "</gold>."));
        reopen(p, pr);
    }

    private void rearm(Player p, Prompt pr) {
        prompts.put(p.getUniqueId(), new Prompt(pr.shop(), pr.idx(), pr.page(), System.currentTimeMillis() + TIMEOUT_MS));
    }

    private void reopen(Player p, Prompt pr) {
        if (plugin.getShopManager().isLive(pr.shop())) new TradeEditorGui(plugin, p, pr.shop(), pr.page()).open();
    }

    /** "1500", "1,500", "$2.5k", "3m" -> 1500, 1500, 2500, 3000000. */
    static OptionalDouble parse(String raw) {
        String s = raw.replace(",", "").replace("$", "").trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return OptionalDouble.empty();
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') mult = 1_000;
        else if (last == 'm') mult = 1_000_000;
        else if (last == 'b') mult = 1_000_000_000;
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
