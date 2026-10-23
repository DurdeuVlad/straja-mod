package com.dwurdy.straja.domain.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One scheduled unit operation — instrucție, patrulare or exercițiu. Members
 * sign up while it is SCHEDULED (or walk up and report while ACTIVE); the
 * server clock starts and completes it — no admin babysitting. Completion
 * awards every reported participant service blocks and moves the unit's
 * readiness by turnout.
 */
public class ArmyOperation {
    public enum Kind {
        /** Drill — the default formation exercise. */
        INSTRUCTIE(2, "Instrucție"),
        /** Patrol sweep — a longer field operation. */
        PATRULA(3, "Patrulare"),
        /** Full exercise — the biggest reward and readiness swing. */
        EXERCITIU(4, "Exercițiu");

        public final int rewardBlocks;
        public final String label;

        Kind(int rewardBlocks, String label) {
            this.rewardBlocks = rewardBlocks;
            this.label = label;
        }

        public static Kind byName(String value) {
            for (Kind kind : values()) {
                if (kind.name().equalsIgnoreCase(value == null ? "" : value.trim())) return kind;
            }
            return null;
        }
    }

    public enum Status { SCHEDULED, ACTIVE, COMPLETED, FAILED, CANCELLED }

    public String operationId = "";
    public String unitId = "";
    public Kind kind = Kind.INSTRUCTIE;
    public Status status = Status.SCHEDULED;
    /** Wall-clock window (millis): starts at {@code scheduledStart}, musters
     *  until {@code scheduledEnd}. */
    public long scheduledStart;
    public long scheduledEnd;
    /** Officer/commander/auto scheduler that created the op. */
    public String scheduledBy = "";
    /** Members who committed while SCHEDULED. */
    public Set<String> signups = new LinkedHashSet<>();
    /** Members who actually reported while ACTIVE — the turnout. */
    public Set<String> participants = new LinkedHashSet<>();
    public int awardedBlocks;
    public String resultNote = "";
    public long createdAt;
    public long completedAt;
    public long version;

    public Set<String> ensureSignups() {
        if (signups == null) signups = new LinkedHashSet<>();
        return signups;
    }

    public Set<String> ensureParticipants() {
        if (participants == null) participants = new LinkedHashSet<>();
        return participants;
    }
}
