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
import org.riftlabs.villagerMerchants.manager.Access;
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
        this.inv = Bukkit.createInventory(this, 54, plugin.msg().get("staff-gui.title"));
        render();
    }

    private void render() {
        inv.clear();
        bySlot.clear();
        var m = plugin.msg();
        int i = 0;
        for (Map.Entry<UUID, StaffRole> e : shop.getStaff().entrySet()) {
            if (i >= MAX_HEADS) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(e.getKey());
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(op);
            meta.displayName(m.line("staff-gui.member.name", "name", StaffManager.nameOf(op)));
            meta.lore(m.lines("staff-gui.member.lore", "role", e.getValue().label(),
                    "description", m.ref("staff-gui.role-description." + e.getValue().key())));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            head.setItemMeta(meta);
            inv.setItem(i, head);
            bySlot.put(i, e.getKey());
            i++;
        }
        ItemStack pane = Gui.filler(plugin);
        for (int s = 36; s < 54; s++) inv.setItem(s, pane);
        inv.setItem(BACK, Gui.back(plugin));
        inv.setItem(47, m.icon(plugin.material("staff-roles", Material.PAPER), "staff-gui.roles",
                "stocker", StaffRole.STOCKER.label(), "manager", StaffRole.MANAGER.label(), "co_owner", StaffRole.CO_OWNER.label()));
        int max = plugin.getStaff().maxStaff(player);   // owner-only menu, so the viewer is the owner
        boolean full = shop.getStaff().size() >= max;
        inv.setItem(ADD, full
                ? m.icon(plugin.material("staff-full", Material.GRAY_DYE), "staff-gui.full", "max", Access.cap(max))
                : m.icon(plugin.material("staff-add", Material.EMERALD), "staff-gui.add", "count", shop.getStaff().size(), "max", Access.cap(max)));
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
            if (shop.getStaff().size() >= sm.maxStaff(player)) return;
            later(() -> {
                player.closeInventory();
                plugin.getChatPrompts().begin(player,
                        plugin.msg().get("staff-gui.prompt", "role", StaffRole.STOCKER.label()),
                        name -> addByName(name), () -> reopen());
            });
            return;
        }
        UUID target = bySlot.get(slot);
        if (target == null) return;
        Component err;
        if (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) {
            err = sm.remove(player, shop, target);
        } else {
            StaffRole cur = shop.roleOf(target);
            err = cur == null ? null : sm.setRole(player, shop, target, cur.next());
        }
        if (err != null) player.sendMessage(err);
        render();
    }

    private void addByName(String name) {
        OfflinePlayer target = plugin.getStaff().findPlayer(name.split("\\s+")[0]);
        if (target == null) {
            plugin.msg().send(player, "staff-gui.unknown-player", "name", name);
        } else {
            Component err = plugin.getStaff().add(player, shop, target, StaffRole.STOCKER);
            player.sendMessage(err != null ? err : plugin.msg().get("staff-gui.added",
                    "name", StaffManager.nameOf(target), "role", StaffRole.STOCKER.label()));
        }
        reopen();
    }

    private void reopen() {
        if (plugin.getShopManager().isLive(shop)) new StaffGui(plugin, player, shop).open();
    }

    private void later(Runnable r) { Bukkit.getScheduler().runTask(plugin, r); }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}
