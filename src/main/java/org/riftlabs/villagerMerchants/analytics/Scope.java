package org.riftlabs.villagerMerchants.analytics;

import java.util.UUID;

/**
 * What a dashboard covers: one shop (its transactions and ratings), or every shop an owner ever ran
 * ({@code shopId == null}). {@code label} is the plain text shown in the header.
 */
public record Scope(UUID owner, UUID shopId, String label) {
    public boolean singleShop() { return shopId != null; }
}
