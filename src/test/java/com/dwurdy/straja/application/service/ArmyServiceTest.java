package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.application.port.out.ArmyRepository;
import com.dwurdy.straja.domain.model.ArmyOperation;
import com.dwurdy.straja.domain.model.ArmyStore;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Headless army loop: enlistment self-organizes the unit, the tick drives
 * schedule → muster → complete, turnout pays service blocks once, and every
 * authority boundary (member-only, officer-only, one unit per player) holds.
 */
class ArmyServiceTest {
    private static final String P1 = "00000000-0000-0000-0000-0000000000a1";
    private static final String P2 = "00000000-0000-0000-0000-0000000000b2";

    private final ArmyStore store = new ArmyStore();
    private final ArmyRepository armyRepo = new ArmyRepository() {
        @Override public ArmyStore read() { return store; }
        @Override public void write(ArmyStore ignored) {}
    };
    private final Map<String, MemoryStore> memory = new HashMap<>();
    private final SavedStores.Personnel personnelRepo =
            new SavedStores.Personnel(name -> memory.computeIfAbsent(name, k -> new MemoryStore()));
    private final Fakes.FixedClock clock = new Fakes.FixedClock(1_000L);
    private final PersonnelService personnel =
            new PersonnelService(personnelRepo, clock, new Fakes.SeqIds());
    private final Map<String, Integer> awarded = new HashMap<>();
    private final ArmyService army = new ArmyService(armyRepo, personnelRepo, clock, new Fakes.SeqIds());

    ArmyServiceTest() {
        army.useAwardHook((uuid, blocks) -> awarded.merge(uuid, blocks, Integer::sum));
    }

    private void enlistMilitary(String uuid, CareerGrade grade) {
        personnel.authorize("test", uuid, grade,
                grade.fullTimeRequired() ? EmploymentMode.FULL_TIME : EmploymentMode.PART_TIME,
                "TEST", "hq", "auth:" + uuid);
    }

