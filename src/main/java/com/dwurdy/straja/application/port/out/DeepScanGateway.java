package com.dwurdy.straja.application.port.out;

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
}
