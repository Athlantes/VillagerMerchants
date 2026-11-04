package org.riftlabs.villagerMerchants.manager;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.model.Perm;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.CurrencyToken;
import org.riftlabs.villagerMerchants.util.ItemCodec;
import org.riftlabs.villagerMerchants.util.ItemMatch;
import org.riftlabs.villagerMerchants.util.Skins;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.*;

public class ShopManager {
    public static final long DAY = 86_400_000L;

    private final VillagerMerchants plugin;
    private final DatabaseManager db;
    private final NamespacedKey shopKey;
    private final Map<UUID, Shop> shops = new HashMap<>();      // by stable shop id
    private final Map<String, Shop> byNpc = new HashMap<>();    // by NPC slot id (the /vm create <shop-id> name)
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Set<UUID> claiming = new HashSet<>();

    public ShopManager(VillagerMerchants plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        this.shopKey = new NamespacedKey(plugin, "is_shop");
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    public NamespacedKey getShopKey() { return shopKey; }

    public void load() {
        profiles.putAll(db.loadProfiles());
        for (PlayerProfile pp : profiles.values()) {
            recomputeSlots(pp);
            saveProfile(pp);
        }
        NpcManager npcs = plugin.getNpcs();
        npcs.load();
        for (DatabaseManager.ShopRow r : db.loadShops()) {
            try {
                UUID shopId = UUID.fromString(r.id());
                String npcId = r.npcId();
                boolean migrated = false;
                if (npcId == null || npcId.isBlank()) {           // shop saved by an older version: give it a slot
                    npcId = npcs.adoptLegacy(shopId, r.location());
                    migrated = npcId != null;
                }
                Shop s = new Shop(shopId, UUID.fromString(r.owner()), r.expiry(), r.location(), npcId);

                ItemStack[] arr = ItemCodec.decode(r.trades());
                for (int i = 0; i + 2 < arr.length; i += 3) {
                    if (arr[i] == null && arr[i + 1] == null && arr[i + 2] == null) continue;
                    Trade t = new Trade();
                    t.set(0, arr[i]);
                    t.set(1, arr[i + 1]);
                    t.set(2, arr[i + 2]);
                    s.setTrade(i / 3, t);
                }
                for (Map.Entry<Integer, Double> pe : decodePrices(r.prices()).entrySet()) {
                    s.getOrCreateTrade(pe.getKey()).setPrice(pe.getValue());
                }
                ItemStack[] stock = ItemCodec.decode(r.stock());
                ItemStack[] fit = new ItemStack[54];
                System.arraycopy(stock, 0, fit, 0, Math.min(stock.length, 54));
                s.getStorage().setContents(fit);

                s.startSale(r.salePercent(), r.saleExpiry(), r.saleListed());
                s.setFeaturedUntil(r.featuredUntil());
                s.loadStaff(r.staff());
                if (r.linkedSkin() != null) s.setLinkedSkin(r.linkedSkin());
                s.setDirty(migrated);

                register(s);
            } catch (Exception ex) {
                plugin.getLogger().severe("Skipping corrupt shop row " + r.id() + ": " + ex.getMessage());
            }
        }
        npcs.spawnAll();
        plugin.getLogger().info("Loaded " + shops.size() + " rented shops on " + npcs.all().size() + " shop NPCs.");
    }

    private void register(Shop s) {
        shops.put(s.getId(), s);
        if (s.getNpcId() != null) byNpc.put(s.getNpcId(), s);
    }

    /** Removes every vanilla shop villager from the world (they are re-created on start); linked bodies are left alone. */
    public void despawnAll() { plugin.getNpcs().shutdown(); }

    public void startTasks() {
        long auto = Math.max(5, cfg().getLong("autosave-seconds", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, auto, auto);
        long check = Math.max(10, cfg().getLong("rent.expiry-check-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkExpiry, 20L * 30, check);
    }

    private DatabaseManager.ShopRow toRow(Shop s) {
        Map<Integer, Trade> map = s.getTrades();
        int max = map.isEmpty() ? -1 : Collections.max(map.keySet());
        ItemStack[] arr = new ItemStack[(max + 1) * 3];
        for (Map.Entry<Integer, Trade> e : map.entrySet()) {
            int b = e.getKey() * 3;
            arr[b] = e.getValue().getPay1();
            arr[b + 1] = e.getValue().getPay2();
            arr[b + 2] = e.getValue().getResult();
        }
        return new DatabaseManager.ShopRow(s.getId().toString(), s.getOwner().toString(), s.getLocation(),
                s.getRentExpiry(), ItemCodec.encode(arr), ItemCodec.encode(s.getStorage().getContents()),
                encodePrices(map), s.getSalePercent(), s.getSaleExpiry(), s.isSaleListed(), s.getFeaturedUntil(),
                s.serializeStaff(), s.getNpcId(), s.getLinkedSkin());
    }

    private static String encodePrices(Map<Integer, Trade> trades) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Trade> e : trades.entrySet()) {
            if (!e.getValue().isMoney()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue().getPrice());
        }
        return sb.toString();
    }

    private static Map<Integer, Double> decodePrices(String raw) {
        Map<Integer, Double> out = new HashMap<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            String[] kv = part.split(":");
            if (kv.length != 2) continue;
            try {
                double price = Double.parseDouble(kv[1]);
                if (price > 0) out.put(Integer.parseInt(kv[0].trim()), price);
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public void saveShop(Shop shop) {
        if (shops.get(shop.getId()) != shop) return;
        DatabaseManager.ShopRow row = toRow(shop);
        shop.setDirty(false);
        db.async(() -> db.upsertShop(row));
    }

    public void saveDirty() {
        for (Shop s : shops.values()) if (s.isDirty()) saveShop(s);
    }

    public void saveAll() {
        for (Shop s : shops.values()) saveShop(s);
    }

    public void saveProfile(PlayerProfile p) {
        String u = p.getUuid().toString();
        int slots = p.getUnlockedSlots(), tax = p.getTaxLevel(), days = p.getMaxRentDays();
        String skins = p.serializeSkins(), st = p.getSkinType(), sp = p.getSkinProfession();
        int sLvl = p.getSlotLevel();
        db.async(() -> db.upsertProfile(u, slots, tax, days, skins, st, sp, sLvl));
    }

    /** By stable shop id (what ratings, transactions and sessions use). */
    public Shop getShop(UUID shopId) { return shops.get(shopId); }
    /** The shop rented at a NPC slot, or null if that slot is free. */
    public Shop shopOfNpc(String npcId) { return npcId == null ? null : byNpc.get(npcId); }
    /** The shop of the NPC body with this entity UUID, or null (free slot or not a shop NPC). */
    public Shop getShopByEntity(UUID entity) {
        NpcManager.Slot slot = plugin.getNpcs().ofEntity(entity);
        return slot == null ? null : byNpc.get(slot.id);
    }
    /** The live NPC body of a shop, or null while its chunk is unloaded. */
    public Entity entityOf(Shop s) { return plugin.getNpcs().entity(s.getNpcId()); }
    public UUID entityIdOf(Shop s) {
        NpcManager.Slot slot = plugin.getNpcs().get(s.getNpcId());
        return slot == null ? null : slot.entity;
    }
    public Collection<Shop> allShops() { return Collections.unmodifiableCollection(shops.values()); }
    public boolean isLive(Shop s) { return shops.get(s.getId()) == s; }

    public int countOwned(UUID owner) {
        int n = 0;
        for (Shop s : shops.values()) if (s.isOwner(owner)) n++;
        return n;
    }

    public void recomputeSlots(PlayerProfile p) {
        p.setUnlockedSlots(cfg().getInt("defaults.unlocked-slots", 3)
                + p.getSlotLevel() * (int) perLevel(UpgradeType.SLOTS));
    }

    public PlayerProfile getProfile(UUID uuid) {
        return profiles.computeIfAbsent(uuid, u -> {
            PlayerProfile p = new PlayerProfile(u, cfg().getInt("defaults.unlocked-slots", 3), 0,
                    cfg().getInt("rent.base-days", 7));
            saveProfile(p);
            return p;
        });
    }

    public String ownerName(UUID owner) {
        String n = Bukkit.getOfflinePlayer(owner).getName();
        return n == null ? plugin.msg().raw("misc.unknown") : n;
    }

    public String money(double amount) {
        Economy e = plugin.getEconomy();
        return e != null ? e.format(amount)
                : plugin.msg().str("money.fallback", "amount", String.format(Locale.ROOT, "%.2f", amount));
    }

    public void refreshNameTag(Entity v) {
        Shop s = getShopByEntity(v.getUniqueId());
        boolean vanilla = plugin.getNpcs().isVanilla(v);   // linked bodies keep their own name and look
        if (vanilla) {
            v.customName(null);
            v.setCustomNameVisible(false);
        }

        plugin.getNametagManager().removeNametag(v.getUniqueId());
        plugin.getEffectManager().clear(v.getUniqueId());

        if (s == null) {
            if (vanilla && v instanceof Villager vil) Skins.apply(vil, "plains", "none");
            plugin.getNametagManager().spawnNametag(v, plugin.msg().raw("nametag.available"));
        } else {
            PlayerProfile pr = getProfile(s.getOwner());
            if (vanilla && v instanceof Villager vil) Skins.apply(vil, pr.getSkinType(), pr.getSkinProfession());

            if (s.isActive(System.currentTimeMillis())) {
                plugin.getNametagManager().spawnNametag(v, activeNametagFormat(s));
                plugin.getEffectManager().set(v, activeEffectId(s));
            } else {
                plugin.getNametagManager().spawnNametag(v, plugin.msg().raw("nametag.closed"));
            }
        }
    }

    /** Name tag text of an open shop: owner name, rating and (while a sale runs) the ON SALE banner. */
    public String activeNametagFormat(Shop s) {
        PlayerProfile pr = getProfile(s.getOwner());
        String rating = plugin.getRatings() == null ? "" : plugin.getRatings().nametagSuffix(s);
        var m = plugin.msg();
        String personalized = pr.getEquippedNametagFormat().replace("<bold>",
                "<bold>" + m.str("nametag.owner-line", "owner", Text.esc(ownerName(s.getOwner()))));
        personalized = personalized.replace(m.raw("nametag.replace-from"), m.raw("nametag.replace-to"));
        String banner = plugin.getPromotions() == null ? "" : plugin.getPromotions().nametagBanner(s);
        return personalized + rating + banner;
    }

    public String activeEffectId(Shop s) {
        String own = getProfile(s.getOwner()).getEquippedEffect();
        return plugin.getPromotions() == null ? own : plugin.getPromotions().effectFor(s, own);
    }

    /** By entity UUID. */
    public void refreshNameTag(UUID npcEntity) {
        Entity e = npcEntity == null ? null : Bukkit.getEntity(npcEntity);
        if (e != null && plugin.getNpcs().isManaged(e)) refreshNameTag(e);
    }

    public void refreshNameTag(Shop shop) {
        Entity e = entityOf(shop);
        if (e != null) refreshNameTag(e);
    }

    public double rentPrice(PlayerProfile p) {
        double base = cfg().getDouble("rent.price", 5000.0);
        double disc = p.getTaxLevel() * cfg().getDouble("upgrades.tax.per-level", 0.05);
        return Math.round(Math.max(0, base * (1 - disc)) * 100.0) / 100.0;
    }

    public boolean rentShop(Player p, UUID npcId) {
        if (getShopByEntity(npcId) != null) {
            plugin.msg().send(p, "shop.rent.taken");
            return false;
        }
        if (!plugin.access().canRent(p)) {
            plugin.msg().send(p, "shop.rent.no-rent-permission");
            return false;
        }
        int limit = plugin.access().maxShops(p);   // rank-based: villagermerchants.maxshops.<n>
        if (countOwned(p.getUniqueId()) >= limit) {
            plugin.msg().send(p, "shop.rent.max-shops", "max", Access.cap(limit));
            return false;
        }
        // Region / claim rules for where this NPC stands, checked before the player is charged.
        Entity npcEntity = Bukkit.getEntity(npcId);
        var verdict = plugin.getProtection().checkRent(p, npcEntity == null ? null : npcEntity.getLocation());
        if (!verdict.allowed()) {
            verdict.send(plugin.msg(), p);
            return false;
        }
        NpcManager.Slot slot = plugin.getNpcs().ofEntity(npcId);
        if (slot == null) return false;   // the NPC was removed while the menu was open
        PlayerProfile prof = getProfile(p.getUniqueId());
        double price = rentPrice(prof);
        if (!charge(p, price)) return false;

        int days = Math.min(cfg().getInt("rent.base-days", 7), prof.getMaxRentDays());

        Shop s = new Shop(UUID.randomUUID(), p.getUniqueId(), System.currentTimeMillis() + days * DAY, slot.location, slot.id);
        register(s);
        saveShop(s);
        refreshNameTag(s);
        plugin.msg().send(p, "shop.rent.success", "days", days);
        return true;
    }

    public boolean extendRent(Player p, Shop shop) {
        if (!shop.can(p.getUniqueId(), Perm.BILLING)) {
            plugin.msg().send(p, "shop.rent.no-permission");
            return false;
        }
        PlayerProfile prof = getProfile(shop.getOwner());   // the shop's upgrades apply, whoever pays
        long now = System.currentTimeMillis();
        long addMs = cfg().getInt("rent.base-days", 7) * DAY;
        long base = Math.max(now, shop.getRentExpiry());
        long room = now + prof.getMaxRentDays() * DAY - base;
        long ext = Math.min(addMs, room);
        if (ext < DAY) {
            plugin.msg().send(p, "shop.rent.too-far");
            return false;
        }
        double price = Math.round(rentPrice(prof) * ((double) ext / addMs) * 100.0) / 100.0;
        if (!charge(p, price)) return false;
        shop.setRentExpiry(base + ext);
        saveShop(shop);
        refreshNameTag(shop);
        plugin.msg().send(p, "shop.rent.extended", "days", ext / DAY);
        return true;
    }

    /** Withdraws money from the player (nothing is paid to anyone: a money sink). */
    public boolean charge(Player p, double price) {
        Economy econ = plugin.getEconomy();
        if (!econ.has(p, price)) {
            plugin.msg().send(p, "economy.not-enough", "amount", money(price));
            return false;
        }
        if (!econ.withdrawPlayer(p, price).transactionSuccess()) {
            plugin.msg().send(p, "economy.payment-failed");
            return false;
        }
        return true;
    }

    public void checkExpiry() {
        long now = System.currentTimeMillis();
        long grace = cfg().getLong("rent.grace-hours", 48) * 3_600_000L;
        for (Shop s : new ArrayList<>(shops.values())) {
            if (now > s.getRentExpiry() + grace) releaseShop(s, true);
        }
    }

    public void releaseShop(Shop shop, boolean keepItems) { releaseShop(shop, keepItems, true); }

    public void releaseShop(Shop shop, boolean keepItems, boolean notify) {
        if (shops.remove(shop.getId()) == null) return;
        if (shop.getNpcId() != null) byNpc.remove(shop.getNpcId(), shop);
        for (HumanEntity v : new ArrayList<>(shop.getStorage().getViewers())) v.closeInventory();
        plugin.getCustomerTrades().closeSessions(shop.getId());

        if (keepItems) {
            ItemStack[] contents = shop.getStorage().getContents();
            boolean any = Arrays.stream(contents).anyMatch(i -> i != null && !i.getType().isAir());
            if (any) {
                String data = ItemCodec.encode(contents);
                String owner = shop.getOwner().toString();
                db.async(() -> db.addClaim(owner, data));
            }
        }
        shop.getStorage().clear();
        String id = shop.getId().toString();
        db.async(() -> db.deleteShop(id));
        plugin.getNpcs().resetLook(shop.getNpcId());   // bought looks leave with the owner
        Entity body = entityOf(shop);
        if (body != null) refreshNameTag(body);

        Player op = Bukkit.getPlayer(shop.getOwner());
        if (notify && op != null) plugin.msg().send(op, "shop.released");
    }

    public void claimItems(Player p) {
        UUID u = p.getUniqueId();
        if (!claiming.add(u)) return;
        db.async(() -> {
            List<DatabaseManager.ClaimRow> rows = db.loadClaims(u.toString());
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!p.isOnline()) return;
                    if (rows.isEmpty()) {
                        plugin.msg().send(p, "shop.claim.nothing");
                        return;
                    }
                    int count = 0;
                    for (DatabaseManager.ClaimRow r : rows) {
                        for (ItemStack it : ItemCodec.decode(r.data())) {
                            if (it == null) continue;
                            count += it.getAmount();
                            p.getInventory().addItem(it).values()
                                    .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                        }
                        int id = r.id();
                        db.async(() -> db.deleteClaim(id));
                    }
                    plugin.msg().send(p, "shop.claim.done", "count", count);
                } finally {
                    claiming.remove(u);
                }
            });
        });
    }

