package org.riftlabs.villagerMerchants.manager;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
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
    private int animationPhase = 0;

    public NametagManager(VillagerMerchants plugin) {
        this.plugin = plugin;
        this.nametagKey = new NamespacedKey(plugin, "is_vm_nametag");
        startAnimationTask();
    }

    public void spawnNametag(Villager npc, String format) {
        npc.setCustomNameVisible(false);

        TextDisplay display = (TextDisplay) npc.getWorld().spawnEntity(npc.getLocation(), EntityType.TEXT_DISPLAY);
        display.getPersistentDataContainer().set(nametagKey, PersistentDataType.BYTE, (byte) 1);
        display.setPersistent(false); // Prevents permanent ghosting in chunks

        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(false);
        display.setShadowed(true);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);

        Transformation transformation = new Transformation(
                new Vector3f(0, 0.3f, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(1, 1, 1),
                new AxisAngle4f(0, 0, 0, 1)
        );
        display.setTransformation(transformation);

        npc.addPassenger(display);

        display.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "raw_format"),
                PersistentDataType.STRING,
                format
        );

        activeNametags.put(npc.getUniqueId(), display);
        updateDisplay(display, format);
    }

    public void updateShopNametag(UUID npcUuid, String newFormat) {
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

    private void updateDisplay(TextDisplay display, String format) {
        if (format.contains("<anim_rainbow>")) {
            format = format.replace("<anim_rainbow>", "<rainbow:" + animationPhase + ">")
                    .replace("</anim_rainbow>", "</rainbow>");
        }
        display.text(miniMessage.deserialize(format));
    }

    private void startAnimationTask() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            animationPhase++;
            if (animationPhase > 100) animationPhase = 0;

            for (TextDisplay display : activeNametags.values()) {
                if (display.isValid()) {
                    String rawFormat = display.getPersistentDataContainer().get(
                            new NamespacedKey(plugin, "raw_format"), PersistentDataType.STRING);
                    if (rawFormat != null && rawFormat.contains("<anim_rainbow>")) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> updateDisplay(display, rawFormat));
                    }
                }
            }
        }, 0L, 4L);
    }

    public void removeNametag(UUID npcUuid) {
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
    }
}