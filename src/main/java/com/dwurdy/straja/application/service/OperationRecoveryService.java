package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.OperationRepository;
import com.dwurdy.straja.domain.model.OperationRecord;
import com.dwurdy.straja.domain.model.OperationStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable idempotency journal shared by all V2 value-moving workflows. */
public final class OperationRecoveryService {
    private final OperationRepository repository;
    private final Clock clock;
    private final IdGenerator ids;

    public OperationRecoveryService(OperationRepository repository, Clock clock, IdGenerator ids) {
        this.repository = repository;
        this.clock = clock;
        this.ids = ids;
    }

    public synchronized OperationRecord prepare(String idempotencyKey, String kind,
                                                String actorUuid, String subjectUuid,
                                                String correlationId,
                                                List<String> aggregateRefs,
                                                Map<String, Long> expectedVersions) {
        if (idempotencyKey == null || idempotencyKey.isBlank())
            throw new IllegalArgumentException("idempotency key is required");
        OperationStore store = repository.read();
        if (store.idempotencyIndex == null) store.idempotencyIndex = new java.util.LinkedHashMap<>();
        if (store.operations == null) store.operations = new java.util.LinkedHashMap<>();
        String existingId = store.idempotencyIndex.get(idempotencyKey);
        if (existingId != null && store.operations.get(existingId) != null) {
            OperationRecord existing = store.operations.get(existingId);
            String fingerprint = RequestFingerprint.of(kind, actorUuid, subjectUuid, correlationId,
                    aggregateRefs == null ? List.of() : aggregateRefs,
                    expectedVersions == null ? Map.of() : expectedVersions);
            if (!java.util.Objects.equals(existing.requestFingerprint, fingerprint)
                    || !java.util.Objects.equals(existing.kind, kind == null ? "" : kind)
                    || !java.util.Objects.equals(existing.actorUuid, actorUuid == null ? "" : actorUuid)
                    || !java.util.Objects.equals(existing.subjectUuid, subjectUuid == null ? "" : subjectUuid))
                throw new IllegalStateException("OPERATION_PAYLOAD_MISMATCH");
            return existing;
        }

        long now = clock.nowMillis();
        OperationRecord operation = new OperationRecord();
        operation.operationId = ids.newId("OP");
        operation.idempotencyKey = idempotencyKey;
        operation.requestFingerprint = RequestFingerprint.of(kind, actorUuid, subjectUuid, correlationId,
                aggregateRefs == null ? List.of() : aggregateRefs,
                expectedVersions == null ? Map.of() : expectedVersions);
        operation.kind = kind == null ? "" : kind;
        operation.actorUuid = actorUuid == null ? "" : actorUuid;
        operation.subjectUuid = subjectUuid == null ? "" : subjectUuid;
        operation.correlationId = correlationId == null || correlationId.isBlank()
                ? operation.operationId : correlationId;
        operation.aggregateRefs = aggregateRefs == null ? new ArrayList<>() : new ArrayList<>(aggregateRefs);
        operation.expectedVersions = expectedVersions == null
                ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(expectedVersions);
        operation.createdAt = now;
        operation.updatedAt = now;
        store.operations.put(operation.operationId, operation);
        store.idempotencyIndex.put(idempotencyKey, operation.operationId);
        store.storeRevision++;
        repository.write(store);
        return operation;
    }

    public synchronized OperationRecord transition(String operationId, OperationRecord.OperationStatus next,
                                                   String result) {
        OperationStore store = repository.read();
        OperationRecord operation = getRequired(store, operationId);
        if (operation.status == next) return operation;
        if (operation.status == OperationRecord.OperationStatus.COMPLETED
                || operation.status == OperationRecord.OperationStatus.ABORTED) return operation;
        if (!validTransition(operation.status, next))
            throw new IllegalStateException("invalid operation transition: " + operation.status + " -> " + next);
        operation.status = next;
        if (result != null) operation.result = result;
        operation.attempts++;
        operation.updatedAt = clock.nowMillis();
        store.storeRevision++;
        repository.write(store);
        return operation;
    }

    public synchronized OperationRecord findByIdempotencyKey(String key) {
        OperationStore store = repository.read();
        String id = store.idempotencyIndex == null ? null : store.idempotencyIndex.get(key);
        return id == null || store.operations == null ? null : store.operations.get(id);
    }

    public synchronized List<OperationRecord> pendingFor(String subjectUuid) {
        List<OperationRecord> result = new ArrayList<>();
        OperationStore store = repository.read();
        if (store.operations == null) return result;
        for (OperationRecord operation : store.operations.values()) {
            if (operation == null) continue;
            boolean pending = operation.status != OperationRecord.OperationStatus.COMPLETED
                    && operation.status != OperationRecord.OperationStatus.ABORTED;
            if (pending && (subjectUuid == null || subjectUuid.equals(operation.subjectUuid))) result.add(operation);
        }
        return result;
    }

    private static OperationRecord getRequired(OperationStore store, String id) {
        OperationRecord operation = store.operations == null ? null : store.operations.get(id);
        if (operation == null) throw new IllegalArgumentException("unknown operation: " + id);
        return operation;
    }

    private static boolean validTransition(OperationRecord.OperationStatus from, OperationRecord.OperationStatus to) {
        return switch (from) {
            case PREPARED -> to == OperationRecord.OperationStatus.DOMAIN_COMMITTED
                    || to == OperationRecord.OperationStatus.ABORTED
                    || to == OperationRecord.OperationStatus.RETRYABLE;
            case DOMAIN_COMMITTED -> to == OperationRecord.OperationStatus.SIDE_EFFECT_PENDING
                    || to == OperationRecord.OperationStatus.COMPLETED
                    || to == OperationRecord.OperationStatus.RETRYABLE
                    || to == OperationRecord.OperationStatus.ABORTED;
            case SIDE_EFFECT_PENDING -> to == OperationRecord.OperationStatus.COMPLETED
                    || to == OperationRecord.OperationStatus.RETRYABLE
                    || to == OperationRecord.OperationStatus.REVIEW
                    || to == OperationRecord.OperationStatus.ABORTED;
            case RETRYABLE -> to == OperationRecord.OperationStatus.SIDE_EFFECT_PENDING
                    || to == OperationRecord.OperationStatus.REVIEW
                    || to == OperationRecord.OperationStatus.ABORTED;
            case REVIEW -> to == OperationRecord.OperationStatus.RETRYABLE
                    || to == OperationRecord.OperationStatus.COMPLETED
                    || to == OperationRecord.OperationStatus.ABORTED;
            case COMPLETED, ABORTED -> false;
        };
    }
}
