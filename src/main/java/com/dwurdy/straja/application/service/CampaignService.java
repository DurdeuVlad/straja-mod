package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.CampaignRepository;
import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.CampaignStore;
import com.dwurdy.straja.domain.model.MissionCampaign;
import com.dwurdy.straja.domain.model.QuotaReservation;

public final class CampaignService {
    private final CampaignRepository repository;
    private final Clock clock;
    private final IdGenerator ids;
    private final AuthorizationService authorization;
    private java.util.function.Consumer<MissionCampaign> lifecycleListener = ignored -> {};

    public CampaignService(CampaignRepository repository, Clock clock, IdGenerator ids) {
        this(repository, clock, ids, null);
    }

    public CampaignService(CampaignRepository repository, Clock clock, IdGenerator ids,
                           AuthorizationService authorization) {
        this.repository = repository; this.clock = clock; this.ids = ids; this.authorization = authorization;
    }

    public void onLifecycle(java.util.function.Consumer<MissionCampaign> listener) {
        this.lifecycleListener = listener == null ? ignored -> {} : listener;
    }

    public synchronized MissionCampaign create(String actorUuid, String type, String stationId,
                                                String jurisdiction, long startsAt, long deadline, long quota) {
        if (quota <= 0 || deadline <= startsAt) throw new IllegalArgumentException("invalid campaign window/quota");
        authorize(actorUuid, stationId, jurisdiction);
        CampaignStore store = repository.read();
        MissionCampaign campaign = new MissionCampaign();
        campaign.campaignId = ids.newId("CAM"); campaign.type = type == null ? "" : type;
        campaign.stationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        campaign.jurisdiction = jurisdiction == null ? "" : jurisdiction;
        campaign.startsAt = startsAt; campaign.globalDeadline = deadline; campaign.globalQuota = quota;
        campaign.status = MissionCampaign.CampaignStatus.SCHEDULED;
        store.campaigns.put(campaign.campaignId, campaign); store.storeRevision++; repository.write(store);
        return campaign;
    }

    public synchronized MissionCampaign start(String campaignId) {
        return start(null, campaignId);
    }

    public synchronized MissionCampaign start(String actorUuid, String campaignId) {
        CampaignStore store = repository.read(); MissionCampaign c = require(store, campaignId);
        authorize(actorUuid, c.stationId, c.jurisdiction);
        if (c.status == MissionCampaign.CampaignStatus.ACTIVE) return c;
        if (c.status != MissionCampaign.CampaignStatus.SCHEDULED && c.status != MissionCampaign.CampaignStatus.DRAFT)
            throw new IllegalStateException("invalid campaign state");
        c.status = MissionCampaign.CampaignStatus.ACTIVE; c.version++; store.storeRevision++; repository.write(store);
        lifecycleListener.accept(c); return c;
    }

    public synchronized MissionCampaign end(String actorUuid, String campaignId) {
        CampaignStore store = repository.read(); MissionCampaign c = require(store, campaignId);
        authorize(actorUuid, c.stationId, c.jurisdiction);
        if (c.status == MissionCampaign.CampaignStatus.COMPLETED) return c;
        if (c.status != MissionCampaign.CampaignStatus.ACTIVE && c.status != MissionCampaign.CampaignStatus.CLOSING)
            throw new IllegalStateException("invalid campaign state");
        c.status = MissionCampaign.CampaignStatus.COMPLETED; c.version++; store.storeRevision++; repository.write(store);
        lifecycleListener.accept(c); return c;
    }

    public synchronized MissionCampaign cancel(String actorUuid, String campaignId, String reason) {
        CampaignStore store = repository.read(); MissionCampaign c = require(store, campaignId);
        authorize(actorUuid, c.stationId, c.jurisdiction);
        if (c.status == MissionCampaign.CampaignStatus.CANCELLED) return c;
        if (c.status == MissionCampaign.CampaignStatus.COMPLETED)
            throw new IllegalStateException("campaign already completed");
        c.status = MissionCampaign.CampaignStatus.CANCELLED;
        c.cancellationReason = reason == null ? "" : reason;
        c.version++;
        releaseOutstanding(store, c, clock.nowMillis());
        store.storeRevision++; repository.write(store); lifecycleListener.accept(c); return c;
    }

