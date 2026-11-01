package org.riftlabs.villagerMerchants.manager;

public enum UpgradeType {
    SLOTS("slots"), TAX("tax"), DURATION("duration");

    public final String key;

    UpgradeType(String key) { this.key = key; }
}
