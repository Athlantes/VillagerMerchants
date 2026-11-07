package org.riftlabs.villagerMerchants.manager;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.util.Sched;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.Messages;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Timed sales (percentage off money-priced trades) and paid "featured" listings that pin a shop to the top of
 * /shops. Featured fees are withdrawn and never paid to anyone, so they drain money out of the economy.
 */
public class PromotionManager {
    public static final long MINUTE = 60_000L;
    public static final long HOUR = 3_600_000L;
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*([dhm])");

    public record FeaturedPackage(int hours, double price) {}

    private final VillagerMerchants plugin;
    private int tickCount;

    public PromotionManager(VillagerMerchants plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private ShopManager sm() { return plugin.getShopManager(); }

    private int tickSeconds() { return Math.max(5, cfg().getInt("promotions.tick-seconds", 20)); }

    public void startTasks() {
        long period = tickSeconds() * 20L;
        Sched.globalTimer(this::tick, period, period);   // global region: it only looks at shop data, entity work hops by itself
    }

    // ------------------------------------------------------------ sales: config

    public boolean salesEnabled() { return cfg().getBoolean("sales.enabled", true); }
    public int minPercent() { return Math.max(1, cfg().getInt("sales.min-percent", 5)); }
    public int maxPercent() { return Math.min(95, Math.max(minPercent(), cfg().getInt("sales.max-percent", 50))); }
    public long maxDurationMs() { return (long) (cfg().getDouble("sales.max-hours", 24) * HOUR); }
    public long cooldownMs() { return cfg().getLong("sales.cooldown-minutes", 30) * MINUTE; }
    /** Shortest allowed sale, in milliseconds. */
    public long minDurationMs() { return Math.max(1, cfg().getLong("sales.min-minutes", 5)) * MINUTE; }
    public double saleFee() { return Math.max(0, cfg().getDouble("sales.fee", 0.0)); }

    public List<Integer> percentOptions() {
        List<Integer> out = new ArrayList<>();
        for (int v : cfg().getIntegerList("sales.percent-options")) if (v >= minPercent() && v <= maxPercent()) out.add(v);
        if (out.isEmpty()) out.addAll(List.of(10, 20, 30, 50));
        return out;
    }

    /** Durations in milliseconds. */
    public List<Long> durationOptions() {
        List<Long> out = new ArrayList<>();
        for (int mins : cfg().getIntegerList("sales.duration-options")) {
            long ms = mins * MINUTE;
            if (ms >= minDurationMs() && ms <= maxDurationMs()) out.add(ms);
        }
        if (out.isEmpty()) out.addAll(List.of(30 * MINUTE, HOUR, 2 * HOUR, 6 * HOUR));
        return out;
    }

    /** "2h", "90m", "1d", "1h30m" -> milliseconds, or -1 if it can't be read. */
    public static long parseDuration(String raw) {
        if (raw == null) return -1;
        Matcher m = DURATION.matcher(raw.toLowerCase(Locale.ROOT));
        long total = 0;
        int end = 0;
        String s = raw.toLowerCase(Locale.ROOT);
        while (m.find()) {
            if (!s.substring(end, m.start()).isBlank()) return -1;
            end = m.end();
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) { case "d" -> n * 24 * HOUR; case "h" -> n * HOUR; default -> n * MINUTE; };
        }
        if (end == 0 || !s.substring(end).isBlank()) return -1;
        return total;
    }

    public int discountableTrades(Shop shop) {
        int n = 0;
        for (Trade t : shop.completeTrades()) if (t.isMoney()) n++;
        return n;
    }

    // ------------------------------------------------------------ sales: actions

    /** @return null if the shop could start a sale right now, otherwise the reason (ready to show in chat or lore). */
    public Component saleBlocker(Shop shop) {
        long now = System.currentTimeMillis();
        var m = plugin.msg();
        if (!salesEnabled()) return m.line("sale.blocker.disabled");
        if (!shop.isActive(now)) return m.line("sale.blocker.rent-expired");
        if (shop.isOnSale(now)) return m.line("sale.blocker.running");
        if (discountableTrades(shop) == 0) return m.line("sale.blocker.no-money-trades");
        long wait = shop.getSaleExpiry() + cooldownMs() - now;
        if (wait > 0) return m.line("sale.blocker.cooldown", "time", Text.duration(wait));
        return null;
    }

