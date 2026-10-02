package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One crossing decision in the permanent inspection ledger (AT4). The full
 * inventory snapshot is taken before any mutation so the record is an exact
 * accountability trail.
 */
public class InspectionLedgerEntry {
    public String id = "";
    public long timestamp;
    public String checkpointId = "";
    public String playerUuid = "";
    public String playerName = "";
    public CrossingDirection direction = CrossingDirection.INCOMING;
    public CrossingOutcome outcome = CrossingOutcome.PASS;
    /** Found contraband: "count x itemId" lines or itemId keys. */
    public List<String> contrabandSummary = new ArrayList<>();
    /** Full inventory at crossing time: hotbar+main+armor+offhand+nested. */
    public List<SnapshotItem> inventorySnapshot = new ArrayList<>();
    /** Free-text detail (reason, site notes). */
    public String detail = "";

    public InspectionLedgerEntry() {}

    public InspectionLedgerEntry(String id, long timestamp, String checkpointId,
                                 String playerUuid, String playerName,
                                 CrossingDirection direction, CrossingOutcome outcome) {
        this.id = id;
        this.timestamp = timestamp;
        this.checkpointId = checkpointId;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.direction = direction;
        this.outcome = outcome;
    }
}