    public synchronized MissionCampaign extend(String actorUuid, String campaignId, long extensionMillis) {
        if (extensionMillis <= 0) throw new IllegalArgumentException("extension must be positive");
        CampaignStore store = repository.read(); MissionCampaign c = require(store, campaignId);
        authorize(actorUuid, c.stationId, c.jurisdiction);
        if (c.status != MissionCampaign.CampaignStatus.ACTIVE
                && c.status != MissionCampaign.CampaignStatus.SCHEDULED)
            throw new IllegalStateException("invalid campaign state");
        c.globalDeadline += extensionMillis; c.version++;
        store.storeRevision++; repository.write(store); return c;
    }

    public synchronized QuotaReservation reserve(String campaignId, String missionId, String playerUuid,
                                                  long quantity, String operationId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (operationId == null || operationId.isBlank())
            throw new IllegalArgumentException("operation id required");
        CampaignStore store = repository.read(); MissionCampaign c = require(store, campaignId);
        if (authorization != null) {
            AuthorizationContext context = AuthorizationContext.of(playerUuid, "SELF_SERVICE_FORMS");
            context.subjectUuid = playerUuid; context.beneficiaryUuid = playerUuid;
            context.stationId = c.stationId; context.jurisdiction = c.jurisdiction;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        }
        for (QuotaReservation existing : store.reservations.values()) {
            if (existing != null && operationId.equals(existing.operationId)) {
                if (!campaignId.equals(existing.campaignId)
                        || !java.util.Objects.equals(missionId == null ? "" : missionId, existing.missionId)
                        || !java.util.Objects.equals(playerUuid, existing.playerUuid)
                        || existing.reservedQuantity != quantity)
                    throw new IllegalStateException("OPERATION_PAYLOAD_MISMATCH");
                return existing;
            }
        }
        if (!c.canReserve(quantity)) throw new IllegalStateException("QUOTA_EXHAUSTED");
        QuotaReservation reservation = new QuotaReservation();
        reservation.reservationId = ids.newId("QUO"); reservation.campaignId = campaignId;
        reservation.missionId = missionId == null ? "" : missionId; reservation.playerUuid = playerUuid;
        reservation.reservedQuantity = quantity; reservation.expiresAt = c.globalDeadline;
        reservation.operationId = operationId;
        c.quantityReserved += quantity;
        store.reservations.put(reservation.reservationId, reservation); store.storeRevision++; repository.write(store);
        return reservation;
    }

    public synchronized QuotaReservation fulfill(String reservationId, long quantity) {
        return fulfill(reservationId, quantity, "legacy:" + reservationId + ":" + quantity);
    }

    /** Idempotent delivery against one reservation. */
    public synchronized QuotaReservation fulfill(String reservationId, long quantity, String operationId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operation id required");
        CampaignStore store = repository.read(); QuotaReservation r = requireReservation(store, reservationId);
        if (r.fulfillmentOperations == null) r.fulfillmentOperations = new java.util.LinkedHashMap<>();
        Long replayQuantity = r.fulfillmentOperations.get(operationId);
        if (replayQuantity != null) {
            if (replayQuantity.longValue() != quantity) throw new IllegalStateException("OPERATION_PAYLOAD_MISMATCH");
            return r;
        }
        if (r.status != QuotaReservation.ReservationStatus.RESERVED
                && r.status != QuotaReservation.ReservationStatus.PARTIALLY_FULFILLED)
            throw new IllegalStateException("RESERVATION_NOT_DELIVERABLE");
        MissionCampaign campaign = store.campaigns.get(r.campaignId);
        if (campaign == null) throw new IllegalStateException("reservation campaign missing");
        long remaining = r.reservedQuantity - r.fulfilledQuantity;
        if (quantity > remaining) throw new IllegalArgumentException("fulfillment exceeds reservation");
        r.fulfilledQuantity += quantity;
        r.status = r.fulfilledQuantity == r.reservedQuantity ? QuotaReservation.ReservationStatus.FULFILLED
                : QuotaReservation.ReservationStatus.PARTIALLY_FULFILLED;
        r.fulfillmentOperations.put(operationId, quantity);
        r.version++;
        campaign.quantityReserved = Math.max(0, campaign.quantityReserved - quantity);
        campaign.quantityAccepted += quantity;
        store.storeRevision++; repository.write(store); return r;
    }