    public int maxLevel(UpgradeType t) { return cfg().getInt("upgrades." + t.key + ".max-level", 1); }
    public double perLevel(UpgradeType t) { return cfg().getDouble("upgrades." + t.key + ".per-level", 1); }

    public int level(UpgradeType t, PlayerProfile p) {
        int lvl = switch (t) {
            case SLOTS -> p.getSlotLevel();
            case TAX -> p.getTaxLevel();
            case DURATION -> (int) ((p.getMaxRentDays() - cfg().getInt("rent.base-days", 7)) / Math.max(1, perLevel(t)));
        };
        return Math.max(0, lvl);
    }

    public double upgradeCost(UpgradeType t, PlayerProfile p) {
        double base = cfg().getDouble("upgrades." + t.key + ".base-cost", 10000);
        double mult = cfg().getDouble("upgrades." + t.key + ".multiplier", 1.5);
        return Math.round(base * Math.pow(mult, level(t, p)) * 100.0) / 100.0;
    }

    public boolean purchaseUpgrade(Player pl, UpgradeType t) {
        if (!plugin.access().canUpgrade(pl, t)) {
            plugin.msg().send(pl, "upgrade.no-permission");
            return false;
        }
        PlayerProfile p = getProfile(pl.getUniqueId());
        if (level(t, p) >= maxLevel(t)) {
            plugin.msg().send(pl, "upgrade.maxed");
            return false;
        }
        if (!charge(pl, upgradeCost(t, p))) return false;
        switch (t) {
            case SLOTS -> { p.setSlotLevel(p.getSlotLevel() + 1); recomputeSlots(p); }
            case TAX -> p.setTaxLevel(p.getTaxLevel() + 1);
            case DURATION -> p.setMaxRentDays(p.getMaxRentDays() + (int) perLevel(t));
        }
        saveProfile(p);
        plugin.msg().send(pl, "upgrade.purchased");
        return true;
    }

