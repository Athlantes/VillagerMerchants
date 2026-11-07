package org.riftlabs.villagerMerchants.effect;

import org.bukkit.Material;

import java.util.List;

/** One purchasable effect: GUI metadata plus the particle layers it plays. {@code currency} is a currency id ("vault", "playerpoints"...). */
public record EffectDefinition(String id, String name, Material icon, double price, String currency, String permission,
                               List<String> description, List<EffectLayer> layers,
                               List<String> lockedLore) {}
