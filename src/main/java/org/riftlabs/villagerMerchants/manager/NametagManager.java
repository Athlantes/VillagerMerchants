package org.riftlabs.villagerMerchants.manager;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.util.Sched;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Floating name tags over shop NPCs.
 * <p>
 * Folia: a tag is an entity, so it can only be touched on the thread of the region it stands in. Every public method
 * can be called from any thread: it updates the bookkeeping maps right away and hops to the right thread for the
 * entity work. The maps are concurrent because several regions use them at once.
 */
public class NametagManager {

    private final VillagerMerchants plugin;
    private final NamespacedKey nametagKey;
    private final NamespacedKey rawFormatKey;
    private final Map<UUID, TextDisplay> activeNametags = new ConcurrentHashMap<>();
    private final Map<UUID, String> formats = new ConcurrentHashMap<>();             // raw format of each display tag
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, String> boneTags = new ConcurrentHashMap<>();            // linked bodies whose model has its own "nametag" bone
    private final Map<UUID, Entity> boneBodies = new ConcurrentHashMap<>();          // ...and the body each belongs to
    private final Map<UUID, Double> followHeights = new ConcurrentHashMap<>();       // linked bodies: tag floats this high above the feet
    private final Map<UUID, Sched.Handle> followTasks = new ConcurrentHashMap<>();
    private volatile int animationPhase = 0;

    public NametagManager(VillagerMerchants plugin) {
        this.plugin = plugin;
        this.nametagKey = new NamespacedKey(plugin, "is_vm_nametag");
        this.rawFormatKey = new NamespacedKey(plugin, "raw_format");
        startAnimationTask();
    }

    /** Safe from any thread. */
    public void spawnNametag(Entity npc, String format) {
        Sched.entity(npc, () -> spawnNow(npc, format));
    }

    /** Runs on the thread that owns {@code npc}. */
    private void spawnNow(Entity npc, String format) {
        if (!npc.isValid()) return;
        // Villagers carry the tag as a passenger. A MythicMob / ModelEngine body has a hitbox that has nothing to do with
        // how tall the model is (a passenger would sit at its feet), so its tag floats at a configured height and follows it.
        double height = plugin.getNpcs().nametagHeight(npc);
        // Best case: the model has a "nametag" bone, so ModelEngine draws the tag where the modeler placed it.
        if (height >= 0 && plugin.getConfig().getBoolean("nametag.use-model-bone", true)
                && plugin.getNpcs().modelEngine() != null && plugin.getNpcs().modelEngine().setNametag(npc, render(format))) {
            boneTags.put(npc.getUniqueId(), format);
            boneBodies.put(npc.getUniqueId(), npc);
            return;
        }
        boolean follow = height >= 0;
        Location at = follow ? npc.getLocation().add(0, height, 0) : npc.getLocation();
        TextDisplay display = (TextDisplay) npc.getWorld().spawnEntity(at, EntityType.TEXT_DISPLAY);
        display.getPersistentDataContainer().set(nametagKey, PersistentDataType.BYTE, (byte) 1);
        display.setPersistent(false); // Prevents permanent ghosting in chunks

        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(plugin.getConfig().getBoolean("nametag.see-through", false));
        display.setShadowed(plugin.getConfig().getBoolean("nametag.shadowed", true));
        display.setAlignment(TextDisplay.TextAlignment.CENTER);

        Transformation transformation = new Transformation(
                new Vector3f(0, follow ? 0f : (float) plugin.getConfig().getDouble("nametag.height", 0.3), 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1, 1, 1),
                new AxisAngle4f(0, 0, 0, 1)
        );
        display.setTransformation(transformation);

        UUID id = npc.getUniqueId();
        if (follow) {
            followHeights.put(id, height);
            Sched.Handle old = followTasks.put(id, Sched.entityTimer(npc, h -> followStep(npc, id, h), null, 2L, 2L));
            if (old != null) old.cancel();
        } else {
            npc.addPassenger(display);
        }

        display.getPersistentDataContainer().set(rawFormatKey, PersistentDataType.STRING, format);
        formats.put(id, format);
        activeNametags.put(id, display);
        updateDisplay(display, format);
    }

