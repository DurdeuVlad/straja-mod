package com.dwurdy.straja.domain.model;

/**
 * FUN-9: dig progress on one cell block — swings accumulate until the
 * block gives. Persisted so a tunnel survives restarts; a guard filling
 * the spot or finding it on inspection clears the record and earns the
 * digger added time.
 */
public class TunnelRecord {
    public String id = "";
    public String cellId = "";
    public String dimension = "minecraft:overworld";
    public int x, y, z;
    public String diggerUuid = "";
    public String diggerName = "";
    public int progress;
    public int required;
    public long startedAt;
    public long lastDigAt;
    /** Set when a guard finds the tunnel — pending fill clears it. */
    public long discoveredAt;
}
