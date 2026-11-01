package org.riftlabs.villagerMerchants.manager;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.model.PlayerProfile;
import org.riftlabs.villagerMerchants.model.Shop;
import org.riftlabs.villagerMerchants.model.Trade;
import org.riftlabs.villagerMerchants.util.CurrencyToken;
import org.riftlabs.villagerMerchants.util.ItemCodec;
import org.riftlabs.villagerMerchants.util.Skins;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.*;

/** Owns all in-memory shop/profile state. Everything here runs on the main thread. */
public class ShopManager {
    public static final long DAY = 86_400_000L;

    private final VillagerMerchants plugin;
    private final DatabaseManager db;
    private final NamespacedKey shopKey;
    private final Map<UUID, Shop> shops = new HashMap<>();
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final Set<UUID> claiming = new HashSet<>();

    public ShopManager(VillagerMerchants plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        this.shopKey = new NamespacedKey(plugin, "is_shop");
    }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    public NamespacedKey getShopKey() { return shopKey; }

    // ------------------------------------------------------------ loading / saving

    public void load() {
        profiles.putAll(db.loadProfiles());
        for (PlayerProfile pp : profiles.values()) {
            recomputeSlots(pp);
            saveProfile(pp); // persists migrated slot levels
        }
        for (DatabaseManager.ShopRow r : db.loadShops()) {
            try {
                Shop s = new Shop(UUID.fromString(r.id()), UUID.fromString(r.owner()), r.expiry(), r.location());
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
                shops.put(s.getId(), s);
            } catch (Exception ex) {
                plugin.getLogger().severe("Skipping corrupt shop row " + r.id() + ": " + ex.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + shops.size() + " rented shops and " + profiles.size() + " player profiles.");
    }

    public void startTasks() {
        long auto = Math.max(5, cfg().getLong("autosave-seconds", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveDirty, auto, auto);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkExpiry, 20L * 30, 20L * 60);
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
                encodePrices(map));
    }

    /** "slot:price,slot:price" for money-priced trades only. */
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

    /** Serializes on the main thread (snapshot), writes asynchronously. */
    public void saveShop(Shop shop) {
        if (shops.get(shop.getId()) != shop) return; // released meanwhile
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

    // ------------------------------------------------------------ lookups

    public Shop getShop(UUID npc) { return shops.get(npc); }
    public Collection<Shop> allShops() { return Collections.unmodifiableCollection(shops.values()); }
    public boolean isLive(Shop s) { return shops.get(s.getId()) == s; }

    public int countOwned(UUID owner) {
        int n = 0;
        for (Shop s : shops.values()) if (s.isOwner(owner)) n++;
        return n;
    }

    /** unlocked slots = config default + purchased levels * slots-per-level (config changes apply to everyone). */
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
        return n == null ? "Unknown" : n;
    }

    public String money(double amount) {
        Economy e = plugin.getEconomy();
        return e != null ? e.format(amount) : String.format("$%.2f", amount);
    }

    // ------------------------------------------------------------ name tags

    /** Syncs name tag AND skin of a shop NPC with the current shop state. */
    public void refreshNameTag(Villager v) {
        Shop s = shops.get(v.getUniqueId());
        if (s == null) {
            Skins.apply(v, "plains", "none");
        } else {
            PlayerProfile pr = getProfile(s.getOwner());
            Skins.apply(v, pr.getSkinType(), pr.getSkinProfession());
        }
        if (s == null) {
            v.customName(Text.mm("<green>Available Shop"));
        } else if (s.isActive(System.currentTimeMillis())) {
            String rating = plugin.getRatings() == null ? "" : plugin.getRatings().nametagSuffix(s);
            v.customName(Text.mm("<gold>" + ownerName(s.getOwner()) + "'s Shop" + rating));
        } else {
            v.customName(Text.mm("<gray>Closed Shop"));
        }
        v.setCustomNameVisible(true);
    }

    public void refreshNameTag(UUID npc) {
        Entity e = Bukkit.getEntity(npc);
        if (e instanceof Villager v) refreshNameTag(v);
    }

    // ------------------------------------------------------------ renting

    public double rentPrice(PlayerProfile p) {
        double base = cfg().getDouble("rent.price", 5000.0);
        double disc = p.getTaxLevel() * cfg().getDouble("upgrades.tax.per-level", 0.05);
        return Math.round(Math.max(0, base * (1 - disc)) * 100.0) / 100.0;
    }

    public boolean rentShop(Player p, UUID npcId) {
        if (shops.containsKey(npcId)) {
            p.sendMessage(Text.mm("<red>This shop was just rented by someone else."));
            return false;
        }
        if (countOwned(p.getUniqueId()) >= cfg().getInt("rent.max-shops-per-player", 3)) {
            p.sendMessage(Text.mm("<red>You have reached the maximum number of shops."));
            return false;
        }
        PlayerProfile prof = getProfile(p.getUniqueId());
        double price = rentPrice(prof);
        if (!charge(p, price)) return false;

        int days = Math.min(cfg().getInt("rent.base-days", 7), prof.getMaxRentDays());
        Entity ent = Bukkit.getEntity(npcId);
        String loc = ent == null ? "unknown" : ent.getWorld().getName() + ";" + ent.getLocation().getBlockX()
                + ";" + ent.getLocation().getBlockY() + ";" + ent.getLocation().getBlockZ();
        Shop s = new Shop(npcId, p.getUniqueId(), System.currentTimeMillis() + days * DAY, loc);
        shops.put(npcId, s);
        saveShop(s);
        refreshNameTag(npcId);
        p.sendMessage(Text.mm("<green>Shop rented for " + days + " days! Right-click it to manage it."));
        return true;
    }

    public boolean extendRent(Player p, Shop shop) {
        PlayerProfile prof = getProfile(p.getUniqueId());
        long now = System.currentTimeMillis();
        long addMs = cfg().getInt("rent.base-days", 7) * DAY;
        long base = Math.max(now, shop.getRentExpiry());
        long room = now + prof.getMaxRentDays() * DAY - base;
        long ext = Math.min(addMs, room);
        if (ext < DAY) {
            p.sendMessage(Text.mm("<red>You can't rent further ahead. Upgrade your max rent duration!"));
            return false;
        }
        double price = Math.round(rentPrice(prof) * ((double) ext / addMs) * 100.0) / 100.0;
        if (!charge(p, price)) return false;
        shop.setRentExpiry(base + ext);
        saveShop(shop);
        refreshNameTag(shop.getId());
        p.sendMessage(Text.mm("<green>Rent extended by " + (ext / DAY) + " days."));
        return true;
    }

    private boolean charge(Player p, double price) {
        Economy econ = plugin.getEconomy();
        if (!econ.has(p, price)) {
            p.sendMessage(Text.mm("<red>You need " + money(price) + " for that."));
            return false;
        }
        if (!econ.withdrawPlayer(p, price).transactionSuccess()) {
            p.sendMessage(Text.mm("<red>The payment failed."));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ expiry / release

    public void checkExpiry() {
        long now = System.currentTimeMillis();
        long grace = cfg().getLong("rent.grace-hours", 48) * 3_600_000L;
        for (Shop s : new ArrayList<>(shops.values())) {
            if (now > s.getRentExpiry() + grace) releaseShop(s, true);
        }
    }

    /** Frees the NPC. Storage contents are moved into the owner's /vm claim pile. */
    public void releaseShop(Shop shop, boolean keepItems) { releaseShop(shop, keepItems, true); }

    public void releaseShop(Shop shop, boolean keepItems, boolean notify) {
        if (shops.remove(shop.getId()) == null) return;
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
        refreshNameTag(shop.getId());

        Player op = Bukkit.getPlayer(shop.getOwner());
        if (notify && op != null) op.sendMessage(Text.mm("<yellow>Your shop has been released. Use <gold>/vm claim</gold> to retrieve leftover items."));
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
                        p.sendMessage(Text.mm("<gray>You have nothing to claim."));
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
                    p.sendMessage(Text.mm("<green>Claimed " + count + " items."));
                } finally {
                    claiming.remove(u);
                }
            });
        });
    }

    // ------------------------------------------------------------ upgrades

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
        PlayerProfile p = getProfile(pl.getUniqueId());
        if (level(t, p) >= maxLevel(t)) {
            pl.sendMessage(Text.mm("<red>That upgrade is already maxed."));
            return false;
        }
        if (!charge(pl, upgradeCost(t, p))) return false;
        switch (t) {
            case SLOTS -> { p.setSlotLevel(p.getSlotLevel() + 1); recomputeSlots(p); }
            case TAX -> p.setTaxLevel(p.getTaxLevel() + 1);
            case DURATION -> p.setMaxRentDays(p.getMaxRentDays() + (int) perLevel(t));
        }
        saveProfile(p);
        pl.sendMessage(Text.mm("<green>Upgrade purchased!"));
        return true;
    }

    // ------------------------------------------------------------ cancel & refund

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
        p.sendMessage(Text.mm("<green>Shop cancelled." + (refund > 0 ? " Refunded " + money(refund) + "." : "")));
        claimItems(p); // queued after the claim insert, so the stored items are returned right away
    }

    // ------------------------------------------------------------ skins

    public double skinPrice(Skins.Skin s) {
        return cfg().getDouble(s.kind() == Skins.Kind.TYPE ? "skins.type-price" : "skins.profession-price", 15000.0);
    }

    public boolean owns(PlayerProfile p, Skins.Skin s) { return s.isFree() || p.ownsSkin(s.id()); }

    public boolean isEquipped(PlayerProfile p, Skins.Skin s) {
        return s.kind() == Skins.Kind.TYPE ? p.getSkinType().equals(s.key()) : p.getSkinProfession().equals(s.key());
    }

    public void equipSkin(Player pl, Skins.Skin s) {
        PlayerProfile p = getProfile(pl.getUniqueId());
        if (!owns(p, s)) return;
        if (s.kind() == Skins.Kind.TYPE) p.setSkinType(s.key()); else p.setSkinProfession(s.key());
        saveProfile(p);
        for (Shop sh : shops.values()) if (sh.isOwner(pl.getUniqueId())) refreshNameTag(sh.getId());
    }

    public boolean buySkin(Player pl, Skins.Skin s) {
        PlayerProfile p = getProfile(pl.getUniqueId());
        if (owns(p, s)) return false;
        if (!charge(pl, skinPrice(s))) return false;
        p.unlockSkin(s.id());
        pl.sendMessage(Text.mm("<green>Unlocked the <gold>" + s.name() + "</gold> skin permanently!"));
        equipSkin(pl, s);
        return true;
    }

    // ------------------------------------------------------------ customer recipes

    /** The item used to display a money price inside the trading UI (never handed to players). */
    public Material tokenMaterial() {
        Material m = Material.matchMaterial(cfg().getString("money.token-material", "EMERALD"));
        return (m == null || m.isAir() || !m.isItem()) ? Material.EMERALD : m;
    }

    public boolean moneyTradesEnabled() { return cfg().getBoolean("money.enabled", true); }

    public List<MerchantRecipe> buildRecipes(Shop shop) {
        List<MerchantRecipe> list = new ArrayList<>();
        for (Trade t : shop.completeTrades()) {
            ItemStack res = t.getResult();
            boolean inStock = shop.getStorage().containsAtLeast(res, res.getAmount());
            // uses == maxUses locks the recipe (red X) when the backend stock runs out
            MerchantRecipe r = new MerchantRecipe(res.clone(), inStock ? 0 : 1, inStock ? 1_000_000 : 1, false, 0, 0f);
            r.setIgnoreDiscounts(true);
            if (t.isMoney()) {
                r.addIngredient(CurrencyToken.create(tokenMaterial(), money(t.getPrice())));
            } else {
                r.addIngredient(t.getPay1().clone());
                if (t.getPay2() != null) r.addIngredient(t.getPay2().clone());
            }
            list.add(r);
        }
        return list;
    }
}