    public boolean startSale(Player p, Shop shop, int percent, long durationMs, boolean listed) {
        if (!shop.can(p.getUniqueId(), Perm.SALES) || !plugin.access().canRunSales(p)) {
            plugin.msg().send(p, "sale.no-permission");
            return false;
        }
        Component block = saleBlocker(shop);
        if (block != null) { p.sendMessage(block); return false; }
        if (percent < minPercent() || percent > maxPercent()) {
            plugin.msg().send(p, "sale.invalid-percent", "min", minPercent(), "max", maxPercent());
            return false;
        }
        if (durationMs < minDurationMs() || durationMs > maxDurationMs()) {
            plugin.msg().send(p, "sale.invalid-duration", "min", Text.duration(minDurationMs()), "max", Text.duration(maxDurationMs()));
            return false;
        }
        double fee = saleFee();
        if (fee > 0 && !sm().charge(p, fee)) return false;

        shop.startSale(percent, System.currentTimeMillis() + durationMs, listed);
        sm().saveShop(shop);
        sm().refreshNameTag(shop);
        var m = plugin.msg();
        m.send(p, "sale.started", "percent", percent, "time", Text.duration(durationMs),
                "fee", fee > 0 ? m.ref("sale.started-fee", "fee", Text.esc(sm().money(fee))) : Messages.parsed(""),
                "listed", listed ? m.ref("sale.started-listed") : Messages.parsed(""));
        return true;
    }

    public boolean stopSale(Player p, Shop shop) {
        if (!shop.can(p.getUniqueId(), Perm.SALES)) { plugin.msg().send(p, "sale.no-permission"); return false; }
        long now = System.currentTimeMillis();
        if (!shop.isOnSale(now)) { plugin.msg().send(p, "sale.none-running"); return false; }
        shop.endSale(now);
        sm().saveShop(shop);
        sm().refreshNameTag(shop);
        plugin.msg().send(p, "sale.stopped");
        return true;
    }

    public boolean toggleListed(Player p, Shop shop) {
        if (!shop.can(p.getUniqueId(), Perm.SALES)) return false;
        shop.setSaleListed(!shop.isSaleListed());
        sm().saveShop(shop);
        return true;
    }

    // ------------------------------------------------------------ sales: how it looks

    /** Extra name tag line while a sale is running ("" otherwise). */
    public String nametagBanner(Shop shop) {
        long now = System.currentTimeMillis();
        if (!shop.isOnSale(now) || !cfg().getBoolean("sales.banner", true)) return "";
        return "<br>" + plugin.msg().str("sale.banner", "percent", shop.getSalePercent(),
                "time", Text.duration(shop.getSaleExpiry() - now));
    }

    /** Effect to play on the NPC: the optional sale effect while a sale runs, otherwise the owner's own. */
    public String effectFor(Shop shop, String ownersEffect) {
        String fx = cfg().getString("sales.effect", "");
        if (fx != null && !fx.isBlank() && shop.isOnSale(System.currentTimeMillis())
                && plugin.getEffectManager().getRegistry().get(fx) != null) return fx;
        return ownersEffect;
    }

    // ------------------------------------------------------------ featured

    public boolean featuredEnabled() { return cfg().getBoolean("featured.enabled", true); }
    public int maxFeaturedSlots() { return Math.max(1, cfg().getInt("featured.max-slots", 5)); }
    public int maxStackHours() { return Math.max(1, cfg().getInt("featured.max-stack-hours", 72)); }