    /** Safe from any thread. */
    public void updateShopNametag(UUID npcUuid, String newFormat) {
        if (npcUuid == null) return;
        if (boneTags.containsKey(npcUuid)) {
            Entity npc = boneBodies.get(npcUuid);
            if (npc != null) Sched.entity(npc, () -> {
                if (plugin.getNpcs().modelEngine().setNametag(npc, render(newFormat))) boneTags.put(npcUuid, newFormat);
            });
            return;
        }
        TextDisplay display = activeNametags.get(npcUuid);
        if (display != null) {
            formats.put(npcUuid, newFormat);
            Sched.entity(display, () -> {
                display.getPersistentDataContainer().set(rawFormatKey, PersistentDataType.STRING, newFormat);
                updateDisplay(display, newFormat);
            });
        }
    }

    private net.kyori.adventure.text.Component render(String format) {
        if (format.contains("<anim_rainbow>")) {
            format = format.replace("<anim_rainbow>", "<rainbow:" + animationPhase + ">")
                    .replace("</anim_rainbow>", "</rainbow>");
        }
        return miniMessage.deserialize(format);
    }

    private void updateDisplay(TextDisplay display, String format) {
        display.text(render(format));
    }

    /**
     * Rainbow animation. This loop only decides WHO needs an update; the update itself runs on each tag's own region
     * thread, so no entity is ever touched from here.
     */
    private void startAnimationTask() {
        long period = Math.max(1, plugin.getConfig().getLong("nametag.animation-ticks", 4));
        Sched.globalTimer(() -> {
            animationPhase++;
            if (animationPhase > 100) animationPhase = 0;

            for (Map.Entry<UUID, String> bt : boneTags.entrySet()) {
                if (!bt.getValue().contains("<anim_rainbow>")) continue;
                Entity npc = boneBodies.get(bt.getKey());
                String f = bt.getValue();
                if (npc != null) Sched.entity(npc, () -> {
                    if (npc.isValid()) plugin.getNpcs().modelEngine().setNametag(npc, render(f));
                });
            }
            for (Map.Entry<UUID, String> en : formats.entrySet()) {
                String raw = en.getValue();
                if (!raw.contains("<anim_rainbow>")) continue;
                TextDisplay display = activeNametags.get(en.getKey());
                if (display != null) Sched.entity(display, () -> {
                    if (display.isValid()) updateDisplay(display, raw);
                });
            }
        }, 1L, period);
    }

    /** One follow step for a linked body's floating tag. Runs on the body's thread. */
    private void followStep(Entity npc, UUID id, Sched.Handle self) {
        Double height = followHeights.get(id);
        TextDisplay d = activeNametags.get(id);
        if (height == null || d == null) { self.cancel(); return; }   // tag was removed
        if (!npc.isValid()) { self.cancel(); return; }
        if (!d.isValid()) return;
        Location want = npc.getLocation().add(0, height, 0);
        Location now = d.getLocation();
        if (!want.getWorld().equals(now.getWorld()) || want.distanceSquared(now) > 0.0004) d.teleportAsync(want);
    }

    /** Safe from any thread. */
    public void removeNametag(UUID npcUuid) {
        if (npcUuid == null) return;
        followHeights.remove(npcUuid);
        Sched.Handle follow = followTasks.remove(npcUuid);
        if (follow != null) follow.cancel();
        formats.remove(npcUuid);
        Entity boneBody = boneBodies.remove(npcUuid);
        if (boneTags.remove(npcUuid) != null && boneBody != null) {
            Sched.entity(boneBody, () -> plugin.getNpcs().modelEngine().setNametag(boneBody, null));   // hide the bone's tag
        }
        TextDisplay display = activeNametags.remove(npcUuid);
        if (display != null) Sched.entity(display, () -> { if (display.isValid()) display.remove(); });
    }

    /**
     * Plugin shutdown. The tags are not persistent, so the server never saves them; on Paper they are also removed
     * right away. On Folia there is no thread to do that from during shutdown, and none is needed.
     */
    public void cleanup() {
        if (!Sched.isFolia()) {
            for (TextDisplay display : activeNametags.values()) {
                try { if (display.isValid()) display.remove(); } catch (Throwable ignored) {}
            }
            for (Entity body : boneBodies.values()) {
                try { plugin.getNpcs().modelEngine().setNametag(body, null); } catch (Throwable ignored) {}   // hide the bone's tag
            }
        }
        activeNametags.clear();
        followHeights.clear();
        formats.clear();
        followTasks.values().forEach(Sched.Handle::cancel);
        followTasks.clear();
        boneTags.clear();
        boneBodies.clear();
    }
}
