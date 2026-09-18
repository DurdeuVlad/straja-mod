package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent incident registry and rate-limit state. */
public class IncidentStore {
    public int nextId = 1;
    public List<Incident> incidents = new ArrayList<>();
    public Map<String, Long> lastWhistleAt = new LinkedHashMap<>();
    public Map<String, Long> lastCitizenReportAt = new LinkedHashMap<>();

    public String nextIncidentId() {
        return "INC-" + String.format(java.util.Locale.ROOT, "%04d", nextId++);
    }

    public Incident find(String id) {
        if (id == null) return null;
        for (Incident incident : incidents) {
            if (incident != null && id.equals(incident.id)) return incident;
        }
        return null;
    }
}
