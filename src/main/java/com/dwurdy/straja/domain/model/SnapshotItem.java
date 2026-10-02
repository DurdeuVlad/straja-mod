package com.dwurdy.straja.domain.model;

/**
 * One item line in an inventory snapshot. {@code slot} identifies position
 * ("main:12", "armor:2", "offhand:0", nested paths like "main:4>0").
 * {@code componentsTag} holds the stack's component data (serialized SNBT or
 * a digest) so evidence snapshots are tamper-evident, not just id+count.
 */
public class SnapshotItem {
    public String slot = "";
    public String itemId = "";
    public int count;
    public String componentsTag;
    /** Display name for ledger readability (optional). */
    public String name;

    public SnapshotItem() {}

    public SnapshotItem(String slot, String itemId, int count, String componentsTag, String name) {
        this.slot = slot;
        this.itemId = itemId;
        this.count = count;
        this.componentsTag = componentsTag;
        this.name = name;
    }
}
