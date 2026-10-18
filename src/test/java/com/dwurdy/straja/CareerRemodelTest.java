package com.dwurdy.straja;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.application.service.AuditService;
import com.dwurdy.straja.application.service.AuthorizationService;
import com.dwurdy.straja.application.service.PersonnelService;
import com.dwurdy.straja.application.service.PromotionService;
import com.dwurdy.straja.domain.model.AppointmentType;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.CareerTrack;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import com.dwurdy.straja.domain.model.PromotionStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Rank remodel (RANK-1): two part-time ladders — enlisted
 * STAGIAR→STRAJER, trades ZILIER→MESTER — feeding a shared full-time
 * officer corps LOCOTENENT→CAPITAN→COLONEL. Old enum names migrate on
 * store read; approving an officer petition upgrades to FULL_TIME from
 * either origin track.
 */
class CareerRemodelTest {
    private final Map<String, MemoryStore> raw = new HashMap<>();
    private final StoreAccess access = name -> raw.computeIfAbsent(name, ignored -> new MemoryStore());
    private Fakes.FixedClock clock;
    private Fakes.SeqIds ids;
    private SavedStores.Personnel people;
    private PersonnelService personnel;
    private PromotionService promotions;

    @BeforeEach
    void setup() {
        clock = new Fakes.FixedClock(1_000);
        ids = new Fakes.SeqIds();
        people = new SavedStores.Personnel(access);
        personnel = new PersonnelService(people, clock, ids);
        promotions = new PromotionService(new SavedStores.Promotions(access), people,
                new AuthorizationService(people, clock), clock, ids);
        personnel.authorize("bootstrap", "commissioner", CareerGrade.OFFICER_COLONEL,
                EmploymentMode.FULL_TIME, "TEST", "hq", "auth:commissioner");
        personnel.appoint("bootstrap", "commissioner", AppointmentType.COMMISSIONER, "hq", "", null);
    }

    private PersonnelRecord authorize(String subject, CareerGrade grade, EmploymentMode mode) {
        return personnel.authorize("commissioner", subject, grade, mode,
                "TEST", "hq", "auth:" + subject + ":" + grade);
    }

    private void petitionToReady(String subject, CareerGrade target) {
        var application = promotions.submit(subject, target);
        promotions.recordServerTrainingEvidence(application.applicationId, "instructor",
                "PETITION", true, null, "petition:" + subject + ":" + target);
        promotions.markReady(application.applicationId);
    }

    // ------------------------------------------------------------ migration

