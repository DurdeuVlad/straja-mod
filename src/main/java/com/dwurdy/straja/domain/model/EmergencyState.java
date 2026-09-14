package com.dwurdy.straja.domain.model;

/**
 * §25 emergency state: a short-lived urgency call ("all hands to HQ") and a
 * sustained emergency mode (hazard pay + extra patrol rounds). Persisted; all
 * time checks are pure functions of the supplied clock reading.
 */
public class EmergencyState {

    // Urgency call — a broadcast with a TTL. Late logins still see it while live.
    public String urgencyMessage;
    public Long urgencyIssuedAt;
    public String urgencyIssuedBy;

    // Sustained emergency mode — hazard pay + required patrol rounds.
    public boolean active;
    public double payMultiplier = 1.0;
    public int requiredRounds;
    public String reason;
    public Long startedAt;
    public String startedBy;

    public boolean urgencyLive(long now, long ttlMinutes) {
        return urgencyMessage != null && urgencyIssuedAt != null
                && now - urgencyIssuedAt < ttlMinutes * 60_000L;
    }

    public void clearUrgency() {
        urgencyMessage = null;
        urgencyIssuedAt = null;
        urgencyIssuedBy = null;
    }

    public void clearEmergency() {
        active = false;
        payMultiplier = 1.0;
        requiredRounds = 0;
        reason = null;
        startedAt = null;
        startedBy = null;
    }
}
