package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.EquipmentRepository;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.domain.model.EquipmentIssue;
import com.dwurdy.straja.domain.model.EquipmentObligation;
import com.dwurdy.straja.domain.model.EquipmentStore;
import java.util.List;

/** Per-player equipment obligations with line-level retry safety. */
public final class EquipmentLedgerService {
    private final EquipmentRepository repository;
    private final Clock clock;
    private final IdGenerator ids;
    private final AuthorizationService authorization;
    private DocumentService documents;
    private StationService stations;

    public EquipmentLedgerService(EquipmentRepository repository, Clock clock, IdGenerator ids) {
        this(repository, clock, ids, null);
    }
    public EquipmentLedgerService(EquipmentRepository repository, Clock clock, IdGenerator ids,
                                  AuthorizationService authorization) {
        this.repository = repository;
        this.clock = clock;
        this.ids = ids;
        this.authorization = authorization;
    }

    public void useDocumentService(DocumentService documents) { this.documents = documents; }
    public void useStationService(StationService stations) { this.stations = stations; }

    public synchronized EquipmentIssue createIssue(String actorUuid, String playerUuid, String stationId,
                                                   String sourceInstrumentId, String mobilizationId,
                                                   List<RequestedLine> requested, String operationId) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("equipment lines required");
        if (authorization != null) {
            var context = com.dwurdy.straja.domain.model.AuthorizationContext.of(actorUuid, "ISSUE_DOCUMENTS");
            context.subjectUuid = playerUuid;
            context.stationId = stationId;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        EquipmentStore store = repository.read();
        String effectiveOperationId = operationId == null || operationId.isBlank()
                ? ids.newId("OP") : operationId;
        String effectiveStationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        java.util.Map<String, Long> requestedByItem = new java.util.LinkedHashMap<>();
        for (RequestedLine request : requested) {
            if (request == null || request.quantity <= 0 || request.itemId == null || request.itemId.isBlank())
                throw new IllegalArgumentException("invalid equipment line");
            requestedByItem.merge(request.itemId, request.quantity, Long::sum);
        }
        String requestFingerprint = RequestFingerprint.of(playerUuid, effectiveStationId,
                sourceInstrumentId, mobilizationId, requestedByItem);
        if (operationId != null && !operationId.isBlank()) {
            for (EquipmentIssue existing : store.issues.values()) {
                if (existing != null && operationId.equals(existing.operationId)) {
                    String existingFingerprint = existing.requestFingerprint;
                    if (existingFingerprint == null || existingFingerprint.isBlank()) {
                        java.util.Map<String, Long> existingLines = new java.util.LinkedHashMap<>();
                        for (EquipmentIssue.Line line : existing.lines) {
                            if (line != null) existingLines.merge(line.itemId, line.requestedQuantity, Long::sum);
                        }
                        existingFingerprint = RequestFingerprint.of(existing.playerUuid, existing.stationId,
                                existing.sourceInstrumentId, existing.mobilizationId, existingLines);
                    }
                    if (!requestFingerprint.equals(existingFingerprint))
                        throw new IllegalStateException("OPERATION_PAYLOAD_MISMATCH");
                    return existing;
                }
            }
        }
        if (stations != null && stations.get(effectiveStationId) == null)
            throw new IllegalStateException("DENIED_STATION");
        if (stations != null) {
            var station = stations.get(effectiveStationId);
            if (station != null && station.inventoryAccounts != null && !station.inventoryAccounts.isEmpty()) {
                for (var entry : requestedByItem.entrySet()) {
                    long available = station.inventoryAccounts.getOrDefault(entry.getKey(), 0L);
                    if (available < entry.getValue()) throw new IllegalStateException("INSUFFICIENT_STATION_INVENTORY");
                }
            }
        }
        if (documents != null && sourceInstrumentId != null && !sourceInstrumentId.isBlank()) {
            long quantity = requestedByItem.values().stream().mapToLong(Long::longValue).sum();
            var redemption = documents.redeem(actorUuid, sourceInstrumentId, quantity, effectiveStationId,
                    effectiveOperationId + ":instrument");
            if (!redemption.accepted()) throw new IllegalStateException(redemption.reason());
        }
        EquipmentIssue issue = new EquipmentIssue();
        issue.issueId = ids.newId("EQI");
        issue.playerUuid = playerUuid == null ? "" : playerUuid;
        issue.stationId = effectiveStationId;
        issue.sourceInstrumentId = sourceInstrumentId == null ? "" : sourceInstrumentId;
        issue.mobilizationId = mobilizationId == null ? "" : mobilizationId;
        issue.operationId = effectiveOperationId;
        issue.issuedAt = clock.nowMillis();
        issue.status = EquipmentIssue.EquipmentStatus.RESERVED;
        issue.requestFingerprint = requestFingerprint;
        for (RequestedLine request : requested) {
            EquipmentIssue.Line line = new EquipmentIssue.Line();
            line.lineId = ids.newId("EQL");
            line.itemId = request.itemId;
            line.requestedQuantity = request.quantity;
            issue.lines.add(line);
        }
        if (stations != null) stations.reserveInventory(effectiveStationId, requestedByItem);
        store.issues.put(issue.issueId, issue);
        store.storeRevision++;
        repository.write(store);
        return issue;
    }

