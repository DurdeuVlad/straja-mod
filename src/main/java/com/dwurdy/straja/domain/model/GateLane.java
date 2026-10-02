package com.dwurdy.straja.domain.model;

/**
 * A one-way crossing lane: a 2D segment a→b drawn across a corridor plus
 * {@code fromX}/{@code fromZ}, a point on the side travelers legitimately
 * come FROM. Crossing the segment starting on the wrong side is a denial;
 * the teleport target is the movement origin, never {@code from} (an exit
 * violation pushed to {@code from} would deliver the violator to their
 * destination). Pure geometry — no Minecraft deps.
 */
public record GateLane(
        String dimension,
        double ax, double az,
        double bx, double bz,
        double fromX, double fromZ) {

    public GateLane {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        else dimension = dimension.trim();
    }

    /** Which side of the directed a→b line a point sits on (0 = on the line). */
    public double sideOf(double x, double z) {
        return (bx - ax) * (z - az) - (bz - az) * (x - ax);
    }

    /** Strict 2D segment intersection between movement p→q and this lane. */
    public boolean crosses(double px, double pz, double qx, double qz) {
        double d1 = (qx - px) * (az - pz) - (qz - pz) * (ax - px);
        double d2 = (qx - px) * (bz - pz) - (qz - pz) * (bx - px);
        double d3 = (bz - az) * (px - ax) - (bx - ax) * (pz - az);
        double d4 = (bz - az) * (qx - ax) - (bx - ax) * (qz - az);
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0));
    }

    /** Movement-segment bbox vs lane bbox with {@code margin} blocks of slack. */
    public boolean near(double px, double pz, double qx, double qz, double margin) {
        double lx = Math.min(px, qx), hx = Math.max(px, qx);
        double lz = Math.min(pz, qz), hz = Math.max(pz, qz);
        double gx1 = Math.min(ax, bx), gx2 = Math.max(ax, bx);
        double gz1 = Math.min(az, bz), gz2 = Math.max(az, bz);
        return !(hx < gx1 - margin || lx > gx2 + margin || hz < gz1 - margin || lz > gz2 + margin);
    }

    /** True when the crossing origin is on the wrong (denied) side. */
    public boolean wrongWay(double px, double pz) {
        double origin = sideOf(px, pz);
        if (origin == 0) return false; // started exactly on the line — ambiguous
        double allowed = sideOf(fromX, fromZ);
        return (origin > 0) != (allowed > 0);
    }
}
