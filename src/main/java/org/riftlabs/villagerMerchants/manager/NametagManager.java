package org.riftlabs.villagerMerchants.manager;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NametagManager {

    private final VillagerMerchants plugin;
    private final NamespacedKey nametagKey;
    private final Map<UUID, TextDisplay> activeNametags = new HashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, String> boneTags = new HashMap<>();        // linked bodies whose model has its own "nametag" bone
    private final Map<UUID, Double> followHeights = new HashMap<>();   // linked bodies: tag floats this high above the feet
    private int animationPhase = 0;

    public NametagManager(VillagerMerchants plugin) {
        this.plugin = plugin;
        this.nametagKey = new NamespacedKey(plugin, "is_vm_nametag");
        startAnimationTask();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::followLinked, 2L, 2L);
    }

    public void spawnNametag(Entity npc, String format) {
        // Villagers carry the tag as a passenger. A MythicMob / ModelEngine body has a hitbox that has nothing to do with
        // how tall the model is (a passenger would sit at its feet), so its tag floats at a configured height and follows it.
        double height = plugin.getNpcs().nametagHeight(npc);
        // Best case: the model has a "nametag" bone, so ModelEngine draws the tag where the modeler placed it.
        if (height >= 0 && plugin.getConfig().getBoolean("nametag.use-model-bone", true)
                && plugin.getNpcs().modelEngine() != null && plugin.getNpcs().modelEngine().setNametag(npc, render(format))) {
            boneTags.put(npc.getUniqueId(), format);
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

        if (follow) followHeights.put(npc.getUniqueId(), height); else npc.addPassenger(display);

        display.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "raw_format"),
                PersistentDataType.STRING,
                format
        );

        activeNametags.put(npc.getUniqueId(), display);
        updateDisplay(display, format);
    }

    public void updateShopNametag(UUID npcUuid, String newFormat) {
        if (npcUuid != null && boneTags.containsKey(npcUuid)) {
            Entity npc = Bukkit.getEntity(npcUuid);
            if (npc != null && plugin.getNpcs().modelEngine().setNametag(npc, render(newFormat))) boneTags.put(npcUuid, newFormat);
            return;
        }
        TextDisplay display = activeNametags.get(npcUuid);
        if (display != null) {
            display.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, "raw_format"),
                    PersistentDataType.STRING,
                    newFormat
            );
            updateDisplay(display, newFormat);
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

    private void startAnimationTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            animationPhase++;
            if (animationPhase > 100) animationPhase = 0;

            for (Map.Entry<UUID, String> bt : boneTags.entrySet()) {
                if (bt.getValue().contains("<anim_rainbow>")) {
                    UUID id = bt.getKey(); String f = bt.getValue();
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        Entity npc = Bukkit.getEntity(id);
                        if (npc != null) plugin.getNpcs().modelEngine().setNametag(npc, render(f));
                    });
                }
            }
            for (TextDisplay display : activeNametags.values()) {
                if (display.isValid()) {
                    String rawFormat = display.getPersistentDataContainer().get(
                            new NamespacedKey(plugin, "raw_format"), PersistentDataType.STRING);
                    if (rawFormat != null && rawFormat.contains("<anim_rainbow>")) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> updateDisplay(display, rawFormat));
                    }
                }
            }
        }, 0L, Math.max(1, plugin.getConfig().getLong("nametag.animation-ticks", 4)));
    }

    private void followLinked() {
        for (Map.Entry<UUID, Double> en : followHeights.entrySet()) {
            Entity npc = Bukkit.getEntity(en.getKey());
            TextDisplay d = activeNametags.get(en.getKey());
            if (npc == null || d == null || !d.isValid()) continue;
            Location want = npc.getLocation().add(0, en.getValue(), 0);
            Location now = d.getLocation();
            if (!want.getWorld().equals(now.getWorld()) || want.distanceSquared(now) > 0.0004) d.teleport(want);
        }
    }

    public void removeNametag(UUID npcUuid) {
        followHeights.remove(npcUuid);
        if (boneTags.remove(npcUuid) != null) {
            Entity npc = Bukkit.getEntity(npcUuid);
            if (npc != null) plugin.getNpcs().modelEngine().setNametag(npc, null);   // hide the bone's tag
        }
        TextDisplay display = activeNametags.remove(npcUuid);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    public void cleanup() {
        for (TextDisplay display : activeNametags.values()) {
            if (display.isValid()) display.remove();
        }
        activeNametags.clear();
        followHeights.clear();
        for (UUID id : new java.util.ArrayList<>(boneTags.keySet())) removeNametag(id);
    }
}