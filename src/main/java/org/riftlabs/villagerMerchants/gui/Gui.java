package org.riftlabs.villagerMerchants.gui;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.riftlabs.villagerMerchants.VillagerMerchants;
import org.riftlabs.villagerMerchants.util.Text;

/** Buttons shared by every menu. Names come from the "gui.*" keys of the language file, icons from gui.materials in config.yml. */
final class Gui {
    private Gui() {}

    static ItemStack back(VillagerMerchants plugin) {
        return plugin.msg().icon(plugin.material("back", Material.OAK_DOOR), "gui.back");
    }

    static ItemStack close(VillagerMerchants plugin) {
        return plugin.msg().icon(plugin.material("close", Material.BARRIER), "gui.close");
    }

    static ItemStack previousPage(VillagerMerchants plugin) {
        return plugin.msg().icon(plugin.material("previous-page", Material.ARROW), "gui.previous-page");
    }

    static ItemStack nextPage(VillagerMerchants plugin) {
        return plugin.msg().icon(plugin.material("next-page", Material.ARROW), "gui.next-page");
    }

    /** Page buttons that also show "Page x / y" in their lore. */
    static ItemStack previousPage(VillagerMerchants plugin, int page, int pages) {
        return plugin.msg().icon(plugin.material("previous-page", Material.ARROW), "gui.previous-page-numbered",
                "page", page, "pages", pages);
    }

    static ItemStack nextPage(VillagerMerchants plugin, int page, int pages) {
        return plugin.msg().icon(plugin.material("next-page", Material.ARROW), "gui.next-page-numbered",
                "page", page, "pages", pages);
    }

    /** The blank pane used to fill unused slots. */
    static ItemStack filler(VillagerMerchants plugin) {
        return Text.icon(plugin.material("filler", Material.BLACK_STAINED_GLASS_PANE), " ");
    }
}
