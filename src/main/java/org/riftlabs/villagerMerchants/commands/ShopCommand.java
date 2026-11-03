package org.riftlabs.villagerMerchants.commands;

import org.bukkit.Location;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager.TxMode;
import org.riftlabs.villagerMerchants.gui.ShopDirectoryGui;
import org.riftlabs.villagerMerchants.manager.Access;
import org.riftlabs.villagerMerchants.manager.CategoryRegistry;
import org.riftlabs.villagerMerchants.manager.LeaderboardManager;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.manager.StaffManager;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.StaffRole;
import org.riftlabs.villagerMerchants.util.Messages;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

public class ShopCommand implements CommandExecutor, TabCompleter {
    private final VillagerMerchants plugin;

    public ShopCommand(VillagerMerchants plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("shops")) {
            shopsCommand(sender, args);
            return true;
        }
        if (args.length == 0) {
            plugin.msg().sendLines(sender, "cmd.help");
            if (plugin.access().anyAdmin(sender)) plugin.msg().sendLines(sender, "cmd.help-admin");
            return true;
        }
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "shops" -> { shopsCommand(sender, java.util.Arrays.copyOfRange(args, 1, args.length)); return true; }
            case "sale" -> { saleCommand(sender, args); return true; }
            case "staff" -> { staffCommand(sender, args); return true; }
            case "sales", "purchases" -> {
                if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return true; }
                if (!plugin.access().canUse(p) || !plugin.access().canViewHistory(p)) { plugin.msg().send(p, "misc.no-permission"); return true; }
                TxMode mode = sub.equals("sales") ? TxMode.SALES : TxMode.PURCHASES;
                plugin.getTransactions().showHistory(p, mode, p.getUniqueId(), p.getName(), pageArg(args, 1));
                return true;
            }
            case "rate" -> {
                if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return true; }
                rate(p, args);
                return true;
            }
            default -> {}
        }

        if (sub.equals("claim")) {
            if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return true; }
            if (!plugin.access().canClaim(p)) { plugin.msg().send(p, "misc.no-permission"); return true; }
            plugin.getShopManager().claimItems(p);
            return true;
        }

        // villagermerchants.admin = every admin action; villagermerchants.admin.<create|remove|reload|effect|log> = just that one
        if (!plugin.access().admin(sender, sub)) {
            plugin.msg().send(sender, "misc.no-permission");
            return true;
        }

        switch (sub) {
            case "reload" -> {
                plugin.reloadAll();
                plugin.msg().send(sender, "cmd.reloaded");
            }
            case "create" -> {
                if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return true; }
                Location loc = p.getLocation();
                var verdict = plugin.getProtection().checkCreate(p, loc);
                if (!verdict.allowed()) { verdict.send(plugin.msg(), p); return true; }
                Villager npc = (Villager) p.getWorld().spawnEntity(loc, EntityType.VILLAGER);
                npc.setVillagerType(Villager.Type.PLAINS);
                npc.setProfession(Villager.Profession.NONE);
                npc.setAdult();
                npc.setAgeLock(true);
                npc.setAI(false);
                npc.setInvulnerable(true);
                npc.setCollidable(false);
                npc.setSilent(true);
                npc.setPersistent(false); // We now control persistence dynamically
                npc.setRemoveWhenFarAway(false);
                npc.setCanPickupItems(false);
                npc.customName(null);
                npc.setCustomNameVisible(false);

                npc.getPersistentDataContainer().set(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE, (byte) 1);

                String formattedLoc = loc.getWorld().getName() + ";" + loc.getX() + ";" + loc.getY() + ";" + loc.getZ() + ";" + loc.getYaw() + ";" + loc.getPitch();

                // Save this brand-new, unrented dummy shop to the DB so it persists on restart
                Shop blankShop = new Shop(npc.getUniqueId(), UUID.randomUUID(), 0, formattedLoc);
                plugin.getShopManager().saveShop(blankShop);

                plugin.getNametagManager().spawnNametag(npc, plugin.msg().raw("nametag.available"));
                plugin.msg().send(p, "cmd.npc-created");
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return true; }
                Villager v = findLookedShopNpc(p);
                if (v == null) {
                    plugin.msg().send(p, "cmd.look-at-npc", "range", lookRange());
                    return true;
                }
                Shop s = plugin.getShopManager().getShop(v.getUniqueId());
                if (s != null) plugin.getShopManager().releaseShop(s, true);

                for (Entity passenger : v.getPassengers()) passenger.remove();
                plugin.getNametagManager().removeNametag(v.getUniqueId());
                plugin.getEffectManager().clear(v.getUniqueId());

                v.remove();
                plugin.msg().send(p, s != null ? "cmd.npc-removed-owned" : "cmd.npc-removed");
            }
            case "effect" -> {
                var fx = plugin.getEffectManager();
                String act = args.length > 1 ? args[1].toLowerCase() : "list";
                if (act.equals("list")) {
                    plugin.msg().send(sender, "cmd.effect.list", "list", Messages.parsed(String.join(plugin.msg().raw("cmd.effect.separator"),
                            fx.getRegistry().all().stream().map(d -> Text.esc(d.id())).toList())));
                    plugin.msg().send(sender, "cmd.effect.list-usage");
                    return true;
                }
                if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return true; }
                Villager v = findLookedShopNpc(p);
                if (v == null) { plugin.msg().send(p, "cmd.look-at-npc", "range", lookRange()); return true; }
                if (act.equals("off")) {
                    plugin.getShopManager().refreshNameTag(v);   // back to whatever the owner has equipped
                    plugin.msg().send(p, "cmd.effect.stopped");
                } else if (act.equals("test") && args.length > 2) {
                    int secs = fx.getRegistry().previewSeconds();
                    if (args.length > 3) { try { secs = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {} }
                    Shop s = plugin.getShopManager().getShop(v.getUniqueId());
                    String back = s == null ? "" : plugin.getShopManager().getProfile(s.getOwner()).getEquippedEffect();
                    if (fx.preview(v.getUniqueId(), args[2], secs, back)) plugin.msg().send(p, "cmd.effect.playing", "effect", args[2], "seconds", secs);
                    else plugin.msg().send(p, "cmd.effect.unknown");
                } else {
                    plugin.msg().send(p, "cmd.effect.usage");
                }
            }
            case "protection" -> {
                var pm = plugin.getProtection();
                var m = plugin.msg();
                m.send(sender, "protection.status.header");
                for (String[] row : pm.status()) m.send(sender, "protection.status.line", "name", row[0], "value", row[1]);
                if (sender instanceof Player p) {
                    var v = pm.test(p, p.getLocation());   // what the rules say about the spot you are standing on
                    if (v.allowed()) { m.send(p, "protection.status.here-ok"); }
                    else { m.send(p, "protection.status.here-denied"); v.send(m, p); }
                }
            }
            case "suspicious" -> {
                int count = 10;
                if (args.length > 1) {
                    try { count = Math.max(1, Math.min(100, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
                }
                plugin.getAntiAbuse().show(sender, count);
            }
            case "log" -> {
                if (args.length < 2) { plugin.msg().send(sender, "cmd.log.usage"); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (op == null) { plugin.msg().send(sender, "cmd.unknown-player-joined"); return true; }
                String name = op.getName() == null ? args[1] : op.getName();
                plugin.getTransactions().showHistory(sender, TxMode.BOTH, op.getUniqueId(), name, pageArg(args, 2));
            }
            default -> plugin.msg().send(sender, "cmd.unknown-subcommand");
        }
        return true;
    }

    // ------------------------------------------------------------ /shops [search|category|sale]

    private void shopsCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return; }
        if (!plugin.access().canBrowse(p)) { plugin.msg().send(p, "misc.no-permission"); return; }
        CategoryRegistry cats = plugin.getCategories();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "search", "find" -> {
                if (args.length < 2) { plugin.msg().send(p, "cmd.shops.search-usage"); return; }
                new ShopDirectoryGui(plugin, p).withQuery(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))).open();
            }
            case "category", "cat" -> {
                CategoryRegistry.Category c = args.length < 2 ? null : cats.find(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                if (c == null) {
                    String list = String.join(plugin.msg().raw("cmd.shops.category-separator"),
                            cats.all().stream().map(cc -> Text.esc(cc.id())).toList());
                    plugin.msg().send(p, "cmd.shops.pick-category", "list", Messages.parsed(list));
                    return;
                }
                new ShopDirectoryGui(plugin, p).withCategory(c.id()).open();
            }
            case "sale", "sales" -> new ShopDirectoryGui(plugin, p).withSaleOnly(true).open();
            case "top", "leaderboard", "lb", "ranking" -> leaderboardCommand(p, java.util.Arrays.copyOfRange(args, 1, args.length));
            default -> new ShopDirectoryGui(plugin, p).open();
        }
    }

    // ------------------------------------------------------------ /shops top [earners|rated|visited] [daily|weekly]

    private void leaderboardCommand(Player p, String[] args) {
        var m = plugin.msg();
        LeaderboardManager lb = plugin.getLeaderboards();
        if (!lb.enabled() || lb.enabledPeriods().isEmpty()) { m.send(p, "leaderboards.disabled"); return; }
        LeaderboardManager.Category cat = null;
        LeaderboardManager.Period period = null;
        for (String a : args) {
            LeaderboardManager.Category c = LeaderboardManager.Category.parse(a);
            LeaderboardManager.Period pe = LeaderboardManager.Period.parse(a);
            if (c != null) cat = c;
            else if (pe != null) period = pe;
            else { m.send(p, "leaderboards.usage"); return; }
        }
        if (period == null) period = lb.enabledPeriods().get(0);
        if (!lb.periodEnabled(period)) { m.send(p, "leaderboards.period-disabled"); return; }
        if (cat != null) { showBoard(p, lb, period, cat, lb.size()); return; }
        for (LeaderboardManager.Category c : LeaderboardManager.Category.values()) showBoard(p, lb, period, c, lb.chatLines());
        m.send(p, "leaderboards.hint");
    }

    private void showBoard(Player p, LeaderboardManager lb, LeaderboardManager.Period period,
                           LeaderboardManager.Category cat, int limit) {
        var m = plugin.msg();
        m.send(p, "leaderboards.header", "title", m.raw("leaderboards.title." + cat.id),
                "period", m.raw("leaderboards.period." + period.id), "reset", Text.duration(lb.millisUntilReset(period)));
        List<LeaderboardManager.Entry> list = lb.top(period, cat);
        if (list.isEmpty()) { m.send(p, "leaderboards.empty"); return; }
        for (int i = 0; i < list.size() && i < limit; i++) {
            LeaderboardManager.Entry e = list.get(i);
            m.send(p, "leaderboards.line", "rank", i + 1, "name", e.name(), "value", lb.format(cat, e));
        }
    }

    // ------------------------------------------------------------ shop resolution for /vm sale and /vm staff

    /** The shop NPC you're looking at if you qualify, otherwise the only shop that qualifies. */
    private Shop resolveShop(Player p, Predicate<Shop> allowed, String what) {
        var sm = plugin.getShopManager();
        Villager v = findLookedShopNpc(p);
        if (v != null) {
            Shop s = sm.getShop(v.getUniqueId());
            if (s != null && allowed.test(s)) return s;
        }
        List<Shop> usable = new ArrayList<>();
        for (Shop s : sm.allShops()) if (allowed.test(s)) usable.add(s);
        if (usable.size() == 1) return usable.get(0);
        plugin.msg().send(p, usable.isEmpty() ? "cmd.resolve.none" : "cmd.resolve.several",
                "action", plugin.msg().raw(what), "range", lookRange());
        return null;
    }

    // ------------------------------------------------------------ /vm sale

    private void saleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return; }
        PromotionManager promo = plugin.getPromotions();
        String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (act.equals("stop") || act.equals("end")) {
            Shop s = resolveShop(p, sh -> sh.can(p.getUniqueId(), Perm.SALES) && sh.isOnSale(System.currentTimeMillis()), "cmd.what.stop-sale");
            if (s != null) promo.stopSale(p, s);
            return;
        }
        if (act.equals("start") && args.length >= 4) {
            int percent;
            try { percent = Integer.parseInt(args[2].replace("%", "")); }
            catch (NumberFormatException e) { plugin.msg().send(p, "cmd.sale.bad-percent"); return; }
            long dur = PromotionManager.parseDuration(args[3]);
            if (dur < 0) { plugin.msg().send(p, "cmd.sale.bad-duration"); return; }
            boolean listed = args.length < 5 || !args[4].equalsIgnoreCase("unlisted");
            Shop s = resolveShop(p, sh -> sh.can(p.getUniqueId(), Perm.SALES), "cmd.what.run-sale");
            if (s != null) promo.startSale(p, s, percent, dur, listed);
            return;
        }
        plugin.msg().sendLines(p, "cmd.sale.usage");
    }

    // ------------------------------------------------------------ /vm staff

    private void staffCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { plugin.msg().send(sender, "misc.players-only"); return; }
        StaffManager staff = plugin.getStaff();
        String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        UUID me = p.getUniqueId();
        switch (act) {
            case "list" -> {
                boolean any = false;
                for (Shop s : plugin.getShopManager().allShops()) {
                    if (!s.isOwner(me) && !s.getStaff().containsKey(me)) continue;
                    any = true;
                    var m = plugin.msg();
                    m.send(p, "cmd.staff.list-header", "owner", plugin.getShopManager().ownerName(s.getOwner()));
                    if (!s.isOwner(me)) m.send(p, "cmd.staff.list-your-role", "role", s.roleOf(me).label());
                    if (s.getStaff().isEmpty()) m.send(p, "cmd.staff.list-empty");
                    for (Map.Entry<UUID, StaffRole> e : s.getStaff().entrySet()) {
                        m.send(p, "cmd.staff.list-member", "name", StaffManager.nameOf(Bukkit.getOfflinePlayer(e.getKey())),
                                "role", e.getValue().label());
                    }
                }
                if (!any) plugin.msg().send(p, "cmd.staff.none");
            }
            case "add" -> {
                if (args.length < 3) { plugin.msg().send(p, "cmd.staff.add-usage"); return; }
                StaffRole role = args.length > 3 ? StaffRole.parse(args[3]) : StaffRole.STOCKER;
                if (role == null) { plugin.msg().send(p, "cmd.staff.roles-hint"); return; }
                OfflinePlayer target = staff.findPlayer(args[2]);
                if (target == null) { plugin.msg().send(p, "cmd.unknown-player-joined"); return; }
                Shop s = resolveShop(p, sh -> sh.isOwner(me), "cmd.what.manage-staff");
                if (s == null) return;
                Component err = staff.add(p, s, target, role);
                p.sendMessage(err != null ? err : plugin.msg().get("cmd.staff.added", "name", StaffManager.nameOf(target), "role", role.label()));
            }
            case "remove", "kick" -> {
                if (args.length < 3) { plugin.msg().send(p, "cmd.staff.remove-usage"); return; }
                OfflinePlayer target = staff.findPlayer(args[2]);
                if (target == null) { plugin.msg().send(p, "cmd.unknown-player"); return; }
                Shop s = resolveShop(p, sh -> sh.isOwner(me) && sh.getStaff().containsKey(target.getUniqueId()), "cmd.what.remove-staff");
                if (s == null) return;
                Component err = staff.remove(p, s, target.getUniqueId());
                p.sendMessage(err != null ? err : plugin.msg().get("cmd.staff.removed", "name", StaffManager.nameOf(target)));
            }
            case "role" -> {
                StaffRole role = args.length > 3 ? StaffRole.parse(args[3]) : null;
                if (args.length < 4 || role == null) { plugin.msg().send(p, "cmd.staff.role-usage"); return; }
                OfflinePlayer target = staff.findPlayer(args[2]);
                if (target == null) { plugin.msg().send(p, "cmd.unknown-player"); return; }
                Shop s = resolveShop(p, sh -> sh.isOwner(me) && sh.getStaff().containsKey(target.getUniqueId()), "cmd.what.change-roles");
                if (s == null) return;
                Component err = staff.setRole(p, s, target.getUniqueId(), role);
                p.sendMessage(err != null ? err : plugin.msg().get("cmd.staff.role-set", "name", StaffManager.nameOf(target), "role", role.label()));
            }
            default -> plugin.msg().send(p, "cmd.staff.usage");
        }
    }

    private int pageArg(String[] args, int index) {
        if (args.length <= index) return 1;
        try { return Math.max(1, Integer.parseInt(args[index])); } catch (NumberFormatException e) { return 1; }
    }

    private void rate(Player p, String[] args) {
        try {
            if (args.length == 2) {
                UUID shop = plugin.getRatings().lastPurchased(p.getUniqueId());
                if (shop == null) { plugin.msg().send(p, "cmd.rate.buy-first"); return; }
                plugin.getRatings().rate(p, shop, Integer.parseInt(args[1]));
            } else if (args.length >= 3) {
                plugin.getRatings().rate(p, UUID.fromString(args[1]), Integer.parseInt(args[2]));
            } else {
                plugin.msg().send(p, "cmd.rate.usage");
            }
        } catch (IllegalArgumentException e) {
            plugin.msg().send(p, "cmd.rate.usage");
        }
    }

    /** How far (blocks) /vm remove, /vm sale, /vm staff look for a shop NPC. */
    private double lookRange() { return Math.max(1, plugin.getConfig().getDouble("commands.look-range", 6.0)); }

    private Villager findLookedShopNpc(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Villager best = null;
        double bestDot = plugin.getConfig().getDouble("commands.look-accuracy", 0.9);
        double r = lookRange();
        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof Villager v)
                    || !v.getPersistentDataContainer().has(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE)) continue;
            Vector to = v.getLocation().add(0, 0.9, 0).toVector().subtract(eye.toVector());
            if (to.lengthSquared() < 0.01) return v;
            double dot = to.normalize().dot(dir);
            if (dot > bestDot) { bestDot = dot; best = v; }
        }
        return best;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("shops")) return completeShops(args);
        if (args.length >= 2 && args[0].equalsIgnoreCase("sale")) {
            if (args.length == 2) { for (String o : List.of("start", "stop")) if (o.startsWith(args[1].toLowerCase())) out.add(o); }
            else if (args.length == 3 && args[1].equalsIgnoreCase("start")) out.addAll(plugin.getPromotions().percentOptions().stream().map(String::valueOf).toList());
            else if (args.length == 4 && args[1].equalsIgnoreCase("start")) out.addAll(plugin.getConfig().isList("sales.tab-complete-durations") ? plugin.getConfig().getStringList("sales.tab-complete-durations") : List.of("30m", "1h", "2h", "6h", "12h", "1d"));
            else if (args.length == 5 && args[1].equalsIgnoreCase("start")) out.add("unlisted");
            return out;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("staff")) {
            if (args.length == 2) { for (String o : List.of("list", "add", "remove", "role")) if (o.startsWith(args[1].toLowerCase())) out.add(o); }
            else if (args.length == 3 && !args[1].equalsIgnoreCase("list")) {
                for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase().startsWith(args[2].toLowerCase())) out.add(pl.getName());
            } else if (args.length == 4 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("role"))) {
                for (String o : List.of("stocker", "manager", "co-owner")) if (o.startsWith(args[3].toLowerCase())) out.add(o);
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("rate")) return new ArrayList<>(List.of("1", "2", "3", "4", "5"));
        if (args.length == 2 && args[0].equalsIgnoreCase("log") && plugin.access().admin(sender, "log")) {
            for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
            return out;
        }
        if (plugin.access().admin(sender, "effect") && args.length >= 2 && args[0].equalsIgnoreCase("effect")) {
            if (args.length == 2) {
                for (String o : List.of("list", "test", "off")) if (o.startsWith(args[1].toLowerCase())) out.add(o);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("test")) {
                for (var d : plugin.getEffectManager().getRegistry().all())
                    if (d.id().startsWith(args[2].toLowerCase())) out.add(d.id());
            }
            return out;
        }
        if (args.length != 1) return out;
        List<String> opts = new ArrayList<>(List.of("claim", "shops", "sales", "purchases", "rate", "sale", "staff"));
        for (String a : Access.ADMIN_ACTIONS) if (plugin.access().admin(sender, a)) opts.add(a);
        for (String o : opts) if (o.startsWith(args[0].toLowerCase())) out.add(o);
        return out;
    }

    private List<String> completeShops(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String o : List.of("search", "category", "sale", "top")) if (o.startsWith(args[0].toLowerCase())) out.add(o);
        } else if (args.length >= 2 && args.length <= 3 && args[0].equalsIgnoreCase("top")) {
            for (String o : List.of("earners", "rated", "visited", "daily", "weekly")) if (o.startsWith(args[args.length - 1].toLowerCase())) out.add(o);
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("category")) {
            for (CategoryRegistry.Category c : plugin.getCategories().all()) if (c.id().startsWith(args[1].toLowerCase())) out.add(c.id());
        } else if (args.length >= 2 && args[0].equalsIgnoreCase("search")) {
            // suggest the word being typed, taken from item names that start with everything typed so far
            String typed = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).toLowerCase(Locale.ROOT);
            int wordIdx = args.length - 2;
            for (String name : plugin.getCategories().sellableNames(plugin.getShopManager().allShops(), System.currentTimeMillis())) {
                if (!name.toLowerCase(Locale.ROOT).startsWith(typed)) continue;
                String[] words = name.split(" ");
                if (wordIdx < words.length && !out.contains(words[wordIdx])) out.add(words[wordIdx]);
                if (out.size() >= 30) break;
            }
        }
        return out;
    }
}
