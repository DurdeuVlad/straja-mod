package com.dwurdy.straja.domain.model;

/** A discovered civilian room (guard housing), bounded and protectable. */
public class Room {
    public String id = "";               // camera_<n> or commissioner-chosen
    public int order;
    public String source = "auto-discovery";
    public String dimension = "minecraft:overworld";
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int doorX, doorY, doorZ;
    public boolean hasDoor;
    public String signDimension = "";
    public int signX, signY, signZ;
    public String signFacing = "";
    public String signStatus = "";
    public long createdAt;

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX + 1 && y >= minY && y <= maxY + 1 && z >= minZ && z <= maxZ + 1;
    }

    public boolean isSign(String dimension, int x, int y, int z) {
        return !signDimension.isEmpty() && signDimension.equals(dimension)
                && signX == x && signY == y && signZ == z;
    }
}
