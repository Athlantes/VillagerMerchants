package org.riftlabs.villagerMerchants.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.riftlabs.villagerMerchants.VillagerMerchants;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Talks to ModelEngine (R4) through reflection. Detects modeled entities, lists their model ids, and forwards
 * clicks on a model's hitbox (ModelEngine's own BaseEntityInteractEvent) to the shop, because those clicks
 * do not always arrive as a vanilla PlayerInteractEntityEvent.
 */
public final class ModelEngineHook {
    private static final String API = "com.ticxo.modelengine.api.ModelEngineAPI";
    private static final String INTERACT_EVENT = "com.ticxo.modelengine.api.events.BaseEntityInteractEvent";

    private final VillagerMerchants plugin;
    private Method byEntity;
    private Method byUuid;
    private boolean ready;

    public ModelEngineHook(VillagerMerchants plugin) {
        this.plugin = plugin;
        if (!Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) return;
        try {
            Class<?> api = Class.forName(API);
            try { byEntity = api.getMethod("getModeledEntity", Entity.class); } catch (NoSuchMethodException ignored) { /* optional */ }
            try { byUuid = api.getMethod("getModeledEntity", UUID.class); } catch (NoSuchMethodException ignored) { /* optional */ }
            ready = byEntity != null || byUuid != null;
            if (ready) plugin.getLogger().info("ModelEngine found: shops can be linked to ModelEngine entities.");
            else plugin.getLogger().warning("ModelEngine is installed but getModeledEntity() was not found; /vm link will not see its models.");
        } catch (Throwable t) {
            plugin.getLogger().warning("ModelEngine is installed but its API could not be used (" + t + ").");
        }
    }

    public boolean available() { return ready; }

