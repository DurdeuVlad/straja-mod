package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class JoinRequestStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, JoinRequest> requests = new LinkedHashMap<>();
    /** Lazily re-creates {@code requests} after a corrupt or partial read. */
    public Map<String, JoinRequest> ensureRequests() {
        if (requests == null) requests = new LinkedHashMap<>();
        return requests;
    }

}