    public synchronized EquipmentIssue fulfillLine(String issueId, String lineId, long quantity, String assetId) {
        return fulfillLine(issueId, lineId, quantity, assetId,
                "legacy-fulfill:" + issueId + ":" + lineId + ":" + quantity + ":" + (assetId == null ? "" : assetId));
    }

    public synchronized EquipmentIssue fulfillLine(String issueId, String lineId, long quantity, String assetId,
                                                   String operationId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        EquipmentStore store = repository.read();
        EquipmentIssue issue = requireIssue(store, issueId);
        EquipmentIssue.Line line = requireLine(issue, lineId);
        if (line.fulfillmentOperations == null) line.fulfillmentOperations = new java.util.LinkedHashMap<>();
        if (line.fulfillmentFingerprints == null) line.fulfillmentFingerprints = new java.util.LinkedHashMap<>();
        if (operationId != null && !operationId.isBlank()) {
            Long previousQuantity = line.fulfillmentOperations.get(operationId);
            if (previousQuantity != null) {
                String fingerprint = line.fulfillmentFingerprints.get(operationId);
                if (fingerprint == null || !fingerprint.equals(RequestFingerprint.of(quantity, assetId)))
                    throw new IllegalStateException("OPERATION_PAYLOAD_MISMATCH");
                return issue;
            }
        }
        long remaining = line.requestedQuantity - line.deliveredQuantity;
        if (quantity > remaining) throw new IllegalArgumentException("delivery exceeds requested quantity");
        line.deliveredQuantity += quantity;
        line.assetId = assetId == null ? line.assetId : assetId;
        if (operationId != null && !operationId.isBlank()) {
            line.fulfillmentOperations.put(operationId, quantity);
            line.fulfillmentFingerprints.put(operationId, RequestFingerprint.of(quantity, assetId));
        }
        line.fulfillmentStatus = line.deliveredQuantity == line.requestedQuantity ? "DELIVERED" : "PARTIAL";
        long delivered = issue.lines.stream().mapToLong(l -> l.deliveredQuantity).sum();
        long requested = issue.lines.stream().mapToLong(l -> l.requestedQuantity).sum();
        issue.status = delivered == requested ? EquipmentIssue.EquipmentStatus.OUTSTANDING
                : EquipmentIssue.EquipmentStatus.ISSUED;
        createObligations(store, issue);
        store.storeRevision++;
        repository.write(store);
        return issue;
    }

