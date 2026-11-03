package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.CustomItems;
import org.riftlabs.villagerMerchants.util.Text;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Anti-abuse rules for shops. Everything is configured under {@code anti-abuse:} in config.yml.
 *
 * <ul>
 *   <li><b>Item blacklist</b>: materials (wildcards allowed), custom-item ids, and a built-in list of
 *       creative-only items. A trade that contains a banned item can't be edited in and is locked for customers.</li>
 *   <li><b>Max price per item</b>: a cap per single unit of the item being sold, with per-item overrides.
 *       A money trade priced above {@code cap * result amount} is refused in the editor and locked for customers.</li>
 *   <li><b>Purchase cooldown</b>: minimum time between a player's purchases, plus a rolling-window cap.</li>
 *   <li><b>Suspicious-activity log</b>: {@code suspicious.log}, console, and an in-game alert for staff with
 *       {@code villagermerchants.alerts}. Readable in game with {@code /vm suspicious [count]}.</li>
 * </ul>
 *
 * Runtime enforcement (recipe locking and the purchase click) is the authority; the editor checks are there so
 * owners get immediate feedback instead of discovering a locked trade later. That also covers trades saved before
 * the rules changed. All methods are meant to be called from the main thread.
 */
public class AntiAbuse {
    public enum Problem { NONE, BANNED_ITEM, PRICE_CAP }

    /**
     * Items that can't be obtained in survival. Names that don't exist on the running server version are simply
     * never matched, so this list is safe across versions. Wildcards: {@code *} matches any run of characters.
     */
    private static final List<String> CREATIVE_ONLY = List.of(
            "BEDROCK", "BARRIER", "LIGHT", "STRUCTURE_BLOCK", "STRUCTURE_VOID", "JIGSAW",
            "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "COMMAND_BLOCK_MINECART",
            "DEBUG_STICK", "KNOWLEDGE_BOOK", "SPAWNER", "TRIAL_SPAWNER", "VAULT",
            "REINFORCED_DEEPSLATE", "END_PORTAL_FRAME", "FARMLAND", "BUDDING_AMETHYST", "CHORUS_PLANT",
            "FROGSPAWN", "PETRIFIED_OAK_SLAB", "TEST_BLOCK", "TEST_INSTANCE_BLOCK",
            "*_SPAWN_EGG", "INFESTED_*");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final VillagerMerchants plugin;

    // ---- config snapshot (rebuilt by reload())
    private boolean enabled;
    private final Set<String> bannedNames = new HashSet<>();
    private final List<Pattern> bannedPatterns = new ArrayList<>();
    private final Set<String> bannedCustom = new HashSet<>();
    private double defaultCap;
    private final Map<String, Double> caps = new HashMap<>();
    private long cooldownMs;
    private int maxPurchases;
    private long windowMs;
    private double highValue;
    private int pairCount;
    private long pairWindowMs;
    private boolean sameIp;
    private boolean toConsole;
    private boolean alertStaff;
    private long throttleMs;
    private int memoryEntries;
    private File logFile;

    // ---- runtime state
    private final Map<UUID, Long> lastPurchase = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> purchaseWindow = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> pairs = new HashMap<>();
    private final Map<String, Long> pairFlagged = new HashMap<>();
    private final Map<String, Long> throttle = new HashMap<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private int sweepCounter;
    private ExecutorService writer;

    public AntiAbuse(VillagerMerchants plugin) {
        this.plugin = plugin;
        reload();
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }

    // ------------------------------------------------------------ config

