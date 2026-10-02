package com.dwurdy.straja.domain.model;

import java.util.List;

/** Admin-picked placement for the deposit chest, protected zone, and watched chests. */
public record StorageSetup(
        StoragePoint dest,
        StorageZone zone,
        List<StoragePoint> chests) {
    public StorageSetup {
        chests = chests == null ? List.of() : List.copyOf(chests);
    }

    public StorageSetup withDest(StoragePoint point) {
        return new StorageSetup(point, zone, chests);
    }

    public StorageSetup withZone(StorageZone z) {
        return new StorageSetup(dest, z, chests);
    }

    public StorageSetup addChest(StoragePoint point) {
        var next = new java.util.ArrayList<>(chests);
        next.add(point);
        return new StorageSetup(dest, zone, next);
    }
}