    @Test
    void enlistSelfOrganizesStationUnit() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        assertEquals("hq", unit.stationId);
        assertEquals("Garda hq", unit.name);
        assertTrue(unit.isMember(P1));
        assertEquals(unit, army.unitFor(P1));
    }

    @Test
    void enlistRequiresActiveMembership() {
        var error = assertThrows(IllegalStateException.class, () -> army.enlist(P1));
        assertEquals("STRAJA_MEMBER_REQUIRED", error.getMessage());
    }

    @Test
    void enlistRejectsProfessionalTrack() {
        enlistMilitary(P1, CareerGrade.PROFESSIONAL_ZILIER);
        var error = assertThrows(IllegalStateException.class, () -> army.enlist(P1));
        assertEquals("MILITARY_TRACK_REQUIRED", error.getMessage());
    }

    @Test
    void firstOfficerTakesCommand() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        enlistMilitary(P2, CareerGrade.OFFICER_CAPITAN);
        var unit = army.enlist(P1);
        unit = army.enlist(P2);
        assertEquals(P2, unit.commanderUuid);
    }

    @Test
    void secondEnlistReturnsSameUnit() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var first = army.enlist(P1);
        var second = army.enlist(P1);
        assertEquals(first.unitId, second.unitId);
        assertEquals(1, second.ensureMembers().size());
    }

    @Test
    void tickAutoSchedulesOperationForPopulatedUnit() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        army.enlist(P1);
        army.tick();
        var op = army.nextOpFor(army.unitFor(P1).unitId);
        assertNotNull(op);
        assertEquals(ArmyOperation.Status.SCHEDULED, op.status);
        assertEquals(ArmyOperation.Kind.INSTRUCTIE, op.kind);
    }

    @Test
    void signupReportCompleteAwardsServiceBlocks() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        army.tick(); // auto-schedule
        var op = army.signup(P1);
        assertTrue(op.ensureSignups().contains(P1));

        clock.advance(ArmyService.MUSTER_DELAY_MS + 1_000L);
        army.tick();
        assertEquals(ArmyOperation.Status.ACTIVE, army.findOperation(op.operationId).status);

        army.report(P1);
        clock.advance(ArmyService.ACTIVE_WINDOW_MS + 1_000L);
        army.tick();

        var finished = army.findOperation(op.operationId);
        assertEquals(ArmyOperation.Status.COMPLETED, finished.status);
        assertEquals(ArmyOperation.Kind.INSTRUCTIE.rewardBlocks, finished.awardedBlocks);
        assertEquals(ArmyOperation.Kind.INSTRUCTIE.rewardBlocks, awarded.get(P1).intValue());
        assertEquals(1, army.findUnit(unit.unitId).operationsCompleted);
        assertTrue(army.findUnit(unit.unitId).readiness > 50);
    }

    @Test
    void awardIsSingleFire() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        army.enlist(P1);
        army.tick();
        army.signup(P1);
        clock.advance(ArmyService.MUSTER_DELAY_MS + 1_000L);
        army.tick();
        army.report(P1);
        clock.advance(ArmyService.ACTIVE_WINDOW_MS + 1_000L);
        army.tick();
        int after = awarded.get(P1);
        army.tick();
        army.tick();
        assertEquals(after, awarded.get(P1).intValue(), "repeated ticks must not re-pay");
    }

    @Test
    void emptyMusterFailsTheOperation() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        int readinessBefore = army.findUnit(unit.unitId).readiness;
        army.tick();
        var op = army.nextOpFor(unit.unitId);
        clock.advance(ArmyService.MUSTER_DELAY_MS + ArmyService.ACTIVE_WINDOW_MS + 2_000L);
        army.tick();
        var finished = army.findOperation(op.operationId);
        assertEquals(ArmyOperation.Status.FAILED, finished.status);
        assertTrue(army.findUnit(unit.unitId).readiness < readinessBefore);
        assertNull(awarded.get(P1));
    }

    @Test
    void walkUpReportCountsWithoutSignup() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        enlistMilitary(P2, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        army.enlist(P2);
        army.tick();
        var op = army.nextOpFor(unit.unitId);
        clock.advance(ArmyService.MUSTER_DELAY_MS + 1_000L);
        army.tick();
        // P2 never signed up — walk-up muster still counts.
        var reported = army.report(P2);
        assertTrue(reported.ensureParticipants().contains(P2));
        assertTrue(reported.ensureSignups().contains(P2));
    }

    @Test
    void scheduleRequiresOfficerMember() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        var error = assertThrows(IllegalStateException.class, () ->
                army.schedule(P1, unit.unitId, ArmyOperation.Kind.PATRULA, 0, 60_000L));
        assertEquals("DENIED_ARMY_COMMAND", error.getMessage());
    }

    @Test
    void officerMemberSchedulesOperation() {
        enlistMilitary(P1, CareerGrade.OFFICER_CAPITAN);
        var unit = army.enlist(P1);
        var op = army.schedule(P1, unit.unitId, ArmyOperation.Kind.EXERCITIU, 1_000L, 60_000L);
        assertEquals(ArmyOperation.Kind.EXERCITIU, op.kind);
        assertEquals(ArmyOperation.Status.SCHEDULED, op.status);
    }

    @Test
    void leaveClearsMembershipAndSignups() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        army.tick();
        var op = army.signup(P1);
        assertTrue(army.leave(P1));
        assertNull(army.unitFor(P1));
        assertFalse(army.findOperation(op.operationId).ensureSignups().contains(P1));
        assertFalse(army.leave(P1));
    }

    @Test
    void departedCommanderHandsOffToNextOfficer() {
        enlistMilitary(P1, CareerGrade.OFFICER_CAPITAN);
        enlistMilitary(P2, CareerGrade.OFFICER_LOCOTENENT);
        var unit = army.enlist(P1);
        army.enlist(P2);
        assertEquals(P1, unit.commanderUuid);
        army.leave(P1);
        assertEquals(P2, army.findUnit(unit.unitId).commanderUuid);
    }

    @Test
    void autoCycleRotatesOperationKinds() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        army.tick();
        var first = army.nextOpFor(unit.unitId);
        army.cancel(first.operationId);
        // Second auto op would take AUTO_INTERVAL; force by resetting the gate.
        army.findUnit(unit.unitId).nextAutoAt = 0;
        army.tick();
        var second = army.nextOpFor(unit.unitId);
        assertNotEquals(first.operationId, second.operationId);
        assertEquals(ArmyOperation.Kind.PATRULA, second.kind);
    }

    @Test
    void historyReportsFinishedOperations() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        army.tick();
        var op = army.nextOpFor(unit.unitId);
        clock.advance(ArmyService.MUSTER_DELAY_MS + ArmyService.ACTIVE_WINDOW_MS + 2_000L);
        army.tick();
        var history = army.historyFor(unit.unitId, 5);
        assertEquals(1, history.size());
        assertEquals(op.operationId, history.get(0).operationId);
    }

    @Test
    void disbandFreesRoster() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        assertTrue(army.disband(unit.unitId));
        assertNull(army.unitFor(P1));
        assertFalse(army.disband(unit.unitId));
    }

    @Test
    void suspendedMemberCannotSignupOrReport() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        army.enlist(P1);
        army.tick(); // auto-scheduled op exists
        personnel.suspend("test", P1, "TEST");

        assertEquals("STRAJA_MEMBER_REQUIRED",
                assertThrows(IllegalStateException.class, () -> army.signup(P1)).getMessage());
        assertEquals("STRAJA_MEMBER_REQUIRED",
                assertThrows(IllegalStateException.class, () -> army.report(P1)).getMessage());
    }

    @Test
    void tickSweepsSuspendedMembersOffRoster() {
        enlistMilitary(P1, CareerGrade.OFFICER_CAPITAN);
        enlistMilitary(P2, CareerGrade.OFFICER_LOCOTENENT);
        var unit = army.enlist(P1);
        army.enlist(P2);
        assertEquals(P1, unit.commanderUuid);
        personnel.suspend("test", P1, "TEST");

        army.tick();
        assertNull(army.unitFor(P1));
        assertFalse(army.findUnit(unit.unitId).isMember(P1));
        assertEquals(P2, army.findUnit(unit.unitId).commanderUuid,
                "suspension must hand command to the next active officer");
    }

    @Test
    void suspendedParticipantIsNotPaid() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        enlistMilitary(P2, CareerGrade.MILITARY_STRAJER);
        army.enlist(P1);
        army.enlist(P2);
        army.tick();
        army.signup(P1);
        army.signup(P2);
        clock.advance(ArmyService.MUSTER_DELAY_MS + 1_000L);
        army.tick();
        army.report(P1);
        army.report(P2);
        personnel.suspend("test", P2, "TEST"); // suspended between report and completion

        clock.advance(ArmyService.ACTIVE_WINDOW_MS + 1_000L);
        army.tick();

        assertNotNull(awarded.get(P1), "active participant is paid");
        assertNull(awarded.get(P2), "suspended participant earns nothing");
    }

    @Test
    void disbandStandsDownOpenOperations() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        army.tick();
        var op = army.nextOpFor(unit.unitId);
        assertEquals(ArmyOperation.Status.SCHEDULED, op.status);

        assertTrue(army.disband(unit.unitId));
        assertEquals(ArmyOperation.Status.CANCELLED,
                army.findOperation(op.operationId).status,
                "disband must stand down open ops so nothing pays out");
    }

    @Test
    void cancelRefusesFinishedOperations() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        army.enlist(P1);
        army.tick();
        var op = army.nextOpFor(army.unitFor(P1).unitId);
        clock.advance(ArmyService.MUSTER_DELAY_MS + ArmyService.ACTIVE_WINDOW_MS + 2_000L);
        army.tick();
        assertEquals(ArmyOperation.Status.FAILED, army.findOperation(op.operationId).status);

        assertEquals("OP_NOT_OPEN",
                assertThrows(IllegalStateException.class,
                        () -> army.cancel(op.operationId)).getMessage());
    }

    @Test
    void memberUnitHealsStaleIndex() {
        enlistMilitary(P1, CareerGrade.MILITARY_STRAJER);
        var unit = army.enlist(P1);
        unit.ensureMembers().remove(P1); // torn state: index rows survives roster loss
        assertEquals("NOT_IN_UNIT",
                assertThrows(IllegalStateException.class, () -> army.signup(P1)).getMessage());
        assertNull(store.ensureMemberIndex().get(P1),
                "a stale index row is dropped on contact");
    }
}
