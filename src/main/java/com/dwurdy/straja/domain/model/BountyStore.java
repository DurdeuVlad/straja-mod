package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BountyStore {
    public int nextId = 1;
    public List<BountyRecord> records = new ArrayList<>();
    /** targetUuid -> surrender flag expiry (epoch ms). A bountied player's
     *  voluntary give-up; consumed by the next restraint application. */
    public Map<String, Long> surrenders = new LinkedHashMap<>();

    public String nextBountyId() {
        return "BNT-" + String.format(java.util.Locale.ROOT, "%04d", nextId++);
    }

    public BountyRecord find(String id) {
        if (id == null) return null;
        for (BountyRecord record : records) {
            if (record != null && id.equals(record.id)) return record;
        }
        return null;
    }

    public BountyRecord activeFor(String targetUuidOrName) {
        if (targetUuidOrName == null || targetUuidOrName.isBlank()) return null;
        for (BountyRecord record : records) {
            if (record == null || record.status != BountyStatus.ACTIVE) continue;
            if (targetUuidOrName.equals(record.targetUuid)
                    || targetUuidOrName.equalsIgnoreCase(record.targetName)) {
                return record;
            }
        }
        return null;
    }
}