    public void reload() {
        FileConfiguration c = cfg();
        enabled = c.getBoolean("anti-abuse.enabled", true);

        bannedNames.clear();
        bannedPatterns.clear();
        bannedCustom.clear();
        if (c.getBoolean("anti-abuse.blacklist.creative-only", true)) for (String s : CREATIVE_ONLY) addBanned(s);
        for (String s : c.getStringList("anti-abuse.blacklist.materials")) addBanned(s);
        for (String s : c.getStringList("anti-abuse.blacklist.custom-items")) {
            if (s != null && !s.isBlank()) bannedCustom.add(s.trim().toLowerCase(Locale.ROOT));
        }

        defaultCap = Math.max(0, c.getDouble("anti-abuse.max-unit-price.default", 0));
        caps.clear();
        ConfigurationSection ov = c.getConfigurationSection("anti-abuse.max-unit-price.overrides");
        if (ov != null) {
            for (String k : ov.getKeys(false)) caps.put(k.trim().toUpperCase(Locale.ROOT), Math.max(0, ov.getDouble(k, 0)));
        }

        cooldownMs = Math.max(0, c.getLong("anti-abuse.cooldown.purchase-ms", 750));
        maxPurchases = Math.max(0, c.getInt("anti-abuse.cooldown.max-purchases", 30));
        windowMs = Math.max(1, c.getLong("anti-abuse.cooldown.window-seconds", 10)) * 1000L;

        highValue = Math.max(0, c.getDouble("anti-abuse.suspicious.high-value", 1_000_000));
        pairCount = Math.max(0, c.getInt("anti-abuse.suspicious.repeat-pair.count", 15));
        pairWindowMs = Math.max(1, c.getLong("anti-abuse.suspicious.repeat-pair.window-minutes", 60)) * 60_000L;
        sameIp = c.getBoolean("anti-abuse.suspicious.same-ip", true);
        toConsole = c.getBoolean("anti-abuse.suspicious.console", true);
        alertStaff = c.getBoolean("anti-abuse.suspicious.alert-staff", true);
        throttleMs = Math.max(0, c.getLong("anti-abuse.suspicious.throttle-seconds", 5)) * 1000L;
        memoryEntries = Math.max(10, c.getInt("anti-abuse.suspicious.memory-entries", 200));

        String name = c.getString("anti-abuse.suspicious.log-file", "suspicious.log");
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.contains("..")) name = "suspicious.log";
        File newFile = new File(plugin.getDataFolder(), name);
        boolean changed = logFile == null || !logFile.equals(newFile);
        logFile = newFile;
        if (changed) loadTail();
    }

    private void addBanned(String raw) {
        if (raw == null) return;
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return;
        if (s.contains("*")) {
            StringBuilder re = new StringBuilder("^");
            for (String part : s.split("\\*", -1)) {
                if (re.length() > 1) re.append(".*");
                re.append(Pattern.quote(part));
            }
            bannedPatterns.add(Pattern.compile(re.append('$').toString()));
        } else {
            bannedNames.add(s);
        }
    }

    public boolean enabled() { return enabled; }

    // ------------------------------------------------------------ item blacklist

    public boolean isBannedItem(ItemStack it) {
        if (!enabled || it == null || it.getType().isAir()) return false;
        String key = CustomItems.keyOf(it);
        if (key != null && bannedCustom.contains(key.toLowerCase(Locale.ROOT))) return true;
        String name = it.getType().name();
        if (bannedNames.contains(name)) return true;
        for (Pattern p : bannedPatterns) if (p.matcher(name).matches()) return true;
        return false;
    }

    // ------------------------------------------------------------ price cap

    /** Highest price allowed for one unit of this item, or 0 if uncapped. */
    public double unitCap(ItemStack result) {
        if (!enabled || result == null) return 0;
        String key = CustomItems.keyOf(result);
        if (key != null) {
            Double d = caps.get(key.toUpperCase(Locale.ROOT));
            if (d != null) return d;
        }
        Double d = caps.get(result.getType().name());
        return d != null ? d : defaultCap;
    }

    /** Highest price for the whole result stack (cap x amount), or +infinity if uncapped. A cap of 0 means no cap. */
    public double maxTotal(ItemStack result) {
        double unit = unitCap(result);
        return unit > 0 ? Text.round2(unit * Math.max(1, result.getAmount())) : Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------ trade verdict

    public Problem check(Trade t) {
        if (!enabled || t == null) return Problem.NONE;
        if (isBannedItem(t.getResult()) || isBannedItem(t.getPay1()) || isBannedItem(t.getPay2())) return Problem.BANNED_ITEM;
        if (t.isMoney() && t.getResult() != null && t.getPrice() > maxTotal(t.getResult()) + 0.0001) return Problem.PRICE_CAP;
        return Problem.NONE;
    }

    public boolean isBlocked(Trade t) { return check(t) != Problem.NONE; }

    // ------------------------------------------------------------ purchase cooldown

    /**
     * Call when a player clicks to buy. Returns false (and tells the player) if they are inside the cooldown or over
     * the rolling-window cap; otherwise records the attempt and returns true.
     */
    public boolean allowPurchase(Player p, Shop shop) {
        if (!enabled || plugin.access().bypassTradeCooldown(p)) return true;
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();

        Long last = lastPurchase.get(id);
        if (cooldownMs > 0 && last != null && now - last < cooldownMs) {
            double left = (cooldownMs - (now - last)) / 1000.0;
            p.sendActionBar(plugin.msg().get("anti-abuse.cooldown", "seconds", String.format(Locale.ROOT, "%.1f", left)));
            return false;
        }
        if (maxPurchases > 0) {
            Deque<Long> q = purchaseWindow.computeIfAbsent(id, k -> new ArrayDeque<>());
            while (!q.isEmpty() && q.peekFirst() < now - windowMs) q.pollFirst();
            if (q.size() >= maxPurchases) {
                p.sendActionBar(plugin.msg().get("anti-abuse.rate-limited"));
                log("RATE_LIMIT", p, shop, true, "made " + q.size() + " purchases in " + (windowMs / 1000) + "s (limit " + maxPurchases + ")");
                return false;
            }
            q.addLast(now);
        }
        lastPurchase.put(id, now);
        return true;
    }

    /** Drops a player's cooldown state (call on quit). */
    public void forget(UUID id) {
        lastPurchase.remove(id);
        purchaseWindow.remove(id);
    }

    // ------------------------------------------------------------ detection

    /** Call after a purchase went through. Looks for high-value sales, repeated buyer/owner pairs, and shared IPs. */
    public void onSale(Player buyer, Shop shop, Trade trade, int times, double unit) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        ItemStack res = trade.getResult();
        String what = res == null ? "?" : (res.getAmount() * times) + "x " + Text.itemName(res);

        if (trade.isMoney()) {
            double total = Text.round2(unit * times);
            if (highValue > 0 && total >= highValue) {
                log("HIGH_VALUE", buyer, shop, false, "paid " + plugin.getShopManager().money(total) + " for " + what);
            }
        }

        String key = buyer.getUniqueId() + ">" + shop.getOwner();
        Deque<Long> q = pairs.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst() < now - pairWindowMs) q.pollFirst();
        q.addLast(now);

        if (pairCount > 0 && q.size() >= pairCount && notFlaggedRecently("pair|" + key, now)) {
            log("REPEAT_PAIR", buyer, shop, false, q.size() + " purchases from the same owner in "
                    + (pairWindowMs / 60_000L) + " min (possible wash trading), latest: " + what);
        }
        if (sameIp && notFlaggedRecently("ip|" + key, now)) {
            Player seller = Bukkit.getPlayer(shop.getOwner());
            if (seller != null && buyer.getAddress() != null && seller.getAddress() != null
                    && buyer.getAddress().getAddress().equals(seller.getAddress().getAddress())) {
                log("SAME_IP", buyer, shop, false, "buyer and shop owner are connected from the same IP address, bought " + what);
            }
        }
        sweep(now);
    }

    /** True (and marks it) if this flag wasn't raised within the pair window. */
    private boolean notFlaggedRecently(String flag, long now) {
        Long f = pairFlagged.get(flag);
        if (f != null && now - f < pairWindowMs) return false;
        pairFlagged.put(flag, now);
        return true;
    }

    private void sweep(long now) {
        if (++sweepCounter % 256 != 0) return;
        pairs.values().removeIf(d -> d.isEmpty() || d.peekLast() < now - pairWindowMs);
        pairFlagged.values().removeIf(t -> now - t >= pairWindowMs);
        throttle.values().removeIf(t -> now - t >= Math.max(throttleMs, 1000));
    }

    // ------------------------------------------------------------ reporting helpers

    /** An owner tried to put a banned item into a trade (or into a price-capped one). */
    public void logBlockedEdit(Player p, Shop shop, ItemStack item, int tradeIdx, int row) {
        String slot = switch (row) { case 0 -> "payment 1"; case 1 -> "payment 2"; default -> "result"; };
        log("BANNED_ITEM_EDIT", p, shop, true, "tried to set banned item " + item.getType().name()
                + " as " + slot + " of trade #" + (tradeIdx + 1));
    }

    public void logBlockedPrice(Player p, Shop shop, ItemStack result, double price, double max) {
        log("PRICE_CAP_EDIT", p, shop, true, "tried to price " + result.getAmount() + "x " + result.getType().name()
                + " at " + plugin.getShopManager().money(price) + " (max " + plugin.getShopManager().money(max) + ")");
    }

    /** A customer clicked a trade that is locked by the rules (it was saved before they changed, or was crafted around them). */
    public void logBlockedPurchase(Player p, Shop shop, Trade t, Problem why) {
        String res = t.getResult() == null ? "?" : t.getResult().getType().name();
        log("BLOCKED_PURCHASE", p, shop, true, "tried to buy " + res + " from a trade locked for " + why.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    // ------------------------------------------------------------ log

    /**
     * Records a suspicious event: appended to the log file (off the main thread), printed to the console, kept in
     * memory for /vm suspicious, and announced to online staff with villagermerchants.alerts.
     *
     * @param throttle drop repeats of the same type by the same player within throttle-seconds (for noisy events)
     */
    public void log(String type, Player actor, Shop shop, boolean throttle, String detail) {
        if (!enabled) return;
        long now = System.currentTimeMillis();
        if (throttle && throttleMs > 0 && actor != null) {
            String k = type + "|" + actor.getUniqueId();
            Long l = this.throttle.get(k);
            if (l != null && now - l < throttleMs) return;
            this.throttle.put(k, now);
        }

        String actorName = actor == null ? "-" : actor.getName();
        String ownerName = shop == null ? "-" : plugin.getShopManager().ownerName(shop.getOwner());
        String where = shop == null ? "" : " @ " + shop.getLocation();
        String clean = clean(detail);
        String line = STAMP.format(LocalDateTime.now()) + " | " + type + " | " + clean(actorName)
                + (actor == null ? "" : " (" + actor.getUniqueId() + ")")
                + " | shop of " + clean(ownerName) + clean(where) + " | " + clean;

        synchronized (recent) {
            recent.addLast(line);
            while (recent.size() > memoryEntries) recent.pollFirst();
        }
        append(line);
        if (toConsole) plugin.getLogger().warning("[suspicious] " + line);
        if (alertStaff) alert(type, actorName, ownerName, clean);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ');
    }

    private void alert(String type, String player, String owner, String detail) {
        Runnable r = () -> {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (plugin.access().canSeeAlerts(staff)) {
                    plugin.msg().send(staff, "anti-abuse.alert", "type", type, "player", player, "owner", owner, "detail", detail);
                }
            }
        };
        if (Bukkit.isPrimaryThread()) r.run(); else plugin.sync(r);
    }

    private synchronized void append(String line) {
        if (writer == null) {
            writer = Executors.newSingleThreadExecutor(run -> {
                Thread t = new Thread(run, "VillagerMerchants-SuspiciousLog");
                t.setDaemon(true);
                return t;
            });
        }
        final File f = logFile;
        writer.execute(() -> {
            try {
                Files.writeString(f.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().warning("Could not write " + f.getName() + ": " + e.getMessage());
            }
        });
    }

    /** Fills the in-memory list from the end of an existing log file, so /vm suspicious survives restarts. */
    private void loadTail() {
        synchronized (recent) {
            recent.clear();
            if (logFile == null || !logFile.isFile()) return;
            try (var lines = Files.lines(logFile.toPath(), StandardCharsets.UTF_8)) {
                lines.forEach(l -> {
                    if (l.isBlank()) return;
                    recent.addLast(l);
                    if (recent.size() > memoryEntries) recent.pollFirst();
                });
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Could not read " + logFile.getName() + ": " + e.getMessage());
            }
        }
    }

    /** Sends the newest {@code count} entries, oldest first. */
    public void show(CommandSender to, int count) {
        List<String> all;
        synchronized (recent) { all = new ArrayList<>(recent); }
        var m = plugin.msg();
        if (all.isEmpty()) { m.send(to, "anti-abuse.suspicious.empty"); return; }
        int n = Math.max(1, Math.min(count, all.size()));
        m.send(to, "anti-abuse.suspicious.header", "shown", n, "total", all.size(), "file", logFile.getName());
        for (String l : all.subList(all.size() - n, all.size())) m.send(to, "anti-abuse.suspicious.line", "entry", l);
    }

    /** Flushes pending log writes; called from onDisable. */
    public synchronized void close() {
        if (writer == null) return;
        writer.shutdown();
        try { writer.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        writer = null;
    }
}
