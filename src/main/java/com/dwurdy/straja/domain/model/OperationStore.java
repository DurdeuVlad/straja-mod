package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class OperationStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public long storeRevision;
    public Map<String, OperationRecord> operations = new LinkedHashMap<>();
    public Map<String, String> idempotencyIndex = new LinkedHashMap<>();
    /** Lazily re-creates {@code operations} after a corrupt or partial read. */
    public Map<String, OperationRecord> ensureOperations() {
        if (operations == null) operations = new LinkedHashMap<>();
        return operations;
    }

    /** Lazily re-creates {@code idempotencyIndex} after a corrupt or partial read. */
    public Map<String, String> ensureIdempotencyIndex() {
        if (idempotencyIndex == null) idempotencyIndex = new LinkedHashMap<>();
        return idempotencyIndex;
    }

}
