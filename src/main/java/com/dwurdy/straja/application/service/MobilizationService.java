package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.MobilizationRepository;
import com.dwurdy.straja.application.port.out.PersonnelRepository;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.MobilizationOrder;
import com.dwurdy.straja.domain.model.MobilizationStore;
import com.dwurdy.straja.domain.model.PersonnelRecord;

public final class MobilizationService {
    private final MobilizationRepository repository;
    private final PersonnelRepository personnel;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final IdGenerator ids;
    private SettlementService settlements;
    private long settlementAmount;

    public MobilizationService(MobilizationRepository repository, PersonnelRepository personnel,
                               AuthorizationService authorization, Clock clock, IdGenerator ids) {
        this.repository = repository;
        this.personnel = personnel;
        this.authorization = authorization;
        this.clock = clock;
        this.ids = ids;
    }

    /** Installs the exactly-once pay ledger after composition is complete. */
    public void useSettlementService(SettlementService settlements, long amount) {
        this.settlements = settlements;
        this.settlementAmount = Math.max(0, amount);
    }

    public synchronized MobilizationOrder authorize(String actorUuid, String specialistUuid, String stationId,
                                                     String jurisdiction, long durationMillis,
                                                     String missionId, String campaignId, String source) {
        PersonnelRecord person = personnel.read().records.get(specialistUuid);
        if (person == null || !person.active() || (person.careerGrade != CareerGrade.PROFESSIONAL_SPECIALIST
                && person.careerGrade != CareerGrade.PROFESSIONAL_STAGIAR_SPECIALIST))
            throw new IllegalStateException("specialist required");
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "MOBILIZE_SPECIALISTS");
        context.subjectUuid = specialistUuid;
        context.stationId = stationId;
        context.jurisdiction = jurisdiction;
        if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (durationMillis <= 0) throw new IllegalArgumentException("duration must be positive");
        MobilizationStore store = repository.read();
        long now = clock.nowMillis();
        MobilizationOrder order = new MobilizationOrder();
        order.mobilizationId = ids.newId("MOB");
        order.specialistUuid = specialistUuid;
        order.stationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        order.jurisdiction = jurisdiction == null ? "" : jurisdiction;
        order.authorizedBy = actorUuid;
        order.authorizationSource = source == null ? "" : source;
        order.startsAt = now;
        order.expiresAt = now + durationMillis;
        order.missionId = missionId == null ? "" : missionId;
        order.campaignId = campaignId == null ? "" : campaignId;
        store.orders.put(order.mobilizationId, order);
        store.storeRevision++;
        repository.write(store);
        return order;
    }

    public synchronized MobilizationOrder muster(String specialistUuid, String mobilizationId) {
        MobilizationStore store = repository.read();
        MobilizationOrder order = require(store, mobilizationId);
        if (!specialistUuid.equals(order.specialistUuid)) throw new IllegalStateException("DENIED_SUBJECT");
        if (order.status != MobilizationOrder.MobilizationStatus.AUTHORIZED) throw new IllegalStateException("INVALID_STATE");
        order.status = MobilizationOrder.MobilizationStatus.MUSTERED;
        order.musteredAt = clock.nowMillis();
        order.version++;
        store.storeRevision++;
        repository.write(store);
        return order;
    }

    public synchronized MobilizationOrder activate(String specialistUuid, String mobilizationId) {
        MobilizationStore store = repository.read();
        MobilizationOrder order = require(store, mobilizationId);
        if (!specialistUuid.equals(order.specialistUuid)) throw new IllegalStateException("DENIED_SUBJECT");
        if (order.status == MobilizationOrder.MobilizationStatus.ACTIVE) return order;
        if (order.status != MobilizationOrder.MobilizationStatus.MUSTERED) throw new IllegalStateException("INVALID_STATE");
        if (clock.nowMillis() >= order.expiresAt) {
            order.status = MobilizationOrder.MobilizationStatus.EXPIRED;
            order.demobilizedAt = clock.nowMillis();
            order.version++;
            store.storeRevision++;
            repository.write(store);
            throw new IllegalStateException("MOBILIZATION_EXPIRED");
        }
        order.status = MobilizationOrder.MobilizationStatus.ACTIVE;
        order.version++;
        store.storeRevision++;
        repository.write(store);
        return order;
    }

    public synchronized MobilizationOrder demobilize(String actorUuid, String mobilizationId) {
        MobilizationStore store = repository.read();
        MobilizationOrder order = require(store, mobilizationId);
        if (!java.util.Objects.equals(actorUuid, order.specialistUuid)) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "MOBILIZE_SPECIALISTS");
            context.subjectUuid = order.specialistUuid;
            context.stationId = order.stationId;
            context.jurisdiction = order.jurisdiction;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        if (order.status == MobilizationOrder.MobilizationStatus.COMPLETED) return order;
        if (order.status != MobilizationOrder.MobilizationStatus.ACTIVE
                && order.status != MobilizationOrder.MobilizationStatus.MUSTERED)
            throw new IllegalStateException("INVALID_STATE");
        order.status = MobilizationOrder.MobilizationStatus.DEMOBILIZING;
        order.demobilizedAt = clock.nowMillis();
        order.version++;
        store.storeRevision++;
        repository.write(store);
        order.status = MobilizationOrder.MobilizationStatus.COMPLETED;
        order.version++;
        createSettlement(order);
        store.storeRevision++;
        repository.write(store);
        return order;
    }

    public synchronized MobilizationOrder cancel(String actorUuid, String mobilizationId, String reason) {
        MobilizationStore store = repository.read();
        MobilizationOrder order = require(store, mobilizationId);
        if (!java.util.Objects.equals(actorUuid, order.specialistUuid)) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "MOBILIZE_SPECIALISTS");
            context.subjectUuid = order.specialistUuid;
            context.stationId = order.stationId;
            context.jurisdiction = order.jurisdiction;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        if (order.status == MobilizationOrder.MobilizationStatus.COMPLETED
                || order.status == MobilizationOrder.MobilizationStatus.EXPIRED) return order;
        order.status = MobilizationOrder.MobilizationStatus.CANCELLED;
        order.authorizationSource = (order.authorizationSource == null ? "" : order.authorizationSource)
                + (reason == null || reason.isBlank() ? "" : ":" + reason);
        order.version++; store.storeRevision++; repository.write(store); return order;
    }

    public synchronized int expireDue() {
        MobilizationStore store = repository.read();
        int count = 0;
        long now = clock.nowMillis();
        for (MobilizationOrder order : store.orders.values()) {
            if (order != null && (order.status == MobilizationOrder.MobilizationStatus.AUTHORIZED
                    || order.status == MobilizationOrder.MobilizationStatus.MUSTERED
                    || order.status == MobilizationOrder.MobilizationStatus.ACTIVE)
                    && now >= order.expiresAt) {
                order.status = MobilizationOrder.MobilizationStatus.EXPIRED;
                order.demobilizedAt = now;
                order.version++;
                createSettlement(order);
                count++;
            }
        }
        if (count > 0) { store.storeRevision++; repository.write(store); }
        return count;
    }

    public synchronized java.util.List<MobilizationOrder> all() {
        MobilizationStore store = repository.read();
        return store.orders == null ? java.util.List.of() : java.util.List.copyOf(store.orders.values());
    }

    public synchronized MobilizationOrder find(String mobilizationId) {
        MobilizationStore store = repository.read();
        return store.orders == null ? null : store.orders.get(mobilizationId);
    }

    private static MobilizationOrder require(MobilizationStore store, String id) {
        MobilizationOrder order = store.orders.get(id);
        if (order == null) throw new IllegalArgumentException("unknown mobilization: " + id);
        return order;
    }

    private void createSettlement(MobilizationOrder order) {
        if (settlements == null || order == null || order.settlementId != null && !order.settlementId.isBlank()) return;
        var settlement = settlements.create(order.specialistUuid,
                com.dwurdy.straja.domain.model.Settlement.SettlementCategory.MOBILIZATION,
                settlementAmount, "MOBILIZATION:" + order.mobilizationId + ":" + order.specialistUuid,
                order.mobilizationId);
        order.settlementId = settlement.settlementId;
        order.settlementAmount = settlementAmount;
    }
}
