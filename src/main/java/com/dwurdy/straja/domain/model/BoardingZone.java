package com.dwurdy.straja.domain.model;

/**
 * A dock rectangle (XZ box + vertical tolerance): players mounting a boat or
 * raft inside it are checked on the spot so they can't skip the land-side
 * checkpoint mid-route. Clean riders receive a boarding stamp consumed by
 * the linked checkpoint's gate.
 */
public record BoardingZone(
        String dimension,
        double x1, double z1,
        double x2, double z2,
        double y) {

    public BoardingZone {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        else dimension = dimension.trim();
    }

    private static final double Y_TOLERANCE = 6.0;

    public boolean contains(String dimension, double x, double py, double z) {
        if (!this.dimension.equals(dimension)) return false;
        double lx = Math.min(x1, x2), hx = Math.max(x1, x2);
        double lz = Math.min(z1, z2), hz = Math.max(z1, z2);
        if (x < lx || x > hx || z < lz || z > hz) return false;
        return Math.abs(py - y) <= Y_TOLERANCE;
    }
}
