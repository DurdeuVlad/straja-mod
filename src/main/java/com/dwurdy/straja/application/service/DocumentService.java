package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.DocumentRepository;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.DocumentInstrument;
import com.dwurdy.straja.domain.model.DocumentRecord;
import com.dwurdy.straja.domain.model.DocumentRedemption;
import com.dwurdy.straja.domain.model.DocumentStatus;
import com.dwurdy.straja.domain.model.DocumentStore;
import com.dwurdy.straja.domain.model.FormRequest;
import java.util.Map;

/** Server-authoritative official documents and fungible instruments. */
public final class DocumentService {
    private final DocumentRepository repository;
    private final Clock clock;
    private final IdGenerator ids;
    private final AuthorizationService authorization;
    private final OperationRecoveryService operations;

    public DocumentService(DocumentRepository repository, Clock clock, IdGenerator ids) {
        this(repository, clock, ids, null, null);
    }

    public DocumentService(DocumentRepository repository, Clock clock, IdGenerator ids,
                           AuthorizationService authorization, OperationRecoveryService operations) {
        this.repository = repository;
        this.clock = clock;
        this.ids = ids;
        this.authorization = authorization;
        this.operations = operations;
    }

    public synchronized DocumentRecord issue(String issuer, String subject, com.dwurdy.straja.domain.model.DocumentType type,
                                             String scope, String stationId, String jurisdiction, Long expiresAt,
                                             String payloadRef, String correlationId) {
        return issue(issuer, subject, type, scope, stationId, jurisdiction, expiresAt, payloadRef, correlationId, null);
    }

    public synchronized DocumentRecord issue(String issuer, String subject, com.dwurdy.straja.domain.model.DocumentType type,
                                             String scope, String stationId, String jurisdiction, Long expiresAt,
                                             String payloadRef, String correlationId, String idempotencyKey) {
        requireDocumentAuthority(issuer, subject, stationId, jurisdiction);
        return issueUnchecked(issuer, subject, type, scope, stationId, jurisdiction, expiresAt,
                payloadRef, correlationId, idempotencyKey);
    }

    /** Internal projection used by committed domain ledgers, never exposed as a command. */
    synchronized DocumentRecord issueInternalSystem(String subject, com.dwurdy.straja.domain.model.DocumentType type,
                                                     String scope, String stationId, String jurisdiction,
                                                     String payloadRef, String correlationId, String idempotencyKey) {
        return issueUnchecked("SYSTEM", subject, type, scope, stationId, jurisdiction, null,
                payloadRef, correlationId, idempotencyKey);
    }

