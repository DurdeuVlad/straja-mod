package com.dwurdy.straja.domain.model;

/** Axis-aligned protected region; stores the two picked corners raw. */
public record StorageZone(
        String dimension,
        int ax, int ay, int az,
        int bx, int by, int bz) {
    public StorageZone {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        else dimension = dimension.trim();
    }

    public boolean contains(String dimension, int x, int y, int z) {
        if (!this.dimension.equals(dimension)) return false;
        int minX = Math.min(ax, bx), maxX = Math.max(ax, bx);
        int minY = Math.min(ay, by), maxY = Math.max(ay, by);
        int minZ = Math.min(az, bz), maxZ = Math.max(az, bz);
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public double centerX() { return (ax + bx) / 2.0; }
    public double centerY() { return (ay + by) / 2.0; }
    public double centerZ() { return (az + bz) / 2.0; }
}
