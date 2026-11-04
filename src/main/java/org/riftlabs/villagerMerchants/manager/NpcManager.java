package org.riftlabs.villagerMerchants.manager;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.database.DatabaseManager;
import org.riftlabs.villagerMerchants.database.DatabaseManager.NpcRow;
import org.riftlabs.villagerMerchants.hook.MythicHook;
import org.riftlabs.villagerMerchants.hook.ModelEngineHook;
import org.riftlabs.villagerMerchants.util.LinkedSkins;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Shop NPC slots. A slot has a stable, admin-chosen shop-id and a body: a vanilla villager this plugin spawns
 * ({@code /vm create}) or an existing MythicMob / ModelEngine entity ({@code /vm link}).
 *
 * <p>Bodies are found again through a PDC tag on the entity, and (vanilla / MythicMobs) re-created when a chunk
 * loads without one. Rentals hang off the slot id, so they survive the body being replaced.
 */
public class NpcManager {
    public enum Kind {
        VANILLA, MYTHIC, MODEL_ENGINE;
        public static Kind parse(String s) {
            try { return valueOf(s); } catch (Exception e) { return VANILLA; }
        }
    }

    public static final class Slot {
        public final String id;
        public Kind kind;
        public String location;   // world;x;y;z;yaw;pitch
        public String ref;        // MYTHIC: mob type, MODEL_ENGINE: model ids, VANILLA: null
        public UUID entity;       // current body, null while its chunk is unloaded
        String world; int cx, cz;

        Slot(String id, Kind kind, String location, String ref) {
            this.id = id; this.kind = kind; this.ref = ref;
            setLocation(location);
        }

        void setLocation(String loc) {
            this.location = loc;
            String[] p = loc.split(";");
            world = p[0];
            try { cx = (int) Math.floor(Double.parseDouble(p[1])) >> 4; cz = (int) Math.floor(Double.parseDouble(p[3])) >> 4; }
            catch (Exception e) { cx = cz = 0; }
        }

        public boolean linked() { return kind != Kind.VANILLA; }
    }

    public static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final byte TAG_VANILLA = 1, TAG_LINKED = 2;

    private final VillagerMerchants plugin;
    private final DatabaseManager db;
    private final NamespacedKey idKey;
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final Map<UUID, Slot> byEntity = new HashMap<>();
    private MythicHook mythic;
    private ModelEngineHook modelEngine;
    private LinkedSkins linkedSkins;

    public NpcManager(VillagerMerchants plugin, DatabaseManager db) {
        this.plugin = plugin;
        this.db = db;
        this.idKey = new NamespacedKey(plugin, "npc_id");
    }

    public void initHooks() {
        mythic = new MythicHook(plugin);
        modelEngine = new ModelEngineHook(plugin);
        reloadSkins();
    }

    public void reloadSkins() { linkedSkins = new LinkedSkins(plugin); }
    public LinkedSkins linkedSkins() { return linkedSkins; }

    public MythicHook mythic() { return mythic; }
    public ModelEngineHook modelEngine() { return modelEngine; }

    // ------------------------------------------------------------ lookup

    public Slot get(String id) { return id == null ? null : slots.get(id.toLowerCase(Locale.ROOT)); }
    public Collection<Slot> all() { return Collections.unmodifiableCollection(slots.values()); }
    public Slot ofEntity(UUID u) { return u == null ? null : byEntity.get(u); }
    public Slot ofEntity(Entity e) { return e == null ? null : byEntity.get(e.getUniqueId()); }

    public Entity entity(String id) {
        Slot s = get(id);
        return s == null || s.entity == null ? null : Bukkit.getEntity(s.entity);
    }

    public boolean isManaged(Entity e) { return e != null && byEntity.containsKey(e.getUniqueId()); }

    /** True for villagers this plugin spawned (they may be turned to face players); false for linked bodies. */
    public boolean isVanilla(Entity e) {
        Slot s = ofEntity(e);
        return s != null && s.kind == Kind.VANILLA;
    }