    private Object modeled(Entity e) {
        try {
            return byEntity != null ? byEntity.invoke(null, e) : byUuid.invoke(null, e.getUniqueId());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Comma separated model ids on this entity, or null if it carries no ModelEngine model. */
    public String modelsOf(Entity e) {
        if (!ready) return null;
        Object me = modeled(e);
        if (me == null) return null;
        try {
            Object models = me.getClass().getMethod("getModels").invoke(me);
            if (models instanceof Map<?, ?> m && !m.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Object k : m.keySet()) { if (sb.length() > 0) sb.append(','); sb.append(k); }
                return sb.toString();
            }
        } catch (Throwable ignored) { /* modeled but unreadable: treat as unmodeled */ }
        return null;
    }

    /** False only if ModelEngine says this model id does not exist (true when it cannot tell). */
    public boolean modelExists(String id) {
        if (!ready) return false;
        try {
            Object bp = Class.forName(API).getMethod("getBlueprint", String.class).invoke(null, id);
            return bp != null;
        } catch (NoSuchMethodException e) {
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Turns a plain entity into a ModelEngine entity wearing these comma separated model ids, hiding the base entity. */
    public boolean applyNewModels(Entity base, String modelIds) {
        if (!ready || modelIds == null || modelIds.isBlank()) return false;
        try {
            Class<?> api = Class.forName(API);
            Object me = api.getMethod("createModeledEntity", Entity.class).invoke(null, base);
            Method create = api.getMethod("createActiveModel", String.class);
            for (String id : modelIds.split(",")) {
                if (id.isBlank()) continue;
                Object model = create.invoke(null, id.trim());
                for (Method add : me.getClass().getMethods()) {
                    if (add.getName().equals("addModel") && add.getParameterCount() == 2) { add.invoke(me, model, true); break; }
                }
            }
            Method hide = find(me, "setBaseEntityVisible", 1);
            if (hide != null) hide.invoke(me, false);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not put ModelEngine model '" + modelIds + "' on an entity: " + t);
            return false;
        }
    }

    private static final String[] BEHAVIOR_TYPES = {
            "com.ticxo.modelengine.api.model.bone.behavior.BoneBehaviorTypes",
            "com.ticxo.modelengine.api.model.bone.type.BoneBehaviorTypes",
            "com.ticxo.modelengine.api.model.bone.BoneBehaviorTypes"};

    private static Method find(Object target, String name, int params) {
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == params) { try { m.setAccessible(true); } catch (Throwable ignored) { } return m; }
        }
        return null;
    }

    /**
     * Shows {@code text} on the model's own "nametag" bone (ModelEngine's NAMETAG bone behavior), so the tag sits
     * exactly where the modeler put it. Returns false when the model has no such bone or the API is not what we
     * expect; the caller then falls back to a floating text display.
     * {@code text == null} hides the bone's tag.
     */
    public boolean setNametag(Entity e, net.kyori.adventure.text.Component text) {
        if (!ready) return false;
        Object me = modeled(e);
        if (me == null) return false;
        try {
            Object type = null;
            for (String cn : BEHAVIOR_TYPES) {
                try { type = Class.forName(cn).getField("NAMETAG").get(null); break; } catch (Throwable ignored) { /* next */ }
            }
            if (type == null) return false;
            Object models = me.getClass().getMethod("getModels").invoke(me);
            if (!(models instanceof Map<?, ?> m)) return false;
            boolean done = false;
            for (Object model : m.values()) {
                Method getBone = find(model, "getBone", 1);
                if (getBone == null) continue;
                Object bone = getBone.invoke(model, "nametag");
                if (bone instanceof java.util.Optional<?> o) bone = o.orElse(null);
                if (bone == null) continue;
                Method getBehavior = find(bone, "getBoneBehavior", 1);
                if (getBehavior == null) continue;
                Object behavior = getBehavior.invoke(bone, type);
                if (behavior instanceof java.util.Optional<?> o) behavior = o.orElse(null);
                if (behavior == null) continue;
                Method setVisible = find(behavior, "setVisible", 1);
                Method setComponent = find(behavior, "setComponent", 1);
                if (setComponent == null) continue;
                if (text != null) setComponent.invoke(behavior, text);
                if (setVisible != null) setVisible.invoke(behavior, text != null);
                done = true;
            }
            return done;
        } catch (Throwable t) {
            plugin.getLogger().fine("ModelEngine nametag bone: " + t);
            return false;
        }
    }

    /**
     * Replaces the models on this entity with the given comma separated model ids.
     * Does nothing (returns false) if the entity is not modeled or the API differs from what we expect.
     */
    public boolean setModels(Entity e, String modelIds) {
        if (!ready || modelIds == null || modelIds.isBlank()) return false;
        if (modelIds.equals(modelsOf(e))) return true;   // already wearing it
        Object me = modeled(e);
        if (me == null) return false;
        try {
            Class<?> api = Class.forName(API);
            Method create = api.getMethod("createActiveModel", String.class);
            Object current = me.getClass().getMethod("getModels").invoke(me);
            if (current instanceof Map<?, ?> m) {
                Method remove = me.getClass().getMethod("removeModel", String.class);
                for (Object k : new java.util.ArrayList<>(m.keySet())) remove.invoke(me, String.valueOf(k));
            }
            for (String id : modelIds.split(",")) {
                if (id.isBlank()) continue;
                Object model = create.invoke(null, id.trim());
                for (Method add : me.getClass().getMethods()) {   // addModel(ActiveModel, boolean) in R4
                    if (add.getName().equals("addModel") && add.getParameterCount() == 2) { add.invoke(me, model, true); break; }
                }
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not change ModelEngine model to '" + modelIds + "': " + t);
            return false;
        }
    }

    /**
     * Registers a listener for clicks on model hitboxes. {@code onClick} gets the player and the base entity.
     * Nothing happens (apart from a log line) if ModelEngine's event class is not what we expect.
     */
    @SuppressWarnings("unchecked")
    public void registerInteractBridge(BiConsumer<Player, Entity> onClick, java.util.function.Predicate<Entity> isShop) {
        if (!ready) return;
        try {
            Class<? extends Event> type = (Class<? extends Event>) Class.forName(INTERACT_EVENT);
            Method getPlayer = type.getMethod("getPlayer");
            Method getBase = type.getMethod("getBaseEntity");
            Method getAction = null;
            try { getAction = type.getMethod("getAction"); } catch (NoSuchMethodException ignored) { /* optional */ }
            final Method action = getAction;
            Bukkit.getPluginManager().registerEvent(type, new Listener() {}, EventPriority.HIGH, (l, ev) -> {
                try {
                    if (!type.isInstance(ev)) return;
                    if (action != null && String.valueOf(action.invoke(ev)).toUpperCase().contains("ATTACK")) return;
                    Object base = getBase.invoke(ev);
                    Object original = base.getClass().getMethod("getOriginal").invoke(base);
                    if (!(original instanceof Entity ent) || !isShop.test(ent)) return;
                    if (ev instanceof Cancellable c) c.setCancelled(true);
                    onClick.accept((Player) getPlayer.invoke(ev), ent);
                } catch (Throwable t) {
                    plugin.getLogger().fine("ModelEngine interact bridge: " + t);
                }
            }, plugin, false);
        } catch (Throwable t) {
            plugin.getLogger().info("ModelEngine click bridge not active (" + t + "); vanilla entity clicks still work.");
        }
    }
}
