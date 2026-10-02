package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Authoritative law-enforcement checkpoint aggregate (LAW-001). Unlike the
 * lightweight patrol points in {@link SetupData.Checkpoint}, this is a
 * multi-dimensional physical boundary: stage boxes, doors, pushback,
 * evidence chests, and per-site rule overrides.
 * Public mutable fields — Gson round-trips through {@code JsonBackedStore}.
 */
public class LawCheckpointRecord {
    public String id = "";
    public String name = "";
    public String dimension = "minecraft:overworld";
    public boolean active = true;

    /** Stage-1 crossing box (warn/inspect); null when unconfigured. */
    public LawBounds stage1;
    /** Stage-2 crossing box (arrest line); null when unconfigured. */
    public LawBounds stage2;
    /** Deny destination + optional velocity shove; null when unconfigured. */
    public PushbackPoint pushback;

    /** Door blocks force-closed on denial. */
    public List<StoragePoint> doors = new ArrayList<>();
    /** Ordered evidence chests; seizure fills them sequentially. */
    public List<StoragePoint> evidenceChests = new ArrayList<>();

    /** Directed one-way crossing lanes (prototype gate model). */
    public List<GateLane> gates = new ArrayList<>();
    /** Dock boarding zone; null when the site has none. */
    public BoardingZone boardZone;

    public CheckpointMode mode = CheckpointMode.DENY;
    public CrossingDirection direction = CrossingDirection.BIDIRECTIONAL;

    /** Paired checkpoint id (opposite side of the same site, shared rules). */
    public String linkedCheckpointId;

    /** Site-local illegal item ids (extend the global contraband list). */
    public List<String> localIllegalItems = new ArrayList<>();
    /** Item ids explicitly allowed here despite the global list. */
    public List<String> localAllowedItems = new ArrayList<>();
    /** Players banned from crossing (UUID strings). */
    public List<String> bannedPlayerUuids = new ArrayList<>();
    /** Roles entirely barred from crossing. */
    public List<String> roleBans = new ArrayList<>();
    /** role -> item ids that role may not carry through. */
    public Map<String, List<String>> roleCarryBans = new LinkedHashMap<>();
    /** Exempt player names/UUIDs (site-local). */
    public List<String> exemptions = new ArrayList<>();

    /** Legacy banned names that could not be resolved to UUIDs on import. */
    public List<String> legacyBannedNames = new ArrayList<>();

    /**
     * Where this site's arrests deliver the prisoner: empty = the prison
     * cells; {@code "CAMP:<campId>"} routes them into a labor camp instead
     * (LAW-006). The {@code CAMP:} prefix is compared case-insensitively.
     */
    public String arrestDestination = "";

    /** Self-heals explicit {@code null}s a Gson payload may carry. */
    public void normalize() {
        if (doors == null) doors = new ArrayList<>();
        if (evidenceChests == null) evidenceChests = new ArrayList<>();
        if (gates == null) gates = new ArrayList<>();
        if (localIllegalItems == null) localIllegalItems = new ArrayList<>();
        if (localAllowedItems == null) localAllowedItems = new ArrayList<>();
        if (bannedPlayerUuids == null) bannedPlayerUuids = new ArrayList<>();
        if (roleBans == null) roleBans = new ArrayList<>();
        if (roleCarryBans == null) roleCarryBans = new LinkedHashMap<>();
        if (exemptions == null) exemptions = new ArrayList<>();
        if (legacyBannedNames == null) legacyBannedNames = new ArrayList<>();
        if (arrestDestination == null) arrestDestination = "";
        else arrestDestination = arrestDestination.trim();
        if (mode == null) mode = CheckpointMode.DENY;
        if (direction == null) direction = CrossingDirection.BIDIRECTIONAL;
    }
}