    public synchronized int expireDue() {
        CampaignStore store = repository.read(); long now = clock.nowMillis(); int changed = 0;
        for (MissionCampaign campaign : store.campaigns.values()) {
            if (campaign != null && (campaign.status == MissionCampaign.CampaignStatus.ACTIVE
                    || campaign.status == MissionCampaign.CampaignStatus.SCHEDULED)
                    && now >= campaign.globalDeadline) {
                campaign.status = MissionCampaign.CampaignStatus.EXPIRED;
                campaign.version++;
                changed++;
            }
        }
        for (QuotaReservation r : store.reservations.values()) {
            if (r != null && (r.status == QuotaReservation.ReservationStatus.RESERVED
                    || r.status == QuotaReservation.ReservationStatus.PARTIALLY_FULFILLED)
                    && now >= r.expiresAt) {
                r.status = QuotaReservation.ReservationStatus.EXPIRED;
                MissionCampaign c = store.campaigns.get(r.campaignId);
                if (c != null) c.quantityReserved = Math.max(0,
                        c.quantityReserved - Math.max(0, r.reservedQuantity - r.fulfilledQuantity));
                r.version++;
                changed++;
            }
        }
        if (changed > 0) { store.storeRevision++; repository.write(store); } return changed;
    }

    public synchronized java.util.List<MissionCampaign> all() {
        CampaignStore store = repository.read();
        return store.campaigns == null ? java.util.List.of() : java.util.List.copyOf(store.campaigns.values());
    }

    public synchronized MissionCampaign find(String campaignId) {
        CampaignStore store = repository.read();
        return store.campaigns == null ? null : store.campaigns.get(campaignId);
    }

    public synchronized java.util.List<QuotaReservation> reservations(String campaignId) {
        CampaignStore store = repository.read();
        if (store.reservations == null) return java.util.List.of();
        return store.reservations.values().stream()
                .filter(value -> value != null && java.util.Objects.equals(campaignId, value.campaignId))
                .toList();
    }

    public synchronized QuotaReservation findReservation(String reservationId) {
        CampaignStore store = repository.read();
        return store.reservations == null ? null : store.reservations.get(reservationId);
    }

    /** Releases only the still-reserved quantity; fulfilled delivery is immutable. */
    public synchronized QuotaReservation release(String reservationId) {
        CampaignStore store = repository.read();
        QuotaReservation reservation = requireReservation(store, reservationId);
        if (reservation.status == QuotaReservation.ReservationStatus.RELEASED
                || reservation.status == QuotaReservation.ReservationStatus.EXPIRED
                || reservation.status == QuotaReservation.ReservationStatus.FULFILLED) return reservation;
        MissionCampaign campaign = store.campaigns.get(reservation.campaignId);
        if (campaign != null) {
            campaign.quantityReserved = Math.max(0,
                    campaign.quantityReserved - Math.max(0, reservation.reservedQuantity - reservation.fulfilledQuantity));
        }
        reservation.status = QuotaReservation.ReservationStatus.RELEASED;
        reservation.version++;
        store.storeRevision++;
        repository.write(store);
        return reservation;
    }

    private static MissionCampaign require(CampaignStore store, String id) { MissionCampaign c = store.campaigns.get(id); if (c == null) throw new IllegalArgumentException("unknown campaign"); return c; }
    private static QuotaReservation requireReservation(CampaignStore store, String id) { QuotaReservation r = store.reservations.get(id); if (r == null) throw new IllegalArgumentException("unknown reservation"); return r; }

    private static void releaseOutstanding(CampaignStore store, MissionCampaign campaign, long now) {
        for (QuotaReservation r : store.reservations.values()) {
            if (r == null || !campaign.campaignId.equals(r.campaignId)) continue;
            if (r.status == QuotaReservation.ReservationStatus.RESERVED
                    || r.status == QuotaReservation.ReservationStatus.PARTIALLY_FULFILLED) {
                campaign.quantityReserved = Math.max(0,
                        campaign.quantityReserved - Math.max(0, r.reservedQuantity - r.fulfilledQuantity));
                r.status = QuotaReservation.ReservationStatus.RELEASED;
                r.version++;
            }
        }
    }

    private void authorize(String actorUuid, String stationId, String jurisdiction) {
        if (authorization == null || actorUuid == null || actorUuid.isBlank()) return;
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "MANAGE_CAMPAIGNS");
        context.stationId = stationId; context.jurisdiction = jurisdiction;
        if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
    }
}
