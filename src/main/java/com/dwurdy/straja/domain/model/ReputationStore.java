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
}
