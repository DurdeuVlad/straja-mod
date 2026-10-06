package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.SeizedStack;
import com.dwurdy.straja.domain.model.SnapshotItem;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port: recursively enumerates every item a player carries —
 * main+hotbar+armor+offhand, then inside any container item (vanilla
 * CONTAINER component, bundles, ItemHandler capability, NBT fallback),
 * depth-limited. The returned list IS the tamper-evident inventory snapshot
 * (AT4); contraband detection is the domain filtering this list by item id.
 * Offline players yield an empty list.
 */
public interface DeepScanGateway {
    List<SnapshotItem> deepScan(UUID playerUuid);

    /**
     * Physically confiscates every carried stack — main+hotbar+armor+offhand
     * and equipped Curios when present — returning each stack with its full
     * serialized component data and the set of item ids reachable inside it.
     * Nested contents travel inside the parent's SNBT, so nothing is lost.
     */
    default List<SeizedStack> seizeAll(UUID playerUuid) {
        return List.of();
    }

    /**
     * Removes and returns the stack living at one deepScan slot path —
     * {@code craft:N}, {@code cursor:0}, {@code curios:N}, or a top-level
     * {@code main:N}/{@code armor:N}/{@code offhand:N}. Nested paths address
     * their top-level parent (the whole container is lifted). Null when the
     * slot can't be addressed or holds nothing.
     */
    default SeizedStack seizeAt(UUID playerUuid, String slotPath) {
        return null;
    }
}
