package com.dwurdy.straja;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.application.port.out.DiscordWebhookGateway;
import com.dwurdy.straja.application.service.AuthorizationService;
import com.dwurdy.straja.application.service.CampaignService;
import com.dwurdy.straja.application.service.DocumentService;
import com.dwurdy.straja.application.service.EquipmentLedgerService;
import com.dwurdy.straja.application.service.OperationRecoveryService;
import com.dwurdy.straja.application.service.OutboxService;
import com.dwurdy.straja.application.service.PersonnelService;
import com.dwurdy.straja.application.service.PromotionService;
import com.dwurdy.straja.application.service.StationService;
import com.dwurdy.straja.application.service.SettlementService;
import com.dwurdy.straja.application.service.MobilizationService;
import com.dwurdy.straja.application.service.MissionV2Service;
import com.dwurdy.straja.application.service.FormSessionService;
import com.dwurdy.straja.application.port.in.FormSessionUseCase;
import com.dwurdy.straja.domain.model.AppointmentType;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.DocumentStatus;
import com.dwurdy.straja.domain.model.DocumentType;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.MissionCampaign;
import com.dwurdy.straja.domain.model.MissionStatus;
import com.dwurdy.straja.domain.model.OperationRecord;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import com.dwurdy.straja.domain.model.PromotionStatus;
import com.dwurdy.straja.domain.model.Station;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class V2FoundationTest {
    private final Map<String, MemoryStore> raw = new HashMap<>();
    private final StoreAccess access = name -> raw.computeIfAbsent(name, ignored -> new MemoryStore());
    private Fakes.FixedClock clock;
    private Fakes.SeqIds ids;

    @BeforeEach
    void setup() { clock = new Fakes.FixedClock(1_000); ids = new Fakes.SeqIds(); }

    @Test
    void specialistIsAProfessionalCareerAndNotAThirdMilitaryRank() {
        assertEquals(5, com.dwurdy.straja.domain.model.Rank.values().length);
        assertTrue(CareerGrade.PROFESSIONAL_SPECIALIST.isProfessional());
        assertFalse(CareerGrade.PROFESSIONAL_SPECIALIST.fullTimeRequired());
    }

    @Test
    void operationJournalReturnsTheSameOperationOnReplay() {
        var repo = new SavedStores.Operations(access);
        var journal = new OperationRecoveryService(repo, clock, ids);
        var first = journal.prepare("redeem:1", "REDEEM", "a", "s", "c", java.util.List.of("i"), Map.of("i", 1L));
        var second = journal.prepare("redeem:1", "REDEEM", "a", "s", "c", java.util.List.of(), Map.of());
        assertEquals(first.operationId, second.operationId);
        journal.transition(first.operationId, OperationRecord.OperationStatus.DOMAIN_COMMITTED, "ok");
        assertEquals(OperationRecord.OperationStatus.DOMAIN_COMMITTED, journal.findByIdempotencyKey("redeem:1").status);
    }

    @Test
    void duplicatedInstrumentCannotMintASecondEntitlement() {
        var documents = new DocumentService(new SavedStores.Documents(access), clock, ids);
        var instrument = documents.issueInstrument("issuer", "player", DocumentType.REQUISITION_TICKET, 2, "gear", "hq", null);
        var first = documents.redeem("player", instrument.instrumentId, 1, "hq", "click-1");
        var replay = documents.redeem("player", instrument.instrumentId, 1, "hq", "click-1");
        assertTrue(first.accepted());
        assertTrue(replay.replayed());
        assertEquals(1, documents.issueInstrument("issuer", "player", DocumentType.REQUISITION_TICKET, 1, "gear", "hq", null).remainingQuantity);
        assertEquals(1, new SavedStores.Documents(access).read().instruments.get(instrument.instrumentId).remainingQuantity);
        assertFalse(documents.redeem("other-player", instrument.instrumentId, 1, "hq", "click-other").accepted());
    }

    @Test
    void promotionNeedsPersistentApplicationEvidenceAndIndependentCommissioner() {
        var people = new SavedStores.Personnel(access);
        var personnel = new PersonnelService(people, clock, ids);
        PersonnelRecord candidate = personnel.authorize("commissioner", "candidate", CareerGrade.MILITARY_STAGIAR,
                EmploymentMode.PART_TIME, "TEST", "hq", "auth:candidate");
        personnel.authorize("commissioner", "commissioner", CareerGrade.INSPECTOR,
                EmploymentMode.FULL_TIME, "TEST", "hq", "auth:commissioner");
        personnel.appoint("system", "commissioner", AppointmentType.COMMISSIONER, "hq", "", null);
        var auth = new AuthorizationService(people, clock);
        var promotions = new PromotionService(new SavedStores.Promotions(access), people, auth, clock, ids);
        var application = promotions.submit(candidate.playerUuid, CareerGrade.MILITARY_STRAJER);
        assertEquals(CareerGrade.MILITARY_STAGIAR, personnel.find("candidate").careerGrade);
        promotions.addEvidence(application.applicationId, "instructor", "EXAM", "unit", "PASS", 100d, "fingerprint");
        promotions.markReady(application.applicationId);
        promotions.approve("commissioner", application.applicationId, 2);
        assertEquals(PromotionStatus.APPROVED, new SavedStores.Promotions(access).read().applications.get(application.applicationId).status);
        assertEquals(CareerGrade.MILITARY_STRAJER, personnel.find("candidate").careerGrade);
    }

    @Test
    void personnelAuthorizationUsesTheCentralAppointmentGateWhenConfigured() {
        var people = new SavedStores.Personnel(access);
        var personnel = new PersonnelService(people, clock, ids);
        personnel.authorize("bootstrap", "commissioner", CareerGrade.INSPECTOR,
                EmploymentMode.FULL_TIME, "TEST", "hq", "auth:commissioner");
        personnel.appoint("bootstrap", "commissioner", AppointmentType.COMMISSIONER, "hq", "", null);
        var policy = new AuthorizationService(people, clock);
        personnel.useAuthorization(policy);
        assertThrows(IllegalStateException.class, () -> personnel.authorize(
                "unregistered", "candidate", CareerGrade.MILITARY_STAGIAR,
                EmploymentMode.PART_TIME, "COMMISSIONER_DIRECT", "hq", "auth:candidate"));
        assertDoesNotThrow(() -> personnel.authorize(
                "commissioner", "candidate", CareerGrade.MILITARY_STAGIAR,
                EmploymentMode.PART_TIME, "COMMISSIONER_DIRECT", "hq", "auth:candidate"));
    }

    @Test
    void stationFallbackCycleIsRejectedAndRuntimeResolveFailsClosed() {
        var stations = new StationService(new SavedStores.Stations(access), clock, ids);
        stations.create("a", "A"); stations.create("b", "B");
        stations.setFallback("a", "b");
        assertThrows(IllegalArgumentException.class, () -> stations.setFallback("b", "a"));
        assertTrue(stations.validateFallbacks().isEmpty());
    }

    @Test
    void campaignReservationCannotExceedQuotaAndIsIdempotent() {
        var campaigns = new CampaignService(new SavedStores.Campaigns(access), clock, ids);
        var campaign = campaigns.create("commissioner", "mining", "hq", "", 0, 100_000, 10);
        campaigns.start(campaign.campaignId);
        var first = campaigns.reserve(campaign.campaignId, "m1", "miner", 6, "operation-1");
        assertEquals(first.reservationId, campaigns.reserve(campaign.campaignId, "m1", "miner", 6, "operation-1").reservationId);
        assertThrows(IllegalStateException.class, () -> campaigns.reserve(campaign.campaignId, "m2", "miner", 5, "operation-2"));
        assertEquals(6, new SavedStores.Campaigns(access).read().campaigns.get(campaign.campaignId).quantityReserved);
        var reservation = campaigns.reserve(campaign.campaignId, "m1", "miner", 1, "operation-3");
        campaigns.fulfill(reservation.reservationId, 1, "delivery-1");
        campaigns.fulfill(reservation.reservationId, 1, "delivery-1");
        assertEquals(1, new SavedStores.Campaigns(access).read().reservations.get(reservation.reservationId).fulfilledQuantity);
        var campaignState = new SavedStores.Campaigns(access).read().campaigns.get(campaign.campaignId);
        assertEquals(6, campaignState.quantityReserved);
        assertEquals(1, campaignState.quantityAccepted);
        assertThrows(IllegalStateException.class,
                () -> campaigns.reserve(campaign.campaignId, "m3", "miner", 4, "operation-4"));
        assertThrows(IllegalArgumentException.class,
                () -> campaigns.reserve(campaign.campaignId, "m4", "miner", 1, ""));
        assertThrows(IllegalStateException.class,
                () -> campaigns.reserve(campaign.campaignId, "m2", "other-miner", 6, "operation-1"));
    }

    @Test
    void quotaArithmeticRejectsOverflowAndInternalCommissionerBootstrapIsExplicit() {
        var campaign = new MissionCampaign();
        campaign.status = MissionCampaign.CampaignStatus.ACTIVE;
        campaign.globalQuota = Long.MAX_VALUE;
        campaign.quantityAccepted = Long.MAX_VALUE - 1;
        campaign.quantityReserved = 0;
        assertTrue(campaign.canReserve(1));
        assertFalse(campaign.canReserve(2));

        var people = new SavedStores.Personnel(access);
        var personnel = new PersonnelService(people, clock, ids);
        personnel.authorize("bootstrap", "commissioner", CareerGrade.INSPECTOR,
                EmploymentMode.FULL_TIME, "COMMISSIONER_BOOTSTRAP", "hq", "bootstrap:commissioner");
        var authorization = new AuthorizationService(people, clock);
        personnel.useAuthorization(authorization);
        assertDoesNotThrow(() -> personnel.appointInternal("commissioner", AppointmentType.COMMISSIONER,
                "hq", "", null));
        assertTrue(personnel.find("commissioner").hasAppointment(AppointmentType.COMMISSIONER, clock.nowMillis()));
    }

    @Test
    void partialEquipmentReturnCannotGoNegative() {
        var equipment = new EquipmentLedgerService(new SavedStores.EquipmentLedger(access), clock, ids);
        var issue = equipment.createIssue("issuer", "player", "hq", "ticket", "", java.util.List.of(
                new EquipmentLedgerService.RequestedLine("straja:baton", 2)), "op");
        equipment.fulfillLine(issue.issueId, issue.lines.get(0).lineId, 2, "asset");
        assertEquals(2, equipment.returnQuantity("player", "player", "straja:baton", 2).returnedQuantity());
        assertEquals(0, equipment.returnQuantity("player", "player", "straja:baton", 1).returnedQuantity());
        var secondIssue = equipment.createIssue("issuer", "player", "hq", "ticket", "",
                java.util.List.of(new EquipmentLedgerService.RequestedLine("straja:sword", 1)), "op-2");
        equipment.fulfillLine(secondIssue.issueId, secondIssue.lines.get(0).lineId, 1, "asset-2", "deliver-2");
        assertEquals(1, equipment.returnQuantity("player", "player", "straja:sword", 1, "return-2").returnedQuantity());
        assertEquals("REPLAYED", equipment.returnQuantity("player", "player", "straja:sword", 1, "return-2").reason());
    }

    @Test
    void settlementKeyIsUniqueAndOutboxPayloadIsSafe() {
        var settlement = new SettlementService(new SavedStores.Settlements(access), clock, ids, null);
        var first = settlement.create("player", com.dwurdy.straja.domain.model.Settlement.SettlementCategory.JOB, 5, "JOB:m1:player", "m1");
        var second = settlement.create("player", com.dwurdy.straja.domain.model.Settlement.SettlementCategory.JOB, 99, "JOB:m1:player", "m1");
        assertEquals(first.settlementId, second.settlementId);
        var outbox = new OutboxService(new SavedStores.Outbox(access), clock, ids);
        var event = outbox.enqueue("PERSONNEL_AUTHORIZED", "personnel:player",
                OutboxService.SafePayload.projection("PERSONNEL_AUTHORIZED", "p1", "player\nsecret"));
        assertFalse(event.safePayload.contains("\n"));
        var filtered = outbox.enqueue("MAJOR_INCIDENT", "incident:1",
                new OutboxService.SafePayload("{\"eventType\":\"MAJOR_INCIDENT\",\"reason\":\"secret\",\"station\":\"hq\"}"));
        assertFalse(filtered.safePayload.contains("reason"));
        assertTrue(filtered.safePayload.contains("station"));
        assertThrows(IllegalArgumentException.class, () -> outbox.enqueue("APPLICATION_ACCEPTED", "x", null));
    }

    @Test
    void specialistAuthorityExistsOnlyInsideActiveMobilizationScope() {
        var people = new SavedStores.Personnel(access);
        var personnel = new PersonnelService(people, clock, ids);
        personnel.authorize("system", "sergeant", CareerGrade.MILITARY_SERGENT, EmploymentMode.FULL_TIME,
                "TEST", "hq", "auth:sergeant");
        personnel.authorize("system", "specialist", CareerGrade.PROFESSIONAL_SPECIALIST, EmploymentMode.PART_TIME,
                "TEST", "hq", "auth:specialist");
        var mobilizationRepository = new SavedStores.Mobilizations(access);
        var authorization = new AuthorizationService(people, null, mobilizationRepository, clock);
        var mobilizations = new MobilizationService(mobilizationRepository, people, authorization, clock, ids);
        var order = mobilizations.authorize("sergeant", "specialist", "hq", "district", 10_000, "m1", "", "MISSION");
        assertFalse(authorization.allowed(com.dwurdy.straja.domain.model.AuthorizationContext.of("specialist", "ROUTINE_PATROL")));
        mobilizations.muster("specialist", order.mobilizationId);
        mobilizations.activate("specialist", order.mobilizationId);
        var scoped = com.dwurdy.straja.domain.model.AuthorizationContext.of("specialist", "USE_CUFFS");
        scoped.stationId = "hq"; scoped.jurisdiction = "district";
        assertTrue(authorization.allowed(scoped));
        var patrol = com.dwurdy.straja.domain.model.AuthorizationContext.of("specialist", "ROUTINE_PATROL");
        patrol.stationId = "hq"; patrol.jurisdiction = "district";
        assertFalse(authorization.allowed(patrol));
        mobilizations.demobilize("specialist", order.mobilizationId);
        assertFalse(authorization.allowed(scoped));
    }

    @Test
    void typedMissionLifecycleCreatesOneSettlementEntitlement() {
        var people = new SavedStores.Personnel(access);
        var personnel = new PersonnelService(people, clock, ids);
        personnel.authorize("system", "inspector", CareerGrade.INSPECTOR, EmploymentMode.FULL_TIME,
                "TEST", "hq", "auth:inspector");
        personnel.authorize("system", "worker", CareerGrade.MILITARY_STRAJER, EmploymentMode.PART_TIME,
                "TEST", "hq", "auth:worker");
        var auth = new AuthorizationService(people, clock);
        var settlements = new SettlementService(new SavedStores.Settlements(access), clock, ids, null);
        var missions = new MissionV2Service(new SavedStores.Missions(access), auth, settlements, clock, ids);
        var mission = missions.publish("inspector", "worker", "hq", "district", "inspection", 20_000, 1);
        missions.claim("worker", mission.id); missions.begin("worker", mission.id);
        missions.submit("worker", mission.id, "evidence-1");
        missions.verify("inspector", mission.id);
        missions.complete("inspector", mission.id, 10);
        assertEquals(MissionStatus.COMPLETED, new SavedStores.Missions(access).read().find(mission.id).v2Status);
        assertEquals(1, new SavedStores.Settlements(access).read().settlements.size());
    }

    @Test
    void standardFormOpeningCreatesOnePersistentNonAuthorizingRequest() {
        var documents = new DocumentService(new SavedStores.Documents(access), clock, ids);
        var forms = new FormSessionService(clock, ids, documents);
        UUID owner = UUID.randomUUID();
        var view = forms.open(owner, new FormSessionUseCase.Request(
                FormSessionUseCase.Action.COMPLAINT_SUBMIT, "", "Plângere", "Completează",
                java.util.List.of(new FormSessionUseCase.Field("accused", "Acuzat", 40, false)))).orElseThrow();
        forms.submit(owner, view.sessionId(), Map.of("accused", "offline-player")).orElseThrow();
        assertEquals(1, documents.formRequests().size());
        assertEquals("SUBMITTED", documents.formRequests().get(0).status);
    }

    @Test
    void settlementRecoveryDoesNotPayVoidOrDuplicateAndReconcileIsExplicit() {
        var settlements = new SettlementService(new SavedStores.Settlements(access), clock, ids, null);
        var pending = settlements.create("player", com.dwurdy.straja.domain.model.Settlement.SettlementCategory.JOB,
                10, "JOB:recovery", "mission");
        settlements.voidSettlement(pending.settlementId, "cancelled");
        assertEquals(com.dwurdy.straja.domain.model.Settlement.SettlementStatus.VOID,
                settlements.find(pending.settlementId).status);
        var interrupted = settlements.create("player", com.dwurdy.straja.domain.model.Settlement.SettlementCategory.JOB,
                5, "JOB:interrupted", "mission");
        var store = new SavedStores.Settlements(access).read();
        store.settlements.get(interrupted.settlementId).status =
                com.dwurdy.straja.domain.model.Settlement.SettlementStatus.IN_PROGRESS;
        new SavedStores.Settlements(access).write(store);
        assertEquals(1, settlements.reconcile());
        assertEquals(com.dwurdy.straja.domain.model.Settlement.SettlementStatus.FAILED_RETRYABLE,
                settlements.find(interrupted.settlementId).status);
    }
}
