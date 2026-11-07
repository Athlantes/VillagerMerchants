package org.riftlabs.villagerMerchants.gui;

import org.riftlabs.villagerMerchants.util.Sched;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.CategoryRegistry;
import org.riftlabs.villagerMerchants.manager.CategoryRegistry.Category;
import org.riftlabs.villagerMerchants.manager.RatingManager;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.Messages;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * /shops - every open shop on the server. Featured shops are pinned to the top; the rest can be filtered by
 * category, free-text search, minimum rating and "on sale", and sorted by rating, popularity, price or name.
 */
public class ShopDirectoryGui implements GuiHolder {
    private static final int PER_PAGE = 45;
    private static final int PREV = 45, CATEGORY = 46, SORT = 47, SEARCH = 48, INFO = 49,
            RATING = 50, SALE = 51, CLOSE = 52, NEXT = 53;

    private enum Sort {
        RATING("directory.sort.rating"), POPULAR("directory.sort.popular"),
        PRICE("directory.sort.price"), NAME("directory.sort.name");

        /** Language key of the label shown on the sort button. */
        final String key;
        Sort(String key) { this.key = key; }
        Sort next() { return values()[(ordinal() + 1) % values().length]; }
    }

    private final VillagerMerchants plugin;
    private final Player player;
    private final Inventory inv;
    private final Map<Integer, Shop> bySlot = new HashMap<>();
    /** Offers that matched the current filters, per listed shop (also what the entry lore shows). */
    private final Map<Shop, List<Trade>> matches = new HashMap<>();
    private int page;
    private Sort sort = Sort.RATING;
    private String categoryId;      // null = all
    private String query;           // null = no search
    private int minRatingIdx;
    private double[] minRatings = {0};
    private boolean saleOnly;

    public ShopDirectoryGui(VillagerMerchants plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.minRatings = loadMinRatings();
        this.inv = Bukkit.createInventory(this, 54, plugin.msg().get("directory.title"));
        render();
    }

    /** Rating filter steps from directory.min-rating-options (0 = "any" is always the first step). */
    private double[] loadMinRatings() {
        List<Double> out = new ArrayList<>();
        out.add(0.0);
        for (double d : plugin.getConfig().getDoubleList("directory.min-rating-options")) if (d > 0 && d <= 5) out.add(d);
        if (out.size() == 1) { out.add(3.0); out.add(4.0); out.add(4.5); }
        return out.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    }

    // ---- presets used by /shops search | category | sale
    public ShopDirectoryGui withQuery(String q) {
        this.query = q == null || q.isBlank() ? null : q.trim();
        this.page = 0;
        render();
        return this;
    }

    public ShopDirectoryGui withCategory(String id) { this.categoryId = id; this.page = 0; render(); return this; }
    public ShopDirectoryGui withSaleOnly(boolean b) { this.saleOnly = b; this.page = 0; render(); return this; }

    public void open() { player.openInventory(inv); }

    // ------------------------------------------------------------ data

    private boolean ownerMatches(Shop s) {
        return query != null && plugin.getShopManager().ownerName(s.getOwner()).toLowerCase(Locale.ROOT)
                .contains(query.toLowerCase(Locale.ROOT));
    }

    private List<Trade> matching(Shop s) {
        CategoryRegistry cats = plugin.getCategories();
        boolean owner = ownerMatches(s);
        List<Trade> out = new ArrayList<>();
        for (Trade t : s.completeTrades()) {
            ItemStack res = t.getResult();
            if (categoryId != null && !cats.inCategory(res, categoryId)) continue;
            if (query != null && !owner && !CategoryRegistry.matchesQuery(res, query)) continue;
            out.add(t);
        }
        return out;
    }

    /** Cheapest money price among the offers that matched, or +infinity if none is money-priced. */
    private double priceOf(Shop s, long now) {
        double best = Double.MAX_VALUE;
        for (Trade t : matches.getOrDefault(s, List.of())) {
            if (t.isMoney()) best = Math.min(best, s.effectivePrice(t, now));
        }
        return best;
    }

