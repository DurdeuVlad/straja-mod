package com.dwurdy.straja.domain.model;

/**
 * A labor mining camp (AT7): bounded zone with its own exit checkpoint,
 * quartermaster desk, intake/release spawns, and a freedom-price rule.
 */
public class LaborCampRecord {
    public String id = "";
    public String name = "";
    public String dimension = "minecraft:overworld";
    public LawBounds boundary;
    /** Merchant desk inside the camp that buys mined ore. */
    public String quartermasterDeskId = "";
    /** Gate that repels inmates trying to leave. */
    public String exitCheckpointId = "";
    /** Where arrested prisoners land on intake. */
    public StoragePoint intakeSpawn;
    /** Where released players respawn/exit to. */
    public StoragePoint releaseSpawn;
    /** Dormitory respawn point inside the camp (death does not drop custody). */
    public StoragePoint dormitorySpawn;
    public FreedomPriceMode freedomMode = FreedomPriceMode.FLAT;
    /** Flat buyout in base units when mode is FLAT (0 = use TOML default). */
    public int freedomFlatPrice;
    /** Outstanding-fines multiplier when mode is FINES_MULTIPLIER (0 = TOML default). */
    public double freedomFineMultiplier;
}
