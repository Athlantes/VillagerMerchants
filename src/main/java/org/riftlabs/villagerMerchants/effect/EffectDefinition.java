package org.riftlabs.villagerMerchants.effect;

import org.bukkit.Material;

import java.util.List;

/** One purchasable effect: GUI metadata plus the particle layers it plays. */
public record EffectDefinition(String id, String name, Material icon, double price, String permission,
                               List<String> description, List<EffectLayer> layers,
                               List<String> lockedLore) {}