    public double cancelRefund(Shop shop) {
        PlayerProfile prof = getProfile(shop.getOwner());
        long left = Math.max(0, shop.getRentExpiry() - System.currentTimeMillis());
        double perDay = rentPrice(prof) / Math.max(1, cfg().getInt("rent.base-days", 7));
        double pct = cfg().getDouble("rent.cancel-refund", 0.5);
        return Math.round(perDay * ((double) left / DAY) * pct * 100.0) / 100.0;
    }

    public void cancelShop(Player p, Shop shop) {
        double refund = cancelRefund(shop);
        if (refund > 0) plugin.getEconomy().depositPlayer(p, refund);
        releaseShop(shop, true, false);
        if (refund > 0) plugin.msg().send(p, "shop.cancelled-refund", "refund", money(refund));
        else plugin.msg().send(p, "shop.cancelled");
        claimItems(p);
    }

    public double skinPrice(Skins.Skin s) {
        String own = "skins.prices." + s.key();           // optional price for one specific skin
        if (cfg().contains(own)) return Math.max(0, cfg().getDouble(own));
        return cfg().getDouble(s.kind() == Skins.Kind.TYPE ? "skins.type-price" : "skins.profession-price", 15000.0);
    }

    public boolean owns(PlayerProfile p, Skins.Skin s) { return s.isFree() || p.ownsSkin(s.id()); }

