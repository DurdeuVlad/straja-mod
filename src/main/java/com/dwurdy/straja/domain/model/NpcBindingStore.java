package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Durable provider-aware NPC binding aggregate. */
public class NpcBindingStore {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public Map<String, NpcBinding> bindings = new LinkedHashMap<>();
    public Map<String, PendingOperation> pendingOperations = new LinkedHashMap<>();

    public static class PendingOperation {
        public NpcBinding binding;
        public NpcProviderOperation operation;

        public PendingOperation() {}

        public PendingOperation(NpcBinding binding, NpcProviderOperation operation) {
            this.binding = binding;
            this.operation = operation;
        }
    }
}
