package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.CampaignRepository;
import com.dwurdy.straja.application.port.out.EquipmentRepository;
import com.dwurdy.straja.application.port.out.OperationRepository;
import com.dwurdy.straja.application.port.out.OutboxRepository;
import com.dwurdy.straja.application.port.out.PersonnelRepository;
import com.dwurdy.straja.application.port.out.SettlementRepository;
import com.dwurdy.straja.application.port.out.StationRepository;
import com.dwurdy.straja.domain.model.CampaignStore;
import com.dwurdy.straja.domain.model.EquipmentObligation;
import com.dwurdy.straja.domain.model.EquipmentStore;
import com.dwurdy.straja.domain.model.OperationRecord;
import com.dwurdy.straja.domain.model.OperationStore;
import com.dwurdy.straja.domain.model.PersonnelStore;
import com.dwurdy.straja.domain.model.SettlementStore;
import com.dwurdy.straja.domain.model.StationStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only doctor checks. It never repairs an authoritative ledger implicitly. */
public final class ConsistencyService {
    private final PersonnelRepository personnel;
    private final StationRepository stations;
    private final EquipmentRepository equipment;
    private final OperationRepository operations;
    private final SettlementRepository settlements;
    private final CampaignRepository campaigns;
    private final OutboxRepository outbox;

    public ConsistencyService(PersonnelRepository personnel, StationRepository stations,
                              EquipmentRepository equipment, OperationRepository operations,
                              SettlementRepository settlements, CampaignRepository campaigns) {
        this(personnel, stations, equipment, operations, settlements, campaigns, null);
    }

    public ConsistencyService(PersonnelRepository personnel, StationRepository stations,
                              EquipmentRepository equipment, OperationRepository operations,
                              SettlementRepository settlements, CampaignRepository campaigns,
                              OutboxRepository outbox) {
        this.personnel = personnel; this.stations = stations; this.equipment = equipment;
        this.operations = operations; this.settlements = settlements; this.campaigns = campaigns; this.outbox = outbox;
    }

    public List<String> check() {
        List<String> issues = new ArrayList<>();
        checkPersonnel(issues, personnel.read()); checkStations(issues, stations.read());
        checkEquipment(issues, equipment.read()); checkOperations(issues, operations.read());
        checkSettlements(issues, settlements.read()); checkCampaigns(issues, campaigns.read());
        return issues;
    }

    /** OP3-safe scoped doctor report used by the command surface. */
    public List<String> check(String section) {
        if (section == null || section.isBlank() || "consistency".equalsIgnoreCase(section)) return check();
        List<String> issues = new ArrayList<>();
        switch (section.toLowerCase(java.util.Locale.ROOT)) {
            case "operations" -> checkOperations(issues, operations.read());
            case "equipment" -> checkEquipment(issues, equipment.read());
            case "settlements" -> checkSettlements(issues, settlements.read());
            case "stations" -> checkStations(issues, stations.read());
            case "outbox" -> checkOutbox(issues);
            default -> issues.add("doctor.unknown_scope");
        }
        return issues;
    }

    private static void checkPersonnel(List<String> issues, PersonnelStore store) {
        if (store.records == null) { issues.add("personnel.records.missing"); return; }
        for (var entry : store.records.entrySet()) if (entry.getValue() == null) issues.add("personnel.null:" + entry.getKey());
    }
    private static void checkStations(List<String> issues, StationStore store) {
        if (store.stations == null || !store.stations.containsKey("hq")) issues.add("stations.hq.missing");
        if (store.stations != null) for (var station : store.stations.values())
            if (station != null && station.fallbackStationId != null && !station.fallbackStationId.isBlank()
                    && !store.stations.containsKey(station.fallbackStationId)) issues.add("station.fallback.missing:" + station.stationId);
    }
    private static void checkEquipment(List<String> issues, EquipmentStore store) {
        if (store.obligations == null) return;
        for (EquipmentObligation obligation : store.obligations.values()) {
            if (obligation == null) continue;
            if (obligation.outstandingQuantity < 0 || obligation.outstandingQuantity > obligation.issuedQuantity)
                issues.add("equipment.quantity.invalid:" + obligation.obligationId);
        }
    }
    private static void checkOperations(List<String> issues, OperationStore store) {
        if (store.operations == null || store.idempotencyIndex == null) return;
        Set<String> activeKeys = new HashSet<>();
        for (OperationRecord operation : store.operations.values()) {
            if (operation == null) continue;
            if (operation.status != OperationRecord.OperationStatus.COMPLETED
                    && operation.status != OperationRecord.OperationStatus.ABORTED
                    && !activeKeys.add(operation.idempotencyKey)) issues.add("operation.duplicate:" + operation.idempotencyKey);
        }
    }
    private static void checkSettlements(List<String> issues, SettlementStore store) {
        if (store.settlements == null || store.settlementKeyIndex == null) return;
        Set<String> keys = new HashSet<>();
        for (var settlement : store.settlements.values()) if (settlement != null && !keys.add(settlement.settlementKey))
            issues.add("settlement.duplicate:" + settlement.settlementKey);
    }
    private static void checkCampaigns(List<String> issues, CampaignStore store) {
        if (store.campaigns == null || store.reservations == null) return;
        for (var campaign : store.campaigns.values()) if (campaign != null
                && (campaign.quantityReserved < 0 || campaign.quantityAccepted < 0
                || campaign.quantityAccepted + campaign.quantityReserved > campaign.globalQuota))
            issues.add("campaign.quota.invalid:" + campaign.campaignId);
    }

    private void checkOutbox(List<String> issues) {
        if (outbox == null) return;
        java.util.Set<String> dedupe = new java.util.HashSet<>();
        for (var event : outbox.read()) {
            if (event == null) { issues.add("outbox.null"); continue; }
            if (event.eventId == null || event.eventId.isBlank()) issues.add("outbox.id.missing");
            if (event.dedupeKey == null || event.dedupeKey.isBlank() || !dedupe.add(event.dedupeKey))
                issues.add("outbox.dedupe.invalid:" + event.eventId);
            if (event.attempts < 0) issues.add("outbox.attempts.invalid:" + event.eventId);
            if (event.safePayload == null || event.safePayload.isBlank()) issues.add("outbox.payload.missing:" + event.eventId);
            if (event.status == com.dwurdy.straja.domain.model.OutboxEvent.OutboxStatus.SENT && event.sentAt == null)
                issues.add("outbox.sent_without_timestamp:" + event.eventId);
        }
    }
}
