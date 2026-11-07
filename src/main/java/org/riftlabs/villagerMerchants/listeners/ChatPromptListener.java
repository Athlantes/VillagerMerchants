package org.riftlabs.villagerMerchants.listeners;

import org.riftlabs.villagerMerchants.util.Sched;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.util.Text;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Generic "type your answer in chat" prompt (directory search, adding staff). The next chat message from the
 * player is swallowed and handed to the callback on the main thread; "cancel" calls {@code onCancel}.
 */
public class ChatPromptListener implements Listener {
    private long timeoutMs() { return Math.max(5, plugin.getConfig().getLong("prompts.timeout-seconds", 60)) * 1000L; }

    private record Prompt(Consumer<String> onInput, Runnable onCancel, long expires) {}

    private final VillagerMerchants plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();

    public ChatPromptListener(VillagerMerchants plugin) { this.plugin = plugin; }

    /** @param message shown to the player; the "type cancel" hint is added automatically. */
    public void begin(Player p, Component message, Consumer<String> onInput, Runnable onCancel) {
        prompts.put(p.getUniqueId(), new Prompt(onInput, onCancel, System.currentTimeMillis() + timeoutMs()));
        p.sendMessage(message);
        plugin.msg().send(p, "prompts.cancel-hint", "cancel", plugin.msg().raw("prompts.cancel-word"));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Prompt pr = prompts.get(p.getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        String text = Text.plain(e.message()).trim();
        Sched.entityNext(p, () -> {
            if (!prompts.remove(p.getUniqueId(), pr) || !p.isOnline()) return;
            if (System.currentTimeMillis() > pr.expires()) {
                plugin.msg().send(p, "prompts.timed-out");
                pr.onCancel().run();
            } else if (text.equalsIgnoreCase(plugin.msg().raw("prompts.cancel-word")) || text.isEmpty()) {
                pr.onCancel().run();
            } else {
                pr.onInput().accept(text);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); }
}