    public List<FeaturedPackage> packages() {
        List<FeaturedPackage> out = new ArrayList<>();
        ConfigurationSection sec = cfg().getConfigurationSection("featured.packages");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                try {
                    int h = Integer.parseInt(k.trim());
                    double price = sec.getDouble(k);
                    if (h > 0 && price >= 0) out.add(new FeaturedPackage(h, price));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (out.isEmpty()) out.addAll(List.of(new FeaturedPackage(6, 2500), new FeaturedPackage(24, 8000), new FeaturedPackage(72, 20000)));
        out.sort(Comparator.comparingInt(FeaturedPackage::hours));
        return out;
    }

    public int featuredInUse() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Shop s : sm().allShops()) if (s.isFeatured(now) && s.isActive(now)) n++;
        return n;
    }

    /** Millis until the soonest featured slot frees up (0 if one is free now). */
    public long nextSlotIn() {
        long now = System.currentTimeMillis();
        if (featuredInUse() < maxFeaturedSlots()) return 0;
        long best = Long.MAX_VALUE;
        for (Shop s : sm().allShops()) if (s.isFeatured(now) && s.isActive(now)) best = Math.min(best, s.getFeaturedUntil() - now);
        return best == Long.MAX_VALUE ? 0 : best;
    }

    /** @return null if the package can be bought for this shop right now, otherwise the reason (ready to show in chat or lore). */
    public Component featuredBlocker(Shop shop, FeaturedPackage pkg) {
        long now = System.currentTimeMillis();
        var m = plugin.msg();
        if (!featuredEnabled()) return m.line("featured.blocker.disabled");
        if (!shop.isActive(now)) return m.line("featured.blocker.rent-expired");
        if (shop.completeTrades().isEmpty()) return m.line("featured.blocker.no-offers");
        if (!shop.isFeatured(now) && featuredInUse() >= maxFeaturedSlots()) {
            return m.line("featured.blocker.slots-full", "slots", maxFeaturedSlots(), "time", Text.duration(nextSlotIn()));
        }
        long base = Math.max(now, shop.getFeaturedUntil());
        if (base + pkg.hours() * HOUR - now > maxStackHours() * HOUR) {
            return m.line("featured.blocker.too-far", "hours", maxStackHours());
        }
        return null;
    }

    public boolean buyFeatured(Player p, Shop shop, FeaturedPackage pkg) {
        if (!shop.can(p.getUniqueId(), Perm.BILLING) || !plugin.access().canPromote(p)) {
            plugin.msg().send(p, "featured.no-permission");
            return false;
        }
        Component block = featuredBlocker(shop, pkg);
        if (block != null) { p.sendMessage(block); return false; }
        if (!sm().charge(p, pkg.price())) return false;   // withdrawn, never paid out: a pure money sink
        long base = Math.max(System.currentTimeMillis(), shop.getFeaturedUntil());
        shop.setFeaturedUntil(base + pkg.hours() * HOUR);
        sm().saveShop(shop);
        plugin.msg().send(p, "featured.bought", "hours", pkg.hours(),
                "time", Text.duration(shop.getFeaturedUntil() - System.currentTimeMillis()));
        return true;
    }

    // ------------------------------------------------------------ ticking

    private void tick() {
        tickCount++;
        long now = System.currentTimeMillis();
        int every = Math.max(1, cfg().getInt("sales.banner-refresh-seconds", 60) / tickSeconds());
        boolean refreshBanners = tickCount % every == 0;
        for (Shop s : new ArrayList<>(sm().allShops())) {
            if (!sm().isLive(s)) continue;
            Player owner = Bukkit.getPlayer(s.getOwner());

            if (s.getSalePercent() > 0 && !s.isOnSale(now)) {            // sale ran out
                s.endSale(s.getSaleExpiry());
                sm().saveShop(s);
                sm().refreshNameTag(s);
                if (owner != null) plugin.syncFor(owner, () -> plugin.msg().send(owner, "sale.ended"));
            } else if (refreshBanners && s.isOnSale(now)) {               // keep the "(1h 20m)" fresh
                plugin.getNametagManager().updateShopNametag(sm().entityIdOf(s), sm().activeNametagFormat(s));
            }

            if (s.getFeaturedUntil() > 0 && !s.isFeatured(now)) {        // featured time ran out
                s.setFeaturedUntil(0);
                sm().saveShop(s);
                if (owner != null) plugin.syncFor(owner, () -> plugin.msg().send(owner, "featured.expired"));
            }
        }
    }
}
