package org.riftlabs.villagerMerchants.commands;

import org.bukkit.Location;
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
import org.riftlabs.villagerMerchants.manager.CategoryRegistry;
import org.riftlabs.villagerMerchants.manager.PromotionManager;
import org.riftlabs.villagerMerchants.manager.StaffManager;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.StaffRole;
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
            sender.sendMessage(Text.mm("<gold>/vm claim <gray>- retrieve items from expired shops"));
            sender.sendMessage(Text.mm("<gold>/shops <gray>- browse and teleport to open shops"));
            sender.sendMessage(Text.mm("<gold>/shops search <item> | category <name> | sale <gray>- find shops"));
            sender.sendMessage(Text.mm("<gold>/vm sale start <percent> <time> | stop <gray>- run a sale (e.g. /vm sale start 20 2h)"));
            sender.sendMessage(Text.mm("<gold>/vm staff list | add <player> [role] | remove <player> | role <player> <role>"));
            sender.sendMessage(Text.mm("<gold>/vm sales | purchases [page] <gray>- your transaction log"));
            sender.sendMessage(Text.mm("<gold>/vm rate <1-5> <gray>- rate the shop you last bought from"));
            if (sender.hasPermission("villagermerchants.admin"))
                sender.sendMessage(Text.mm("<gold>/vm create | remove | reload | effect | log <player> [page] <gray>(admin)"));
            return true;
        }
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "shops" -> { shopsCommand(sender, java.util.Arrays.copyOfRange(args, 1, args.length)); return true; }
            case "sale" -> { saleCommand(sender, args); return true; }
            case "staff" -> { staffCommand(sender, args); return true; }
            case "sales", "purchases" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                if (!p.hasPermission("villagermerchants.use")) { p.sendMessage(Text.mm("<red>No permission.")); return true; }
                TxMode mode = sub.equals("sales") ? TxMode.SALES : TxMode.PURCHASES;
                plugin.getTransactions().showHistory(p, mode, p.getUniqueId(), p.getName(), pageArg(args, 1));
                return true;
            }
            case "rate" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                rate(p, args);
                return true;
            }
            default -> {}
        }

        if (sub.equals("claim")) {
            if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
            plugin.getShopManager().claimItems(p);
            return true;
        }

        if (!sender.hasPermission("villagermerchants.admin")) {
            sender.sendMessage(Text.mm("<red>No permission."));
            return true;
        }

        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.getEffectManager().reload();
                plugin.getCategories().load();
                sender.sendMessage(Text.mm("<green>Config, effects and categories reloaded."));
            }
            case "create" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Location loc = p.getLocation();
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

                plugin.getNametagManager().spawnNametag(npc, "<green><bold>Available Shop</bold></green>");
                p.sendMessage(Text.mm("<green>Shop NPC created & saved to database!"));
            }
            case "remove" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Villager v = findLookedShopNpc(p);
                if (v == null) {
                    p.sendMessage(Text.mm("<red>Look at a shop NPC (within 6 blocks)."));
                    return true;
                }
                Shop s = plugin.getShopManager().getShop(v.getUniqueId());
                if (s != null) plugin.getShopManager().releaseShop(s, true);

                for (Entity passenger : v.getPassengers()) passenger.remove();
                plugin.getNametagManager().removeNametag(v.getUniqueId());
                plugin.getEffectManager().clear(v.getUniqueId());

                v.remove();
                p.sendMessage(Text.mm("<green>Shop NPC removed" + (s != null ? " (owner's items moved to /vm claim)." : ".")));
            }
            case "effect" -> {
                var fx = plugin.getEffectManager();
                String act = args.length > 1 ? args[1].toLowerCase() : "list";
                if (act.equals("list")) {
                    sender.sendMessage(Text.mm("<gold>Effects: <white>" + String.join("<gray>, <white>",
                            fx.getRegistry().all().stream().map(d -> d.id()).toList())));
                    sender.sendMessage(Text.mm("<gray>/vm effect test <id> [seconds] | off  <dark_gray>(look at a shop NPC)"));
                    return true;
                }
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Villager v = findLookedShopNpc(p);
                if (v == null) { p.sendMessage(Text.mm("<red>Look at a shop NPC (within 6 blocks).")); return true; }
                if (act.equals("off")) {
                    plugin.getShopManager().refreshNameTag(v);   // back to whatever the owner has equipped
                    p.sendMessage(Text.mm("<green>Test effect stopped."));
                } else if (act.equals("test") && args.length > 2) {
                    int secs = fx.getRegistry().previewSeconds();
                    if (args.length > 3) { try { secs = Math.max(1, Integer.parseInt(args[3])); } catch (NumberFormatException ignored) {} }
                    Shop s = plugin.getShopManager().getShop(v.getUniqueId());
                    String back = s == null ? "" : plugin.getShopManager().getProfile(s.getOwner()).getEquippedEffect();
                    if (fx.preview(v.getUniqueId(), args[2], secs, back)) p.sendMessage(Text.mm("<green>Playing <white>" + args[2] + "<green> for " + secs + "s."));
                    else p.sendMessage(Text.mm("<red>Unknown effect. Use /vm effect list."));
                } else {
                    p.sendMessage(Text.mm("<red>Usage: /vm effect list | test <id> [seconds] | off"));
                }
            }
            case "log" -> {
                if (args.length < 2) { sender.sendMessage(Text.mm("<red>Usage: /vm log <player> [page]")); return true; }
                OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (op == null) { sender.sendMessage(Text.mm("<red>Unknown player (they must have joined before).")); return true; }
                String name = op.getName() == null ? args[1] : op.getName();
                plugin.getTransactions().showHistory(sender, TxMode.BOTH, op.getUniqueId(), name, pageArg(args, 2));
            }
            default -> sender.sendMessage(Text.mm("<red>Unknown subcommand."));
        }
        return true;
    }

    // ------------------------------------------------------------ /shops [search|category|sale]

    private void shopsCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        if (!p.hasPermission("villagermerchants.shops")) { p.sendMessage(Text.mm("<red>No permission.")); return; }
        CategoryRegistry cats = plugin.getCategories();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "search", "find" -> {
                if (args.length < 2) { p.sendMessage(Text.mm("<red>Usage: /shops search <item, enchant or player>")); return; }
                new ShopDirectoryGui(plugin, p).withQuery(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length))).open();
            }
            case "category", "cat" -> {
                CategoryRegistry.Category c = args.length < 2 ? null : cats.find(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                if (c == null) {
                    StringBuilder sb = new StringBuilder();
                    for (CategoryRegistry.Category cc : cats.all()) { if (sb.length() > 0) sb.append("<gray>, "); sb.append("<white>").append(cc.id()); }
                    p.sendMessage(Text.mm("<red>Pick a category: " + sb));
                    return;
                }
                new ShopDirectoryGui(plugin, p).withCategory(c.id()).open();
            }
            case "sale", "sales" -> new ShopDirectoryGui(plugin, p).withSaleOnly(true).open();
            default -> new ShopDirectoryGui(plugin, p).open();
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
        p.sendMessage(Text.mm(usable.isEmpty()
                ? "<red>You don't have a shop you can " + what + "."
                : "<red>You have several shops you can " + what + " - look at the shop villager you mean (within 6 blocks)."));
        return null;
    }

    // ------------------------------------------------------------ /vm sale

    private void saleCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        PromotionManager promo = plugin.getPromotions();
        String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        if (act.equals("stop") || act.equals("end")) {
            Shop s = resolveShop(p, sh -> sh.can(p.getUniqueId(), Perm.SALES) && sh.isOnSale(System.currentTimeMillis()), "stop a sale for");
            if (s != null) promo.stopSale(p, s);
            return;
        }
        if (act.equals("start") && args.length >= 4) {
            int percent;
            try { percent = Integer.parseInt(args[2].replace("%", "")); }
            catch (NumberFormatException e) { p.sendMessage(Text.mm("<red>The discount must be a whole number, e.g. 20.")); return; }
            long dur = PromotionManager.parseDuration(args[3]);
            if (dur < 0) { p.sendMessage(Text.mm("<red>Couldn't read the duration. Try 30m, 2h or 1d.")); return; }
            boolean listed = args.length < 5 || !args[4].equalsIgnoreCase("unlisted");
            Shop s = resolveShop(p, sh -> sh.can(p.getUniqueId(), Perm.SALES), "run a sale for");
            if (s != null) promo.startSale(p, s, percent, dur, listed);
            return;
        }
        p.sendMessage(Text.mm("<red>Usage: /vm sale start <percent> <time> [unlisted] | /vm sale stop"));
        p.sendMessage(Text.mm("<gray>Example: <white>/vm sale start 20 2h</white> - 20% off for 2 hours. Or use the Run a Sale button in your shop menu."));
    }

    // ------------------------------------------------------------ /vm staff

    private void staffCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        StaffManager staff = plugin.getStaff();
        String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        UUID me = p.getUniqueId();
        switch (act) {
            case "list" -> {
                boolean any = false;
                for (Shop s : plugin.getShopManager().allShops()) {
                    if (!s.isOwner(me) && !s.getStaff().containsKey(me)) continue;
                    any = true;
                    p.sendMessage(Text.mm("<gold>--- <yellow>" + Text.esc(plugin.getShopManager().ownerName(s.getOwner())) + "'s shop</yellow> ---"));
                    if (!s.isOwner(me)) p.sendMessage(Text.mm("<gray>Your role: <white>" + s.roleOf(me).label()));
                    if (s.getStaff().isEmpty()) p.sendMessage(Text.mm("<gray>No staff."));
                    for (Map.Entry<UUID, StaffRole> e : s.getStaff().entrySet()) {
                        p.sendMessage(Text.mm("<gray>- <white>" + Text.esc(StaffManager.nameOf(Bukkit.getOfflinePlayer(e.getKey())))
                                + " <gray>(" + e.getValue().label() + ")"));
                    }
                }
                if (!any) p.sendMessage(Text.mm("<gray>You don't own or work at any shop."));
            }
            case "add" -> {
                if (args.length < 3) { p.sendMessage(Text.mm("<red>Usage: /vm staff add <player> [stocker|manager|co-owner]")); return; }
                StaffRole role = args.length > 3 ? StaffRole.parse(args[3]) : StaffRole.STOCKER;
                if (role == null) { p.sendMessage(Text.mm("<red>Roles: stocker, manager, co-owner.")); return; }
                OfflinePlayer target = staff.findPlayer(args[2]);
                if (target == null) { p.sendMessage(Text.mm("<red>Unknown player (they must have joined before).")); return; }
                Shop s = resolveShop(p, sh -> sh.isOwner(me), "manage staff for");
                if (s == null) return;
                String err = staff.add(p, s, target, role);
                p.sendMessage(Text.mm(err != null ? err : "<green>Added <white>" + Text.esc(StaffManager.nameOf(target)) + "</white> as <yellow>" + role.label() + "</yellow>."));
            }
            case "remove", "kick" -> {
                if (args.length < 3) { p.sendMessage(Text.mm("<red>Usage: /vm staff remove <player>")); return; }
                OfflinePlayer target = staff.findPlayer(args[2]);
                if (target == null) { p.sendMessage(Text.mm("<red>Unknown player.")); return; }
                Shop s = resolveShop(p, sh -> sh.isOwner(me) && sh.getStaff().containsKey(target.getUniqueId()), "remove that staff member from");
                if (s == null) return;
                String err = staff.remove(p, s, target.getUniqueId());
                p.sendMessage(Text.mm(err != null ? err : "<green>Removed <white>" + Text.esc(StaffManager.nameOf(target)) + "</white> from your staff."));
            }
            case "role" -> {
                StaffRole role = args.length > 3 ? StaffRole.parse(args[3]) : null;
                if (args.length < 4 || role == null) { p.sendMessage(Text.mm("<red>Usage: /vm staff role <player> <stocker|manager|co-owner>")); return; }
                OfflinePlayer target = staff.findPlayer(args[2]);
                if (target == null) { p.sendMessage(Text.mm("<red>Unknown player.")); return; }
                Shop s = resolveShop(p, sh -> sh.isOwner(me) && sh.getStaff().containsKey(target.getUniqueId()), "change roles in");
                if (s == null) return;
                String err = staff.setRole(p, s, target.getUniqueId(), role);
                p.sendMessage(Text.mm(err != null ? err : "<green><white>" + Text.esc(StaffManager.nameOf(target)) + "</white> is now <yellow>" + role.label() + "</yellow>."));
            }
            default -> p.sendMessage(Text.mm("<red>Usage: /vm staff list | add <player> [role] | remove <player> | role <player> <role>"));
        }
    }

    private void openDirectory(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        if (!p.hasPermission("villagermerchants.shops")) { p.sendMessage(Text.mm("<red>No permission.")); return; }
        new ShopDirectoryGui(plugin, p).open();
    }

    private int pageArg(String[] args, int index) {
        if (args.length <= index) return 1;
        try { return Math.max(1, Integer.parseInt(args[index])); } catch (NumberFormatException e) { return 1; }
    }

    private void rate(Player p, String[] args) {
        try {
            if (args.length == 2) {
                UUID shop = plugin.getRatings().lastPurchased(p.getUniqueId());
                if (shop == null) { p.sendMessage(Text.mm("<red>Buy something from a shop first, then rate it.")); return; }
                plugin.getRatings().rate(p, shop, Integer.parseInt(args[1]));
            } else if (args.length >= 3) {
                plugin.getRatings().rate(p, UUID.fromString(args[1]), Integer.parseInt(args[2]));
            } else {
                p.sendMessage(Text.mm("<red>Usage: /vm rate <1-5>"));
            }
        } catch (IllegalArgumentException e) {
            p.sendMessage(Text.mm("<red>Usage: /vm rate <1-5>"));
        }
    }

    private Villager findLookedShopNpc(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Villager best = null;
        double bestDot = 0.9;
        for (Entity e : p.getNearbyEntities(6, 6, 6)) {
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
            else if (args.length == 4 && args[1].equalsIgnoreCase("start")) out.addAll(List.of("30m", "1h", "2h", "6h", "12h", "1d"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("log") && sender.hasPermission("villagermerchants.admin")) {
            for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
            return out;
        }
        if (sender.hasPermission("villagermerchants.admin") && args.length >= 2 && args[0].equalsIgnoreCase("effect")) {
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
        if (sender.hasPermission("villagermerchants.admin")) opts.addAll(List.of("create", "remove", "reload", "effect", "log"));
        for (String o : opts) if (o.startsWith(args[0].toLowerCase())) out.add(o);
        return out;
    }

    private List<String> completeShops(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String o : List.of("search", "category", "sale")) if (o.startsWith(args[0].toLowerCase())) out.add(o);
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
