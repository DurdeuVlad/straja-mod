package com.dwurdy.straja;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.application.service.AuthorizationService;
import com.dwurdy.straja.application.service.JoinRequestService;
import com.dwurdy.straja.application.service.PersonnelService;
import com.dwurdy.straja.domain.model.AppointmentType;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.JoinRequestStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * RANK-5 — professional join petitions: a civilian files "Depune cerere de
 * muncă" (profession + optional reason), the Comisar reviews at the
 * Receptionist, approval enrolls a part-time ZILIER. Sworn members and
 * disciplined records cannot petition; stale review forms fail closed.
 */
class JoinPetitionTest {
    private final Map<String, MemoryStore> raw = new HashMap<>();
    private final StoreAccess access = name -> raw.computeIfAbsent(name, ignored -> new MemoryStore());
    private Fakes.FixedClock clock;
    private Fakes.SeqIds ids;
    private PersonnelService personnel;
    private JoinRequestService joins;

    @BeforeEach
    void setup() {
        clock = new Fakes.FixedClock(1_000);
        ids = new Fakes.SeqIds();
        var people = new SavedStores.Personnel(access);
        personnel = new PersonnelService(people, clock, ids);
        var authorization = new AuthorizationService(people, clock);
        joins = new JoinRequestService(new SavedStores.JoinRequests(access), personnel,
                authorization, () -> Set.of("FIERAR", "TAMPLAR"), clock, ids);
        personnel.authorize("bootstrap", "commissioner", CareerGrade.OFFICER_COLONEL,
                EmploymentMode.FULL_TIME, "TEST", "hq", "auth:commissioner");
        personnel.appoint("bootstrap", "commissioner", AppointmentType.COMMISSIONER, "hq", "", null);
        personnel.useAuthorization(authorization);
    }

    @Test
    void petitionApproveCreatesPartTimeZilier() {
        var request = joins.submit("newbie", "fierar", "vreau să muncesc");

        var decided = joins.approve("commissioner", request.requestId, request.version);

        assertEquals(JoinRequestStatus.APPROVED, decided.status);
        assertEquals("commissioner", decided.reviewedBy);
        var record = personnel.find("newbie");
        assertNotNull(record);
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, record.careerGrade);
        assertEquals(EmploymentMode.PART_TIME, record.employmentMode);
        assertTrue(record.professions.contains("FIERAR"));
    }

    @Test
    void petitionDenyRecordsReasonAndKeepsApplicantCivilian() {
        var request = joins.submit("newbie", "fierar", "");

        var decided = joins.reject("commissioner", request.requestId, request.version,
                "revino săptămâna viitoare");

        assertEquals(JoinRequestStatus.REJECTED, decided.status);
        assertEquals("revino săptămâna viitoare", decided.decisionReason);
        assertNull(personnel.find("newbie"));
    }

    @Test
    void duplicateOpenPetitionIsIdempotentAndEditsBumpVersion() {
        var first = joins.submit("newbie", "fierar", "primul motiv");
        var same = joins.submit("newbie", "fierar", "primul motiv");
        assertEquals(first.requestId, same.requestId);
        assertEquals(first.version, same.version);

        var edited = joins.submit("newbie", "fierar", "motiv nou");
        assertEquals(first.requestId, edited.requestId);
        assertTrue(edited.version > first.version);
        assertEquals("motiv nou", joins.find(first.requestId).reason);
        assertEquals(1, joins.pending().size());
    }

    @Test
    void staleReviewVersionFailsClosed() {
        var request = joins.submit("newbie", "fierar", "a");
        joins.submit("newbie", "fierar", "motiv schimbat"); // bumps version

        assertThrows(IllegalStateException.class,
                () -> joins.approve("commissioner", request.requestId, request.version));
        assertEquals(JoinRequestStatus.PENDING, joins.find(request.requestId).status);
    }

    @Test
    void reviewRequiresCommissionerAppointment() {
        var request = joins.submit("newbie", "fierar", "");
        joins.submit("clerk", "tamplar", "");

        assertThrows(IllegalStateException.class,
                () -> joins.approve("clerk", request.requestId, request.version));
        assertThrows(IllegalStateException.class,
                () -> joins.reject("clerk", request.requestId, request.version, "nu"));
        assertEquals(JoinRequestStatus.PENDING, joins.find(request.requestId).status);
    }

    @Test
    void swornMembersAndDisciplinedRecordsCannotPetition() {
        personnel.authorize("commissioner", "sworn", CareerGrade.MILITARY_STRAJER,
                EmploymentMode.PART_TIME, "TEST", "hq", "auth:sworn");
        assertThrows(IllegalStateException.class,
                () -> joins.submit("sworn", "fierar", "vreau meserie"));

        personnel.authorize("commissioner", "bad", CareerGrade.PROFESSIONAL_ZILIER,
                EmploymentMode.PART_TIME, "TEST", "hq", "auth:bad");
        personnel.suspend("commissioner", "bad", "abuz");
        assertThrows(IllegalStateException.class,
                () -> joins.submit("bad", "fierar", "reintru"));

        personnel.terminate("commissioner", "bad", "abuz repetat");
        assertThrows(IllegalStateException.class,
                () -> joins.submit("bad", "fierar", "reintru"));
    }

    @Test
    void resignedMemberMayPetitionToRejoin() {
        personnel.authorize("commissioner", "quit", CareerGrade.PROFESSIONAL_ZILIER,
                EmploymentMode.PART_TIME, "TEST", "hq", "auth:quit");
        personnel.resign("quit", "pauză");

        var request = joins.submit("quit", "tamplar", "mă întorc");
        joins.approve("commissioner", request.requestId, request.version);

        var record = personnel.find("quit");
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, record.careerGrade);
        assertTrue(record.professions.contains("TAMPLAR"));
    }

    @Test
    void unknownProfessionIsRefusedAtSubmit() {
        assertThrows(IllegalArgumentException.class,
                () -> joins.submit("newbie", "pirat", "arra"));
        assertTrue(joins.pending().isEmpty());
    }

    @Test
    void approvalBetweenFilingAndSwornMembershipFails() {
        var request = joins.submit("newbie", "fierar", "");
        // The applicant joined the military track while the petition sat open.
        personnel.authorize("commissioner", "newbie", CareerGrade.MILITARY_STAGIAR,
                EmploymentMode.PART_TIME, "TEST", "hq", "auth:newbie");

        assertThrows(IllegalStateException.class,
                () -> joins.approve("commissioner", request.requestId, request.version));
        assertEquals(JoinRequestStatus.PENDING, joins.find(request.requestId).status);
    }

    @Test
    void outcomeDeliversOnceOnApproval() {
        var gateway = new Fakes.TestPlayer("newbie", 36);
        String subject = gateway.uuid().toString();
        joins.deliverOutcome(gateway);
        assertTrue(gateway.messages.isEmpty());

        var request = joins.submit(subject, "fierar", "");
        joins.approve("commissioner", request.requestId, request.version);

        joins.deliverOutcome(gateway);
        assertEquals(1, gateway.messages.size());
        assertTrue(gateway.messages.get(0).contains("aprobată"));
        joins.deliverOutcome(gateway);
        assertEquals(1, gateway.messages.size()); // delivered once
    }

    @Test
    void pendingListIsOldestFirst() {
        var first = joins.submit("a", "fierar", "");
        clock.advance(10);
        var second = joins.submit("b", "tamplar", "");

        var pending = joins.pending();
        assertEquals(2, pending.size());
        assertEquals(first.requestId, pending.get(0).requestId);
        assertEquals(second.requestId, pending.get(1).requestId);
    }
}
