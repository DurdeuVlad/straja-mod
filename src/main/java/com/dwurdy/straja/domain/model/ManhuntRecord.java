package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * FUN-7: a live manhunt. Runners are everyone implicated in a break —
 * the springer and every prisoner who walked out of the opened cell.
 * The hunt ends captured (all runners gone), cooled (TTL elapsed → each
 * remaining runner converts to a standing warrant), or called off.
 */
public class ManhuntRecord {
    public static class Runner {
        public String uuid = "";
        public String name = "";
        /** True when the runner was serving a sentence — recapture adds time. */
        public boolean wasPrisoner;
    }

    public String id = "";
    public String status = "ACTIVE"; // ACTIVE | COOLED | CALLED_OFF | CAPTURED
    public List<Runner> runners = new ArrayList<>();
    /** Origin of the hunt: the broken cell / transfer site description. */
    public String originLabel = "";
    public String dimension = "minecraft:overworld";
    public String dispatchCallId = "";
    public long startedAt;
    public long lastHintAt;
    /** ACTIVE hunts cool to standing warrants at this timestamp. */
    public long coolAt;
    public String endedReason = "";

    public boolean runner(String uuid) {
        for (Runner r : runners) if (r != null && r.uuid.equals(uuid)) return true;
        return false;
    }
}
