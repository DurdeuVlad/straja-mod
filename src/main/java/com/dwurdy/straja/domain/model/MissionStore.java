package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mission aggregate: missions, reusable drafts, issuer reward budgets. */
public class MissionStore {
    public int nextId = 1;
    public List<Mission> missions = new ArrayList<>();
    /** issuerKey (uuid or normalized name) -> draft */
    public Map<String, MissionDraft> drafts = new LinkedHashMap<>();
    /** "dayWindow:issuerKey" -> coins reserved that day */
    public Map<String, Integer> rewardBudgets = new LinkedHashMap<>();

    public Mission find(String id) {
        for (Mission m : missions) if (m.id.equals(id)) return m;
        return null;
    }

    public String nextMissionId() {
        return String.valueOf(nextId++);
    }
}