    private DocumentRecord issueUnchecked(String issuer, String subject, com.dwurdy.straja.domain.model.DocumentType type,
                                          String scope, String stationId, String jurisdiction, Long expiresAt,
                                          String payloadRef, String correlationId, String idempotencyKey) {
        DocumentStore store = repository.read();
        if (store.idempotencyIndex == null) store.idempotencyIndex = new java.util.LinkedHashMap<>();
        String fingerprint = RequestFingerprint.of(issuer, subject, type, scope, stationId, jurisdiction,
                expiresAt, payloadRef, correlationId);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existingId = store.idempotencyIndex.get(idempotencyKey);
            if (existingId != null && store.documents.get(existingId) != null) {
                DocumentRecord existing = store.documents.get(existingId);
                if (!fingerprint.equals(existing.requestFingerprint))
                    throw new IllegalStateException("IDEMPOTENCY_PAYLOAD_MISMATCH");
                return existing;
            }
        }
        DocumentRecord document = new DocumentRecord();
        document.documentId = ids.newId("DOC");
        document.type = type;
        document.issuer = issuer == null ? "" : issuer;
        document.subject = subject == null ? "" : subject;
        document.scope = scope == null ? "" : scope;
        document.stationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        document.jurisdiction = jurisdiction == null ? "" : jurisdiction;
        document.issuedAt = clock.nowMillis();
        document.expiresAt = expiresAt;
        document.status = DocumentStatus.ACTIVE;
        document.payloadRef = payloadRef == null ? "" : payloadRef;
        document.correlationId = correlationId == null ? document.documentId : correlationId;
        document.requestFingerprint = fingerprint;
        document.version = 1;
        store.documents.put(document.documentId, document);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) store.idempotencyIndex.put(idempotencyKey, document.documentId);
        store.storeRevision++;
        repository.write(store);
        return document;
    }

    public synchronized DocumentInstrument issueInstrument(String issuer, String holder,
                                                            com.dwurdy.straja.domain.model.DocumentType type,
                                                            long quantity, String scope, String stationId,
                                                            Long expiresAt) {
        return issueInstrument(issuer, holder, type, quantity, scope, stationId, expiresAt, null);
    }

    public synchronized DocumentInstrument issueInstrument(String issuer, String holder,
                                                            com.dwurdy.straja.domain.model.DocumentType type,
                                                            long quantity, String scope, String stationId,
                                                            Long expiresAt, String idempotencyKey) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        requireDocumentAuthority(issuer, holder, stationId, "");
        DocumentStore store = repository.read();
        if (store.idempotencyIndex == null) store.idempotencyIndex = new java.util.LinkedHashMap<>();
        String fingerprint = RequestFingerprint.of(issuer, holder, type, quantity, scope, stationId, expiresAt);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String existingId = store.idempotencyIndex.get(idempotencyKey);
            if (existingId != null && store.instruments.get(existingId) != null) {
                DocumentInstrument existing = store.instruments.get(existingId);
                if (!fingerprint.equals(existing.requestFingerprint))
                    throw new IllegalStateException("IDEMPOTENCY_PAYLOAD_MISMATCH");
                return existing;
            }
        }
        DocumentInstrument instrument = new DocumentInstrument();
        instrument.instrumentId = ids.newId("INS");
        instrument.instrumentType = type;
        instrument.issuer = issuer == null ? "" : issuer;
        instrument.holder = holder == null ? "" : holder;
        instrument.scope = scope == null ? "" : scope;
        instrument.stationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        instrument.initialQuantity = quantity;
        instrument.remainingQuantity = quantity;
        instrument.issuedAt = clock.nowMillis();
        instrument.expiresAt = expiresAt;
        instrument.requestFingerprint = fingerprint;
        instrument.status = DocumentStatus.ACTIVE;
        instrument.version = 1;
        store.instruments.put(instrument.instrumentId, instrument);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) store.idempotencyIndex.put(idempotencyKey, instrument.instrumentId);
        store.storeRevision++;
        repository.write(store);
        return instrument;
    }

    public synchronized RedemptionResult redeem(String actorUuid, String instrumentId, long quantity,
                                                String stationId, String idempotencyKey) {
        if (quantity <= 0) return RedemptionResult.failed("INVALID_QUANTITY");
        if (idempotencyKey == null || idempotencyKey.isBlank()) return RedemptionResult.failed("IDEMPOTENCY_REQUIRED");
        DocumentStore store = repository.read();
        if (store.redemptions == null) store.redemptions = new java.util.LinkedHashMap<>();
        if (store.redemptions != null) {
            String fingerprint = RequestFingerprint.of(actorUuid, instrumentId, quantity, stationId);
            for (DocumentRedemption existing : store.redemptions.values()) {
                if (existing != null && idempotencyKey.equals(existing.idempotencyKey)) {
                    if (!fingerprint.equals(existing.requestFingerprint))
                        return RedemptionResult.failed("IDEMPOTENCY_PAYLOAD_MISMATCH");
                    return RedemptionResult.replayed(existing);
                }
            }
        }
        DocumentInstrument instrument = store.instruments == null ? null : store.instruments.get(instrumentId);
        if (instrument == null || !instrument.activeAt(clock.nowMillis())) return RedemptionResult.failed("INVALID_INSTRUMENT");
        if (stationId == null || !instrument.stationId.equals(stationId)) return RedemptionResult.failed("DENIED_STATION");
        if (instrument.holder != null && !instrument.holder.isBlank()
                && !instrument.holder.equals(actorUuid)) return RedemptionResult.failed("DENIED_HOLDER");
        if (instrument.remainingQuantity < quantity) return RedemptionResult.failed("INSUFFICIENT_QUANTITY");
        if (authorization != null) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "ISSUE_DOCUMENTS");
            context.subjectUuid = actorUuid;
            context.stationId = stationId;
            if (!authorization.allowed(context)) return RedemptionResult.failed("DENIED_AUTHORIZATION");
        }
        com.dwurdy.straja.domain.model.OperationRecord operation = operations == null ? null : operations
                .prepare(idempotencyKey, "DOCUMENT_REDEMPTION", actorUuid, actorUuid, null,
                        java.util.List.of(instrumentId), Map.of(instrumentId, instrument.version));
        if (operation != null && operation.status != com.dwurdy.straja.domain.model.OperationRecord.OperationStatus.PREPARED
                && operation.status != com.dwurdy.straja.domain.model.OperationRecord.OperationStatus.RETRYABLE) {
            return RedemptionResult.failed("FULFILLMENT_PENDING");
        }
        instrument.remainingQuantity -= quantity;
        instrument.version++;
        instrument.status = instrument.remainingQuantity == 0 ? DocumentStatus.REDEEMED : DocumentStatus.PARTIALLY_REDEEMED;
        DocumentRedemption redemption = new DocumentRedemption();
        redemption.redemptionId = ids.newId("RED");
        redemption.instrumentId = instrumentId;
        redemption.quantity = quantity;
        redemption.actor = actorUuid == null ? "" : actorUuid;
        redemption.stationId = stationId == null ? "" : stationId;
        redemption.idempotencyKey = idempotencyKey;
        redemption.requestFingerprint = RequestFingerprint.of(actorUuid, instrumentId, quantity, stationId);
        redemption.fulfillmentOperationId = operation == null ? ids.newId("OP") : operation.operationId;
        redemption.status = instrument.remainingQuantity == 0 ? DocumentStatus.REDEEMED : DocumentStatus.PARTIALLY_REDEEMED;
        redemption.createdAt = clock.nowMillis();
        store.redemptions.put(redemption.redemptionId, redemption);
        store.storeRevision++;
        repository.write(store);
        if (operation != null) {
            if (operation.status == com.dwurdy.straja.domain.model.OperationRecord.OperationStatus.PREPARED) {
                operations.transition(operation.operationId,
                        com.dwurdy.straja.domain.model.OperationRecord.OperationStatus.DOMAIN_COMMITTED,
                        redemption.redemptionId);
            }
            operations.transition(operation.operationId,
                    com.dwurdy.straja.domain.model.OperationRecord.OperationStatus.SIDE_EFFECT_PENDING,
                    "fulfillment pending");
        }
        return RedemptionResult.accepted(redemption);
    }

    public synchronized DocumentRecord revoke(String actorUuid, String documentId) {
        if (actorUuid == null || actorUuid.isBlank() || authorization == null)
            throw new IllegalStateException("DENIED_AUTHORIZATION");
        DocumentStore store = repository.read();
        DocumentRecord document = store.documents.get(documentId);
        if (document == null) throw new IllegalArgumentException("unknown document");
        if (authorization != null && actorUuid != null && !actorUuid.isBlank()) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "ISSUE_DOCUMENTS");
            context.subjectUuid = document.subject;
            context.stationId = document.stationId;
            context.jurisdiction = document.jurisdiction;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        if (document.status != DocumentStatus.REVOKED) {
            document.status = DocumentStatus.REVOKED;
            document.version++;
            store.storeRevision++;
            repository.write(store);
        }
        return document;
    }

    public synchronized DocumentRecord inspect(String documentId) {
        DocumentStore store = repository.read();
        return store.documents.get(documentId);
    }

    public synchronized DocumentRecord expire(String actorUuid, String documentId) {
        DocumentStore store = repository.read();
        DocumentRecord document = store.documents.get(documentId);
        if (document == null) throw new IllegalArgumentException("unknown document");
        requireDocumentAuthority(actorUuid, document.subject, document.stationId, document.jurisdiction);
        if (document.status != DocumentStatus.EXPIRED && document.status != DocumentStatus.REVOKED) {
            document.status = DocumentStatus.EXPIRED; document.version++;
            store.storeRevision++; repository.write(store);
        }
        return document;
    }

    public synchronized DocumentRecord reprint(String actorUuid, String documentId, String idempotencyKey) {
        DocumentStore store = repository.read();
        DocumentRecord prior = store.documents.get(documentId);
        if (prior == null) throw new IllegalArgumentException("unknown document");
        requireDocumentAuthority(actorUuid, prior.subject, prior.stationId, prior.jurisdiction);
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "reprint:" + documentId : idempotencyKey;
        if (store.idempotencyIndex == null) store.idempotencyIndex = new java.util.LinkedHashMap<>();
        String fingerprint = RequestFingerprint.of(actorUuid, prior.subject, prior.type, prior.scope,
                prior.stationId, prior.jurisdiction, prior.expiresAt, prior.payloadRef,
                "reprint:" + documentId);
        String existing = store.idempotencyIndex.get(key);
        if (existing != null && store.documents.get(existing) != null) {
            DocumentRecord replay = store.documents.get(existing);
            if (!fingerprint.equals(replay.requestFingerprint))
                throw new IllegalStateException("IDEMPOTENCY_PAYLOAD_MISMATCH");
            return replay;
        }
        DocumentRecord copy = issueUnchecked(actorUuid, prior.subject, prior.type, prior.scope, prior.stationId,
                prior.jurisdiction, prior.expiresAt, prior.payloadRef, "reprint:" + documentId, key);
        copy.predecessorId = prior.documentId;
        prior.status = DocumentStatus.SUPERSEDED;
        prior.supersedesId = copy.documentId;
        prior.version++;
        store = repository.read();
        DocumentRecord persistedCopy = store.documents.get(copy.documentId);
        if (persistedCopy != null) {
            persistedCopy.predecessorId = prior.documentId;
            prior = store.documents.get(prior.documentId);
            if (prior != null) { prior.status = DocumentStatus.SUPERSEDED; prior.supersedesId = persistedCopy.documentId; prior.version++; }
            store.storeRevision++; repository.write(store);
            return persistedCopy;
        }
        return copy;
    }

    public synchronized java.util.List<DocumentRecord> documents() {
        DocumentStore store = repository.read();
        return store.documents == null ? java.util.List.of() : java.util.List.copyOf(store.documents.values());
    }

    public synchronized java.util.List<DocumentInstrument> instruments() {
        DocumentStore store = repository.read();
        return store.instruments == null ? java.util.List.of() : java.util.List.copyOf(store.instruments.values());
    }

    /**
     * Records a request for a blank standard form. This is deliberately not a
     * document entitlement and never grants authority or currency.
     */
    public synchronized FormRequest requestBlankForm(String requesterUuid, String formType,
                                                      String idempotencyKey) {
        if (requesterUuid == null || requesterUuid.isBlank() || formType == null || formType.isBlank())
            throw new IllegalArgumentException("form requester and type required");
        DocumentStore store = repository.read();
        if (store.formRequests == null) store.formRequests = new java.util.LinkedHashMap<>();
        if (store.formRequestIndex == null) store.formRequestIndex = new java.util.LinkedHashMap<>();
        String key = idempotencyKey == null || idempotencyKey.isBlank()
                ? "FORM:" + requesterUuid + ":" + formType + ":" + clock.nowMillis() : idempotencyKey;
        String existingId = store.formRequestIndex.get(key);
        String fingerprint = RequestFingerprint.of(requesterUuid, formType);
        if (existingId != null && store.formRequests.get(existingId) != null) {
            FormRequest existing = store.formRequests.get(existingId);
            if (!fingerprint.equals(existing.requestFingerprint))
                throw new IllegalStateException("IDEMPOTENCY_PAYLOAD_MISMATCH");
            return existing;
        }
        FormRequest request = new FormRequest();
        request.requestId = ids.newId("FORM"); request.requesterUuid = requesterUuid;
        request.formType = formType; request.idempotencyKey = key;
        request.requestFingerprint = fingerprint;
        request.createdAt = clock.nowMillis(); request.updatedAt = request.createdAt;
        request.version = 1;
        store.formRequests.put(request.requestId, request); store.formRequestIndex.put(key, request.requestId);
        store.storeRevision++; repository.write(store); return request;
    }

    /** Marks the form session's persistent request after the one-use session is consumed. */
    public synchronized FormRequest completeFormRequest(String requestId) {
        DocumentStore store = repository.read();
        if (store.formRequests == null) return null;
        FormRequest request = store.formRequests.get(requestId);
        if (request == null) return null;
        if (!"SUBMITTED".equals(request.status)) {
            request.status = "SUBMITTED"; request.updatedAt = clock.nowMillis(); request.version++;
            store.storeRevision++; repository.write(store);
        }
        return request;
    }

    public synchronized java.util.List<FormRequest> formRequests() {
        DocumentStore store = repository.read();
        return store.formRequests == null ? java.util.List.of() : java.util.List.copyOf(store.formRequests.values());
    }

    private void requireDocumentAuthority(String issuer, String subject, String stationId, String jurisdiction) {
        if (authorization == null || issuer == null || issuer.isBlank()) return;
        AuthorizationContext context = AuthorizationContext.of(issuer, "ISSUE_DOCUMENTS");
        context.subjectUuid = subject == null ? "" : subject;
        context.stationId = stationId;
        context.jurisdiction = jurisdiction;
        if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
    }

    public record RedemptionResult(boolean accepted, boolean replayed, String reason, DocumentRedemption redemption) {
        static RedemptionResult accepted(DocumentRedemption value) { return new RedemptionResult(true, false, "", value); }
        static RedemptionResult replayed(DocumentRedemption value) { return new RedemptionResult(true, true, "", value); }
        static RedemptionResult failed(String reason) { return new RedemptionResult(false, false, reason, null); }
    }
}
