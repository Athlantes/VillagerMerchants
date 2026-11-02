package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
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

    public void startTasks() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 20, 20L * 20);
    }

    // ------------------------------------------------------------ sales: config

    public boolean salesEnabled() { return cfg().getBoolean("sales.enabled", true); }
    public int minPercent() { return Math.max(1, cfg().getInt("sales.min-percent", 5)); }
    public int maxPercent() { return Math.min(95, Math.max(minPercent(), cfg().getInt("sales.max-percent", 50))); }
    public long maxDurationMs() { return (long) (cfg().getDouble("sales.max-hours", 24) * HOUR); }
    public long cooldownMs() { return cfg().getLong("sales.cooldown-minutes", 30) * MINUTE; }
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
            if (ms >= 5 * MINUTE && ms <= maxDurationMs()) out.add(ms);
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

    /** @return null if the shop could start a sale right now, otherwise the reason (MiniMessage). */
    public String saleBlocker(Shop shop) {
        long now = System.currentTimeMillis();
        if (!salesEnabled()) return "<red>Sales are disabled on this server.";
        if (!shop.isActive(now)) return "<red>Renew your rent before running a sale.";
        if (shop.isOnSale(now)) return "<red>A sale is already running. Stop it first.";
        if (discountableTrades(shop) == 0) return "<red>Sales only discount money-priced trades. Add one first (shift-left-click Payment 1 in the trade editor).";
        long wait = shop.getSaleExpiry() + cooldownMs() - now;
        if (wait > 0) return "<red>You can run another sale in " + Text.duration(wait) + ".";
        return null;
    }

    public boolean startSale(Player p, Shop shop, int percent, long durationMs, boolean listed) {
        if (!shop.can(p.getUniqueId(), Perm.SALES) || !p.hasPermission("villagermerchants.sales")) {
            p.sendMessage(Text.mm("<red>You can't run sales for this shop."));
            return false;
        }
        String block = saleBlocker(shop);
        if (block != null) { p.sendMessage(Text.mm(block)); return false; }
        if (percent < minPercent() || percent > maxPercent()) {
            p.sendMessage(Text.mm("<red>The discount must be between " + minPercent() + "% and " + maxPercent() + "%."));
            return false;
        }
        if (durationMs < 5 * MINUTE || durationMs > maxDurationMs()) {
            p.sendMessage(Text.mm("<red>A sale must last between 5 minutes and " + Text.duration(maxDurationMs()) + "."));
            return false;
        }
        double fee = saleFee();
        if (fee > 0 && !sm().charge(p, fee)) return false;

        shop.startSale(percent, System.currentTimeMillis() + durationMs, listed);
        sm().saveShop(shop);
        sm().refreshNameTag(shop.getId());
        p.sendMessage(Text.mm("<green>Sale started: <yellow>" + percent + "% off</yellow> for <white>" + Text.duration(durationMs)
                + "</white>" + (fee > 0 ? " <gray>(fee " + sm().money(fee) + ")" : "") + "."
                + (listed ? " <gray>It's tagged ON SALE in /shops." : "")));
        return true;
    }

    public boolean stopSale(Player p, Shop shop) {
        if (!shop.can(p.getUniqueId(), Perm.SALES)) { p.sendMessage(Text.mm("<red>You can't run sales for this shop.")); return false; }
        long now = System.currentTimeMillis();
        if (!shop.isOnSale(now)) { p.sendMessage(Text.mm("<gray>There's no sale running.")); return false; }
        shop.endSale(now);
        sm().saveShop(shop);
        sm().refreshNameTag(shop.getId());
        p.sendMessage(Text.mm("<yellow>Sale stopped."));
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
        String fmt = cfg().getString("sales.banner-format",
                "<red><bold>ON SALE</bold> <yellow>-<percent>%</yellow> <gray>(<time>)</gray>");
        return "<br>" + fmt.replace("<percent>", String.valueOf(shop.getSalePercent()))
                .replace("<time>", Text.duration(shop.getSaleExpiry() - now));
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

    /** @return null if the package can be bought for this shop right now, otherwise the reason (MiniMessage). */
    public String featuredBlocker(Shop shop, FeaturedPackage pkg) {
        long now = System.currentTimeMillis();
        if (!featuredEnabled()) return "<red>Featured listings are disabled on this server.";
        if (!shop.isActive(now)) return "<red>Renew your rent before promoting this shop.";
        if (shop.completeTrades().isEmpty()) return "<red>Add at least one live offer before promoting this shop.";
        if (!shop.isFeatured(now) && featuredInUse() >= maxFeaturedSlots()) {
            return "<red>All " + maxFeaturedSlots() + " featured slots are taken. Next one frees in " + Text.duration(nextSlotIn()) + ".";
        }
        long base = Math.max(now, shop.getFeaturedUntil());
        if (base + pkg.hours() * HOUR - now > maxStackHours() * HOUR) {
            return "<red>You can't be featured more than " + maxStackHours() + "h ahead.";
        }
        return null;
    }

    public boolean buyFeatured(Player p, Shop shop, FeaturedPackage pkg) {
        if (!shop.can(p.getUniqueId(), Perm.BILLING) || !p.hasPermission("villagermerchants.promote")) {
            p.sendMessage(Text.mm("<red>You can't buy promotions for this shop."));
            return false;
        }
        String block = featuredBlocker(shop, pkg);
        if (block != null) { p.sendMessage(Text.mm(block)); return false; }
        if (!sm().charge(p, pkg.price())) return false;   // withdrawn, never paid out: a pure money sink
        long base = Math.max(System.currentTimeMillis(), shop.getFeaturedUntil());
        shop.setFeaturedUntil(base + pkg.hours() * HOUR);
        sm().saveShop(shop);
        p.sendMessage(Text.mm("<green>Featured for <white>" + pkg.hours() + "h</white> more! Pinned at the top of /shops for another <white>"
                + Text.duration(shop.getFeaturedUntil() - System.currentTimeMillis()) + "</white>."));
        return true;
    }

    // ------------------------------------------------------------ ticking

    private void tick() {
        tickCount++;
        long now = System.currentTimeMillis();
        boolean refreshBanners = tickCount % 3 == 0;   // once a minute
        for (Shop s : new ArrayList<>(sm().allShops())) {
            if (!sm().isLive(s)) continue;
            Player owner = Bukkit.getPlayer(s.getOwner());

            if (s.getSalePercent() > 0 && !s.isOnSale(now)) {            // sale ran out
                s.endSale(s.getSaleExpiry());
                sm().saveShop(s);
                sm().refreshNameTag(s.getId());
                if (owner != null) owner.sendMessage(Text.mm("<yellow>Your shop sale has ended. Prices are back to normal."));
            } else if (refreshBanners && s.isOnSale(now)) {               // keep the "(1h 20m)" fresh
                plugin.getNametagManager().updateShopNametag(s.getId(), sm().activeNametagFormat(s));
            }

            if (s.getFeaturedUntil() > 0 && !s.isFeatured(now)) {        // featured time ran out
                s.setFeaturedUntil(0);
                sm().saveShop(s);
                if (owner != null) owner.sendMessage(Text.mm("<yellow>Your shop's featured listing has expired."));
            }
        }
    }
}
