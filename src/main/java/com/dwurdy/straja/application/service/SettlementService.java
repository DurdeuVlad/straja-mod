package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.CurrencyProvider;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.SettlementRepository;
import com.dwurdy.straja.domain.model.Settlement;
import com.dwurdy.straja.domain.model.SettlementStore;

/** Exactly-once business settlement records; coin receipts are transport evidence only. */
public final class SettlementService {
    private final SettlementRepository repository;
    private final Clock clock;
    private final IdGenerator ids;
    private final CurrencyProvider currency;

    public SettlementService(SettlementRepository repository, Clock clock, IdGenerator ids, CurrencyProvider currency) {
        this.repository = repository; this.clock = clock; this.ids = ids; this.currency = currency;
    }

    public synchronized Settlement create(String playerUuid, Settlement.SettlementCategory category,
                                          long amount, String settlementKey, String workUnitId) {
        if (amount < 0 || amount > Integer.MAX_VALUE || settlementKey == null || settlementKey.isBlank())
            throw new IllegalArgumentException("invalid settlement");
        SettlementStore store = repository.read();
        if (store.settlementKeyIndex == null) store.settlementKeyIndex = new java.util.LinkedHashMap<>();
        String existingId = store.settlementKeyIndex.get(settlementKey);
        String fingerprint = RequestFingerprint.of(playerUuid, category, amount, settlementKey, workUnitId);
        if (existingId != null && store.settlements.get(existingId) != null) {
            Settlement existing = store.settlements.get(existingId);
            if (!fingerprint.equals(existing.requestFingerprint))
                throw new IllegalStateException("IDEMPOTENCY_PAYLOAD_MISMATCH");
            return existing;
        }
        Settlement settlement = new Settlement(); settlement.settlementId = ids.newId("SET");
        settlement.settlementKey = settlementKey; settlement.requestFingerprint = fingerprint;
        settlement.playerUuid = playerUuid;
        settlement.category = category; settlement.amount = amount; settlement.workUnitId = workUnitId == null ? "" : workUnitId;
        settlement.createdAt = clock.nowMillis();
        store.settlements.put(settlement.settlementId, settlement); store.settlementKeyIndex.put(settlementKey, settlement.settlementId);
        store.storeRevision++; repository.write(store); return settlement;
    }

    /** Creates the calendar-period stipend entitlement exactly once. */
    public synchronized Settlement createWeeklyStipend(String playerUuid, String weekId,
                                                       String eligibilityStatus, long amount,
                                                       boolean eligible) {
        if (!eligible || amount <= 0) return null;
        if (playerUuid == null || playerUuid.isBlank() || weekId == null || weekId.isBlank())
            throw new IllegalArgumentException("stipend identity required");
        String status = eligibilityStatus == null || eligibilityStatus.isBlank() ? "ELIGIBLE" : eligibilityStatus;
        return create(playerUuid, Settlement.SettlementCategory.WEEKLY_STIPEND, Math.max(0, amount),
                "STIPEND:" + playerUuid + ":" + weekId + ":" + status, weekId);
    }

    public synchronized Settlement payout(Settlement settlement, PlayerGateway player) {
        if (settlement == null) throw new IllegalArgumentException("settlement required");
        SettlementStore store = repository.read(); Settlement current = store.settlements.get(settlement.settlementId);
        if (current == null) throw new IllegalArgumentException("unknown settlement");
        if (current.status == Settlement.SettlementStatus.PAID) return current;
        if (current.status == Settlement.SettlementStatus.VOID) throw new IllegalStateException("settlement is void");
        if (current.status == Settlement.SettlementStatus.IN_PROGRESS
                || current.status == Settlement.SettlementStatus.REVIEW) return current;
        if (player == null || !player.isOnline() || currency == null || !currency.available()) {
            current.status = Settlement.SettlementStatus.FAILED_RETRYABLE;
            store.storeRevision++; repository.write(store); return current;
        }
        current.status = Settlement.SettlementStatus.IN_PROGRESS;
        if (current.payoutAttempts == null) current.payoutAttempts = new java.util.ArrayList<>();
        Settlement.PayoutAttempt attempt = new Settlement.PayoutAttempt();
        attempt.attemptId = ids.newId("PAY"); attempt.startedAt = clock.nowMillis(); attempt.status = "IN_PROGRESS";
        current.payoutAttempts.add(attempt); current.version++;
        store.storeRevision++; repository.write(store);
        CurrencyProvider.Deposit result = currency.deposit(player, (int) current.amount, current.settlementId);
        if (result.ok()) {
            current.status = Settlement.SettlementStatus.PAID; current.paidAt = clock.nowMillis(); attempt.status = "PAID";
        } else if (result.delivered() > 0 || "partial_receipt_requires_review".equals(result.error())) {
            // A provider that violates the atomic deposit contract, or an
            // old partial receipt discovered during recovery, is never
            // retried automatically: doing so could duplicate value.
            current.status = Settlement.SettlementStatus.REVIEW;
            attempt.status = "REVIEW"; attempt.error = result.error();
        } else {
            current.status = Settlement.SettlementStatus.FAILED_RETRYABLE;
            attempt.status = "FAILED_RETRYABLE"; attempt.error = result.error();
        }
        current.version++; store.storeRevision++; repository.write(store); return current;
    }

