package com.dwurdy.straja.domain.model;

import java.util.List;

/**
 * One physically confiscated stack (custody seizure). Unlike
 * {@link SnapshotItem} — which records every nested row for the ledger — a
 * seized stack represents the top-level stack that was removed from the
 * player, preserving its full serialized component data ({@code snbt}) so
 * evidence storage and locker restoration never lose item identity.
 * {@code containedIds} lists every item id reachable inside the stack
 * (containers, bundles, capability contents) for contraband classification.
 */
public record SeizedStack(String slotPath, String itemId, int count, String snbt,
                          List<String> containedIds) {
    public SeizedStack {
        slotPath = slotPath == null ? "" : slotPath;
        itemId = itemId == null ? "" : itemId;
        containedIds = containedIds == null ? List.of() : List.copyOf(containedIds);
    }
}
