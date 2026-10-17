package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class ReputationStore {
    public int nextId = 1;
    public Map<String, ReputationState> states = new LinkedHashMap<>();
    public Map<String, ReputationEvent> events = new LinkedHashMap<>();
    public Map<String, String> idempotency = new LinkedHashMap<>();
    public Map<String, Long> recentHostileDamageAt = new LinkedHashMap<>();

    public String nextEventId() {
        return "REP-" + String.format(java.util.Locale.ROOT, "%05d", nextId++);
    }
    /** Lazily re-creates {@code states} after a corrupt or partial read. */
    public Map<String, ReputationState> ensureStates() {
        if (states == null) states = new LinkedHashMap<>();
        return states;
    }

    /** Lazily re-creates {@code events} after a corrupt or partial read. */
    public Map<String, ReputationEvent> ensureEvents() {
        if (events == null) events = new LinkedHashMap<>();
        return events;
    }

    /** Lazily re-creates {@code idempotency} after a corrupt or partial read. */
    public Map<String, String> ensureIdempotency() {
        if (idempotency == null) idempotency = new LinkedHashMap<>();
        return idempotency;
    }

    /** Lazily re-creates {@code recentHostileDamageAt} after a corrupt or partial read. */
    public Map<String, Long> ensureRecentHostileDamageAt() {
        if (recentHostileDamageAt == null) recentHostileDamageAt = new LinkedHashMap<>();
        return recentHostileDamageAt;
    }

}