    public boolean isEquipped(PlayerProfile p, Skins.Skin s) {
        return s.kind() == Skins.Kind.TYPE ? p.getSkinType().equals(s.key()) : p.getSkinProfession().equals(s.key());
    }

    public void equipSkin(Player pl, Skins.Skin s) {
        PlayerProfile p = getProfile(pl.getUniqueId());
        if (!owns(p, s)) return;
        if (!plugin.access().canUseSkin(pl, s)) {
            plugin.msg().send(pl, "custom-gui.skin-no-access-msg", "skin", s.name());
            return;
        }
        if (s.kind() == Skins.Kind.TYPE) p.setSkinType(s.key()); else p.setSkinProfession(s.key());
        saveProfile(p);
        for (Shop sh : shops.values()) if (sh.isOwner(pl.getUniqueId())) refreshNameTag(sh);
    }

    public boolean buySkin(Player pl, Skins.Skin s) {
        PlayerProfile p = getProfile(pl.getUniqueId());
        if (owns(p, s)) return false;
        if (!plugin.access().canUseSkin(pl, s)) {   // check before charging anything
            plugin.msg().send(pl, "custom-gui.skin-no-access-msg", "skin", s.name());
            return false;
        }
        if (!charge(pl, skinPrice(s))) return false;
        p.unlockSkin(s.id());
        plugin.msg().send(pl, "skins.unlocked", "skin", s.name());
        equipSkin(pl, s);
        return true;
    }

