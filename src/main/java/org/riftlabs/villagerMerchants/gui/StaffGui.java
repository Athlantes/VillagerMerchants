package org.riftlabs.villagerMerchants.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.StaffManager;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.StaffRole;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owner-only: list staff, click a head to change their role, shift-click to remove, add via chat. */
public class StaffGui implements GuiHolder, ShopBound {
    private static final int ADD = 49, BACK = 45, MAX_HEADS = 36;

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;
    private final Map<Integer, UUID> bySlot = new HashMap<>();

    public StaffGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;
        this.inv = Bukkit.createInventory(this, 54, Text.mm("<dark_gray>Staff & Co-owners"));
        render();
    }

    private void render() {
        inv.clear();
        bySlot.clear();
        int i = 0;
        for (Map.Entry<UUID, StaffRole> e : shop.getStaff().entrySet()) {
            if (i >= MAX_HEADS) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(op);
            meta.displayName(Text.item("<white>" + Text.esc(StaffManager.nameOf(op))));
            List<Component> lore = new ArrayList<>();
            lore.add(Text.item("<gray>Role: <gold>" + e.getValue().label()));
            lore.add(Text.item(describe(e.getValue())));
            lore.add(Text.item(""));
            lore.add(Text.item("<yellow>Click: change role"));
            lore.add(Text.item("<red>Shift-click: remove"));
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            head.setItemMeta(meta);
            inv.setItem(i, head);
            bySlot.put(i, e.getKey());
            i++;
        }
        ItemStack pane = Text.icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int s = 36; s < 54; s++) inv.setItem(s, pane);
        inv.setItem(BACK, Text.icon(Material.OAK_DOOR, "<red>Back"));
        inv.setItem(47, Text.icon(Material.PAPER, "<white>Roles",
                "<gold>Stocker<gray>: storage only (can also take payments)",
                "<gold>Manager<gray>: + trades, prices, sales, stats",
                "<gold>Co-owner<gray>: + pay rent, featured listings",
                "<dark_gray>Owner-only: cancel, upgrades, NPC look, staff"));
        boolean full = shop.getStaff().size() >= plugin.getStaff().maxStaff();
        inv.setItem(ADD, full
                ? Text.icon(Material.GRAY_DYE, "<gray>Staff list is full", "<gray>Maximum: <white>" + plugin.getStaff().maxStaff())
                : Text.icon(Material.EMERALD, "<green>Add staff", "<gray>Members: <white>" + shop.getStaff().size() + "/" + plugin.getStaff().maxStaff(),
                        "<yellow>Click, then type a player name in chat"));
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        if (!shop.isOwner(player.getUniqueId())) { player.closeInventory(); return; }
        int slot = e.getSlot();
        StaffManager sm = plugin.getStaff();

        if (slot == BACK) { later(() -> new OwnerMenuGui(plugin, player, shop).open()); return; }
        if (slot == ADD) {
            if (shop.getStaff().size() >= sm.maxStaff()) return;
            later(() -> {
                player.closeInventory();
                plugin.getChatPrompts().begin(player,
                        "<yellow>Type the <gold>name</gold> of the player to add as staff <gray>(they must have joined before; new staff start as Stocker).",
                        name -> addByName(name), () -> reopen());
            });
            return;
        }
        UUID target = bySlot.get(slot);
        if (target == null) return;
        String err;
        if (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) {
            err = sm.remove(player, shop, target);
        } else {
            StaffRole cur = shop.roleOf(target);
            err = cur == null ? null : sm.setRole(player, shop, target, cur.next());
        }
        if (err != null) player.sendMessage(Text.mm(err));
        render();
    }

    private void addByName(String name) {
        OfflinePlayer target = plugin.getStaff().findPlayer(name.split("\\s+")[0]);
        if (target == null) {
            player.sendMessage(Text.mm("<red>I don't know a player called <white>" + Text.esc(name) + "</white>."));
        } else {
            String err = plugin.getStaff().add(player, shop, target, StaffRole.STOCKER);
            player.sendMessage(Text.mm(err != null ? err : "<green>Added <white>" + Text.esc(StaffManager.nameOf(target))
                    + "</white> as Stocker. Click their head to promote them."));
        }
        reopen();
    }

    private void reopen() {
        if (plugin.getShopManager().isLive(shop)) new StaffGui(plugin, player, shop).open();
    }

    private static String describe(StaffRole r) {
        return switch (r) {
            case STOCKER -> "<dark_gray>Storage";
            case MANAGER -> "<dark_gray>Storage, trades, sales, stats";
            case CO_OWNER -> "<dark_gray>Everything except owner-only actions";
        };
    }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