    private List<Shop> listing() {
        ShopManager sm = plugin.getShopManager();
        RatingManager rm = plugin.getRatings();
        long now = System.currentTimeMillis();
        boolean hideEmpty = plugin.getConfig().getBoolean("directory.hide-empty-shops", true);
        double minRating = minRatings[minRatingIdx];

        matches.clear();
        List<Shop> list = new ArrayList<>();
        for (Shop s : sm.allShops()) {
            if (!s.isActive(now)) continue;
            if (hideEmpty && s.completeTrades().isEmpty()) continue;
            if (saleOnly && !(s.isOnSale(now) && s.isSaleListed())) continue;
            if (minRating > 0) {
                RatingManager.Summary r = rm.summary(s);
                if (r.count() == 0 || r.average() < minRating) continue;
            }
            List<Trade> m = matching(s);
            if ((categoryId != null || query != null) && m.isEmpty()) continue;
            matches.put(s, m);
            list.add(s);
        }

        Comparator<Shop> byName = Comparator.comparing((Shop s) -> sm.ownerName(s.getOwner()).toLowerCase(Locale.ROOT));
        Comparator<Shop> bySales = Comparator.comparingLong((Shop s) -> plugin.getTransactions().salesCount(s)).reversed();
        Comparator<Shop> chosen = switch (sort) {
            case RATING -> Comparator.<Shop>comparingDouble(rm::score).reversed().thenComparing(bySales).thenComparing(byName);
            case POPULAR -> bySales.thenComparing(byName);
            case PRICE -> Comparator.<Shop>comparingDouble(s -> priceOf(s, now)).thenComparing(byName);
            case NAME -> byName;
        };
        // featured shops are always pinned above everything else, then the chosen sort applies inside each group
        Comparator<Shop> featuredFirst = Comparator.comparing((Shop s) -> !s.isFeatured(now));
        list.sort(featuredFirst.thenComparing(chosen));
        return list;
    }

    /** Categories that at least one open shop sells from (so the filter never leads to an empty page). */
    private List<Category> availableCategories() {
        CategoryRegistry cats = plugin.getCategories();
        long now = System.currentTimeMillis();
        Set<String> present = new LinkedHashSet<>();
        for (Shop s : plugin.getShopManager().allShops()) {
            if (s.isActive(now) && !s.completeTrades().isEmpty()) present.addAll(cats.categoriesOf(s));
        }
        List<Category> out = new ArrayList<>();
        for (Category c : cats.all()) if (present.contains(c.id())) out.add(c);
        return out;
    }

    private boolean filtersActive() { return categoryId != null || query != null || minRatingIdx > 0 || saleOnly; }

    // ------------------------------------------------------------ rendering

    private void render() {
        inv.clear();
        bySlot.clear();
        var m = plugin.msg();
        List<Shop> list = listing();
        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        page = Math.max(0, Math.min(page, pages - 1));
        long now = System.currentTimeMillis();

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = page * PER_PAGE + i;
            if (idx >= list.size()) break;
            Shop s = list.get(idx);
            inv.setItem(i, entry(s, now));
            bySlot.put(i, s);
        }

        ItemStack pane = Gui.filler(plugin);
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(PREV, Gui.previousPage(plugin));
        if (page + 1 < pages) inv.setItem(NEXT, Gui.nextPage(plugin));
        inv.setItem(CLOSE, Gui.close(plugin));
        inv.setItem(SORT, m.icon(plugin.material("directory-sort", Material.HOPPER), "directory.sort-button", "sort", m.ref(sort.key)));

        // category
        Category cat = categoryId == null ? null : plugin.getCategories().get(categoryId);
        inv.setItem(CATEGORY, m.icon(cat == null ? plugin.material("directory-category-all", Material.COMPASS) : cat.icon(),
                "directory.category", "category", cat == null ? m.ref("directory.category-all") : Messages.parsed(cat.name())));

        // search
        inv.setItem(SEARCH, query == null
                ? m.icon(plugin.material("directory-search", Material.SPYGLASS), "directory.search")
                : Text.glow(m.icon(plugin.material("directory-search", Material.SPYGLASS), "directory.search-active", "query", query)));

        // rating filter
        double mr = minRatings[minRatingIdx];
        inv.setItem(RATING, m.icon(plugin.material("directory-rating", Material.GOLD_NUGGET), "directory.rating",
                "value", mr == 0 ? m.ref("directory.rating-any") : m.ref("directory.rating-min", "rating", Text.oneDecimal(mr))));