    public synchronized ReturnResult returnQuantity(String actorUuid, String playerUuid, String itemId, long quantity) {
        // The compatibility overload is a new user action, not an idempotency
        // key. Callers that can retry must use the explicit operation overload.
        return returnQuantity(actorUuid, playerUuid, itemId, quantity, ids.newId("RETURN"));
    }

    public synchronized ReturnResult returnQuantity(String actorUuid, String playerUuid, String itemId,
                                                    long quantity, String operationId) {
        return returnQuantity(actorUuid, playerUuid, itemId, quantity, operationId, "GOOD");
    }

    public synchronized ReturnResult returnQuantity(String actorUuid, String playerUuid, String itemId,
                                                    long quantity, String operationId, String returnedCondition) {
        if (quantity <= 0) return new ReturnResult(false, "INVALID_QUANTITY", 0);
        if (operationId == null || operationId.isBlank()) return new ReturnResult(false, "IDEMPOTENCY_REQUIRED", 0);
        EquipmentStore store = repository.read();
        if (store.returnOperations == null) store.returnOperations = new java.util.LinkedHashMap<>();
        if (store.returnOperationFingerprints == null) store.returnOperationFingerprints = new java.util.LinkedHashMap<>();
        Long replayQuantity = store.returnOperations.get(operationId);
        if (replayQuantity != null) {
            String fingerprint = store.returnOperationFingerprints.get(operationId);
            if (fingerprint == null || !fingerprint.equals(RequestFingerprint.of(
                    playerUuid, itemId, quantity, returnedCondition)))
                return new ReturnResult(false, "OPERATION_PAYLOAD_MISMATCH", 0);
            return new ReturnResult(true, "REPLAYED", quantity);
        }
        if (actorUuid != null && playerUuid != null && !actorUuid.equals(playerUuid) && authorization != null) {
            var context = com.dwurdy.straja.domain.model.AuthorizationContext.of(actorUuid, "ISSUE_DOCUMENTS");
            context.subjectUuid = playerUuid;
            if (!authorization.allowed(context)) return new ReturnResult(false, "DENIED_AUTHORIZATION", 0);
        }
        long left = quantity;
        for (EquipmentObligation obligation : store.obligations.values()) {
            if (obligation == null || !playerUuid.equals(obligation.playerUuid) || !itemId.equals(obligation.itemId)) continue;
            if (obligation.outstandingQuantity <= 0) continue;
            long returned = Math.min(left, obligation.outstandingQuantity);
            obligation.outstandingQuantity -= returned;
            obligation.returnedQuantity += returned;
            obligation.returnedCondition = returnedCondition == null || returnedCondition.isBlank()
                    ? "UNSPECIFIED" : returnedCondition.trim().toUpperCase(java.util.Locale.ROOT);
            obligation.status = obligation.outstandingQuantity == 0
                    ? EquipmentObligation.EquipmentStatus.RETURNED
                    : EquipmentObligation.EquipmentStatus.PARTIALLY_RETURNED;
            left -= returned;
            if (left == 0) break;
        }
        if (left == quantity) return new ReturnResult(false, "NO_OUTSTANDING_OBLIGATION", 0);
        store.returnOperations.put(operationId, quantity);
        store.returnOperationFingerprints.put(operationId,
                RequestFingerprint.of(playerUuid, itemId, quantity, returnedCondition));
        String proofId = store.returnProofs.get(operationId);
        if ((proofId == null || proofId.isBlank()) && documents != null) {
            var proof = documents.issueInternalSystem(playerUuid,
                    com.dwurdy.straja.domain.model.DocumentType.EQUIPMENT_RETURN_PROOF,
                    "equipment-return", "hq", "", "return:" + operationId,
                    "equipment-return:" + operationId, "equipment-return:" + operationId);
            proofId = proof.documentId;
            store.returnProofs.put(operationId, proofId);
            for (EquipmentObligation obligation : store.obligations.values()) {
                if (obligation != null && playerUuid.equals(obligation.playerUuid)
                        && itemId.equals(obligation.itemId) && obligation.returnedQuantity > 0
                        && (obligation.returnProofDocumentId == null || obligation.returnProofDocumentId.isBlank())) {
                    obligation.returnProofDocumentId = proofId;
                }
            }
        }
        store.storeRevision++;
        repository.write(store);
        return new ReturnResult(true, left == 0 ? "RETURNED" : "PARTIAL_RETURN", quantity - left);
    }

