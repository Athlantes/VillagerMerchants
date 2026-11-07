package org.riftlabs.villagerMerchants.gui;

import org.riftlabs.villagerMerchants.util.Sched;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.riftlabs.villagerMerchants.effect.EffectDefinition;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.manager.ShopManager;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.util.Messages;
import org.riftlabs.villagerMerchants.manager.NpcManager;
import org.riftlabs.villagerMerchants.util.LinkedSkins;
import org.riftlabs.villagerMerchants.util.Skins;
import org.riftlabs.villagerMerchants.util.Text;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomizationGui implements GuiHolder, ShopBound {
    private enum Tab { SKINS, NAMETAGS, EFFECTS }
    private record NametagPreset(String id, String format, String name, double price, String currency, String permission, List<String> lockedLore) {}

    // Fixed layout slots for Skins tab
    private static final int[] TYPE_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] PROF_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    // Paginated grid slots for Nametags tab
    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int PAGE_SIZE = CONTENT_SLOTS.length;

    private final VillagerMerchants plugin;
    private final Player player;
    private final Shop shop;
    private final Inventory inv;

    private final List<NametagPreset> allNametags = new ArrayList<>();
    private final List<EffectDefinition> allEffects = new ArrayList<>();
    private final Map<Integer, Skins.Skin> skinBySlot = new HashMap<>();

    private final boolean linked;                      // shop body is a MythicMob / ModelEngine entity
    private final List<LinkedSkins.Variant> variants = new ArrayList<>();
    private final boolean skinsEnabled;
    private final boolean tagsEnabled;
    private final boolean effectsEnabled;

    private Tab currentTab = Tab.SKINS;
    private int page = 0;
    private String confirming;

    public CustomizationGui(VillagerMerchants plugin, Player player, Shop shop) {
        this.plugin = plugin;
        this.player = player;
        this.shop = shop;

        var access = plugin.access();
        var slot = plugin.getNpcs().get(shop.getNpcId());
        this.linked = slot != null && slot.linked();
        if (linked) variants.addAll(plugin.getNpcs().linkedSkins().forShop(shop.getNpcId()));
        // Villager biome/profession skins only exist for villager shops; linked shops get the admin-defined variants instead.
        this.skinsEnabled = plugin.getConfig().getBoolean("customization.skins-enabled", true)
                && access.canCustomize(player, "skins")
                && (!linked || !variants.isEmpty());
        this.tagsEnabled = plugin.getConfig().getBoolean("customization.nametags-enabled", true)
                && access.canCustomize(player, "nametags");

        this.effectsEnabled = plugin.getConfig().getBoolean("customization.effects-enabled", true)
                && access.canCustomize(player, "effects");

        if (!skinsEnabled) this.currentTab = tagsEnabled ? Tab.NAMETAGS : (effectsEnabled ? Tab.EFFECTS : Tab.SKINS);

        loadSkinsMapping();
        loadNametagsFromFile();
        if (effectsEnabled) allEffects.addAll(plugin.getEffectManager().getRegistry().all());

        this.inv = Bukkit.createInventory(this, 54, plugin.msg().get("custom-gui.title"));
        render();
    }

    private void loadSkinsMapping() {
        skinBySlot.clear();
        for (int i = 0; i < Skins.TYPES.size() && i < TYPE_SLOTS.length; i++) {
            skinBySlot.put(TYPE_SLOTS[i], Skins.TYPES.get(i));
        }
        for (int i = 0; i < Skins.PROFESSIONS.size() && i < PROF_SLOTS.length; i++) {
            skinBySlot.put(PROF_SLOTS[i], Skins.PROFESSIONS.get(i));
        }
    }

    private void loadNametagsFromFile() {
        File file = new File(plugin.getDataFolder(), "nametags.yml");
        if (!file.exists()) {
            plugin.saveResource("nametags.yml", false);
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        for (String key : cfg.getKeys(false)) {
            String name = cfg.getString(key + ".name", plugin.msg().raw("misc.unknown"));
            String format = cfg.getString(key + ".format", plugin.msg().raw("nametag.fallback-format"));
            double price = cfg.getDouble(key + ".price", 0.0);
            String currency = cfg.getString(key + ".currency", "vault");
            String permission = cfg.getString(key + ".permission", "");
            allNametags.add(new NametagPreset(key, format, name, price, currency, permission,
                    org.riftlabs.villagerMerchants.util.Lore.read(cfg, key + ".locked-lore")));
        }
    }

    private void render() {
        ShopManager sm = plugin.getShopManager();
        PlayerProfile p = sm.getProfile(player.getUniqueId());
        var m = plugin.msg();
        inv.clear();

        // Render Tabs
        if (skinsEnabled) {
            inv.setItem(2, m.icon(plugin.material("tab-skins", Material.LEATHER_CHESTPLATE),
                    currentTab == Tab.SKINS ? "custom-gui.tab-skins-active" : "custom-gui.tab-skins"));
        }
        if (tagsEnabled) {
            inv.setItem(4, m.icon(plugin.material("tab-nametags", Material.NAME_TAG),
                    currentTab == Tab.NAMETAGS ? "custom-gui.tab-nametags-active" : "custom-gui.tab-nametags"));
        }
        if (effectsEnabled) {
            inv.setItem(6, m.icon(plugin.material("tab-effects", Material.BLAZE_POWDER),
                    currentTab == Tab.EFFECTS ? "custom-gui.tab-effects-active" : "custom-gui.tab-effects"));
        }

        if (currentTab == Tab.SKINS && skinsEnabled && linked) {
            renderVariants(sm, p);
        } else if (currentTab == Tab.SKINS && skinsEnabled) {
            // Biome and Outfit description items have been removed here.
            for (Map.Entry<Integer, Skins.Skin> entry : skinBySlot.entrySet()) {
                inv.setItem(entry.getKey(), renderSkinItem(sm, p, entry.getValue()));
            }
        } else if (currentTab == Tab.NAMETAGS && tagsEnabled) {
            int maxPages = Math.max(1, (int) Math.ceil((double) allNametags.size() / PAGE_SIZE));
            if (page >= maxPages) page = maxPages - 1;

            int start = page * PAGE_SIZE;
            int end = Math.min(start + PAGE_SIZE, allNametags.size());

            for (int i = start; i < end; i++) {
                NametagPreset data = allNametags.get(i);
                int slot = CONTENT_SLOTS[i - start];
                ItemStack it = new ItemStack(plugin.material("customization-nametag", Material.NAME_TAG));
                org.bukkit.inventory.meta.ItemMeta meta = it.getItemMeta();

                // Safe formatting for inventory items (turn animations into a static rainbow or color)
                String playerName = player.getName();
                String parsedFormat = data.format()
                        .replace("<anim_rainbow>", "<rainbow>") // MiniMessage static rainbow
                        .replace("</anim_rainbow>", "</rainbow>")
                        .replace("<bold>", "<bold>" + m.str("nametag.preview-owner", "owner", Text.esc(playerName)))
                        .replace(m.raw("nametag.replace-from"), m.raw("nametag.replace-to"));

                meta.displayName(Text.mm(parsedFormat));

                List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
                lore.add(m.line("custom-gui.price", "price", data.price() <= 0 ? m.ref("misc.free") : Messages.parsed(Text.esc(sm.format(price(data.currency(), data.price()))))));
                lore.add(net.kyori.adventure.text.Component.empty());

                if (p.getEquippedNametagFormat().equals(data.format())) {
                    lore.add(m.line("custom-gui.status.equipped"));
                    it = Text.glow(it);
                } else if (!plugin.access().canUseNametag(player, data.id(), data.permission())) {
                    if (data.lockedLore().isEmpty()) lore.add(m.line("custom-gui.status.locked"));
                    else for (String l : data.lockedLore()) lore.add(Text.item(l));
                } else if (p.ownsNametag(data.id())) {
                    lore.add(m.line("custom-gui.status.click-equip"));
                } else if (data.id().equals(confirming)) {
                    lore.add(m.line("custom-gui.status.click-confirm"));
                } else {
                    lore.add(m.line("custom-gui.status.click-buy"));
                }

                meta.lore(lore);
                it.setItemMeta(meta);
                inv.setItem(slot, it);
            }

            renderPagination(maxPages);
        }

        if (currentTab == Tab.EFFECTS && effectsEnabled) renderEffects(sm, p);

        inv.setItem(49, Gui.back(plugin));
        fillEmptySlots();
    }

    // ------------------------------------------------------------ linked-shop variants (linked-skins.yml)

    /** Locked items: the admin's own "how to unlock this" lines replace the default lore. Unchanged when none are set. */
    private ItemStack withLockedLore(ItemStack it, List<String> lines) {
        if (lines == null || lines.isEmpty()) return it;
        ItemMeta meta = it.getItemMeta();
        List<Component> lore = new ArrayList<>();
        for (String l : lines) lore.add(Text.item(l));
        meta.lore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private boolean canUseVariant(LinkedSkins.Variant v) {
        return v.permission() == null || v.permission().isBlank() || player.hasPermission(v.permission());
    }

    private NpcManager.Slot slotOf() { return plugin.getNpcs().get(shop.getNpcId()); }

    /** The variant the body wears right now: the equipped one, else the one matching what it was created as. */
    private LinkedSkins.Variant current() {
        var slot = slotOf();
        return slot == null ? null : plugin.getNpcs().currentVariant(slot);
    }

    private void renderVariants(ShopManager sm, PlayerProfile p) {
        var m = plugin.msg();
        var slot = slotOf();
        LinkedSkins.Variant base = slot == null ? null : plugin.getNpcs().baseVariant(slot);
        LinkedSkins.Variant cur = current();
        // The body's own look is a normal entry when linked-skins.yml lists it; otherwise it gets a "Default look" button.
        if (base == null) {
            inv.setItem(45, cur == null
                    ? Text.glow(m.icon(Material.ARMOR_STAND, "custom-gui.lskin-default-equipped"))
                    : m.icon(Material.ARMOR_STAND, "custom-gui.lskin-default"));
        }
        for (int i = 0; i < variants.size() && i < CONTENT_SLOTS.length; i++) {
            LinkedSkins.Variant v = variants.get(i);
            Messages.Parsed name = Messages.parsed(v.name());
            String price = sm.format(price(v.currency(), v.price()));
            boolean own = v == base || (base != null && v.id().equals(base.id()));
            ItemStack it;
            if (cur != null && v.id().equals(cur.id())) {
                it = Text.glow(m.icon(v.icon(), "custom-gui.skin-equipped", "skin", name));
            } else if (!canUseVariant(v)) {
                it = withLockedLore(m.icon(Material.BARRIER, "custom-gui.skin-no-access", "skin", name), v.lockedLore());
            } else if (own || v.price() <= 0 || p.ownsSkin(v.key())) {
                it = m.icon(v.icon(), "custom-gui.skin-owned", "skin", name);
            } else if (v.id().equals(confirming)) {
                it = Text.glow(m.icon(v.icon(), "custom-gui.skin-confirm", "skin", name, "price", price));
            } else {
                it = m.icon(v.icon(), "custom-gui.skin-locked", "skin", name, "price", price);
            }
            inv.setItem(CONTENT_SLOTS[i], it);
        }
    }

    /** null = the body's original look (stored as "", so a look that is not in the list can still be restored). */
    private void equipVariant(LinkedSkins.Variant v) {
        var slot = slotOf();
        LinkedSkins.Variant base = slot == null ? null : plugin.getNpcs().baseVariant(slot);
        boolean original = v == null || (base != null && v.id().equals(base.id()));
        shop.setLinkedSkin(original ? "" : v.id());
        plugin.getShopManager().saveShop(shop);
        if (slot != null) plugin.getNpcs().applySkin(slot, original ? null : v);
    }

    private void handleVariantClick(int slot) {
        var npc = slotOf();
        LinkedSkins.Variant base = npc == null ? null : plugin.getNpcs().baseVariant(npc);
        LinkedSkins.Variant cur = current();
        if (slot == 45 && base == null) { if (cur != null) equipVariant(null); confirming = null; render(); return; }
        int idx = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) if (CONTENT_SLOTS[i] == slot) { idx = i; break; }
        if (idx < 0 || idx >= variants.size()) return;
        LinkedSkins.Variant v = variants.get(idx);
        ShopManager sm = plugin.getShopManager();
        PlayerProfile p = sm.getProfile(player.getUniqueId());

        if (cur != null && v.id().equals(cur.id())) return;
        if (!canUseVariant(v)) { plugin.msg().send(player, "custom-gui.skin-no-access-msg", "skin", Messages.parsed(v.name())); return; }
        boolean own = base != null && v.id().equals(base.id());
        if (own || v.price() <= 0 || p.ownsSkin(v.key())) {
            equipVariant(v);
            confirming = null;
        } else if (v.id().equals(confirming)) {
            if (sm.charge(player, price(v.currency(), v.price()))) {
                p.unlockSkin(v.key());
                sm.saveProfile(p);
                equipVariant(v);
                plugin.msg().send(player, "custom-gui.purchased", "name", Messages.parsed(v.name()));
            }
            confirming = null;
        } else {
            confirming = v.id();
        }
        render();
    }

    private void renderPagination(int maxPages) {
        if (page > 0) {
            inv.setItem(48, Gui.previousPage(plugin, page, maxPages));
        }
        if (page < maxPages - 1) {
            inv.setItem(50, Gui.nextPage(plugin, page + 2, maxPages));
        }
    }

    private void renderEffects(ShopManager sm, PlayerProfile p) {
        int maxPages = Math.max(1, (int) Math.ceil((double) allEffects.size() / PAGE_SIZE));
        if (page >= maxPages) page = maxPages - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allEffects.size());

        for (int i = start; i < end; i++) {
            EffectDefinition def = allEffects.get(i);
            var m = plugin.msg();
            ItemStack it = new ItemStack(def.icon());
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Text.item(def.name()));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);

            List<Component> lore = new ArrayList<>();
            for (String line : def.description()) lore.add(m.line("custom-gui.effect-description-line", "line", Messages.parsed(line)));
            if (!def.description().isEmpty()) lore.add(Component.empty());
            lore.add(m.line("custom-gui.price", "price", def.price() <= 0 ? m.ref("misc.free") : Messages.parsed(Text.esc(sm.format(price(def.currency(), def.price()))))));
            lore.add(Component.empty());

            boolean owned = def.price() <= 0 || p.ownsEffect(def.id());
            if (!plugin.access().canUseEffect(player, def)) {
                if (def.lockedLore().isEmpty()) lore.add(m.line("custom-gui.status.locked"));
                else for (String l : def.lockedLore()) lore.add(Text.item(l));
            } else if (def.id().equals(p.getEquippedEffect())) {
                lore.add(m.line("custom-gui.status.equipped"));
                meta.setEnchantmentGlintOverride(true);
            } else if (owned) {
                lore.add(m.line("custom-gui.status.click-equip"));
            } else if (def.id().equals(confirming)) {
                lore.add(m.line("custom-gui.status.click-confirm"));
            } else {
                lore.add(m.line("custom-gui.status.click-buy"));
            }
            lore.add(m.line("custom-gui.effect-preview-hint"));

            meta.lore(lore);
            it.setItemMeta(meta);
            inv.setItem(CONTENT_SLOTS[i - start], it);
        }

        inv.setItem(45, plugin.msg().icon(plugin.material("effect-remove", Material.BARRIER), "custom-gui.effect-remove"));
        renderPagination(maxPages);
    }

    private void equipEffect(PlayerProfile p, String id) {
        p.setEquippedEffect(id);
        plugin.getEffectManager().set(plugin.getShopManager().entityIdOf(shop), id);
        plugin.getDatabaseManager().async(() ->
                plugin.getDatabaseManager().updateEffectData(player.getUniqueId().toString(),
                        p.getUnlockedEffectsString(), p.getEquippedEffect()));
    }

    private void handleEffectClick(InventoryClickEvent e, int slot) {
        PlayerProfile p = plugin.getShopManager().getProfile(player.getUniqueId());

        if (slot == 45) {
            if (!p.getEquippedEffect().isEmpty()) equipEffect(p, "");
            confirming = null;
            render();
            return;
        }

        int idx = -1;
        for (int i = 0; i < CONTENT_SLOTS.length; i++) {
            if (CONTENT_SLOTS[i] == slot) { idx = page * PAGE_SIZE + i; break; }
        }
        if (idx < 0 || idx >= allEffects.size()) return;
        EffectDefinition def = allEffects.get(idx);

        if (e.isRightClick()) {
            int secs = plugin.getEffectManager().getRegistry().previewSeconds();
            if (plugin.getEffectManager().preview(plugin.getShopManager().entityIdOf(shop), def.id(), secs, p.getEquippedEffect())) {
                plugin.msg().send(player, "custom-gui.effect-previewing", "effect", Messages.parsed(def.name()), "seconds", secs);
                Sched.entityNext(player, () -> player.closeInventory());
            }
            return;
        }

        if (!plugin.access().canUseEffect(player, def)) {
            plugin.msg().send(player, "custom-gui.effect-no-access");
            return;
        }
        if (def.id().equals(p.getEquippedEffect())) return;

        if (def.price() <= 0 || p.ownsEffect(def.id())) {
            equipEffect(p, def.id());
            confirming = null;
        } else if (def.id().equals(confirming)) {
            if (plugin.getShopManager().charge(player, price(def.currency(), def.price()))) {
                p.unlockEffect(def.id());
                equipEffect(p, def.id());
                plugin.msg().send(player, "custom-gui.purchased", "name", Messages.parsed(def.name()));
            }
            confirming = null;
        } else {
            confirming = def.id();
        }
        render();
    }

    private ItemStack renderSkinItem(ShopManager sm, PlayerProfile p, Skins.Skin s) {
        var m = plugin.msg();
        if (sm.isEquipped(p, s)) {
            return Text.glow(m.icon(s.icon(), "custom-gui.skin-equipped", "skin", s.name()));
        } else if (!plugin.access().canUseSkin(player, s)) {
            return withLockedLore(m.icon(Material.BARRIER, "custom-gui.skin-no-access", "skin", s.name()),
                    org.riftlabs.villagerMerchants.util.Lore.read(plugin.getConfig(), "skins.locked-lore." + s.key()));
        } else if (sm.owns(p, s)) {
            return m.icon(s.icon(), "custom-gui.skin-owned", "skin", s.name());
        } else if (s.id().equals(confirming)) {
            return Text.glow(m.icon(s.icon(), "custom-gui.skin-confirm", "skin", s.name(), "price", sm.format(sm.skinCost(s))));
        } else {
            return m.icon(s.icon(), "custom-gui.skin-locked", "skin", s.name(), "price", sm.format(sm.skinCost(s)));
        }
    }

    /** A price in the named currency, rounded to what that currency can charge. */
    private org.riftlabs.villagerMerchants.economy.Price price(String currency, double amount) {
        return plugin.getEconomies().price(currency, amount);
    }

    public void open() { player.openInventory(inv); }

    @Override
    public void onClick(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv) return;
        int slot = e.getSlot();

        if (slot == 49) {
            Sched.entityNext(player, () -> new OwnerMenuGui(plugin, player, shop).open());
            return;
        }

        if (slot == 2 && skinsEnabled) { currentTab = Tab.SKINS; page = 0; confirming = null; render(); return; }
        if (slot == 4 && tagsEnabled) { currentTab = Tab.NAMETAGS; page = 0; confirming = null; render(); return; }
        if (slot == 6 && effectsEnabled) { currentTab = Tab.EFFECTS; page = 0; confirming = null; render(); return; }

        // Pagination Clicks
        if ((currentTab == Tab.NAMETAGS && tagsEnabled) || (currentTab == Tab.EFFECTS && effectsEnabled)) {
            int total = currentTab == Tab.NAMETAGS ? allNametags.size() : allEffects.size();
            int maxPages = Math.max(1, (int) Math.ceil((double) total / PAGE_SIZE));
            if (slot == 48 && page > 0) { page--; confirming = null; render(); return; }
            if (slot == 50 && page < maxPages - 1) { page++; confirming = null; render(); return; }
        }

        if (currentTab == Tab.EFFECTS && effectsEnabled) {
            handleEffectClick(e, slot);
            return;
        }

        if (currentTab == Tab.NAMETAGS && tagsEnabled) {
            int contentIndex = -1;
            for (int i = 0; i < CONTENT_SLOTS.length; i++) {
                if (CONTENT_SLOTS[i] == slot) {
                    contentIndex = (page * PAGE_SIZE) + i;
                    break;
                }
            }

            if (contentIndex >= 0 && contentIndex < allNametags.size()) {
                NametagPreset data = allNametags.get(contentIndex);
                ShopManager sm = plugin.getShopManager();
                PlayerProfile p = sm.getProfile(player.getUniqueId());

                if (p.getEquippedNametagFormat().equals(data.format())) {
                    return;
                } else if (!plugin.access().canUseNametag(player, data.id(), data.permission())) {
                    plugin.msg().send(player, "custom-gui.nametag-no-access", "name", Messages.parsed(data.name()));
                    return;
                } else if (p.ownsNametag(data.id())) {
                    p.setEquippedNametagFormat(data.format());
                    plugin.getNametagManager().updateShopNametag(plugin.getShopManager().entityIdOf(shop), data.format());
                    plugin.getDatabaseManager().async(() ->
                            plugin.getDatabaseManager().updateNametagData(player.getUniqueId().toString(), p.getUnlockedNametagsString(), p.getEquippedNametagFormat())
                    );
                    confirming = null;
                    render();
                } else if (data.id().equals(confirming)) {
                    if (sm.charge(player, price(data.currency(), data.price()))) {
                        p.unlockNametag(data.id());
                        p.setEquippedNametagFormat(data.format());
                        plugin.getNametagManager().updateShopNametag(plugin.getShopManager().entityIdOf(shop), data.format());
                        plugin.getDatabaseManager().async(() ->
                                plugin.getDatabaseManager().updateNametagData(player.getUniqueId().toString(), p.getUnlockedNametagsString(), p.getEquippedNametagFormat())
                        );
                        plugin.msg().send(player, "custom-gui.purchased", "name", Messages.parsed(data.name()));
                    }
                    confirming = null;
                    render();
                } else {
                    confirming = data.id();
                    render();
                }
            }
        } else if (currentTab == Tab.SKINS && skinsEnabled && linked) {
            handleVariantClick(slot);
        } else if (currentTab == Tab.SKINS && skinsEnabled) {
            Skins.Skin clickedSkin = skinBySlot.get(slot);
            if (clickedSkin == null) return;

            ShopManager sm = plugin.getShopManager();
            PlayerProfile p = sm.getProfile(player.getUniqueId());

            if (!sm.isEquipped(p, clickedSkin) && !plugin.access().canUseSkin(player, clickedSkin)) {
                plugin.msg().send(player, "custom-gui.skin-no-access-msg", "skin", clickedSkin.name());
                return;
            }
            if (sm.owns(p, clickedSkin)) {
                sm.equipSkin(player, clickedSkin);
                confirming = null;
            } else if (clickedSkin.id().equals(confirming)) {
                confirming = null;
                sm.buySkin(player, clickedSkin);
            } else {
                confirming = clickedSkin.id();
            }
            render();
        }
    }

    private void fillEmptySlots() {
        ItemStack pane = Gui.filler(plugin);
        for (int i = 0; i < inv.getSize(); i++) {
            if (inv.getItem(i) == null || inv.getItem(i).getType() == Material.AIR) {
                inv.setItem(i, pane);
            }
        }
    }

    @Override public Shop shop() { return shop; }
    @Override public Inventory getInventory() { return inv; }
}