        // sale filter
        long onSale = plugin.getShopManager().allShops().stream().filter(s -> s.isOnSale(now) && s.isSaleListed() && s.isActive(now)).count();
        Material carrot = plugin.material("directory-sale", Material.GOLDEN_CARROT);
        inv.setItem(SALE, saleOnly
                ? Text.glow(m.icon(carrot, "directory.sale-on", "count", onSale))
                : m.icon(carrot, "directory.sale-off", "count", onSale));

        inv.setItem(INFO, m.icon(plugin.material("directory-info", Material.PAPER),
                filtersActive() ? "directory.info-filtered" : "directory.info",
                "shops", list.size(), "page", page + 1, "pages", pages));

        if (list.isEmpty()) {
            inv.setItem(22, m.icon(plugin.material("directory-empty", Material.COBWEB),
                    filtersActive() ? "directory.empty-filtered" : "directory.empty"));
        }
    }

    private ItemStack entry(Shop s, long now) {
        var m = plugin.msg();
        ShopManager sm = plugin.getShopManager();
        CategoryRegistry cats = plugin.getCategories();
        String owner = sm.ownerName(s.getOwner());
        boolean featured = s.isFeatured(now);
        String sep = m.raw("directory.entry.list-separator");
        List<Component> lore = new ArrayList<>();

        if (featured) lore.add(m.line("directory.entry.featured"));
        if (s.isOnSale(now) && s.isSaleListed()) {
            lore.add(m.line("directory.entry.on-sale", "percent", s.getSalePercent(), "time", Text.duration(s.getSaleExpiry() - now)));
        }

        RatingManager.Summary r = plugin.getRatings().summary(s);
        lore.add(r.count() > 0
                ? m.line("directory.entry.rating", "stars", Messages.parsed(Text.stars(r.average())),
                        "avg", Text.oneDecimal(r.average()), "count", r.count())
                : m.line("directory.entry.rating-none"));
        lore.add(m.line("directory.entry.sales", "sales", plugin.getTransactions().salesCount(s)));

        int offers = 0, money = 0;
        Set<String> names = new LinkedHashSet<>();
        for (Trade t : s.completeTrades()) {
            offers++;
            if (t.isMoney()) money++;
            names.add(Text.esc(Text.itemName(t.getResult())));
        }
        lore.add(m.line("directory.entry.offers", "offers", offers,
                "money", money > 0 ? m.ref("directory.entry.offers-money", "money", money) : Messages.parsed("")));

        List<String> catNames = new ArrayList<>();
        for (String id : cats.categoriesOf(s)) {
            Category c = cats.get(id);
            if (c != null) catNames.add(c.name());
        }
        int catLimit = Math.max(1, plugin.getConfig().getInt("directory.categories-shown", 4));
        if (!catNames.isEmpty()) {
            int shown = Math.min(catLimit, catNames.size());
            lore.add(m.line("directory.entry.sells", "list", Messages.parsed(String.join(sep, catNames.subList(0, shown))),
                    "more", catNames.size() > shown ? m.ref("directory.entry.sells-more", "count", catNames.size() - shown) : Messages.parsed("")));
        }

        List<Trade> hit = matches.getOrDefault(s, List.of());
        int offerLimit = Math.max(1, plugin.getConfig().getInt("directory.matching-offers-shown", 3));
        if ((query != null || categoryId != null) && !hit.isEmpty()) {
            lore.add(m.line("directory.entry.matching"));
            for (Trade t : hit.subList(0, Math.min(offerLimit, hit.size()))) {
                lore.add(m.line("directory.entry.matching-line", "offer", offerLine(s, t, now)));
            }
            if (hit.size() > offerLimit) lore.add(m.line("directory.entry.matching-more", "count", hit.size() - offerLimit));
        } else if (!names.isEmpty()) {
            int itemLimit = Math.max(1, plugin.getConfig().getInt("directory.items-shown", 3));
            List<String> shown = new ArrayList<>(names).subList(0, Math.min(itemLimit, names.size()));
            int more = names.size() - shown.size();
            lore.add(m.line("directory.entry.items", "list", Messages.parsed(String.join(sep, shown)),
                    "more", more > 0 ? m.ref("directory.entry.items-more", "count", more) : Messages.parsed("")));
        }

        if (plugin.getConfig().getBoolean("directory.show-coordinates", false)) {
            String[] p = s.getLocation() == null ? new String[0] : s.getLocation().split(";");
            if (p.length >= 4) {
                try {
                    lore.add(m.line("directory.entry.location", "world", p[0], "x", (int) Math.floor(Double.parseDouble(p[1])),
                            "y", (int) Math.floor(Double.parseDouble(p[2])), "z", (int) Math.floor(Double.parseDouble(p[3]))));
                } catch (NumberFormatException ignored) {}
            }
        }

        lore.add(Component.empty());
        var tp = plugin.getTeleports();
        if (tp.enabled() && plugin.access().canTeleport(player)) {
            lore.add(m.line("directory.entry.click-teleport"));
            if (tp.cost() > 0 && !plugin.access().bypassCost(player)) {
                lore.add(m.line("directory.entry.teleport-cost", "cost", sm.money(tp.cost())));
            }
        } else {
            lore.add(m.line("directory.entry.teleport-unavailable"));
        }

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(s.getOwner()));
        meta.displayName(m.line(featured ? "directory.entry.name-featured" : "directory.entry.name", "owner", owner));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (featured) meta.setEnchantmentGlintOverride(true);
        head.setItemMeta(meta);
        return head;
    }

    /** "2x Diamond Sword for $400 (-20%)" or "1x Mending Book for 8x Emerald" (wording from the language file). */
    private Component offerLine(Shop s, Trade t, long now) {
        var m = plugin.msg();
        ItemStack res = t.getResult();
        String qty = String.valueOf(res.getAmount()), item = Text.itemName(res);
        if (t.isMoney()) {
            String price = plugin.getShopManager().money(s.effectivePrice(t, now));
            return m.get("directory.offer-money", "qty", qty, "item", item, "price", price,
                    "sale", s.isOnSale(now) ? m.ref("directory.offer-sale", "percent", s.getSalePercent()) : Messages.parsed(""));
        }
        StringBuilder pay = new StringBuilder(t.getPay1().getAmount() + "x " + Text.itemName(t.getPay1()));
        if (t.getPay2() != null) pay.append(m.raw("directory.offer-pay-join")).append(t.getPay2().getAmount()).append("x ").append(Text.itemName(t.getPay2()));
        return m.get("directory.offer-item", "qty", qty, "item", item, "pay", pay.toString());
    }

    // ------------------------------------------------------------ clicks

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();
        boolean right = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT;
        switch (slot) {
            case PREV -> { if (page > 0) { page--; render(); } }
            case NEXT -> { page++; render(); }
            case SORT -> { sort = sort.next(); page = 0; render(); }
            case CATEGORY -> { cycleCategory(right ? -1 : 1); page = 0; render(); }
            case RATING -> { minRatingIdx = (minRatingIdx + 1) % minRatings.length; page = 0; render(); }
            case SALE -> { saleOnly = !saleOnly; page = 0; render(); }
            case SEARCH -> {
                if (right && query != null) { query = null; page = 0; render(); }
                else promptSearch();
            }
            case INFO -> {
                if (right && filtersActive()) { categoryId = null; query = null; minRatingIdx = 0; saleOnly = false; page = 0; render(); }
            }
            case CLOSE -> later(player::closeInventory);
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

    private void cycleCategory(int dir) {
        List<Category> avail = availableCategories();
        if (avail.isEmpty()) { categoryId = null; return; }
        // positions: -1 = "All", then 0..n-1
        int cur = -1;
        for (int i = 0; i < avail.size(); i++) if (avail.get(i).id().equals(categoryId)) cur = i;
        int next = cur + dir;
        if (next >= avail.size()) next = -1;
        else if (next < -1) next = avail.size() - 1;
        categoryId = next == -1 ? null : avail.get(next).id();
    }

    private void promptSearch() {
        later(() -> {
            player.closeInventory();
            plugin.getChatPrompts().begin(player,
                    plugin.msg().get("directory.search-prompt"),
                    text -> { query = text; page = 0; render(); open(); },
                    this::open);
        });
    }

    private void later(Runnable r) { Sched.entityNext(player, r); }

    @Override public Inventory getInventory() { return inv; }
}
