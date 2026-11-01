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
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ShopCommand implements CommandExecutor, TabCompleter {
    private final VillagerMerchants plugin;

    public ShopCommand(VillagerMerchants plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("shops")) {
            openDirectory(sender);
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Text.mm("<gold>/vm claim <gray>- retrieve items from expired shops"));
            sender.sendMessage(Text.mm("<gold>/shops <gray>- browse and teleport to open shops"));
            sender.sendMessage(Text.mm("<gold>/vm sales | purchases [page] <gray>- your transaction log"));
            sender.sendMessage(Text.mm("<gold>/vm rate <1-5> <gray>- rate the shop you last bought from"));
            if (sender.hasPermission("villagermerchants.admin"))
                sender.sendMessage(Text.mm("<gold>/vm create | remove | reload | log <player> [page] <gray>(admin)"));
            return true;
        }
        String sub = args[0].toLowerCase();

        switch (sub) {
            case "shops" -> { openDirectory(sender); return true; }
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
                sender.sendMessage(Text.mm("<green>Config reloaded."));
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
                npc.setPersistent(true);
                npc.setRemoveWhenFarAway(false);
                npc.setCanPickupItems(false);
                npc.getPersistentDataContainer().set(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE, (byte) 1);
                plugin.getShopManager().refreshNameTag(npc);
                p.sendMessage(Text.mm("<green>Shop NPC created!"));
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
                v.remove();
                p.sendMessage(Text.mm("<green>Shop NPC removed" + (s != null ? " (owner's items moved to /vm claim)." : ".")));
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

    private void openDirectory(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        if (!p.hasPermission("villagermerchants.shops")) { p.sendMessage(Text.mm("<red>No permission.")); return; }
        new ShopDirectoryGui(plugin, p).open();
    }

    private int pageArg(String[] args, int index) {
        if (args.length <= index) return 1;
        try { return Math.max(1, Integer.parseInt(args[index])); } catch (NumberFormatException e) { return 1; }
    }

    /** /vm rate <1-5>  (last shop you bought from)   or   /vm rate <shop-id> <1-5>  (used by the chat stars). */
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
        } catch (IllegalArgumentException e) { // bad number or shop id
            p.sendMessage(Text.mm("<red>Usage: /vm rate <1-5>"));
        }
    }

    /** Picks the shop NPC closest to the player's crosshair (raytraces miss non-collidable NPCs). */
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
        if (args.length == 2 && args[0].equalsIgnoreCase("rate")) return new ArrayList<>(List.of("1", "2", "3", "4", "5"));
        if (args.length == 2 && args[0].equalsIgnoreCase("log") && sender.hasPermission("villagermerchants.admin")) {
            for (Player pl : Bukkit.getOnlinePlayers()) if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
            return out;
        }
        if (args.length != 1) return out;
        List<String> opts = new ArrayList<>(List.of("claim", "shops", "sales", "purchases", "rate"));
        if (sender.hasPermission("villagermerchants.admin")) opts.addAll(List.of("create", "remove", "reload", "log"));
        for (String o : opts) if (o.startsWith(args[0].toLowerCase())) out.add(o);
        return out;
    }
}
