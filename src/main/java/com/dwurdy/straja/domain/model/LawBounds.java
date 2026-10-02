package com.dwurdy.straja.domain.model;

/** Inclusive axis-aligned region used by law-enforcement spatial bounds. */
public record LawBounds(
        String dimension,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ) {

    public LawBounds {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        else dimension = dimension.trim();
        if (minX > maxX) { int t = minX; minX = maxX; maxX = t; }
        if (minY > maxY) { int t = minY; minY = maxY; maxY = t; }
        if (minZ > maxZ) { int t = minZ; minZ = maxZ; maxZ = t; }
    }

    /** Builds a bounds box from two raw corners (order-independent). */
    public static LawBounds of(String dimension, int ax, int ay, int az, int bx, int by, int bz) {
        return new LawBounds(dimension, ax, ay, az, bx, by, bz);
    }

    public boolean contains(String dimension, int x, int y, int z) {
        return this.dimension.equals(dimension)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