    public Material tokenMaterial() {
        Material m = Material.matchMaterial(cfg().getString("money.token-material", "EMERALD"));
        return (m == null || m.isAir() || !m.isItem()) ? Material.EMERALD : m;
    }

    public boolean moneyTradesEnabled() { return cfg().getBoolean("money.enabled", true); }

    /** Price a customer pays right now for a money trade (sale applied). */
    public double effectivePrice(Shop shop, Trade t) { return shop.effectivePrice(t, System.currentTimeMillis()); }

    /** Per live trade (same order as {@link Shop#completeTrades()}): the money price at this moment, 0 for item trades. */
    public double[] snapshotPrices(Shop shop) {
        List<Trade> trades = shop.completeTrades();
        double[] out = new double[trades.size()];
        for (int i = 0; i < out.length; i++) out[i] = effectivePrice(shop, trades.get(i));
        return out;
    }

    public List<MerchantRecipe> buildRecipes(Shop shop) {
        long now = System.currentTimeMillis();
        List<MerchantRecipe> list = new ArrayList<>();
        for (Trade t : shop.completeTrades()) {
            ItemStack res = t.getResult();
            // a trade that breaks the anti-abuse rules stays in the list (so recipe index == trade index) but is locked
            boolean inStock = ItemMatch.count(shop.getStorage(), res) >= res.getAmount() && !plugin.getAntiAbuse().isBlocked(t);
            MerchantRecipe r = new MerchantRecipe(res.clone(), inStock ? 0 : 1, inStock ? 1_000_000 : 1, false, 0, 0f);
            r.setIgnoreDiscounts(true);
            if (t.isMoney()) {
                String price = money(shop.effectivePrice(t, now));
                String label = shop.isOnSale(now)
                        ? plugin.msg().str("trade.price-label-sale", "price", price, "percent", shop.getSalePercent())
                        : plugin.msg().str("trade.price-label", "price", price);
                r.addIngredient(CurrencyToken.create(tokenMaterial(), label));
            } else {
                r.addIngredient(t.getPay1().clone());
                if (t.getPay2() != null) r.addIngredient(t.getPay2().clone());
            }
            list.add(r);
        }
        return list;
    }
}