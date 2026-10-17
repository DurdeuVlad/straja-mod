package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** A discovered prison cell: bounded interior + door + sign position. */
public class Cell {
    public String id = "";
    public String dimension = "minecraft:overworld";
    // interior bounds (inclusive)
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public int doorX, doorY, doorZ;
    public int signX, signY, signZ;
    public long createdAt;
    /** FUN-7: WEAK locks fall to the crowbar quickly; STANDARD needs cutters. */
    public String lockTier = "STANDARD";
    /** FUN-7: a sprung lock stays broken until this timestamp (trades repair). */
    public long brokenUntil;
    public String brokenByUuid = "";
    public String brokenByName = "";

    public boolean contains(double x, double y, double z) {
        return x >= minX - 1 && x <= maxX + 1 && y >= minY - 1 && y <= maxY + 1 && z >= minZ - 1 && z <= maxZ + 1;
    }
}