    public synchronized java.util.List<Settlement> pendingFor(String playerUuid) {
        SettlementStore store = repository.read();
        if (store.settlements == null) return java.util.List.of();
        return store.settlements.values().stream()
                .filter(value -> value != null && java.util.Objects.equals(playerUuid, value.playerUuid)
                        && (value.status == Settlement.SettlementStatus.PENDING
                        || value.status == Settlement.SettlementStatus.FAILED_RETRYABLE))
                .toList();
    }

    public synchronized Settlement markReview(String settlementId, String reason) {
        SettlementStore store = repository.read(); Settlement settlement = store.settlements.get(settlementId);
        if (settlement == null) throw new IllegalArgumentException("unknown settlement");
        settlement.status = Settlement.SettlementStatus.REVIEW;
        Settlement.PayoutAttempt attempt = new Settlement.PayoutAttempt(); attempt.attemptId = ids.newId("REVIEW");
        attempt.startedAt = clock.nowMillis(); attempt.status = "REVIEW"; attempt.error = reason == null ? "" : reason;
        settlement.payoutAttempts.add(attempt); settlement.version++; store.storeRevision++; repository.write(store); return settlement;
    }

    /** Moves a failed or reviewed entitlement back to the durable payout queue. */
    public synchronized Settlement retry(String settlementId) {
        SettlementStore store = repository.read(); Settlement settlement = store.settlements.get(settlementId);
        if (settlement == null) throw new IllegalArgumentException("unknown settlement");
        if (settlement.status == Settlement.SettlementStatus.PAID
                || settlement.status == Settlement.SettlementStatus.VOID) return settlement;
        if (settlement.status == Settlement.SettlementStatus.REVIEW)
            throw new IllegalStateException("settlement requires manual review");
        settlement.status = Settlement.SettlementStatus.PENDING;
        settlement.version++; store.storeRevision++; repository.write(store); return settlement;
    }

    /** Reconciles interrupted payouts after a restart without inventing a receipt. */
    public synchronized int reconcile() {
        SettlementStore store = repository.read(); int changed = 0;
        for (Settlement settlement : store.settlements.values()) {
            if (settlement != null && settlement.status == Settlement.SettlementStatus.IN_PROGRESS) {
                settlement.status = Settlement.SettlementStatus.FAILED_RETRYABLE;
                settlement.version++; changed++;
            }
        }
        if (changed > 0) { store.storeRevision++; repository.write(store); }
        return changed;
    }

    /** Voids an unpaid entitlement; paid money is never silently reversed. */
    public synchronized Settlement voidSettlement(String settlementId, String reason) {
        SettlementStore store = repository.read(); Settlement settlement = store.settlements.get(settlementId);
        if (settlement == null) throw new IllegalArgumentException("unknown settlement");
        if (settlement.status == Settlement.SettlementStatus.PAID)
            throw new IllegalStateException("paid settlement cannot be voided");
        if (settlement.status != Settlement.SettlementStatus.VOID) {
            settlement.status = Settlement.SettlementStatus.VOID;
            Settlement.PayoutAttempt attempt = new Settlement.PayoutAttempt();
            attempt.attemptId = ids.newId("VOID"); attempt.startedAt = clock.nowMillis();
            attempt.status = "VOID"; attempt.error = reason == null ? "" : reason;
            settlement.payoutAttempts.add(attempt); settlement.version++;
            store.storeRevision++; repository.write(store);
        }
        return settlement;
    }

    public synchronized java.util.List<Settlement> all() {
        SettlementStore store = repository.read();
        return store.settlements == null ? java.util.List.of() : java.util.List.copyOf(store.settlements.values());
    }

    public synchronized Settlement find(String settlementId) {
        SettlementStore store = repository.read();
        return store.settlements == null ? null : store.settlements.get(settlementId);
    }
}
