package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersonnelWorkflowServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private GuardService guards;
    private PersonnelWorkflowService personnel;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        StrajaPolicies policies = Fakes.policies();
        ctx = Fakes.context(server, clock, policies);
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        guards = new GuardService(ctx, players, audit, new EquipmentService(ctx));
        personnel = new PersonnelWorkflowService(ctx, players, guards, audit);
    }

    private TestPlayer commissioner() {
        return server.byName("dwurdy") != null ? server.byName("dwurdy") : server.add("dwurdy");
    }

    private void answerRecruitmentQuiz(TestPlayer player) {
        for (int i = 0; i < ctx.policies().quiz.size(); i++) {
            var state = players.state(player.uuid());
            String questionId = state.quizOrder.get(state.quizIndex);
            var question = ctx.policies().quiz.stream()
                    .filter(q -> q.id().equals(questionId)).findFirst().orElseThrow();
            guards.quiz(player, question.answers().get(0));
        }
    }

    @Test
    void receptionistApplicationThenRecruiterQuizCreatesAuthorizedJuniorRecord() {
        TestPlayer recruit = server.add("recruit");

        assertTrue(personnel.submitApplication(recruit));
        var application = players.state(recruit.uuid());
        assertNotNull(application.applicationSubmittedAt);
        assertTrue(application.invited);
        assertEquals(2, recruit.inventory.slots.stream().filter(s -> !s.isEmpty()).count(),
                "reception should hand out two RP paperwork items");

        personnel.recruiterIntake(recruit);
        assertFalse(players.state(recruit.uuid()).quizOrder.isEmpty());
        answerRecruitmentQuiz(recruit);

        var state = personnel.ensurePersonnelRecord(recruit);
        assertTrue(state.authorized());
        assertEquals(Rank.JUNIOR.level(), state.rank);
        assertTrue(state.quizPassed);
        assertNotNull(state.serviceNumber);
        assertTrue(state.serviceNumber.startsWith("SJ-"));
        assertNotNull(state.authorizedAt);
        assertNotNull(state.nextActivityReportDueAt);
    }

    @Test
    void applicantCannotUseRecruiterBeforeReception() {
        TestPlayer recruit = server.add("recruit");
        personnel.recruiterIntake(recruit);
        assertFalse(players.state(recruit.uuid()).invited);
        assertTrue(recruit.told("Recepționistul"));
    }

    @Test
    void commissionerCanAuthorizeExperiencedCaptainWithoutRecruitmentQuiz() {
        TestPlayer commissioner = commissioner();
        TestPlayer veteran = server.add("veteran");

        assertTrue(personnel.authorizeExperienced(commissioner, veteran, Rank.LIEUTENANT.level()));
        var state = players.state(veteran.uuid());
        assertTrue(state.authorized());
        assertEquals(Rank.LIEUTENANT.level(), state.rank);
        assertEquals(commissioner.uuid().toString(), state.authorizedBy);
        assertNotNull(state.serviceNumber);
        assertTrue(veteran.told("Căpitan"));
    }

    @Test
    void firedPlayerCannotSubmitFreshRecruitmentApplication() {
        TestPlayer commissioner = commissioner();
        TestPlayer recruit = server.add("recruit");
        assertTrue(personnel.authorizeExperienced(commissioner, recruit, 1));
        assertTrue(personnel.revokeAuthorization(commissioner, recruit));
        assertTrue(players.state(recruit.uuid()).fired);

        assertFalse(personnel.submitApplication(recruit));
        assertTrue(recruit.told("îndepărtat"));
    }

    @Test
    void reportBecomesDueAfterSevenRealDaysAndBlocksNewDutyUntilSubmitted() {
        TestPlayer commissioner = commissioner();
        TestPlayer guard = server.add("guard");
        assertTrue(personnel.authorizeExperienced(commissioner, guard, 2));
        assertTrue(personnel.canStartDuty(guard));

        clock.advance(7L * 24 * 60 * 60 * 1000 + 1);
        assertTrue(personnel.activityReportDue(guard));
        assertFalse(personnel.canStartDuty(guard));
        assertEquals("DUE", players.state(guard.uuid()).activityReportStatus);

        assertTrue(personnel.submitActivityReport(guard,
                "Am patrulat zona centrală și nu au fost incidente majore."));
        assertFalse(personnel.activityReportDue(guard));
        assertTrue(personnel.canStartDuty(guard));
        assertEquals("SUBMITTED", players.state(guard.uuid()).activityReportStatus);
    }

    @Test
    void commissionerCanReturnThenAcceptActivityReport() {
        TestPlayer commissioner = commissioner();
        TestPlayer guard = server.add("guard");
        personnel.authorizeExperienced(commissioner, guard, 2);
        assertTrue(personnel.submitActivityReport(guard, "Raport inițial"));

        var pending = personnel.pendingPersonnelInbox(commissioner);
        assertEquals(1, pending.size());
        String id = pending.getFirst().id;
        assertEquals(guard.uuid().toString(), pending.getFirst().senderUuid);

        assertTrue(personnel.reviewActivityReport(commissioner, id, "RETURNED", "Adaugă patrulele efectuate."));
        assertEquals("RETURNED", players.state(guard.uuid()).activityReportStatus);
        assertTrue(personnel.activityReportDue(guard));
        assertFalse(personnel.canStartDuty(guard));

        assertTrue(personnel.reviewActivityReport(commissioner, id, "ACCEPTED", null));
        assertEquals("ACCEPTED", players.state(guard.uuid()).activityReportStatus);
        assertFalse(personnel.activityReportDue(guard));
    }

    @Test
    void calledInReportPersistsCommissionerFollowupState() {
        TestPlayer commissioner = commissioner();
        TestPlayer guard = server.add("guard");
        personnel.authorizeExperienced(commissioner, guard, 3);
        personnel.submitActivityReport(guard, "Raport cu incident." );
        String id = personnel.pendingPersonnelInbox(commissioner).getFirst().id;

        assertTrue(personnel.reviewActivityReport(commissioner, id, "CALLED_IN", "Prezintă-te la birou."));
        var state = players.state(guard.uuid());
        assertEquals("CALLED_IN", state.activityReportStatus);
        assertEquals("Prezintă-te la birou.", state.activityReportReviewMessage);
        assertFalse(personnel.canStartDuty(guard));
        assertTrue(guard.told("te cheamă la birou"));
    }

    @Test
    void audienceRequestIsUuidBackedDeduplicatedAndResolvable() {
        TestPlayer commissioner = commissioner();
        TestPlayer guard = server.add("guard");
        personnel.authorizeExperienced(commissioner, guard, 1);

        assertTrue(personnel.requestAudience(guard, "Vreau să discut despre organizarea patrulei."));
        assertFalse(personnel.requestAudience(guard, "A doua cerere"));
        var entries = personnel.pendingPersonnelInbox(commissioner);
        assertEquals(1, entries.size());
        assertEquals("AUDIENCE", entries.getFirst().type);
        assertEquals(guard.uuid().toString(), entries.getFirst().senderUuid);

        assertTrue(personnel.resolveAudience(commissioner, entries.getFirst().id));
        assertEquals("RESOLVED", players.state(guard.uuid()).audienceStatus);
        assertTrue(personnel.pendingPersonnelInbox(commissioner).isEmpty());
    }
}