    @Test
    void legacyGradeNamesRemapOnPersonnelStoreRead() {
        access.store("personnel").put("json",
                "{\"schemaVersion\":1,\"storeRevision\":7,\"records\":{"
                        + "\"s\":{\"playerUuid\":\"s\",\"membershipStatus\":\"AUTHORIZED_ACTIVE\","
                        + "\"careerTrack\":\"MILITARY\",\"careerOrigin\":\"MILITARY\","
                        + "\"careerGrade\":\"MILITARY_SERGENT\",\"employmentMode\":\"FULL_TIME\"},"
                        + "\"i\":{\"playerUuid\":\"i\",\"membershipStatus\":\"AUTHORIZED_ACTIVE\","
                        + "\"careerTrack\":\"MILITARY\",\"careerOrigin\":\"MILITARY\","
                        + "\"careerGrade\":\"INSPECTOR\",\"employmentMode\":\"FULL_TIME\"},"
                        + "\"z\":{\"playerUuid\":\"z\",\"membershipStatus\":\"AUTHORIZED_ACTIVE\","
                        + "\"careerTrack\":\"PROFESSIONAL\",\"careerOrigin\":\"PROFESSIONAL\","
                        + "\"careerGrade\":\"PROFESSIONAL_STAGIAR_SPECIALIST\",\"employmentMode\":\"PART_TIME\"},"
                        + "\"m\":{\"playerUuid\":\"m\",\"membershipStatus\":\"AUTHORIZED_ACTIVE\","
                        + "\"careerTrack\":\"PROFESSIONAL\",\"careerOrigin\":\"PROFESSIONAL\","
                        + "\"careerGrade\":\"PROFESSIONAL_MAISTRU\",\"employmentMode\":\"FULL_TIME\"}"
                        + "}}");

        var store = people.read();
        assertEquals(CareerGrade.OFFICER_LOCOTENENT, store.get("s").careerGrade);
        assertEquals(CareerGrade.OFFICER_CAPITAN, store.get("i").careerGrade);
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, store.get("z").careerGrade);
        assertEquals(CareerGrade.PROFESSIONAL_MESTER, store.get("m").careerGrade);
        // Track coherence: migrated records read back on their grade's
        // track while origin keeps the entry path (military vs professional).
        assertEquals(CareerTrack.OFFICER, store.get("s").careerTrack);
        assertEquals(CareerTrack.PROFESSIONAL, store.get("z").careerTrack);
        assertEquals(CareerTrack.PROFESSIONAL, store.get("m").careerTrack);
        assertEquals(CareerTrack.PROFESSIONAL, store.get("m").careerOrigin);
    }

    @Test
    void legacyGradeNamesRemapOnPromotionStoreRead() {
        access.store("promotions").put("json",
                "{\"applications\":{\"a1\":{\"applicationId\":\"a1\",\"subjectUuid\":\"s\","
                        + "\"fromGrade\":\"PROFESSIONAL_SPECIALIST\",\"targetGrade\":\"PROFESSIONAL_MAISTRU\","
                        + "\"status\":\"SUBMITTED\"}},\"evidence\":{}}");
        var store = new SavedStores.Promotions(access).read();
        assertEquals(CareerGrade.PROFESSIONAL_MESTER, store.applications.get("a1").fromGrade);
        assertEquals(CareerGrade.PROFESSIONAL_MESTER, store.applications.get("a1").targetGrade);
    }

    @Test
    void unknownGradeNameFailsClosedIntoCorruptBackup() {
        access.store("personnel").put("json",
                "{\"schemaVersion\":1,\"records\":{\"x\":{\"playerUuid\":\"x\","
                        + "\"careerGrade\":\"TOTALLY_MADE_UP\"}}}");
        var store = people.read();
        assertTrue(store.records.isEmpty()); // fail-closed default, raw payload preserved
        assertTrue(raw.get("personnel").raw().containsKey("corrupt_backup"));
    }

    @Test
    void gradeParserAcceptsCurrentAndLegacyNames() {
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, CareerGrade.of("PROFESSIONAL_ZILIER"));
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, CareerGrade.of("PROFESSIONAL_STAGIAR_SPECIALIST"));
        assertEquals(CareerGrade.OFFICER_LOCOTENENT, CareerGrade.of("MILITARY_SERGENT"));
        assertNull(CareerGrade.of("CIVIL"));
        assertNull(CareerGrade.of(null));
    }

    // ------------------------------------------------------------ ladder shape

    @Test
    void ladderShapeHoldsPartTimeEnlistedTradesAndFullTimeOfficers() {
        assertEquals(10, CareerGrade.values().length);
        assertEquals(6, Rank.values().length);
        assertFalse(CareerGrade.MILITARY_STAGIAR.fullTimeRequired());
        assertFalse(CareerGrade.MILITARY_STRAJER.fullTimeRequired());
        assertFalse(CareerGrade.MILITARY_SERGEANT.fullTimeRequired());
        assertFalse(CareerGrade.PROFESSIONAL_ZILIER.fullTimeRequired());
        assertFalse(CareerGrade.PROFESSIONAL_MESERIAS.fullTimeRequired());
        assertFalse(CareerGrade.PROFESSIONAL_MESTER.fullTimeRequired());
        assertTrue(CareerGrade.PROFESSIONAL_ZILIER.isProfessional());
        assertTrue(CareerGrade.PROFESSIONAL_MESERIAS.isProfessional());
        assertTrue(CareerGrade.PROFESSIONAL_MESTER.isProfessional());
        for (var officer : new CareerGrade[]{CareerGrade.OFFICER_LOCOTENENT,
                CareerGrade.OFFICER_CAPITAN, CareerGrade.OFFICER_MAIOR,
                CareerGrade.OFFICER_COLONEL}) {
            assertTrue(officer.fullTimeRequired());
            assertTrue(officer.isOfficer());
            assertFalse(officer.isProfessional());
        }
    }

    @Test
    void transitionMatrixFollowsTheTwoTrackLadder() {
        authorize("stagiar", CareerGrade.MILITARY_STAGIAR, EmploymentMode.PART_TIME);
        authorize("strajer", CareerGrade.MILITARY_STRAJER, EmploymentMode.PART_TIME);
        authorize("sergent", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        authorize("meserias", CareerGrade.PROFESSIONAL_MESERIAS, EmploymentMode.PART_TIME);
        authorize("mester", CareerGrade.PROFESSIONAL_MESTER, EmploymentMode.PART_TIME);
        authorize("lt", CareerGrade.OFFICER_LOCOTENENT, EmploymentMode.FULL_TIME);
        authorize("cpt", CareerGrade.OFFICER_CAPITAN, EmploymentMode.FULL_TIME);
        authorize("maior", CareerGrade.OFFICER_MAIOR, EmploymentMode.FULL_TIME);

        assertDoesNotThrow(() -> promotions.submit("stagiar", CareerGrade.MILITARY_STRAJER));
        assertDoesNotThrow(() -> promotions.submit("strajer", CareerGrade.MILITARY_SERGEANT));
        assertDoesNotThrow(() -> promotions.submit("sergent", CareerGrade.OFFICER_LOCOTENENT));
        assertDoesNotThrow(() -> promotions.submit("zilier", CareerGrade.PROFESSIONAL_MESERIAS));
        assertDoesNotThrow(() -> promotions.submit("meserias", CareerGrade.PROFESSIONAL_MESTER));
        assertDoesNotThrow(() -> promotions.submit("mester", CareerGrade.OFFICER_LOCOTENENT));
        assertDoesNotThrow(() -> promotions.submit("lt", CareerGrade.OFFICER_CAPITAN));
        assertDoesNotThrow(() -> promotions.submit("cpt", CareerGrade.OFFICER_MAIOR));
        assertDoesNotThrow(() -> promotions.submit("maior", CareerGrade.OFFICER_COLONEL));

        // No skipping rungs, no cross-track enlisted hops, officers are terminal.
        assertThrows(IllegalArgumentException.class,
                () -> promotions.submit("stagiar", CareerGrade.MILITARY_SERGEANT));
        assertThrows(IllegalArgumentException.class,
                () -> promotions.submit("strajer", CareerGrade.OFFICER_LOCOTENENT));
        assertThrows(IllegalArgumentException.class,
                () -> promotions.submit("zilier", CareerGrade.PROFESSIONAL_MESTER));
        assertThrows(IllegalArgumentException.class,
                () -> promotions.submit("zilier", CareerGrade.MILITARY_STRAJER));
        assertThrows(IllegalArgumentException.class,
                () -> promotions.submit("mester", CareerGrade.PROFESSIONAL_ZILIER));
        authorize("col", CareerGrade.OFFICER_COLONEL, EmploymentMode.FULL_TIME);
        assertThrows(IllegalArgumentException.class,
                () -> promotions.submit("col", CareerGrade.OFFICER_CAPITAN));
    }

    // ------------------------------------------------------------ approval

    @Test
    void officerApprovalUpgradesToFullTimeFromEitherOrigin() {
        authorize("mil", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        authorize("pro", CareerGrade.PROFESSIONAL_MESTER, EmploymentMode.PART_TIME);

        petitionToReady("mil", CareerGrade.OFFICER_LOCOTENENT);
        petitionToReady("pro", CareerGrade.OFFICER_LOCOTENENT);
        var appMil = promotions.findOpen("mil", CareerGrade.OFFICER_LOCOTENENT);
        var appPro = promotions.findOpen("pro", CareerGrade.OFFICER_LOCOTENENT);
        promotions.approve("commissioner", appMil.applicationId, appMil.version);
        promotions.approve("commissioner", appPro.applicationId, appPro.version);

        var mil = personnel.find("mil");
        var pro = personnel.find("pro");
        assertEquals(CareerGrade.OFFICER_LOCOTENENT, mil.careerGrade);
        assertEquals(CareerTrack.OFFICER, mil.careerTrack);
        assertEquals(CareerTrack.MILITARY, mil.careerOrigin);
        assertEquals(EmploymentMode.FULL_TIME, mil.employmentMode);
        assertEquals(CareerGrade.OFFICER_LOCOTENENT, pro.careerGrade);
        assertEquals(CareerTrack.PROFESSIONAL, pro.careerOrigin);
        assertEquals(EmploymentMode.FULL_TIME, pro.employmentMode);
        assertEquals(PromotionStatus.APPROVED,
                promotions.find(appMil.applicationId).status);
    }

    @Test
    void petitionerCannotApproveTheirOwnPromotion() {
        authorize("self", CareerGrade.OFFICER_MAIOR, EmploymentMode.FULL_TIME);
        personnel.appoint("bootstrap", "self", AppointmentType.COMMISSIONER, "hq", "", null);
        petitionToReady("self", CareerGrade.OFFICER_COLONEL);
        var app = promotions.findOpen("self", CareerGrade.OFFICER_COLONEL);
        assertThrows(IllegalStateException.class,
                () -> promotions.approve("self", app.applicationId, app.version));
    }

    // ------------------------------------------------------------ authority

    @Test
    void everyOfficerGradeHoldsTheFullNonCommissionerCapabilitySet() {
        authorize("lt", CareerGrade.OFFICER_LOCOTENENT, EmploymentMode.FULL_TIME);
        authorize("cpt", CareerGrade.OFFICER_CAPITAN, EmploymentMode.FULL_TIME);
        authorize("maior", CareerGrade.OFFICER_MAIOR, EmploymentMode.FULL_TIME);
        authorize("col2", CareerGrade.OFFICER_COLONEL, EmploymentMode.FULL_TIME);
        var auth = new AuthorizationService(people, clock);
        for (String officer : new String[]{"lt", "cpt", "maior", "col2"}) {
            for (String cap : new String[]{"USE_CUFFS", "EXECUTE_ARRESTS", "ISSUE_FINES",
                    "ROUTINE_PATROL", "INVESTIGATE_COMPLAINTS", "MOBILIZE_PLAYERS",
                    "CREATE_MISSIONS", "APPROVE_REWARDS", "REVIEW_APPEALS", "VERIFY_REPORTS",
                    "MOBILIZE_SPECIALISTS", "ISSUE_DOCUMENTS", "WAIVE_EQUIPMENT_DEBT",
                    "MANAGE_CAMPAIGNS", "PROFESSIONAL_JOBS"}) {
                assertTrue(auth.allowed(AuthorizationContext.of(officer, cap)),
                        officer + " should hold " + cap);
            }
        }
    }

    @Test
    void enlistedAndTradesKeepTheirReducedCaps() {
        authorize("stagiar", CareerGrade.MILITARY_STAGIAR, EmploymentMode.PART_TIME);
        authorize("strajer", CareerGrade.MILITARY_STRAJER, EmploymentMode.PART_TIME);
        authorize("sergent", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        authorize("meserias", CareerGrade.PROFESSIONAL_MESERIAS, EmploymentMode.PART_TIME);
        authorize("mester", CareerGrade.PROFESSIONAL_MESTER, EmploymentMode.PART_TIME);
        var auth = new AuthorizationService(people, clock);
        assertFalse(auth.allowed(AuthorizationContext.of("stagiar", "USE_CUFFS")));
        assertTrue(auth.allowed(AuthorizationContext.of("strajer", "USE_CUFFS")));
        assertTrue(auth.allowed(AuthorizationContext.of("sergent", "USE_CUFFS")));
        assertFalse(auth.allowed(AuthorizationContext.of("strajer", "CREATE_MISSIONS")));
        assertTrue(auth.allowed(AuthorizationContext.of("zilier", "PROFESSIONAL_JOBS")));
        assertTrue(auth.allowed(AuthorizationContext.of("meserias", "PROFESSIONAL_JOBS")));
        assertTrue(auth.allowed(AuthorizationContext.of("mester", "PROFESSIONAL_JOBS")));
        assertFalse(auth.allowed(AuthorizationContext.of("zilier", "ISSUE_FINES")));
        assertFalse(auth.allowed(AuthorizationContext.of("meserias", "ISSUE_FINES")));
        // Trades patrol only inside an active mobilization scope — unchanged.
        assertFalse(auth.allowed(AuthorizationContext.of("mester", "ROUTINE_PATROL")));
    }

    // ------------------------------------------------------------ surfaces

    @Test
    void legacyRoleNamesKeepCheckpointBansWorkingAfterMigration() {
        assertTrue(CareerGrade.OFFICER_CAPITAN.legacyRoleNames().contains("INSPECTOR"));
        assertTrue(CareerGrade.OFFICER_LOCOTENENT.legacyRoleNames().contains("MILITARY_SERGENT"));
        assertTrue(CareerGrade.PROFESSIONAL_MESTER.legacyRoleNames().containsAll(
                java.util.Set.of("PROFESSIONAL_SPECIALIST", "PROFESSIONAL_MAISTRU")));
        assertTrue(CareerGrade.PROFESSIONAL_ZILIER.legacyRoleNames().contains("PROFESSIONAL_STAGIAR_SPECIALIST"));
        assertTrue(CareerGrade.MILITARY_STRAJER.legacyRoleNames().isEmpty());
        assertTrue(CareerGrade.MILITARY_SERGEANT.legacyRoleNames().isEmpty());
        assertTrue(CareerGrade.PROFESSIONAL_MESERIAS.legacyRoleNames().isEmpty());
        assertTrue(CareerGrade.OFFICER_MAIOR.legacyRoleNames().isEmpty());
        assertTrue(CareerGrade.OFFICER_COLONEL.legacyRoleNames().isEmpty());
    }

    @Test
    void commissionerBootstrapsAsColonel() {
        var record = personnel.ensureCommissioner("the-comisar");
        assertEquals(CareerGrade.OFFICER_COLONEL, record.careerGrade);
        assertEquals(CareerTrack.OFFICER, record.careerTrack);
        assertEquals(EmploymentMode.FULL_TIME, record.employmentMode);
    }

    @Test
    void tradesEnrollmentLandsOnZilier() {
        var record = personnel.enrollProfessional("worker", "miner");
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, record.careerGrade);
        assertEquals(EmploymentMode.PART_TIME, record.employmentMode);
    }

    // ------------------------------------------------------------ petitions (RANK-4)

    @Test
    void petitionLandsReadyForApprovalSkippingEvidence() {
        authorize("mil", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition("mil", CareerGrade.OFFICER_LOCOTENENT,
                "vreau în corpul ofițeresc");
        assertEquals(PromotionStatus.READY_FOR_APPROVAL, application.status);
        assertEquals("vreau în corpul ofițeresc", application.petitionReason);
        assertEquals(java.util.List.of(application.applicationId),
                promotions.pendingPetitions().stream().map(a -> a.applicationId).toList());
    }

    @Test
    void petitionResubmissionUpdatesOpenReasonAndBumpsVersion() {
        authorize("mil", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        var first = promotions.submitPetition("mil", CareerGrade.OFFICER_LOCOTENENT, "motiv vechi");
        var second = promotions.submitPetition("mil", CareerGrade.OFFICER_LOCOTENENT, "motiv nou");
        assertEquals(first.applicationId, second.applicationId);
        assertEquals("motiv nou", second.petitionReason);
        // The version bump invalidates any review opened against the old text.
        assertTrue(second.version > first.version);
        assertEquals(1, promotions.pendingPetitions().size());
    }

    @Test
    void petitionResubmissionAfterRecordChangeRepinsAndStaysApprovable() {
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        var first = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        // A routine record write — e.g. a second profession approved through a
        // join petition while this one awaited review — bumps the member's
        // personnel version and must not wedge the open petition.
        personnel.assignProfession("commissioner", "zilier", "FIERAR");
        var refiled = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        assertEquals(first.applicationId, refiled.applicationId);
        assertTrue(refiled.version > first.version);
        promotions.approve("commissioner", refiled.applicationId, refiled.version);
        assertEquals(CareerGrade.PROFESSIONAL_MESERIAS, personnel.find("zilier").careerGrade);
    }

    @Test
    void stalePetitionWithoutResubmissionStillFailsClosed() {
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        personnel.assignProfession("commissioner", "zilier", "FIERAR");
        assertThrows(IllegalStateException.class,
                () -> promotions.approve("commissioner", application.applicationId, application.version));
        assertEquals(PromotionStatus.READY_FOR_APPROVAL,
                promotions.find(application.applicationId).status);
    }

    @Test
    void recordChangeAfterRefilingStillBlocksApproval() {
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        personnel.assignProfession("commissioner", "zilier", "FIERAR");
        var refiled = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        // Re-pinning does not weaken the pin: a change after the last filing
        // still fails the optimistic-concurrency check at decision time.
        personnel.assignProfession("commissioner", "zilier", "TESLAR");
        assertThrows(IllegalStateException.class,
                () -> promotions.approve("commissioner", refiled.applicationId, refiled.version));
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, personnel.find("zilier").careerGrade);
    }

    @Test
    void anyOfficerStampsPartTimePetition() {
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        authorize("lt", CareerGrade.OFFICER_LOCOTENENT, EmploymentMode.FULL_TIME);
        var application = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        var decided = promotions.approve("lt", application.applicationId, application.version);
        assertEquals(PromotionStatus.APPROVED, decided.status);
        assertEquals(CareerGrade.PROFESSIONAL_MESERIAS, personnel.find("zilier").careerGrade);
    }

    @Test
    void officerCannotStampOfficerTrackPetition() {
        authorize("mester", CareerGrade.PROFESSIONAL_MESTER, EmploymentMode.PART_TIME);
        authorize("lt", CareerGrade.OFFICER_LOCOTENENT, EmploymentMode.FULL_TIME);
        var application = promotions.submitPetition("mester", CareerGrade.OFFICER_LOCOTENENT, "motiv");
        // Full-time asks still carry the Comisar's seal — officer grade alone
        // does not reach them.
        assertThrows(IllegalStateException.class,
                () -> promotions.approve("lt", application.applicationId, application.version));
    }

    @Test
    void plainMemberCannotStampPartTimePetition() {
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        authorize("strajer", CareerGrade.MILITARY_STRAJER, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        assertThrows(IllegalStateException.class,
                () -> promotions.approve("strajer", application.applicationId, application.version));
    }

    @Test
    void examPassPromotesThroughTheSameDurableFlow() {
        authorize("stagiar", CareerGrade.MILITARY_STAGIAR, EmploymentMode.PART_TIME);
        var decided = promotions.examPassPromote("stagiar", CareerGrade.MILITARY_STRAJER);
        assertEquals(PromotionStatus.APPROVED, decided.status);
        assertEquals("EXAM", decided.reviewedBy);
        assertEquals("COMPLETE", decided.personnelSyncState);
        var record = personnel.find("stagiar");
        assertEquals(CareerGrade.MILITARY_STRAJER, record.careerGrade);
        assertEquals(CareerTrack.MILITARY, record.careerTrack);
    }

    @Test
    void identicalPetitionResubmissionWithoutRecordChangeStaysIdempotent() {
        authorize("mil", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        var first = promotions.submitPetition("mil", CareerGrade.OFFICER_LOCOTENENT, "motiv");
        var second = promotions.submitPetition("mil", CareerGrade.OFFICER_LOCOTENENT, "motiv");
        assertEquals(first.applicationId, second.applicationId);
        assertEquals(first.version, second.version);
    }

    @Test
    void petitionReasonIsCappedAtServiceLimit() {
        authorize("mil", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition("mil", CareerGrade.OFFICER_LOCOTENENT,
                "x".repeat(700));
        assertEquals(500, application.petitionReason.length());
        assertEquals(500, promotions.find(application.applicationId).petitionReason.length());
    }

    @Test
    void petitionReviewDecisionsAreAudited() {
        var audit = new AuditService(Fakes.context(new Fakes.TestServer(), clock));
        promotions.useAuditService(audit);
        authorize("mil", CareerGrade.MILITARY_SERGEANT, EmploymentMode.PART_TIME);
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        var approved = promotions.submitPetition("mil", CareerGrade.OFFICER_LOCOTENENT, "motiv");
        promotions.approve("commissioner", approved.applicationId, approved.version);
        var denied = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS, "motiv");
        promotions.reject("commissioner", denied.applicationId, "nu acum");

        var reviews = audit.tail(50).stream()
                .filter(entry -> "promotion_petition_review".equals(entry.action)).toList();
        assertEquals(2, reviews.size());
        assertTrue(reviews.stream().anyMatch(entry -> "APPROVED".equals(entry.result)
                && approved.applicationId.equals(entry.target) && "mil".equals(entry.targetUuid)
                && entry.details.contains("to=OFFICER_LOCOTENENT")));
        assertTrue(reviews.stream().anyMatch(entry -> "REJECTED".equals(entry.result)
                && denied.applicationId.equals(entry.target) && "nu acum".equals(entry.details)));
    }

    @Test
    void petitionApproveFlipsGradeWithoutEvidence() {
        authorize("zilier", CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition("zilier", CareerGrade.PROFESSIONAL_MESERIAS,
                "pricep meseria");
        promotions.approve("commissioner", application.applicationId, application.version);
        assertEquals(CareerGrade.PROFESSIONAL_MESERIAS, personnel.find("zilier").careerGrade);
    }

    @Test
    void petitionOfficerApprovalConfersFullTime() {
        authorize("pro", CareerGrade.PROFESSIONAL_MESTER, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition("pro", CareerGrade.OFFICER_LOCOTENENT, "");
        promotions.approve("commissioner", application.applicationId, application.version);
        var record = personnel.find("pro");
        assertEquals(CareerGrade.OFFICER_LOCOTENENT, record.careerGrade);
        assertEquals(EmploymentMode.FULL_TIME, record.employmentMode);
    }

    @Test
    void petitionDenyRecordsReasonAndLocksTheDecision() {
        authorize("stagiar", CareerGrade.MILITARY_STAGIAR, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition("stagiar", CareerGrade.MILITARY_STRAJER, "");
        promotions.reject("commissioner", application.applicationId, "mai exersează");
        var persisted = promotions.find(application.applicationId);
        assertEquals(PromotionStatus.REJECTED, persisted.status);
        assertEquals("mai exersează", persisted.decisionReason);
        // A decided application must not be silently flipped back either way.
        assertThrows(IllegalStateException.class,
                () -> promotions.reject("commissioner", application.applicationId, "din nou"));
        assertThrows(IllegalStateException.class,
                () -> promotions.approve("commissioner", application.applicationId, persisted.version));
    }

    @Test
    void petitionOutcomeReachesTheSubjectOnce() {
        // TestPlayer derives uuid = nameUUIDFromBytes(name) — the subject id.
        var gateway = new Fakes.TestPlayer("zilier", 1);
        String zilier = gateway.uuid().toString();
        authorize(zilier, CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME);
        var application = promotions.submitPetition(zilier, CareerGrade.PROFESSIONAL_MESERIAS, "");
        promotions.reject("commissioner", application.applicationId, "insuficient");

        promotions.deliverOutcome(gateway);
        assertEquals(1, gateway.messages.size());
        assertTrue(gateway.messages.get(0).contains("respinsă"));
        assertTrue(gateway.messages.get(0).contains("insuficient"));
        // Delivered once — a second sweep stays silent.
        promotions.deliverOutcome(gateway);
        assertEquals(1, gateway.messages.size());
    }
}
