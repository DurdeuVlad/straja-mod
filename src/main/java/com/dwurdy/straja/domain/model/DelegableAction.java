package com.dwurdy.straja.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The request queues that can be delegated. Each declares which delegation
 * modes are legal and what a timeout presumes — rejections and contested
 * judgments always stay human.
 */
public enum DelegableAction {
    /** Trades (Zilier) work petitions — standing orders filter on profession. */
    JOIN_PETITION("Cereri de muncă", true, "APPROVED"),
    /** Promotion petitions — standing orders filter on target grade. */
    PROMOTION_PETITION("Cereri de avansare", true, "APPROVED"),
    /** Commissioner audience requests — a timeout dismisses, never resolves. */
    AUDIENCE_REQUEST("Cereri de audiență", false, "DISMISSED"),
    /** Weekly activity report reviews — standing orders accept everything. */
    REPORT_REVIEW("Rapoarte de activitate", true, "ACCEPTED"),
    /** Complaint case reviews — judgment and rewards stay human. */
    COMPLAINT_REVIEW("Dosare de plângere", false, null),
    /** Fine appeals — voiding money on a timer stays human. */
    FINE_APPEAL("Apeluri de amendă", false, null),
    /** FUN-11 parole hearings — standing orders only for CLEAN cases
     *  (no victim complainants, still-eligible record); contested stays human. */
    PAROLE_HEARING("Ședințe de eliberare", true, null);

    private final String displayName;
    private final boolean standingOrders;
    private final String timeoutOutcome;

    DelegableAction(String displayName, boolean standingOrders, String timeoutOutcome) {
        this.displayName = displayName;
        this.standingOrders = standingOrders;
        this.timeoutOutcome = timeoutOutcome;
    }

    public String displayName() { return displayName; }
    public boolean allowsStandingOrders() { return standingOrders; }

    /** Outcome a {@link DelegationMode#TIMEOUT} policy presumes, or null when
     *  timeouts are not allowed for this queue. */
    public String timeoutOutcome() { return timeoutOutcome; }

    public boolean allows(DelegationMode mode) {
        if (mode == null) return false;
        return switch (mode) {
            case MANUAL, DIGEST -> true;
            case STANDING_ORDER -> standingOrders;
            case TIMEOUT -> timeoutOutcome != null;
        };
    }

    public Set<DelegationMode> allowedModes() {
        Set<DelegationMode> modes = EnumSet.of(DelegationMode.MANUAL, DelegationMode.DIGEST);
        if (standingOrders) modes.add(DelegationMode.STANDING_ORDER);
        if (timeoutOutcome != null) modes.add(DelegationMode.TIMEOUT);
        return modes;
    }

    public static DelegableAction of(String name) {
        if (name == null) return null;
        try {
            return DelegableAction.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