    public synchronized EquipmentObligation markLost(String actorUuid, String obligationId, long debtAmount) {
        EquipmentStore store = repository.read();
        EquipmentObligation obligation = requireObligation(store, obligationId);
        if (authorization != null && actorUuid != null && !actorUuid.equals(obligation.playerUuid)) {
            var context = com.dwurdy.straja.domain.model.AuthorizationContext.of(actorUuid, "WAIVE_EQUIPMENT_DEBT");
            context.subjectUuid = obligation.playerUuid;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        obligation.status = EquipmentObligation.EquipmentStatus.DEBT_PENDING;
        obligation.debtAmount = Math.max(0, debtAmount);
        store.storeRevision++;
        repository.write(store);
        return obligation;
    }

    public synchronized EquipmentObligation markDestroyed(String actorUuid, String obligationId, long debtAmount) {
        EquipmentObligation obligation = markLost(actorUuid, obligationId, debtAmount);
        EquipmentStore store = repository.read();
        EquipmentObligation current = requireObligation(store, obligation.obligationId);
        current.status = EquipmentObligation.EquipmentStatus.DESTROYED;
        store.storeRevision++; repository.write(store); return current;
    }

    public synchronized EquipmentObligation waive(String actorUuid, String obligationId, String waiverId) {
        EquipmentStore store = repository.read();
        EquipmentObligation obligation = requireObligation(store, obligationId);
        if (waiverId == null || waiverId.isBlank()) throw new IllegalArgumentException("waiver id required");
        if (authorization != null) {
            var context = com.dwurdy.straja.domain.model.AuthorizationContext.of(actorUuid, "WAIVE_EQUIPMENT_DEBT");
            context.subjectUuid = obligation.playerUuid;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        obligation.status = EquipmentObligation.EquipmentStatus.WAIVED;
        obligation.waiverId = waiverId;
        obligation.outstandingQuantity = 0;
        store.storeRevision++;
        repository.write(store);
        return obligation;
    }

    /** Explicit migration marker for pre-V2 gear; it never creates debt. */
    public synchronized EquipmentObligation recordLegacyOwned(String playerUuid, String itemId,
                                                               long quantity, String assetId) {
        if (playerUuid == null || playerUuid.isBlank() || itemId == null || itemId.isBlank() || quantity <= 0)
            throw new IllegalArgumentException("invalid legacy equipment");
        EquipmentStore store = repository.read();
        String id = "LEGACY:" + playerUuid + ":" + itemId + ":" + (assetId == null ? "" : assetId);
        EquipmentObligation existing = store.obligations.get(id);
        if (existing != null) return existing;
        EquipmentObligation obligation = new EquipmentObligation();
        obligation.obligationId = id;
        obligation.playerUuid = playerUuid;
        obligation.itemId = itemId;
        obligation.assetId = assetId == null ? "" : assetId;
        obligation.issuedQuantity = quantity;
        obligation.outstandingQuantity = 0;
        obligation.responsibility = "LEGACY_MIGRATION";
        obligation.status = EquipmentObligation.EquipmentStatus.LEGACY_OWNED;
        store.obligations.put(id, obligation);
        store.storeRevision++;
        repository.write(store);
        return obligation;
    }

    public synchronized java.util.List<EquipmentObligation> obligationsFor(String playerUuid) {
        EquipmentStore store = repository.read();
        if (store.obligations == null) return java.util.List.of();
        return store.obligations.values().stream()
                .filter(value -> value != null && java.util.Objects.equals(playerUuid, value.playerUuid))
                .toList();
    }

    /** Repairs only arithmetic/status drift; it never invents delivery or clears debt. */
    public synchronized int reconcile() {
        EquipmentStore store = repository.read(); int changed = 0;
        for (EquipmentObligation obligation : store.obligations.values()) {
            if (obligation == null || obligation.status == EquipmentObligation.EquipmentStatus.LEGACY_OWNED) continue;
            long returned = Math.max(0, Math.min(obligation.issuedQuantity, obligation.returnedQuantity));
            long outstanding = Math.max(0, obligation.issuedQuantity - returned);
            if (obligation.returnedQuantity != returned || obligation.outstandingQuantity != outstanding) {
                obligation.returnedQuantity = returned; obligation.outstandingQuantity = outstanding; changed++;
            }
            if (obligation.status != EquipmentObligation.EquipmentStatus.LOST
                    && obligation.status != EquipmentObligation.EquipmentStatus.DESTROYED
                    && obligation.status != EquipmentObligation.EquipmentStatus.DEBT_PENDING
                    && obligation.status != EquipmentObligation.EquipmentStatus.WAIVED) {
                var expected = outstanding == 0 ? EquipmentObligation.EquipmentStatus.RETURNED
                        : returned == 0 ? EquipmentObligation.EquipmentStatus.OUTSTANDING
                        : EquipmentObligation.EquipmentStatus.PARTIALLY_RETURNED;
                if (obligation.status != expected) { obligation.status = expected; changed++; }
            }
        }
        if (changed > 0) { store.storeRevision++; repository.write(store); }
        return changed;
    }

    private void createObligations(EquipmentStore store, EquipmentIssue issue) {
        for (EquipmentIssue.Line line : issue.lines) {
            String key = issue.issueId + ":" + line.lineId;
            EquipmentObligation existing = store.obligations.get(key);
            if (existing != null) {
                long newlyDelivered = Math.max(0, line.deliveredQuantity - existing.issuedQuantity);
                existing.issuedQuantity = line.deliveredQuantity;
                existing.outstandingQuantity += newlyDelivered;
                if (existing.outstandingQuantity > 0 && existing.status == EquipmentObligation.EquipmentStatus.RETURNED)
                    existing.status = EquipmentObligation.EquipmentStatus.OUTSTANDING;
                continue;
            }
            EquipmentObligation obligation = new EquipmentObligation();
            obligation.obligationId = key;
            obligation.playerUuid = issue.playerUuid;
            obligation.itemId = line.itemId;
            obligation.issuedQuantity = line.deliveredQuantity;
            obligation.outstandingQuantity = line.deliveredQuantity;
            obligation.responsibility = "V2_ISSUE";
            obligation.issuedCondition = "NEW";
            obligation.status = EquipmentObligation.EquipmentStatus.OUTSTANDING;
            store.obligations.put(key, obligation);
        }
    }

    private static EquipmentIssue requireIssue(EquipmentStore store, String id) {
        EquipmentIssue issue = store.issues.get(id);
        if (issue == null) throw new IllegalArgumentException("unknown equipment issue: " + id);
        return issue;
    }
    private static EquipmentIssue.Line requireLine(EquipmentIssue issue, String id) {
        return issue.lines.stream().filter(line -> id.equals(line.lineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown equipment line: " + id));
    }
    private static EquipmentObligation requireObligation(EquipmentStore store, String id) {
        EquipmentObligation obligation = store.obligations.get(id);
        if (obligation == null) throw new IllegalArgumentException("unknown equipment obligation: " + id);
        return obligation;
    }

    public record RequestedLine(String itemId, long quantity) {}
    public record ReturnResult(boolean accepted, String reason, long returnedQuantity) {}
}
