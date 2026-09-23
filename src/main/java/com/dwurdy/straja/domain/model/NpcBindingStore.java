package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable provider-aware NPC binding aggregate. */
public class NpcBindingStore {
    public static final int CURRENT_SCHEMA_VERSION = 3;

    public int schemaVersion = CURRENT_SCHEMA_VERSION;
    public Map<String, NpcBinding> bindings = new LinkedHashMap<>();
    public Map<String, PendingOperation> pendingOperations = new LinkedHashMap<>();
    /** Provider reassignment intent, persisted before mutating either provider. */
    public Map<String, PendingRebind> pendingRebinds = new LinkedHashMap<>();
    /** Monotonic per-host assignment generations, retained across unassign and audit pruning. */
    public Map<String, Long> assignmentRevisions = new LinkedHashMap<>();
    /** Structured provisioning audit shared by status, recovery, and support tooling. */
    public List<NpcProvisioningAuditEntry> provisioningAudit = new ArrayList<>();
    /** Full desired binding for each pending ASSIGN intent, available before provider side effects. */
    public Map<String, NpcBinding> pendingProvisioningCandidates = new LinkedHashMap<>();

    public static class PendingOperation {
        public NpcBinding binding;
        public NpcProviderOperation operation;
        /** True only when the provider mutation may have happened before failure. */
        public Boolean requiresReconciliation;

        public PendingOperation() {}

        public PendingOperation(NpcBinding binding, NpcProviderOperation operation) {
            this(binding, operation, true);
        }

        public PendingOperation(
                NpcBinding binding,
                NpcProviderOperation operation,
                boolean requiresReconciliation) {
            this.binding = binding;
            this.operation = operation;
            this.requiresReconciliation = requiresReconciliation;
        }
    }

    public static class PendingRebind {
        public NpcBinding previous;
        public NpcBinding replacement;
        public NpcRebindPhase phase;

        public PendingRebind() {}

        public PendingRebind(NpcBinding previous, NpcBinding replacement, NpcRebindPhase phase) {
            this.previous = previous;
            this.replacement = replacement;
            this.phase = phase;
        }
    }
}
