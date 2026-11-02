package org.riftlabs.villagerMerchants.gui;

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
    private static final double[] MIN_RATINGS = {0, 3.0, 4.0, 4.5};

    private enum Sort {
        RATING("Top rated"), POPULAR("Most popular"), PRICE("Price: low to high"), NAME("Owner name (A-Z)");

        final String label;
        Sort(String label) { this.label = label; }
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
    private boolean saleOnly;

    public ShopDirectoryGui(VillagerMerchants plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inv = Bukkit.createInventory(this, 54, Text.mm("<dark_gray>Shop Directory"));
        render();
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
        double minRating = MIN_RATINGS[minRatingIdx];

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

        ItemStack pane = Text.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(PREV, Text.icon(Material.ARROW, "<yellow>Previous page"));
        if (page + 1 < pages) inv.setItem(NEXT, Text.icon(Material.ARROW, "<yellow>Next page"));
        inv.setItem(CLOSE, Text.icon(Material.BARRIER, "<red>Close"));
        inv.setItem(SORT, Text.icon(Material.HOPPER, "<aqua>Sort: <white>" + sort.label, "<yellow>Click to change",
                "<dark_gray>Featured shops always stay on top."));

        // category
        Category cat = categoryId == null ? null : plugin.getCategories().get(categoryId);
        inv.setItem(CATEGORY, Text.icon(cat == null ? Material.COMPASS : cat.icon(),
                "<aqua>Category: <white>" + (cat == null ? "All" : cat.name()),
                "<yellow>Click: next category", "<yellow>Right-click: previous"));

        // search
        inv.setItem(SEARCH, query == null
                ? Text.icon(Material.SPYGLASS, "<aqua>Search", "<gray>Find shops by item, enchant or owner.", "<yellow>Click, then type in chat")
                : Text.glow(Text.icon(Material.SPYGLASS, "<aqua>Search: <white>" + Text.esc(query), "<yellow>Click: new search", "<yellow>Right-click: clear")));

        // rating filter
        double mr = MIN_RATINGS[minRatingIdx];
        inv.setItem(RATING, Text.icon(Material.GOLD_NUGGET,
                "<aqua>Rating: <white>" + (mr == 0 ? "any" : Text.oneDecimal(mr) + "\u2605 and up"),
                "<yellow>Click to change", "<dark_gray>Unrated shops are hidden when set."));

        // sale filter
        long onSale = plugin.getShopManager().allShops().stream().filter(s -> s.isOnSale(now) && s.isSaleListed() && s.isActive(now)).count();
        inv.setItem(SALE, saleOnly
                ? Text.glow(Text.icon(Material.GOLDEN_CARROT, "<red>Only sales: <green>ON", "<gray>Shops on sale: <white>" + onSale, "<yellow>Click to turn off"))
                : Text.icon(Material.GOLDEN_CARROT, "<aqua>Only sales: <white>off", "<gray>Shops on sale: <white>" + onSale, "<yellow>Click to turn on"));

        inv.setItem(INFO, Text.icon(Material.PAPER, "<white>Shop Directory",
                "<gray>" + (filtersActive() ? "Matching shops: " : "Open shops: ") + "<white>" + list.size(),
                "<gray>Page <white>" + (page + 1) + "/" + pages,
                filtersActive() ? "<yellow>Right-click: clear all filters" : "<dark_gray>No filters active"));

        if (list.isEmpty()) {
            inv.setItem(22, filtersActive()
                    ? Text.icon(Material.COBWEB, "<gray>No shops match", "<dark_gray>Try other words or clear the filters", "<dark_gray>(right-click the paper below).")
                    : Text.icon(Material.COBWEB, "<gray>No open shops yet", "<dark_gray>Rent a shop NPC to be the first!"));
        }
    }

    private ItemStack entry(Shop s, long now) {
        ShopManager sm = plugin.getShopManager();
        CategoryRegistry cats = plugin.getCategories();
        String owner = sm.ownerName(s.getOwner());
        boolean featured = s.isFeatured(now);
        List<String> lore = new ArrayList<>();

        if (featured) lore.add("<gold><bold>\u2605 FEATURED</bold>");
        if (s.isOnSale(now) && s.isSaleListed()) {
            lore.add("<red><bold>ON SALE</bold> <yellow>-" + s.getSalePercent() + "% <gray>(" + Text.duration(s.getSaleExpiry() - now) + " left)");
        }

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

        List<String> catNames = new ArrayList<>();
        for (String id : cats.categoriesOf(s)) {
            Category c = cats.get(id);
            if (c != null) catNames.add(c.name());
        }
        if (!catNames.isEmpty()) lore.add("<gray>Sells: <white>" + String.join("<gray>, <white>", catNames.subList(0, Math.min(4, catNames.size())))
                + (catNames.size() > 4 ? " <gray>+" + (catNames.size() - 4) : ""));

        List<Trade> hit = matches.getOrDefault(s, List.of());
        if ((query != null || categoryId != null) && !hit.isEmpty()) {
            lore.add("<gray>Matching offers:");
            for (Trade t : hit.subList(0, Math.min(3, hit.size()))) lore.add(" <dark_gray>- " + offerLine(s, t, now));
            if (hit.size() > 3) lore.add(" <dark_gray>+" + (hit.size() - 3) + " more");
        } else if (!names.isEmpty()) {
            List<String> shown = new ArrayList<>(names).subList(0, Math.min(3, names.size()));
            int more = names.size() - shown.size();
            lore.add("<gray>Items: <white>" + String.join("<gray>, <white>", shown) + (more > 0 ? " <gray>+" + more + " more" : ""));
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
        meta.displayName(Text.item((featured ? "<gold>\u2605 " : "<gold>") + Text.esc(owner) + "'s Shop"));
        List<Component> loreC = new ArrayList<>();
        for (String l : lore) loreC.add(Text.item(l));
        meta.lore(loreC);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (featured) meta.setEnchantmentGlintOverride(true);
        head.setItemMeta(meta);
        return head;
    }

    /** "2x Diamond Sword for $400 (-20%)" or "1x Mending Book for 8x Emerald". */
    private String offerLine(Shop s, Trade t, long now) {
        ItemStack res = t.getResult();
        String what = "<white>" + res.getAmount() + "x " + Text.esc(Text.itemName(res));
        if (t.isMoney()) {
            String price = plugin.getShopManager().money(s.effectivePrice(t, now));
            return what + " <gray>for <gold>" + Text.esc(price) + (s.isOnSale(now) ? " <red>(-" + s.getSalePercent() + "%)" : "");
        }
        StringBuilder pay = new StringBuilder(t.getPay1().getAmount() + "x " + Text.esc(Text.itemName(t.getPay1())));
        if (t.getPay2() != null) pay.append(" + ").append(t.getPay2().getAmount()).append("x ").append(Text.esc(Text.itemName(t.getPay2())));
        return what + " <gray>for <white>" + pay;
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
            case RATING -> { minRatingIdx = (minRatingIdx + 1) % MIN_RATINGS.length; page = 0; render(); }
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
                    "<yellow>Type what you're looking for <gray>(e.g. <white>diamond sword</white>, <white>mending</white>, <white>golden carrot</white> or a player's name).",
                    text -> { query = text; page = 0; render(); open(); },
                    this::open);
        });
    }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    @Override public Inventory getInventory() { return inv; }
}
