package com.dwurdy.straja.gametest;

import com.dwurdy.straja.application.service.EquipmentLedgerService;
import com.dwurdy.straja.application.service.OutboxService;
import com.dwurdy.straja.bootstrap.StrajaRuntime;
import com.dwurdy.straja.domain.model.AppointmentType;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.Complaint;
import com.dwurdy.straja.domain.model.DocumentType;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.MissionStatus;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Named V2 acceptance paths. These use the live runtime and its SavedData
 * repositories, so a passing test covers composition-root wiring as well as
 * the application services. The Discord path deliberately uses the persistent
 * outbox only; no test performs a real network call.
 */
@GameTestHolder("straja")
@PrefixGameTestTemplate(false)
public final class V2EndToEndGameTests {
    private V2EndToEndGameTests() {}

    private static StrajaRuntime runtime(GameTestHelper helper) {
        var runtime = StrajaRuntime.get();
        helper.assertTrue(runtime != null, "StrajaRuntime is not initialised on the game-test server");
        return runtime;
    }

    private static String id(String name) {
        String boot = StrajaRuntime.get() == null ? "pre-boot" : StrajaRuntime.get().bootId();
        return UUID.nameUUIDFromBytes(("straja-v2-e2e:" + boot + ":" + name)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String operation(String name) {
        return "e2e:" + (StrajaRuntime.get() == null ? "pre-boot" : StrajaRuntime.get().bootId())
                + ":" + name;
    }

    private static String commissioner(StrajaRuntime runtime) {
        String actor = id("commissioner");
        PersonnelRecord record = runtime.v2Personnel().find(actor);
        if (record == null) {
            record = runtime.v2Personnel().authorize("bootstrap", actor, CareerGrade.INSPECTOR,
                    EmploymentMode.FULL_TIME, "COMMISSIONER_BOOTSTRAP", "hq", operation("commissioner"));
        }
        if (!record.hasAppointment(AppointmentType.COMMISSIONER, runtime.nowMillis())) {
            runtime.v2Personnel().appointInternal(actor, AppointmentType.COMMISSIONER,
                    "hq", "", null);
        }
        return actor;
    }

    private static PersonnelRecord authorize(StrajaRuntime runtime, String actor, String subject,
                                             CareerGrade grade, EmploymentMode mode, String key) {
        PersonnelRecord existing = runtime.v2Personnel().find(subject);
        if (existing != null) return existing;
        return runtime.v2Personnel().authorize(actor, subject, grade, mode, "E2E", "hq", key);
    }

    @GameTest(template = "empty")
    public static void civil_recruitment_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        String recruit = id("civil-recruit");
        var record = authorize(runtime, actor, recruit, CareerGrade.MILITARY_STAGIAR,
                EmploymentMode.PART_TIME, operation("civil-recruit"));
        helper.assertTrue(record.active() && record.careerGrade == CareerGrade.MILITARY_STAGIAR,
                "civil recruitment must create an active Stagiar personnel record");
        helper.assertTrue(runtime.v2Documents().documents().stream().anyMatch(document ->
                        recruit.equals(document.subject) && document.type == DocumentType.APPOINTMENT_ORDER),
                "civil recruitment must issue an appointment order through the document ledger");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void specialist_recruitment_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        String specialist = id("specialist-recruit");
        var record = authorize(runtime, actor, specialist,
                CareerGrade.PROFESSIONAL_STAGIAR_SPECIALIST, EmploymentMode.PART_TIME,
                operation("specialist-recruit"));
        if (record.careerGrade == CareerGrade.PROFESSIONAL_STAGIAR_SPECIALIST) {
            runtime.v2Personnel().assignProfession(actor, specialist, "MINING");
            var application = runtime.v2Promotions().submit(specialist,
                    CareerGrade.PROFESSIONAL_SPECIALIST);
            runtime.v2Promotions().addEvidence(application.applicationId, actor, "EXAM",
                    "E2E", "PASS", 100d, "e2e:specialist-exam");
            var ready = runtime.v2Promotions().markReady(application.applicationId);
            runtime.v2Promotions().approve(actor, application.applicationId, ready.version);
        }
        helper.assertTrue(runtime.v2Personnel().find(specialist).careerGrade
                        == CareerGrade.PROFESSIONAL_SPECIALIST,
                "specialist recruitment must finish at the professional Specialist grade");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void specialist_mobilization_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        String sergeant = id("mobilization-sergeant");
        String specialist = id("mobilization-specialist");
        authorize(runtime, actor, sergeant, CareerGrade.MILITARY_SERGENT,
                EmploymentMode.FULL_TIME, operation("mobilization-sergeant"));
        authorize(runtime, actor, specialist, CareerGrade.PROFESSIONAL_SPECIALIST,
                EmploymentMode.PART_TIME, operation("mobilization-specialist"));
        var order = runtime.v2Mobilizations().authorize(sergeant, specialist, "hq", "district",
                60_000, "E2E-MISSION", "", "E2E");
        runtime.v2Mobilizations().muster(specialist, order.mobilizationId);
        runtime.v2Mobilizations().activate(specialist, order.mobilizationId);
        var scoped = AuthorizationContext.of(specialist, "USE_CUFFS");
        scoped.stationId = "hq";
        scoped.jurisdiction = "district";
        helper.assertTrue(runtime.v2Authorization().allowed(scoped),
                "active mobilization must grant scoped operational capability");
        runtime.v2Mobilizations().demobilize(specialist, order.mobilizationId);
        helper.assertFalse(runtime.v2Authorization().allowed(scoped),
                "demobilization must remove temporary capability");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void promotion_approval_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        String candidate = id("promotion-candidate");
        authorize(runtime, actor, candidate, CareerGrade.MILITARY_STAGIAR,
                EmploymentMode.PART_TIME, operation("promotion-candidate"));
        var application = runtime.v2Promotions().submit(candidate, CareerGrade.MILITARY_STRAJER);
        runtime.v2Promotions().addEvidence(application.applicationId, actor, "EXAM",
                "E2E", "PASS", 100d, "e2e:promotion-exam");
        var ready = runtime.v2Promotions().markReady(application.applicationId);
        runtime.v2Promotions().approve(actor, application.applicationId, ready.version);
        helper.assertTrue(runtime.v2Personnel().find(candidate).careerGrade
                        == CareerGrade.MILITARY_STRAJER,
                "commissioner approval must apply the approved promotion exactly once");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void document_redemption_restart_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        var instrument = runtime.v2Documents().issueInstrument(actor, actor,
                DocumentType.REQUISITION_TICKET, 2, "equipment", "hq", null,
                operation("document-instrument"));
        var first = runtime.v2Documents().redeem(actor, instrument.instrumentId, 1,
                "hq", operation("document-redeem"));
        var replay = runtime.v2Documents().redeem(actor, instrument.instrumentId, 1,
                "hq", operation("document-redeem"));
        helper.assertTrue(first.accepted() && replay.replayed(),
                "document redemption must remain idempotent across a persisted service reload");
        helper.assertTrue(runtime.v2Documents().instruments().stream()
                        .filter(value -> instrument.instrumentId.equals(value.instrumentId))
                        .findFirst().orElseThrow().remainingQuantity == 1,
                "a replayed redemption must consume the instrument only once");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void equipment_partial_return_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        String player = id("equipment-player");
        var instrument = runtime.v2Documents().issueInstrument(actor, actor,
                DocumentType.REQUISITION_TICKET, 2, "equipment", "hq", null,
                operation("equipment-instrument"));
        var issue = runtime.v2EquipmentLedger().createIssue(actor, player, "hq",
                instrument.instrumentId, "", List.of(new EquipmentLedgerService.RequestedLine(
                        "straja:baton", 2)), operation("equipment-issue"));
        var line = issue.lines.get(0);
        runtime.v2EquipmentLedger().fulfillLine(issue.issueId, line.lineId, 2, "asset-e2e",
                operation("equipment-delivery"));
        var partial = runtime.v2EquipmentLedger().returnQuantity(player, player, "straja:baton",
                1, operation("equipment-return"));
        var replay = runtime.v2EquipmentLedger().returnQuantity(player, player, "straja:baton",
                1, operation("equipment-return"));
        helper.assertTrue(partial.accepted() && partial.returnedQuantity() == 1
                        && "REPLAYED".equals(replay.reason()),
                "partial equipment return must be bounded and idempotent");
        helper.assertTrue(runtime.v2EquipmentLedger().obligationsFor(player).stream()
                        .anyMatch(obligation -> obligation.outstandingQuantity == 1),
                "partial return must leave one outstanding obligation");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void campaign_quota_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        String worker = id("campaign-worker");
        authorize(runtime, actor, worker, CareerGrade.MILITARY_STRAJER,
                EmploymentMode.PART_TIME, "e2e:campaign-worker");
        long now = runtime.nowMillis();
        var campaign = runtime.v2Campaigns().create(actor, "E2E", "hq", "", now,
                now + 60_000, 1);
        runtime.v2Campaigns().start(actor, campaign.campaignId);
        var mission = runtime.v2Missions().publish(actor, worker, "hq", "", "E2E_CAMPAIGN",
                now + 60_000, 1);
        var reservation = runtime.v2Campaigns().reserve(campaign.campaignId, mission.id, worker,
                1, operation("campaign-reservation"));
        runtime.v2Missions().attachQuotaReservation(actor, mission.id, reservation.reservationId);
        runtime.v2Missions().claim(worker, mission.id);
        runtime.v2Missions().begin(worker, mission.id);
        runtime.v2Missions().submit(worker, mission.id, "e2e-evidence");
        runtime.v2Missions().verify(actor, mission.id);
        runtime.v2Missions().complete(actor, mission.id, 0);
        helper.assertTrue(runtime.context().missions().read().find(mission.id).v2Status == MissionStatus.COMPLETED
                        && runtime.v2Campaigns().find(campaign.campaignId).quantityAccepted == 1,
                "mission completion must consume the linked campaign reservation once");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void complaint_escalation_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        String actor = commissioner(runtime);
        var data = runtime.context().complaints().read();
        var complaint = new Complaint();
        complaint.id = data.nextComplaintId();
        complaint.complainant = "e2e-citizen";
        complaint.complainantUuid = id("complainant");
        complaint.accused = "e2e-accused";
        complaint.accusedUuid = id("accused");
        complaint.category = "E2E";
        complaint.description = "persisted complaint";
        complaint.status = "SUBMITTED";
        complaint.createdAt = runtime.nowMillis();
        data.complaints.add(complaint);
        runtime.context().complaints().write(data);
        runtime.v2ComplaintEscalation().assignDeadline(complaint.id, 1);
        runtime.clock().advance(2);
        helper.assertTrue(runtime.v2ComplaintEscalation().escalateDue(actor,  "") == 1,
                "an overdue complaint must escalate once");
        var escalated = runtime.context().complaints().read().find(complaint.id);
        helper.assertTrue("ESCALATED".equals(escalated.status)
                        && escalated.investigationMissionId.startsWith("INVESTIGATION:"),
                "escalation must retain a deterministic investigation link");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void multi_station_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        runtime.v2Stations().create("e2e-north", "North");
        runtime.v2Stations().create("e2e-south", "South");
        runtime.v2Stations().setJurisdictions("e2e-north", List.of("north"));
        runtime.v2Stations().setJurisdictions("e2e-south", List.of("south"));
        runtime.v2Stations().setFallback("e2e-north", "e2e-south");
        helper.assertTrue(runtime.v2Stations().resolve("e2e-north", "north", "E2E").station()
                        .stationId.equals("e2e-north"),
                "a local station must serve its own jurisdiction");
        helper.assertTrue(runtime.v2Stations().resolve("e2e-north", "south", "E2E").station()
                        .stationId.equals("e2e-south"),
                "fallback must resolve to a station that serves the jurisdiction");
        helper.assertTrue(!runtime.v2Stations().resolve("e2e-north", "other", "E2E").available(),
                "fallback must fail closed when no station serves the jurisdiction");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void discord_outbox_persistence_flow(GameTestHelper helper) {
        var runtime = runtime(helper);
        var payload = OutboxService.SafePayload.projection("PERSONNEL_AUTHORIZED", "aggregate-e2e",
                id("outbox-subject"));
        var first = runtime.v2Outbox().enqueue("PERSONNEL_AUTHORIZED", "e2e:outbox:event", payload);
        var replay = runtime.v2Outbox().enqueue("PERSONNEL_AUTHORIZED", "e2e:outbox:event", payload);
        helper.assertTrue(first.eventId.equals(replay.eventId),
                "outbox enqueue must coalesce duplicate logical events");
        helper.assertTrue(runtime.v2Outbox().all().stream()
                        .filter(event -> "e2e:outbox:event".equals(event.dedupeKey)).count() == 1,
                "persistent outbox must contain one logical event");
        helper.succeed();
    }
}
