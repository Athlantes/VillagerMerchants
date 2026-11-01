package org.riftlabs.villagerMerchants.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.util.CurrencyToken;

/** Shows offline earnings when a seller logs in. */
public class JoinListener implements Listener {
    private final VillagerMerchants plugin;

    public JoinListener(VillagerMerchants plugin) { this.plugin = plugin; }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        CurrencyToken.scrub(e.getPlayer());
        plugin.getTransactions().onJoin(e.getPlayer());
    }
}