    public static String format(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private static Location parse(String s) {
        String[] p = s.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null || p.length < 4) return null;
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    p.length > 4 ? Float.parseFloat(p[4]) : 0f, p.length > 5 ? Float.parseFloat(p[5]) : 0f);
        } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        for (NpcRow r : db.loadNpcs()) {
            try { slots.put(r.id(), new Slot(r.id(), Kind.parse(r.kind()), r.location(), r.ref())); }
            catch (Exception ex) { plugin.getLogger().severe("Skipping corrupt shop NPC '" + r.id() + "': " + ex.getMessage()); }
        }
    }

    private void save(Slot s) {
        NpcRow row = new NpcRow(s.id, s.kind.name(), s.location, s.ref);
        db.async(() -> db.upsertNpc(row));
    }

    /** A shop saved by an older version had no slot: give it one so it keeps working. Null if it has no usable location. */
    public String adoptLegacy(UUID shopId, String location) {
        if (location == null || location.equals("unknown") || location.split(";").length < 4) return null;
        String id = "shop-" + shopId.toString().substring(0, 8);
        if (!slots.containsKey(id)) {
            Slot s = new Slot(id, Kind.VANILLA, location, null);
            slots.put(id, s);
            save(s);
        }
        return id;
    }

    /** Spawns / re-binds the body of every slot whose chunk is loaded. The rest follow when their chunk loads. */
    public void spawnAll() {
        for (Slot s : new ArrayList<>(slots.values())) sync(s);
    }

    // ------------------------------------------------------------ create / link / remove

    /** Spawns a default vanilla villager shop. */
    public Slot createVanilla(String id, Location at) {
        Slot s = new Slot(id, Kind.VANILLA, format(at), null);
        slots.put(id, s);
        save(s);
        bind(s, spawnVillager(at));
        return s;
    }

    /** Base entity for a model this plugin spawns: an invisible-model carrier that stays put. */
    private Entity spawnModelBase(Location loc) {
        EntityType type;
        try { type = EntityType.valueOf(plugin.getConfig().getString("modelengine.base-entity", "VILLAGER").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { type = EntityType.VILLAGER; }
        Entity e = loc.getWorld().spawnEntity(loc, type);
        if (e instanceof org.bukkit.entity.LivingEntity le) { le.setAI(false); le.setSilent(true); le.setCollidable(false); le.setRemoveWhenFarAway(false); }
        e.setInvulnerable(true);
        e.setPersistent(true);
        return e;
    }

    private Entity spawnModelBody(Location loc, String models) {
        if (modelEngine == null || !modelEngine.available()) return null;
        Entity base = spawnModelBase(loc);
        if (!modelEngine.applyNewModels(base, models)) { base.remove(); return null; }
        return base;
    }

    /** /vm mm create, /vm meg create: spawns a MythicMob of this type, or a ModelEngine model, as a new shop NPC. Null if the spawn failed. */
    public Slot createCustom(String id, Kind kind, String ref, Location at) {
        Entity body = kind == Kind.MYTHIC ? (mythic == null ? null : mythic.spawn(ref, at)) : spawnModelBody(at, ref);
        if (body == null) return null;
        return link(id, body, kind, ref);
    }

    /**
     * Points a slot at an existing MythicMob / ModelEngine entity. A new id creates the slot; an existing id is
     * re-pointed and keeps its rental, stock and sales.
     */
    public Slot link(String id, Entity target, Kind kind, String ref) {
        Slot s = slots.get(id);
        if (s == null) {
            s = new Slot(id, kind, format(target.getLocation()), ref);
            slots.put(id, s);
        } else {
            releaseBody(s, s.kind == Kind.VANILLA);   // old vanilla villager goes away; an old linked body is just un-tagged
            s.kind = kind;
            s.ref = ref;
            s.setLocation(format(target.getLocation()));
            var shop = plugin.getShopManager().shopOfNpc(id);
            if (shop != null) shop.setLocation(s.location);
        }
        save(s);
        if (kind == Kind.MODEL_ENGINE) target.setPersistent(true);   // so the model's base entity survives restarts
        bind(s, target);
        return s;
    }

    /** Removes a slot. A linked body is only un-tagged unless {@code purge} is set; a vanilla villager is always removed. */
    public void remove(Slot s, boolean purge) {
        releaseBody(s, s.kind == Kind.VANILLA || purge);
        slots.remove(s.id);
        db.async(() -> db.deleteNpc(s.id));
    }

    private void releaseBody(Slot s, boolean kill) {
        Entity e = s.entity == null ? null : Bukkit.getEntity(s.entity);
        if (s.entity != null) {
            byEntity.remove(s.entity);
            plugin.getNametagManager().removeNametag(s.entity);
            plugin.getEffectManager().clear(s.entity);
        }
        s.entity = null;
        if (e == null) return;
        e.getPersistentDataContainer().remove(plugin.getShopManager().getShopKey());
        e.getPersistentDataContainer().remove(idKey);
        if (kill) e.remove();
        else e.setInvulnerable(false);
    }

    /**
     * Height (blocks above the entity's feet) of the floating name tag for a linked body, or -1 for a vanilla villager.
     * Lookup order in config.yml: nametag.linked-heights.<shop-id>, then .<mob type / first model id>, then nametag.linked-height.
     */
    public double nametagHeight(Entity e) {
        Slot s = ofEntity(e);
        if (s == null || !s.linked()) return -1;
        var cfg = plugin.getConfig();
        String base = "nametag.linked-heights.";
        if (cfg.contains(base + s.id)) return cfg.getDouble(base + s.id);
        String ref = s.ref == null ? "" : s.ref.split(",")[0].trim();
        if (!ref.isEmpty() && cfg.contains(base + ref)) return cfg.getDouble(base + ref);
        return cfg.getDouble("nametag.linked-height", 2.4);
    }

    // ------------------------------------------------------------ linked skins

    private LinkedSkins.Variant equipped(Slot s) {
        var shop = plugin.getShopManager().shopOfNpc(s.id);
        return shop == null ? null : linkedSkins.find(s.id, shop.getLinkedSkin());
    }

    /** MythicMob type the body should be: the equipped variant's, else the type it was linked as. */
    private String effectiveMythic(Slot s) {
        var v = equipped(s);
        return v != null && v.mythic() != null ? v.mythic() : s.ref;
    }

    private String effectiveModels(Slot s) {
        var v = equipped(s);
        return v != null && v.model() != null ? v.model() : s.ref;
    }

    /** What the body currently looks like in linked-skins.yml terms: the equipped variant, else the one matching its original type. */
    public LinkedSkins.Variant currentVariant(Slot s) {
        var v = equipped(s);
        return v != null ? v : baseVariant(s);
    }

    /** The variant that is the body's own look (what it was created / linked as), or null if linked-skins.yml does not list it. */
    public LinkedSkins.Variant baseVariant(Slot s) { return linkedSkins.matching(s.id, s.ref); }

    /** The shop ended (rent over / removed): the body goes back to the look it was created with. */
    public void resetLook(String npcId) {
        Slot s = get(npcId);
        if (s != null && s.kind != Kind.VANILLA) applySkin(s, null);
    }

    private void reapplySkin(Slot s) {
        if (slots.get(s.id) != s || s.entity == null) return;
        var v = equipped(s);
        Entity e = Bukkit.getEntity(s.entity);
        if (e == null) return;
        if (v != null && v.model() != null && modelEngine != null) modelEngine.setModels(e, v.model());
    }

    /**
     * Puts a variant on the shop's body, or the original look when {@code v} is null.
     * Model variants swap the ModelEngine model in place; MythicMob variants re-spawn the body as the other type.
     */
    public void applySkin(Slot s, LinkedSkins.Variant v) {
        if (s.kind == Kind.VANILLA) return;
        Entity e = s.entity == null ? null : Bukkit.getEntity(s.entity);
        if (e == null) return;   // chunk not loaded: applied by bind() when the body returns
        boolean wantsMythic = v != null && v.mythic() != null;
        if (s.kind == Kind.MYTHIC && mythic != null && (wantsMythic || (v == null && !s.ref.equals(mythic.typeOf(e))))) {
            String type = wantsMythic ? v.mythic() : s.ref;
            Location at = e.getLocation();
            releaseBody(s, true);
            Entity fresh = mythic.spawn(type, at);
            if (fresh != null) bind(s, fresh);
            return;
        }
        if (modelEngine != null) {
            String ids = v != null && v.model() != null ? v.model() : (s.kind == Kind.MODEL_ENGINE ? s.ref : null);
            if (ids != null) modelEngine.setModels(e, ids);
        }
    }

    // ------------------------------------------------------------ binding

    private Villager spawnVillager(Location loc) {
        Villager npc = (Villager) loc.getWorld().spawnEntity(loc, EntityType.VILLAGER);
        npc.setAdult();
        npc.setAgeLock(true);
        npc.setAI(false);
        npc.setInvulnerable(true);
        npc.setCollidable(false);
        npc.setSilent(true);
        npc.setPersistent(false);          // the plugin re-creates it; vanilla must not save it
        npc.setRemoveWhenFarAway(false);
        npc.setCanPickupItems(false);
        return npc;
    }

    private void bind(Slot s, Entity e) {
        if (s.entity != null && !s.entity.equals(e.getUniqueId())) byEntity.remove(s.entity);
        s.entity = e.getUniqueId();
        byEntity.put(s.entity, s);
        var pdc = e.getPersistentDataContainer();
        pdc.set(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE, s.linked() ? TAG_LINKED : TAG_VANILLA);
        pdc.set(idKey, PersistentDataType.STRING, s.id);
        if (s.linked()) e.setInvulnerable(true);
        plugin.getShopManager().refreshNameTag(e);
        if (s.kind != Kind.VANILLA) Bukkit.getScheduler().runTask(plugin, () -> reapplySkin(s));   // the model may only be there a tick later
    }

    /** Makes sure the slot has a live body if its chunk is loaded. */
    public void sync(Slot s) {
        if (s.entity != null) {
            Entity cur = Bukkit.getEntity(s.entity);
            if (cur != null && cur.isValid()) return;
            byEntity.remove(s.entity);
            s.entity = null;
        }
        World w = Bukkit.getWorld(s.world);
        Location loc = parse(s.location);
        if (w == null || loc == null || !w.isChunkLoaded(s.cx, s.cz)) return;

        // a tagged body already standing there (it persisted through the restart)?
        for (Entity e : w.getChunkAt(s.cx, s.cz).getEntities()) {
            if (s.id.equals(e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING))) {
                if (s.kind == Kind.VANILLA) { e.remove(); continue; }   // leftover villager: replaced below
                bind(s, e);
                return;
            }
        }
        switch (s.kind) {
            case VANILLA -> bind(s, spawnVillager(loc));
            case MYTHIC -> {
                Entity e = mythic != null ? mythic.spawn(effectiveMythic(s), loc) : null;
                if (e != null) bind(s, e);
                else plugin.getLogger().warning("Shop '" + s.id + "': MythicMob '" + s.ref + "' could not be spawned (is MythicMobs running and the mob defined?).");
            }
            case MODEL_ENGINE -> {   // a persistent base entity returns with its chunk; if it is gone, rebuild it from the model ids
                Entity e = spawnModelBody(loc, effectiveModels(s));
                if (e != null) bind(s, e);
            }
        }
    }

    // ------------------------------------------------------------ chunk / world / entity events

    public void onEntitiesLoad(Chunk chunk, List<Entity> entities) {
        for (Entity e : entities) {
            String id = e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (id == null) continue;
            Slot s = slots.get(id);
            byte tag = e.getPersistentDataContainer().getOrDefault(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE, TAG_LINKED);
            if (s == null) {                                   // slot was removed while this body was away
                if (tag == TAG_VANILLA) e.remove(); else stripTags(e);
            } else if (s.kind == Kind.VANILLA) {
                if (!e.getUniqueId().equals(s.entity)) e.remove();   // stale vanilla villager
            } else if (s.entity == null || Bukkit.getEntity(s.entity) == null) {
                bind(s, e);
            } else if (!e.getUniqueId().equals(s.entity)) {
                stripTags(e);                                  // duplicate: the first one wins
            }
        }
        List<Slot> here = new ArrayList<>();
        for (Slot s : slots.values()) {
            if (s.cx == chunk.getX() && s.cz == chunk.getZ() && s.world.equals(chunk.getWorld().getName())
                    && s.entity == null) here.add(s);
        }
        if (!here.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> here.forEach(this::sync));
    }

    public void onEntitiesUnload(List<Entity> entities) {
        for (Entity e : entities) {
            Slot s = byEntity.get(e.getUniqueId());
            if (s == null) continue;
            byEntity.remove(e.getUniqueId());
            plugin.getNametagManager().removeNametag(e.getUniqueId());
            plugin.getEffectManager().clear(e.getUniqueId());
            s.entity = null;
        }
    }

    /** A linked body died (or was removed by its own plugin): forget it and bring it back if we can. */
    public void onBodyGone(Entity e) {
        Slot s = byEntity.remove(e.getUniqueId());
        if (s == null) return;
        plugin.getNametagManager().removeNametag(e.getUniqueId());
        plugin.getEffectManager().clear(e.getUniqueId());
        s.entity = null;
        if (s.kind != Kind.MODEL_ENGINE) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (slots.get(s.id) == s) sync(s); }, 40L);
    }

    public void onWorldLoad(World w) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Slot s : new ArrayList<>(slots.values())) if (s.world.equals(w.getName())) sync(s);
        });
    }

    private void stripTags(Entity e) {
        e.getPersistentDataContainer().remove(plugin.getShopManager().getShopKey());
        e.getPersistentDataContainer().remove(idKey);
    }

    /** Plugin shutdown: vanilla villagers are removed so they are never saved; linked bodies stay, tagged, for next start. */
    public void shutdown() {
        for (Slot s : slots.values()) {
            Entity e = s.entity == null ? null : Bukkit.getEntity(s.entity);
            if (e != null && s.kind == Kind.VANILLA) e.remove();
        }
        for (World w : Bukkit.getWorlds()) {   // crash leftovers
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                Byte tag = v.getPersistentDataContainer().get(plugin.getShopManager().getShopKey(), PersistentDataType.BYTE);
                if (tag != null && tag == TAG_VANILLA) v.remove();
            }
        }
    }
}
