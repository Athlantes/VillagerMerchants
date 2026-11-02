package org.riftlabs.villagerMerchants.model;

/** Things a staff member can be allowed to do in somebody else's shop. */
public enum Perm {
    /** Open the shop storage (restock, and take what customers paid). */
    STORAGE,
    /** Edit trades and prices. */
    TRADES,
    /** Start and stop sales. */
    SALES,
    /** See the shop's sales numbers. */
    STATS,
    /** Pay for rent extensions and featured listings (from their own balance). */
    BILLING
}
