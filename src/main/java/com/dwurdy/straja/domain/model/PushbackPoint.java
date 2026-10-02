package com.dwurdy.straja.domain.model;

/**
 * Where a denied crossing sends the player: destination plus an optional
 * velocity shove (prototype teleported to a yaw-faced deny target).
 */
public record PushbackPoint(
        String dimension,
        double x, double y, double z,
        float yaw,
        double vx, double vy, double vz) {

    public PushbackPoint {
        if (dimension == null || dimension.isBlank()) dimension = "minecraft:overworld";
        else dimension = dimension.trim();
    }

    /** No-velocity variant. */
    public static PushbackPoint at(String dimension, double x, double y, double z, float yaw) {
        return new PushbackPoint(dimension, x, y, z, yaw, 0.0, 0.0, 0.0);
    }
}
