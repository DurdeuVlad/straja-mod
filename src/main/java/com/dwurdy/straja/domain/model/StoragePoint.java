package com.dwurdy.straja.domain.model;

/** A resolved block position in a dimension. */
public record StoragePoint(String dimension, int x, int y, int z) {
    public StoragePoint {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        else dimension = dimension.trim();
    }

    public String key() {
        return dimension + "|" + x + "," + y + "," + z;
    }
}
