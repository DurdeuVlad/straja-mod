package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FUN-11 durable docket. {@link #docket} is insertion-ordered by filing so a
 * prisoner's place in line never silently expires; {@link #paroles} tracks
 * live parole conditions per released prisoner for server-side enforcement.
 */
public class ParoleStore {
    public List<HearingRecord> docket = new ArrayList<>();
    /** uuid → live parole conditions. */
    public Map<String, ParoleCondition> paroles = new LinkedHashMap<>();
    public int nextHearingSeq = 1;
    /** Last enforcement sweep — parole deadlines freeze across server downtime. */
    public long lastTickAt;
    public long version;

    public HearingRecord hearing(String id) {
        if (id == null) return null;
        for (HearingRecord h : docket) {
            if (h != null && id.equalsIgnoreCase(h.id)) return h;
        }
        return null;
    }

    /** The prisoner's unresolved docket entry (FILED or HEARING), if any. */
    public HearingRecord pendingFor(String prisonerUuid) {
        if (prisonerUuid == null) return null;
        for (HearingRecord h : docket) {
            if (h != null && prisonerUuid.equals(h.prisonerUuid)
                    && ("FILED".equals(h.status) || "HEARING".equals(h.status))) return h;
        }
        return null;
    }

    /** Decided entries per prisoner — newest first — for refile cooldowns. */
    public HearingRecord lastDecisionFor(String prisonerUuid) {
        HearingRecord found = null;
        for (HearingRecord h : docket) {
            if (h != null && prisonerUuid.equals(h.prisonerUuid)
                    && ("DENIED".equals(h.status) || "PAROLED".equals(h.status)
                            || "CONDITIONAL".equals(h.status))) {
                if (found == null || h.decidedAt > found.decidedAt) found = h;
            }
        }
        return found;
    }

    public ParoleCondition parole(String uuid) {
        return uuid == null ? null : paroles.get(uuid);
    }

    public void normalize() {
        if (docket == null) docket = new ArrayList<>();
        if (paroles == null) paroles = new LinkedHashMap<>();
        docket.removeIf(h -> h == null || h.id == null || h.id.isBlank());
        paroles.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
        if (nextHearingSeq < 1) nextHearingSeq = 1;
    }

    /** Conditions attached to a PAROLED release — enforced in tick. */
    public static class ParoleCondition {
        public String hearingId = "";
        public long paroleUntil;
        public long checkInBy;
        /** ACTIVE | COMPLETED | VIOLATED */
        public String status = "ACTIVE";
        public long closedAt;
    }
}
