package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Audience request register (§12) plus the Comisar-notification coalescing state. */
public class AudienceStore {
    public Map<String, AudienceRequest> requests = new HashMap<>();
    public int nextId = 1;
    /** Last time the online Comisar was told about new requests. */
    public long lastComisarNotifiedAt;
    /** Requests filed since the last Comisar notification. */
    public int unnotifiedCount;

    /** The member's open (PENDING) request, or null — one open request per member. */
    public AudienceRequest openFor(String uuid) {
        for (AudienceRequest r : requests.values()) {
            if (r != null && uuid.equals(r.requesterUuid)
                    && AudienceRequest.PENDING.equals(r.status)) {
                return r;
            }
        }
        return null;
    }

    /** The member's most recently decided request, or null. */
    public AudienceRequest latestDecidedFor(String uuid) {
        AudienceRequest latest = null;
        for (AudienceRequest r : requests.values()) {
            if (r != null && uuid.equals(r.requesterUuid)
                    && !AudienceRequest.PENDING.equals(r.status)
                    && (latest == null || r.resolvedAt == null
                            || (latest.resolvedAt != null && r.resolvedAt > latest.resolvedAt))) {
                latest = r;
            }
        }
        return latest;
    }

    /** Requests awaiting the Comisar, oldest first. */
    public List<AudienceRequest> pending() {
        List<AudienceRequest> out = new ArrayList<>();
        for (AudienceRequest r : requests.values()) {
            if (r != null && AudienceRequest.PENDING.equals(r.status)) out.add(r);
        }
        out.sort(Comparator.comparingLong(r -> r.createdAt));
        return out;
    }
}
