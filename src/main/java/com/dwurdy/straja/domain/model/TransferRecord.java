package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * FUN-8: a prisoner transfer ride — durable ordered state with route,
 * escorts, and outcome. The prisoner is walked, not teleported: an escort
 * takes the chain, risk zones open escape windows, delivery settles once.
 */
public class TransferRecord {
    public String id = "";
    public String prisonerUuid = "";
    public String prisonerName = "";
    public String sentenceId = "";

    public String originLabel = "";
    public String originDimension = "minecraft:overworld";
    public double originX, originY, originZ;
    /** Custody status at order time — restored when a ride returns home. */
    public String originStatus = "IN_CELL";

    public String destCampId = "";
    public String destLabel = "";
    public String destDimension = "minecraft:overworld";
    public double destX, destY, destZ;

    /** ORDERED, IN_PROGRESS, DELIVERED, BROKEN, RETURNED, CANCELLED. */
    public String status = "ORDERED";
    public String dispatchCallId = "";
    public String orderedByUuid = "";
    public String orderedByName = "";
    public String orderReason = "";

    public List<Escort> escorts = new ArrayList<>();
    public long orderedAt, startedAt, settledAt, endedAt;
    public String endedReason = "";
    /** Escort pickup deadline — an unclaimed ride returns the prisoner. */
    public long escortDeadlineAt;
    /** Last risk zone the prisoner was warned about — dedupes the cue. */
    public String lastZoneId = "";
    /** Ride distance estimate in blocks, captured at order for the budget. */
    public double routeBlocks;

    public boolean isLive() {
        return "ORDERED".equals(status) || "IN_PROGRESS".equals(status);
    }

    public Escort escort(String uuid) {
        if (uuid == null) return null;
        for (Escort e : escorts) {
            if (e != null && uuid.equalsIgnoreCase(e.uuid)) return e;
        }
        return null;
    }

    /** Lead escort — the chain holder; first in the list. */
    public Escort lead() {
        return escorts.isEmpty() ? null : escorts.get(0);
    }

    public static class Escort {
        public String uuid = "";
        public String name = "";
        public long acceptedAt;
        public boolean paid;
    }
}
