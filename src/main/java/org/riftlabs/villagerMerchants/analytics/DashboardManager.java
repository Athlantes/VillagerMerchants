package org.riftlabs.villagerMerchants.analytics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.analytics.AnalyticsData.Mode;
import org.riftlabs.villagerMerchants.analytics.render.Fonts;
import org.riftlabs.villagerMerchants.analytics.render.Frame;
import org.riftlabs.villagerMerchants.analytics.render.Theme;
import org.riftlabs.villagerMerchants.util.ItemCodec;
import org.riftlabs.villagerMerchants.util.Sched;

import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The map dashboard.
 *
 * <p><b>How it shows up.</b> One server-wide map id is created once (and remembered in {@code analytics-map.id}).
 * Its renderer is <i>contextual</i>: every viewer gets their own canvas, so two owners can look at different data
 * on the same map id. While a dashboard is open, a tagged FILLED_MAP sits in the player's selected hotbar slot and
 * the item that was there is held aside, in memory and also inside the player's own persistent data, so a crash or
 * a stop can never lose it: the join sweep puts it back.
 *
 * <p><b>Threading (Folia).</b> {@code MapRenderer.render} runs on whichever thread ticks the viewer, so it only
 * reads immutable snapshots ({@link AnalyticsData}) and does pure arithmetic. Queries run on the database thread
 * and publish their result through a volatile field. Everything that touches a player's inventory goes through
 * {@link Sched#entity}.
 */
public final class DashboardManager {
    private static final double SLIDE_SECONDS = 0.30, ENTER_SECONDS = 0.90;

    /** Per-viewer state. Written from the player's thread, read by the renderer: hence volatile. */
    private static final class Session {
        final UUID player;
        final Scope scope;
        final int slot;
        final ItemStack original;       // what the hotbar slot held before; may be null
        final long openedNs = System.nanoTime();
        volatile AnalyticsData data;
        volatile int page = Pages.OVERVIEW;
        volatile Mode mode;
        volatile long switchedNs = System.nanoTime() - 5_000_000_000L;
        volatile Frame previous;        // last settled frame, for the slide transition
        volatile int direction = 1;
        volatile long lastActiveMs = System.currentTimeMillis();
        volatile long lastLoadMs = 0;
        volatile boolean loading = false;
        volatile long lastClickNs = 0;
        volatile Sched.Handle ticker = Sched.Handle.NONE;

        Session(UUID player, Scope scope, int slot, ItemStack original, Mode mode) {
            this.player = player;
            this.scope = scope;
            this.slot = slot;
            this.original = original;
            this.mode = mode;
        }
    }

    private final VillagerMerchants plugin;
    private final AnalyticsRepository repo;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final NamespacedKey keyItem, keyStash, keySlot;
    private final MapRenderer renderer;
    private volatile MapView view;
    private volatile boolean viewFailed;

    public DashboardManager(VillagerMerchants plugin) {
        this.plugin = plugin;
        this.repo = new AnalyticsRepository(plugin.getDatabaseManager(), plugin.getLogger());
        this.keyItem = new NamespacedKey(plugin, "dashboard_item");
        this.keyStash = new NamespacedKey(plugin, "dashboard_stash");
        this.keySlot = new NamespacedKey(plugin, "dashboard_slot");
        this.renderer = new MapRenderer(true) {   // true = contextual: one canvas per viewing player
            @Override
            public void render(MapView map, MapCanvas canvas, Player player) {
                try {
                    Session s = sessions.get(player.getUniqueId());
                    if (s == null) return;
                    byte[] px = compose(s).px;
                    for (int y = 0; y < Frame.H; y++)
                        for (int x = 0; x < Frame.W; x++) canvas.setPixel(x, y, px[y * Frame.W + x]);
                } catch (Throwable ignored) {
                    // never let a drawing bug reach the server's map code
                }
            }
        };
        // Worlds exist by the first tick on both Paper and Folia; create the map there, on the global thread.
        Sched.global(this::ensureView);
    }

    // ------------------------------------------------------------ configuration

    public boolean enabled() { return plugin.getConfig().getBoolean("analytics.enabled", true); }
    public boolean staffAccess() { return plugin.getConfig().getBoolean("analytics.staff-access", false); }
    private int days() { return Math.max(7, Math.min(21, plugin.getConfig().getInt("analytics.days", 14))); }
    private int weeks() { return Math.max(4, Math.min(12, plugin.getConfig().getInt("analytics.weeks", 8))); }
    private Mode defaultMode() {
        return "week".equalsIgnoreCase(plugin.getConfig().getString("analytics.default-range", "day")) ? Mode.WEEK : Mode.DAY;
    }

    private void sound(Player p, String key) {
        String s = plugin.getConfig().getString("analytics.sounds." + key, "");
        if (s == null || s.isBlank()) return;
        try { p.playSound(p.getLocation(), s, 0.6f, key.equals("close") ? 0.9f : 1.2f); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------ the shared map

    @SuppressWarnings("deprecation")
    private synchronized MapView ensureView() {
        if (view != null) return view;
        try {
            File idFile = new File(plugin.getDataFolder(), "analytics-map.id");
            MapView mv = null;
            if (idFile.isFile()) {
                try { mv = Bukkit.getMap(Integer.parseInt(Files.readString(idFile.toPath()).trim())); } catch (Exception ignored) { }
            }
            if (mv == null) {
                List<World> worlds = Bukkit.getWorlds();
                if (worlds.isEmpty()) return null;
                mv = Bukkit.createMap(worlds.get(0));
                Files.writeString(idFile.toPath(), String.valueOf(mv.getId()));
            }
            for (MapRenderer r : new ArrayList<>(mv.getRenderers())) mv.removeRenderer(r);
            mv.addRenderer(renderer);
            try { mv.setTrackingPosition(false); } catch (Throwable ignored) { }
            try { mv.setUnlimitedTracking(false); } catch (Throwable ignored) { }
            try { mv.setLocked(true); } catch (Throwable ignored) { }
            view = mv;
            return mv;
        } catch (Throwable t) {
            if (!viewFailed) plugin.getLogger().warning("Analytics map could not be created: " + t);
            viewFailed = true;
            return null;
        }
    }

    // ------------------------------------------------------------ items

    public boolean isDashboardItem(ItemStack it) {
        if (it == null || it.getType() != Material.FILLED_MAP || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(keyItem, PersistentDataType.BYTE);
    }

    private ItemStack buildItem(MapView mv) {
        ItemStack it = new ItemStack(Material.FILLED_MAP);
        MapMeta mm = (MapMeta) it.getItemMeta();
        mm.setMapView(mv);
        MiniMessage mini = MiniMessage.miniMessage();
        mm.displayName(mini.deserialize(plugin.msg().raw("analytics.item.name")).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String l : plugin.msg().rawList("analytics.item.lore"))
            lore.add(mini.deserialize(l).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        mm.lore(lore);
        mm.getPersistentDataContainer().set(keyItem, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(mm);
        return it;
    }

    // ------------------------------------------------------------ opening and closing (player's thread)

    public boolean has(Player p) { return sessions.containsKey(p.getUniqueId()); }

    /** Opens (or restarts) the dashboard for {@code p}. Must run on the player's thread. */
    public void open(Player p, Scope scope) {
        if (!enabled()) return;
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        MapView mv = ensureView();
        if (mv == null) { plugin.msg().send(p, "analytics.unavailable"); return; }
        close(p, false);

        PlayerInventory inv = p.getInventory();
        int slot = inv.getHeldItemSlot();
        ItemStack cur = inv.getItem(slot);
        ItemStack original = (cur == null || cur.getType().isAir() || isDashboardItem(cur)) ? null : cur.clone();

        // 1) remember the original inside the player's own data first, 2) only then replace it
        var pdc = p.getPersistentDataContainer();
        pdc.set(keyStash, PersistentDataType.STRING, original == null ? "" : ItemCodec.encode(new ItemStack[]{original}));
        pdc.set(keySlot, PersistentDataType.INTEGER, slot);

        Session s = new Session(p.getUniqueId(), scope, slot, original, defaultMode());
        sessions.put(p.getUniqueId(), s);
        inv.setItem(slot, buildItem(mv));
        s.ticker = Sched.entityTimer(p, h -> tick(p, s), () -> sessions.remove(p.getUniqueId(), s), 10L, 10L);

        load(s, true);
        sound(p, "open");
        p.sendActionBar(plugin.msg().get("analytics.opened"));
    }

    /** Closes the dashboard and gives the hotbar slot back. Safe to call when none is open. */
    public void close(Player p, boolean sound) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        s.ticker.cancel();
        restore(p, s.original, s.slot);
        if (sound) sound(p, "close");
    }

    /** Puts {@code original} back where the dashboard map is and removes any other dashboard map. */
    private void restore(Player p, ItemStack original, int slot) {
        PlayerInventory inv = p.getInventory();
        boolean placed = false;
        ItemStack cur = inv.getItem(slot);
        if (isDashboardItem(cur)) { inv.setItem(slot, original); placed = true; }
        else if (original == null) placed = true;
        sweep(p);
        if (!placed) {
            var left = inv.addItem(original);
            for (ItemStack rest : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        var pdc = p.getPersistentDataContainer();
        pdc.remove(keyStash);
        pdc.remove(keySlot);
    }

    /** Removes every dashboard map from the inventory (hotbar, storage, armor, offhand). */
    private void sweep(Player p) {
        PlayerInventory inv = p.getInventory();
        ItemStack[] all = inv.getContents();
        for (int i = 0; i < all.length; i++) if (isDashboardItem(all[i])) inv.setItem(i, null);
    }

    /** After a crash or a Folia shutdown the map can still be in the saved inventory: heal it on join. */
    public void heal(Player p) {
        var pdc = p.getPersistentDataContainer();
        String stash = pdc.get(keyStash, PersistentDataType.STRING);
        Integer slot = pdc.get(keySlot, PersistentDataType.INTEGER);
        ItemStack original = null;
        if (stash != null && !stash.isEmpty()) {
            try {
                ItemStack[] arr = ItemCodec.decode(stash);
                if (arr.length > 0) original = arr[0];
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not decode a stashed hotbar item for " + p.getName() + ": " + t.getMessage());
            }
        }
        boolean had = false;
        for (ItemStack it : p.getInventory().getContents()) if (isDashboardItem(it)) { had = true; break; }
        if (!had && stash == null) return;
        restore(p, original, slot == null ? p.getInventory().getHeldItemSlot() : Math.max(0, Math.min(40, slot)));
    }

    /** Death: the stashed item must drop (or be kept) instead of the map. Returns true when a dashboard was open. */
    public boolean onDeath(Player p, List<ItemStack> drops, boolean keepInventory) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return false;
        if (keepInventory) { close(p, false); return true; }
        sessions.remove(p.getUniqueId());
        s.ticker.cancel();
        drops.removeIf(this::isDashboardItem);
        if (s.original != null) drops.add(s.original);
        var pdc = p.getPersistentDataContainer();
        pdc.remove(keyStash);
        pdc.remove(keySlot);
        return true;
    }

    /** Server stop. Paper: restore inline. Folia: shutdown threads do not own players, so rely on {@link #heal}. */
    public void shutdown() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = Bukkit.getPlayer(s.player);
            if (p == null) continue;
            try {
                if (!Sched.isFolia() || Bukkit.isOwnedByCurrentRegion(p)) close(p, false);
            } catch (Throwable ignored) { }
        }
        sessions.clear();
    }

    // ------------------------------------------------------------ interaction (player's thread)

    public boolean isViewing(Player p) { return sessions.containsKey(p.getUniqueId()); }

    /** @return true if the click was consumed (the event should be cancelled) */
    public boolean click(Player p, boolean forward, boolean sneaking) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !isDashboardItem(p.getInventory().getItemInMainHand())) return false;
        long now = System.nanoTime();
        if (now - s.lastClickNs < 150_000_000L) return true;   // one swing can raise two events
        s.lastClickNs = now;
        s.lastActiveMs = System.currentTimeMillis();
        if (s.data == null) return true;

        Frame settled = compose(s);
        if (sneaking) {
            if (forward) { s.mode = s.mode.other(); s.direction = 1; }
            else { load(s, true); sound(p, "flip"); return true; }
        } else {
            s.page = Math.floorMod(s.page + (forward ? 1 : -1), Pages.COUNT);
            s.direction = forward ? 1 : -1;
        }
        s.previous = settled;
        s.switchedNs = now;
        sound(p, "flip");
        return true;
    }

    private void tick(Player p, Session s) {
        if (sessions.get(p.getUniqueId()) != s) return;
        if (!p.isOnline() || p.isDead() || !isDashboardItem(p.getInventory().getItem(s.slot))) { close(p, false); return; }
        if (s.scope.singleShop() && plugin.getShopManager().getShop(s.scope.shopId()) == null) { close(p, true); return; }
        int idle = plugin.getConfig().getInt("analytics.idle-timeout-seconds", 120);
        long now = System.currentTimeMillis();
        if (idle > 0 && now - s.lastActiveMs > idle * 1000L) { close(p, true); return; }
        int refresh = plugin.getConfig().getInt("analytics.refresh-seconds", 15);
        if (refresh > 0 && now - s.lastLoadMs > refresh * 1000L) load(s, false);
    }

    // ------------------------------------------------------------ data (database thread)

    private void load(Session s, boolean force) {
        if (s.loading) return;
        s.loading = true;
        s.lastLoadMs = System.currentTimeMillis();
        final int days = days(), weeks = weeks();
        final String label = s.scope.label();
        final Locale locale = Locale.forLanguageTag(plugin.msg().language().replace('_', '-'));
        final String weekPrefix = Fonts.sanitize(plugin.msg().raw("analytics.map.week-prefix"));
        plugin.getDatabaseManager().async(() -> {
            try {
                ZoneId zone = ZoneId.systemDefault();
                long now = System.currentTimeMillis();
                long version = repo.version(s.scope);
                AnalyticsData old = s.data;
                if (!force && old != null && old.version() == version && version >= 0
                        && AnalyticsBuilder.dateOf(old.generatedAt(), zone).equals(AnalyticsBuilder.dateOf(now, zone))) return;
                var w = AnalyticsBuilder.window(zone, days, weeks, now);
                var slots = repo.slots(s.scope, w.fetchSince());
                AnalyticsData d = AnalyticsBuilder.build(label, version, now, zone, locale, weekPrefix, days, weeks, w, slots,
                        repo.items(s.scope, w.dayStartMs()), repo.items(s.scope, w.weekStartMs()),
                        repo.buyers(s.scope, w.dayStartMs()), repo.buyers(s.scope, w.weekStartMs()), repo.ratings(s.scope));
                s.data = d;
            } finally {
                s.loading = false;
            }
        });
    }

    // ------------------------------------------------------------ drawing (any thread)

    private Pages.Ctx ctx(Session s, double progress, double time, String hint) {
        Theme theme = Theme.of(plugin.getConfig().getString("analytics.accent", "gold"));
        String symbol = plugin.getConfig().getString("analytics.currency-symbol", "$");
        String title = Fonts.sanitize(s.scope.label());
        return new Pages.Ctx(theme, k -> plugin.msg().str("analytics.map." + k), symbol == null ? "" : symbol, title, progress, time, hint);
    }

    private Frame compose(Session s) {
        long now = System.nanoTime();
        double time = (now - s.openedNs) / 1e9;
        AnalyticsData d = s.data;
        Mode mode = s.mode;
        if (d == null) return Pages.loading(ctx(s, 1, time, null), mode, mode == Mode.DAY ? days() : weeks());

        String hint = time < 4 ? plugin.msg().str("analytics.map.hint-pages") : time < 8 ? plugin.msg().str("analytics.map.hint-range") : null;
        double since = (now - s.switchedNs) / 1e9;
        Frame prev = s.previous;
        if (prev != null && since < SLIDE_SECONDS) {
            double t = since / SLIDE_SECONDS;
            double e = 1 - Math.pow(1 - t, 3);
            Frame target = Pages.render(s.page, d, mode, ctx(s, 0, time, hint));
            return Frame.slide(prev, target, s.direction * (int) Math.round(Frame.W * e));
        }
        double p = Math.min(1.0, (since - SLIDE_SECONDS) / ENTER_SECONDS);
        return Pages.render(s.page, d, mode, ctx(s, Math.max(0, p), time, hint));
    }
}
