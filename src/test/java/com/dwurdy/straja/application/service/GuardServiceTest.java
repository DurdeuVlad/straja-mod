package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.PermissionLevel;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuardServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private GuardService guards;
    private PlayerService players;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        clock = new FixedClock(0);
        StrajaPolicies policies = Fakes.policies();
        ctx = Fakes.context(server, clock, policies);
        players = new PlayerService(ctx);
        guards = new GuardService(ctx, players, new AuditService(ctx), new EquipmentService(ctx));
    }

    private TestPlayer commissioner() {
        TestPlayer c = server.add("dwurdy");
        return c;
    }

    private void placeCheckpoints() {
        SetupData setup = ctx.setup().read();
        for (int i = 1; i <= 4; i++) {
            String id = "checkpoint_" + i;
            var cp = setup.checkpoints.stream()
                    .filter(c -> c.id.equals(id)).findFirst()
                    .orElseGet(() -> {
                        var created = new SetupData.Checkpoint();
                        created.id = id;
                        setup.checkpoints.add(created);
                        return created;
                    });
            cp.x = (double) i;
            cp.y = 64d;
            cp.z = 0d;
        }
        ctx.setup().write(setup);
    }

    private void standAt(TestPlayer p, int checkpoint) {
        p.x = checkpoint;
        p.y = 64;
        p.z = 0;
    }

    private TestPlayer recruitToStagiar() {
        TestPlayer c = commissioner();
        TestPlayer p = server.add("recruit");
        assertTrue(guards.invite(c, p), "commissioner invite failed");
        guards.recruit(p);
        for (int i = 0; i < ctx.policies().quiz.size(); i++) {
            var state = players.state(p.uuid());
            String questionId = state.quizOrder.get(state.quizIndex);
            var question = ctx.policies().quiz.stream()
                    .filter(q -> q.id().equals(questionId)).findFirst().orElseThrow();
            guards.quiz(p, question.answers().get(0));
        }
        return p;
    }

    private TestPlayer invitedRecruit() {
        TestPlayer c = commissioner();
        TestPlayer p = server.add("recruit");
        assertTrue(guards.invite(c, p));
        return p;
    }

    private StrajaPolicies.QuizQuestion policyQuiz(String id) {
        return ctx.policies().quiz.stream()
                .filter(q -> q.id().equals(id)).findFirst().orElseThrow();
    }

    private StrajaPolicies.QuizQuestion policyTraining(String id) {
        return ctx.policies().trainingQuiz.stream()
                .filter(q -> q.id().equals(id)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- invite/quiz

    @Test
    void onlyCommissionerCanInvite() {
        TestPlayer outsider = server.add("someone");
        TestPlayer target = server.add("recruit");
        assertFalse(guards.invite(outsider, target));
        assertFalse(players.state(target.uuid()).invited);
    }

    @Test
    void fullQuizPromotesToJunior() {
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        assertEquals(Rank.STAGIAR.level(), state.rank);
        assertTrue(state.quizPassed);
        assertTrue(p.told("Stagiar"));
    }

    @Test
    void wrongAnswerAppliesCooldown() {
        TestPlayer c = commissioner();
        TestPlayer p = server.add("recruit");
        guards.invite(c, p);
        guards.quiz(p, "răspuns greșit");
        var state = players.state(p.uuid());
        assertNotNull(state.quizCooldownAt);
        assertTrue(state.quizCooldownAt > clock.nowMillis());
        // still cooling down -> next answer refused silently
        guards.quiz(p, "disciplina");
        assertTrue(p.told("Mai încearcă"));
        // after cooldown the right answer works
        clock.advance(ctx.policies().quizCooldownMinutes * 60_000L + 1);
        var fresh = players.state(p.uuid());
        String currentId = fresh.quizOrder.get(fresh.quizIndex);
        var question = ctx.policies().quiz.stream()
                .filter(q -> q.id().equals(currentId)).findFirst().orElseThrow();
        guards.quiz(p, question.answers().get(0));
        assertEquals(1, players.state(p.uuid()).quizIndex);
    }

    @Test
    void uninvitedCannotQuiz() {
        TestPlayer p = server.add("stranger");
        guards.quiz(p, "disciplina");
        assertTrue(p.told("Recepție"), "no application → directed to Recepție");
        assertEquals(0, players.state(p.uuid()).quizIndex);
    }

    // ---------------------------------------------------------------- native quiz prompts

    @Test
    void invitedPromptPersistsServerQuestionId() {
        TestPlayer p = invitedRecruit();
        var prompt = guards.currentQuizPrompt(p).orElseThrow();
        assertFalse(prompt.questionId().isBlank());
        assertEquals("Recrutare Straja", prompt.title());
        assertEquals(120, prompt.maxLength());
        var state = players.state(p.uuid());
        assertEquals(prompt.questionId(), state.quizOrder.get(state.quizIndex),
                "the exposed prompt id must be the persisted server-selected one");
        assertEquals(policyQuiz(prompt.questionId()).question(), prompt.question());
    }

    @Test
    void boundCorrectAnswerAdvancesExactlyOnce() {
        TestPlayer p = invitedRecruit();
        var prompt = guards.currentQuizPrompt(p).orElseThrow();
        var question = policyQuiz(prompt.questionId());
        assertTrue(guards.answerQuiz(p, prompt.questionId(), question.answers().get(0)));
        assertEquals(1, players.state(p.uuid()).quizIndex);
        assertFalse(guards.answerQuiz(p, prompt.questionId(), question.answers().get(0)),
                "replaying the same bound answer must not advance again");
        assertEquals(1, players.state(p.uuid()).quizIndex);
        assertNull(players.state(p.uuid()).quizCooldownAt);
    }

    @Test
    void staleQuestionIdDoesNotAdvanceOrCooldown() {
        TestPlayer p = invitedRecruit();
        var first = guards.currentQuizPrompt(p).orElseThrow();
        assertTrue(guards.answerQuiz(p, first.questionId(),
                policyQuiz(first.questionId()).answers().get(0)));
        var second = guards.currentQuizPrompt(p).orElseThrow();
        assertNotEquals(first.questionId(), second.questionId());
        assertFalse(guards.answerQuiz(p, first.questionId(), "anything"));
        var state = players.state(p.uuid());
        assertEquals(1, state.quizIndex);
        assertNull(state.quizCooldownAt, "stale bound answer must not set a cooldown");
    }

    @Test
    void wrongBoundAnswerAppliesCooldownAndBlocksPrompt() {
        TestPlayer p = invitedRecruit();
        var prompt = guards.currentQuizPrompt(p).orElseThrow();
        assertTrue(guards.answerQuiz(p, prompt.questionId(), "sigur greșit"),
                "a processed wrong answer still reports true");
        var state = players.state(p.uuid());
        assertNotNull(state.quizCooldownAt);
        assertTrue(state.quizCooldownAt > clock.nowMillis());
        assertTrue(guards.currentQuizPrompt(p).isEmpty());
        assertTrue(p.told("Mai încearcă"));
        assertFalse(guards.answerQuiz(p, prompt.questionId(), "disciplina"),
                "answers during cooldown must fail closed");
    }

    @Test
    void promptRequiresEligibleStatus() {
        TestPlayer stranger = server.add("stranger");
        assertTrue(guards.currentQuizPrompt(stranger).isEmpty());
        assertTrue(stranger.told("Recepție"));

        TestPlayer p = invitedRecruit();
        var state = players.state(p.uuid());
        state.fired = true;
        players.save(p.uuid(), state);
        assertTrue(guards.currentQuizPrompt(p).isEmpty());
        assertFalse(guards.answerQuiz(p, "any", "x"));

        state = players.state(p.uuid());
        state.fired = false;
        state.resigned = true;
        players.save(p.uuid(), state);
        assertTrue(guards.currentQuizPrompt(p).isEmpty());
        assertFalse(guards.answerQuiz(p, "any", "x"));

        state = players.state(p.uuid());
        state.resigned = false;
        state.suspended = true;
        players.save(p.uuid(), state);
        assertTrue(guards.currentQuizPrompt(p).isEmpty());
        assertFalse(guards.answerQuiz(p, "any", "x"));
    }

    @Test
    void resignedAndSuspendedCannotUseLegacyQuiz() {
        TestPlayer p = invitedRecruit();
        var state = players.state(p.uuid());
        state.suspended = true;
        players.save(p.uuid(), state);
        guards.quiz(p, "disciplina");
        assertEquals(0, players.state(p.uuid()).quizIndex);
        state = players.state(p.uuid());
        state.suspended = false;
        state.resigned = true;
        players.save(p.uuid(), state);
        guards.quiz(p, "disciplina");
        assertEquals(0, players.state(p.uuid()).quizIndex);
    }

    @Test
    void trainingPromptPersistsAndBoundAnswerPasses() {
        TestPlayer p = recruitToStagiar();
        var prompt = guards.currentQuizPrompt(p).orElseThrow();
        assertEquals("Instruire Straja", prompt.title());
        assertEquals(prompt.questionId(), players.state(p.uuid()).trainingQuizId,
                "the exposed training id must be the persisted server-selected one");
        var item = policyTraining(prompt.questionId());
        assertTrue(guards.answerQuiz(p, prompt.questionId(), item.answers().get(0)));
        assertEquals(Boolean.TRUE, players.state(p.uuid()).trainingPassed.get(prompt.questionId()));
    }

    @Test
    void boundAnswerRequiresPersistedIdEquality() {
        TestPlayer c = commissioner();
        TestPlayer p1 = server.add("r1");
        TestPlayer p2 = server.add("r2");
        guards.invite(c, p1);
        guards.invite(c, p2);
        var prompt2 = guards.currentQuizPrompt(p2).orElseThrow();
        String otherId = ctx.policies().quiz.stream()
                .map(StrajaPolicies.QuizQuestion::id)
                .filter(id -> !id.equals(prompt2.questionId())).findFirst().orElseThrow();
        assertFalse(guards.answerQuiz(p2, otherId, policyQuiz(otherId).answers().get(0)),
                "a valid question id that is not the player's persisted current id is rejected");
        assertEquals(0, players.state(p2.uuid()).quizIndex);
        assertTrue(guards.answerQuiz(p2, prompt2.questionId(),
                policyQuiz(prompt2.questionId()).answers().get(0)));
    }

    @Test
    void stopDutyReclaimsAllServiceEquipment() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        guards.startDuty(p);
        assertNotNull(players.state(p.uuid()).serviceEquipment);
        guards.stopDuty(p);
        var state = players.state(p.uuid());
        assertFalse(state.duty);
        assertNull(state.serviceEquipment);
        long remaining = 0;
        for (int i = 0; i < p.inventory.slots(); i++) {
            var stack = p.inventory.stackAt(i);
            if (!stack.isEmpty() && "1".equals(stack.data("StrajaService"))) remaining++;
        }
        assertEquals(0, remaining, "service equipment left in inventory after reclaim");
    }

    @Test
    void serviceLeaseExpiryEndsDutyAndReclaims() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        guards.startDuty(p);
        var state = players.state(p.uuid());
        assertTrue(state.duty);
        assertNotNull(state.serviceEquipment);
        // age the lease past serviceLeaseMinutes
        state.serviceEquipment.issuedAt =
                clock.nowMillis() - (ctx.policies().serviceLeaseMinutes + 1L) * 60_000L;
        players.save(p.uuid(), state);
        guards.tickPlayerDuty(p);
        var after = players.state(p.uuid());
        assertFalse(after.duty);
        assertNull(after.serviceEquipment);
        assertEquals("service_lease_expired", after.lastEndReason);
        assertTrue(p.told("echipament"));
    }

    @Test
    void serviceLeaseDoesNotExpireEarly() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        guards.startDuty(p);
        var state = players.state(p.uuid());
        state.serviceEquipment.issuedAt =
                clock.nowMillis() - (ctx.policies().serviceLeaseMinutes - 1L) * 60_000L;
        players.save(p.uuid(), state);
        guards.tickPlayerDuty(p);
        var after = players.state(p.uuid());
        assertTrue(after.duty);
        assertNotNull(after.serviceEquipment);
    }

    @Test
    void recoverOnLoginClosesDutyLeftOverFromPreviousBoot() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        var boot1 = new GuardService(ctx, players, new AuditService(ctx),
                new EquipmentService(ctx), () -> "boot-1");
        boot1.recoverOnLogin(p);
        boot1.startDuty(p);
        assertTrue(players.state(p.uuid()).duty);
        boot1.recoverOnLogin(p); // same boot: a relogin keeps the active duty
        assertTrue(players.state(p.uuid()).duty);
        var boot2 = new GuardService(ctx, players, new AuditService(ctx),
                new EquipmentService(ctx), () -> "boot-2");
        boot2.recoverOnLogin(p); // restart: the stale duty is closed, not resumed
        var state = players.state(p.uuid());
        assertFalse(state.duty);
        assertEquals("server_restart", state.lastEndReason);
        assertEquals("boot-2", state.runtimeBootId);
        assertNull(state.serviceEquipment, "service equipment is reclaimed with the duty");
        boot2.recoverOnLogin(p); // recovery is idempotent
        assertFalse(players.state(p.uuid()).duty);
    }

    @Test
    void partialEquipmentIssueChargesNoDebtForUndeliveredItems() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        // promote to GUARD so the spec has 3 items (sword, baton, cuffs)
        var state = players.state(p.uuid());
        state.rank = Rank.GUARD.level();
        players.save(p.uuid(), state);
        // exactly one free slot → sword delivers, baton + cuffs cannot
        for (int i = 1; i < p.inventory.slots(); i++) {
            p.inventory.slots.set(i, new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }
        guards.startDuty(p);
        state = players.state(p.uuid());
        assertFalse(state.duty, "partial issue must abort the duty start");
        assertNull(state.serviceEquipment);
        assertEquals(0, state.equipmentDebt,
                "gear that was never delivered must not create equipment debt");
        assertFalse(p.told("Echipament lipsă"), "no missing-equipment charge for undelivered items");
        // the one delivered item was reclaimed cleanly on abort
        assertEquals(0, countItem(p, "minecraft:iron_sword"));
        // a retry with free inventory issues everything normally
        for (int i = 0; i < p.inventory.slots(); i++) p.inventory.extract(i, 64);
        standAt(p, 1);
        guards.startDuty(p);
        state = players.state(p.uuid());
        assertTrue(state.duty);
        assertEquals(3, state.serviceEquipment.items.size());
    }

    @Test
    void failedEquipmentIssueWithNoFreeSlotsChargesNothing() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        for (int i = 0; i < p.inventory.slots(); i++) {
            p.inventory.slots.set(i, new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }
        guards.startDuty(p);
        var state = players.state(p.uuid());
        assertFalse(state.duty);
        assertNull(state.serviceEquipment);
        assertEquals(0, state.equipmentDebt);
        assertEquals(0, countItem(p, "minecraft:iron_sword"));
    }

    @Test
    void missingEquipmentBecomesDebt() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        guards.startDuty(p);
        // lose the issued sword: empty the whole inventory before stopping
        for (int i = 0; i < p.inventory.slots(); i++) p.inventory.extract(i, 64);
        guards.stopDuty(p);
        var state = players.state(p.uuid());
        assertNull(state.serviceEquipment);
        assertTrue(state.equipmentDebt > 0, "missing equipment should create debt");
        assertTrue(p.told("Echipament lipsă"));
    }

    // ---------------------------------------------------------------- duty

    @Test
    void startDutyRefusesWithoutCheckpoints() {
        TestPlayer p = recruitToStagiar();
        guards.startDuty(p);
        assertFalse(players.state(p.uuid()).duty);
        assertTrue(p.told("Checkpoint-urile nu sunt configurate"));
    }

    @Test
    void startDutyBeginsPatrolAndIssuesEquipment() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        var state = players.state(p.uuid());
        assertTrue(state.duty);
        assertEquals("ACTIVE", state.patrolState);
        assertEquals(1, state.serviceEquipment.items.size()); // junior: sword only
        assertEquals(1, countItem(p, "minecraft:iron_sword"));
    }

    @Test
    void checkpointActivationFollowsRoute() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        // wrong checkpoint refused
        standAt(p, 2);
        guards.checkpoint(p, "checkpoint_2");
        assertEquals("ACTIVE", players.state(p.uuid()).patrolState);
        // right one
        standAt(p, 1);
        guards.checkpoint(p, "checkpoint_1");
        assertEquals("WAITING", players.state(p.uuid()).patrolState);
    }

    @Test
    void antiAfkStopsAccrualButKeepsPatrolTimers() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        // player idles for 30 minutes of wall time
        clock.advance(30 * 60_000L);
        guards.tickPlayerDuty(p);
        var state = players.state(p.uuid());
        assertTrue(state.salaryActivityPaused, "AFK guard must stop accruing");
        // patrol deadline still evaluated against wall clock (default 30min) -> timed out
        assertFalse(state.duty);
        assertEquals("checkpoint_timeout", state.lastEndReason);
    }

    @Test
    void movementKeepsAccrual() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        // move every ~30s for 10 minutes -> 10 paid minutes at Stagiar 16/h
        for (int i = 0; i < 20; i++) {
            p.x += 1.0;
            clock.advance(30_000);
            guards.tickPlayerDuty(p);
        }
        var state = players.state(p.uuid());
        assertTrue(state.duty);
        assertEquals(2, state.unpaidSalary); // 600s x 16/3600 = 2.67 -> 2 coins, fraction carried
        assertEquals(1, state.serviceBlocks); // 10 minutes = 1 service point
    }

    // ---------------------------------------------------------------- salary

    @Test
    void salaryPayoutIsIdempotent() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        for (int i = 0; i < 20; i++) {
            p.x += 1.0;
            clock.advance(30_000);
            guards.tickPlayerDuty(p);
        }
        guards.salary(p);
        var currency = (Fakes.TestCurrency) ctx.currency();
        assertEquals(2, currency.balance); // 10min at Stagiar 16/h
        // replay: the same payout must not double-deliver
        guards.salary(p);
        assertEquals(2, currency.balance); // 10min at Stagiar 16/h
        assertTrue(p.told("Nu ai salariu disponibil"));
    }

    @Test
    void salaryInProgressWithoutReceiptLocksForReview() {
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        state.unpaidSalary = 50;
        state.salaryPaymentStatus = "IN_PROGRESS";
        state.salaryPayoutId = "salary:" + p.uuid() + ":50";
        players.save(p.uuid(), state);
        var currency = (Fakes.TestCurrency) ctx.currency();
        int before = currency.depositCalls;
        guards.salary(p);
        state = players.state(p.uuid());
        assertEquals("REVIEW", state.salaryPaymentStatus);
        assertEquals(50, state.unpaidSalary, "unconfirmed attempt must not clear the balance");
        assertEquals(before, currency.depositCalls, "no second payout may be attempted");
        assertTrue(p.told("verificarea Comisarului"));
        // subsequent attempts stay locked
        guards.salary(p);
        assertEquals("REVIEW", players.state(p.uuid()).salaryPaymentStatus);
        assertEquals(before, currency.depositCalls);
    }

    @Test
    void salaryInProgressWithReceiptMarksPaid() {
        TestPlayer p = recruitToStagiar();
        String payoutId = "salary:" + p.uuid() + ":50";
        var state = players.state(p.uuid());
        state.unpaidSalary = 50;
        state.salaryPaymentStatus = "IN_PROGRESS";
        state.salaryPayoutId = payoutId;
        players.save(p.uuid(), state);
        var currency = (Fakes.TestCurrency) ctx.currency();
        currency.receipts.add(payoutId); // coins were delivered before the crash
        int before = currency.depositCalls;
        guards.salary(p);
        state = players.state(p.uuid());
        assertEquals("PAID", state.salaryPaymentStatus);
        assertEquals(0, state.unpaidSalary);
        assertNull(state.salaryPayoutId);
        assertEquals(before, currency.depositCalls, "receipt proves delivery; no new payout");
    }

    @Test
    void salarySettlesEquipmentDebtFirst() {
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        state.unpaidSalary = 100;
        state.equipmentDebt = 40;
        players.save(p.uuid(), state);
        guards.salary(p);
        state = players.state(p.uuid());
        assertEquals(0, state.equipmentDebt);
        assertEquals(60, ((Fakes.TestCurrency) ctx.currency()).balance);
    }

    // ---------------------------------------------------------------- food/kit

    @Test
    void foodRequiresActiveDuty() {
        TestPlayer p = recruitToStagiar();
        guards.food(p);
        assertTrue(p.told("numai în timpul unei ture active"));
    }

    @Test
    void kitClaimsOncePerRank() {
        TestPlayer p = recruitToStagiar();
        guards.kit(p);
        // junior kit is 6 items, but the iron sword is leased as service equipment instead
        assertEquals(5, p.inventory.slots.stream().filter(s -> !s.isEmpty()).count());
        guards.kit(p);
        assertTrue(p.told("deja ridicat"));
    }

    // ---------------------------------------------------------------- status hooks

    @Test
    void statusChangeFiresAllRegisteredListeners() {
        TestPlayer c = commissioner();
        TestPlayer p = recruitToStagiar();
        var calls = new java.util.ArrayList<String>();
        guards.onStatusChange((target, reason) -> calls.add("first:" + reason));
        guards.onStatusChange((target, reason) -> calls.add("second:" + reason));
        guards.suspend(c, p);
        assertEquals(2, calls.size(), "every registered status hook must run");
        assertEquals(java.util.List.of("first:suspendat din Strajă", "second:suspendat din Strajă"), calls);
    }

    // ---------------------------------------------------------------- resignation

    @Test
    void resignRequiresNoticeThenConfirm() {
        TestPlayer p = recruitToStagiar();
        guards.resign(p, null);
        var state = players.state(p.uuid());
        assertTrue(state.resignationPending);
        guards.resign(p, "confirm"); // too early
        assertTrue(players.state(p.uuid()).resignationPending);
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        guards.resign(p, "confirm");
        state = players.state(p.uuid());
        assertTrue(state.resigned);
        assertEquals(0, state.rank);
    }

    @Test
    void resignationFiresStatusChangeHooks() {
        TestPlayer p = recruitToStagiar();
        var calls = new java.util.ArrayList<String>();
        guards.onStatusChange((target, reason) -> calls.add(target.uuid() + ":" + reason));
        guards.resign(p, null);
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        guards.resign(p, "confirm");
        assertEquals(java.util.List.of(p.uuid() + ":demisie în preaviz", p.uuid() + ":demisie semnată"), calls,
                "both resignation status transitions must notify room and mission lifecycle hooks");
    }

    @Test
    void rejoinCapsAtSergent() {
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        state.rank = Rank.INSPECTOR.level();
        players.save(p.uuid(), state);
        guards.resign(p, null);
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        guards.resign(p, "confirm");
        clock.advance(ctx.policies().resignationCooldownDays * 24L * 3600_000L + 1);
        guards.rejoin(p);
        state = players.state(p.uuid());
        assertEquals(Rank.SERGENT.level(), state.rank);
        assertFalse(state.resigned);
    }

    // ---------------------------------------------------------------- admin

    @Test
    void promoteRequiresServiceBlocks() {
        TestPlayer c = commissioner();
        TestPlayer p = recruitToStagiar();
        guards.promote(c, p); // 60 blocks required for GUARD
        assertEquals(Rank.STAGIAR.level(), players.state(p.uuid()).rank);
        assertTrue(c.told("blocuri de serviciu"));
        var state = players.state(p.uuid());
        state.serviceBlocks = 60;
        players.save(p.uuid(), state);
        guards.promote(c, p);
        assertEquals(Rank.GUARD.level(), players.state(p.uuid()).rank);
    }

    @Test
    void fireResetsEverything() {
        TestPlayer c = commissioner();
        TestPlayer p = recruitToStagiar();
        guards.fire(c, p);
        var state = players.state(p.uuid());
        assertTrue(state.fired);
        assertEquals(0, state.rank);
        assertFalse(state.invited);
    }

    @Test
    void lieutenantHasElevatedPermissionButNotCommissioner() {
        TestPlayer lt = server.add("locotenent");
        var state = players.state(lt.uuid());
        state.rank = Rank.INSPECTOR.level();
        players.save(lt.uuid(), state);
        assertEquals(PermissionLevel.LIEUTENANT, players.permissionLevel(lt, players.state(lt.uuid())));
        assertFalse(players.isCommissioner(lt));
    }

    // ---------------------------------------------------------------- duty view

    private boolean audited(String action) {
        return ctx.audit().tail(200).stream().anyMatch(e -> action.equals(e.action));
    }

    @Test
    void dutyViewForCivilExposesNothing() {
        TestPlayer p = server.add("civil");
        var view = guards.dutyView(p);
        assertFalse(view.activeGuard());
        assertFalse(view.canStart());
        assertTrue(view.checkpointId().isEmpty());
        assertFalse(view.canStop());
        assertFalse(view.canClaimSalary());
        assertFalse(view.canViewCoins());
        assertFalse(view.canClaimFood());
        assertFalse(view.canClaimKit());
        assertFalse(view.canRequestRegear());
        assertFalse(view.canBeginResignation());
        assertFalse(view.canConfirmResignation());
        assertFalse(view.canCancelResignation());
        assertFalse(view.canRejoin());
    }

    @Test
    void dutyViewForEligibleOffDutyGuard() {
        TestPlayer p = recruitToStagiar();
        var view = guards.dutyView(p);
        assertTrue(view.activeGuard());
        assertTrue(view.canStart());
        assertTrue(view.checkpointId().isEmpty());
        assertFalse(view.canStop());
        assertFalse(view.canClaimSalary());
        assertTrue(view.canViewCoins());
        assertFalse(view.canClaimFood(), "food requires an active duty");
        assertTrue(view.canClaimKit());
        assertTrue(view.canRequestRegear());
        assertTrue(view.canBeginResignation());
        assertFalse(view.canConfirmResignation());
        assertFalse(view.canCancelResignation());
        assertFalse(view.canRejoin());
    }

    @Test
    void dutyViewOnActiveDutyExposesCheckpointAndStop() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        var view = guards.dutyView(p);
        assertTrue(view.activeGuard());
        assertFalse(view.canStart());
        assertEquals("checkpoint_1", view.checkpointId());
        assertTrue(view.canStop());
        assertTrue(view.canClaimFood());
        assertFalse(view.canRequestRegear(), "regear requires off duty");
        assertTrue(view.canClaimKit(), "kit for the current rank was not claimed yet");
    }

    @Test
    void dutyViewWaitingPatrolHidesCheckpoint() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        guards.checkpoint(p, "checkpoint_1");
        assertEquals("WAITING", players.state(p.uuid()).patrolState);
        var view = guards.dutyView(p);
        assertTrue(view.checkpointId().isEmpty());
        assertTrue(view.canStop());
    }

    @Test
    void dutyViewSuspendedAndFiredCannotStart() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        state.suspended = true;
        players.save(p.uuid(), state);
        assertFalse(guards.dutyView(p).activeGuard());
        assertFalse(guards.dutyView(p).canStart());
        state = players.state(p.uuid());
        state.suspended = false;
        state.fired = true;
        players.save(p.uuid(), state);
        assertFalse(guards.dutyView(p).activeGuard());
        assertFalse(guards.dutyView(p).canStart());
    }

    @Test
    void dutyViewResignationPendingLifecycle() {
        TestPlayer p = recruitToStagiar();
        guards.beginResignation(p);
        var view = guards.dutyView(p);
        assertTrue(view.canCancelResignation());
        assertFalse(view.canConfirmResignation(), "confirm is gated by the deadline");
        assertFalse(view.canBeginResignation());
        assertFalse(view.canStart());
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        view = guards.dutyView(p);
        assertTrue(view.canConfirmResignation());
        assertTrue(view.canCancelResignation());
        guards.confirmResignation(p);
        assertTrue(players.state(p.uuid()).resigned);
    }

    @Test
    void dutyViewCancelResignationRestoresEligibility() {
        TestPlayer p = recruitToStagiar();
        guards.beginResignation(p);
        guards.cancelResignation(p);
        var view = guards.dutyView(p);
        assertFalse(players.state(p.uuid()).resignationPending);
        assertTrue(view.canStart());
        assertTrue(view.canBeginResignation());
    }

    @Test
    void dutyViewResignedExposesRejoinOnlyAfterCooldown() {
        TestPlayer p = recruitToStagiar();
        guards.beginResignation(p);
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        guards.confirmResignation(p);
        var view = guards.dutyView(p);
        assertFalse(view.activeGuard());
        assertFalse(view.canRejoin(), "rejoin is gated by the persisted cooldown");
        clock.advance(ctx.policies().resignationCooldownDays * 24L * 3600_000L + 1);
        view = guards.dutyView(p);
        assertTrue(view.canRejoin());
        guards.rejoin(p);
        assertTrue(guards.dutyView(p).activeGuard());
    }

    @Test
    void checkpointRejectsWrongLocationAndUnknownId() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        p.x = 100;
        guards.checkpoint(p, "checkpoint_1");
        assertEquals("ACTIVE", players.state(p.uuid()).patrolState,
                "wrong location must not advance the patrol");
        standAt(p, 1);
        guards.checkpoint(p, "nonexistent");
        assertEquals("ACTIVE", players.state(p.uuid()).patrolState,
                "an unknown checkpoint id must not advance the patrol");
        assertTrue(p.told("Nu ești la checkpoint"));
    }

    @Test
    void setupErrorDoesNotAdvertiseTypedCommand() {
        TestPlayer p = recruitToStagiar();
        guards.startDuty(p);
        assertTrue(p.told("cele patru puncte de patrulare"));
        assertFalse(p.messages.stream().anyMatch(m -> m.contains("/straja")),
                "player-facing setup errors must not advertise typed commands");
    }

    @Test
    void successfulDutyActionsWriteAudit() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        assertTrue(audited("duty_start"));
        guards.checkpoint(p, "checkpoint_1");
        assertTrue(audited("duty_checkpoint"));
        guards.food(p);
        assertTrue(audited("food_claim"));
        guards.kit(p);
        assertTrue(audited("kit_claim"));
        guards.stopDuty(p);
        guards.requestRegear(p);
        assertTrue(audited("regear_request"));
    }

    private int countItem(TestPlayer p, String itemId) {
        return p.inventory.countOf(itemId);
    }

    // ------------------------------------------------------------ free duty

    private TestPlayer guardAtRank(int rank) {
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        state.rank = rank;
        players.save(p.uuid(), state);
        return p;
    }

    @Test
    void seniorStartsFreeDutyWithoutConfiguredCheckpoints() {
        // No checkpoints placed at all — patrol guards are blocked, seniors are not.
        TestPlayer senior = guardAtRank(Rank.SERGENT.level());
        guards.startDuty(senior);
        var state = players.state(senior.uuid());
        assertTrue(state.duty, "senior must start a free shift without checkpoints");
        assertEquals("FREE", state.mode);
        assertNotNull(state.serviceEquipment, "free duty still issues service equipment");
        assertTrue(senior.told("Tură liberă"));
    }

    @Test
    void commissionerStartsFreeDutyWithoutCheckpoints() {
        TestPlayer c = commissioner();
        guards.startDuty(c);
        var state = players.state(c.uuid());
        assertTrue(state.duty, "commissioner starts a free shift without checkpoints");
        assertEquals("FREE", state.mode);
    }

    @Test
    void juniorStillRequiresPatrolRoute() {
        TestPlayer junior = recruitToStagiar();
        guards.startDuty(junior);
        assertFalse(players.state(junior.uuid()).duty);
        assertTrue(junior.told("cele patru puncte de patrulare"));
    }

    @Test
    void freeDutyAccruesHourlyAndIgnoresAfkGate() {
        TestPlayer senior = guardAtRank(Rank.SERGENT.level());
        guards.startDuty(senior);
        // One real hour — far beyond the 90s AFK grace, with zero movement.
        clock.advance(3600_000L);
        guards.tickPlayerDuty(senior);
        var state = players.state(senior.uuid());
        assertTrue(state.duty);
        assertEquals(ctx.policies().salaryPerHour(Rank.SERGENT.level()),
                state.unpaidSalary, "one real hour of free duty pays the hourly wage");
        assertFalse(state.salaryActivityPaused, "trusted ranks never hit the AFK salary gate");
    }

    @Test
    void freeDutyEndsAtWill() {
        TestPlayer senior = guardAtRank(Rank.SERGENT.level());
        guards.startDuty(senior);
        clock.advance(5 * 60_000L);
        guards.stopDuty(senior);
        var state = players.state(senior.uuid());
        assertFalse(state.duty);
        assertEquals("voluntary_stop", state.lastEndReason);
        assertNull(state.serviceEquipment, "equipment is reclaimed when the shift ends");
    }

    @Test
    void freeDutyEquipmentLeaseStillEndsShift() {
        TestPlayer senior = guardAtRank(Rank.SERGENT.level());
        guards.startDuty(senior);
        var state = players.state(senior.uuid());
        state.serviceEquipment.issuedAt =
                clock.nowMillis() - (ctx.policies().serviceLeaseMinutes + 1L) * 60_000L;
        players.save(senior.uuid(), state);
        guards.tickPlayerDuty(senior);
        var after = players.state(senior.uuid());
        assertFalse(after.duty);
        assertEquals("service_lease_expired", after.lastEndReason);
    }

    // ------------------------------------------------------------ native faction

    @Test
    void nativeFactionPersistsAndShowsInStatus() {
        TestPlayer p = server.add("nomad");
        assertTrue(guards.declareNativeFaction(p, "Gilda Mercenarilor"));
        assertEquals("Gilda Mercenarilor", players.state(p.uuid()).nativeFaction);
        guards.showStatus(p);
        assertTrue(p.told("Facțiune nativă: Gilda Mercenarilor"));
    }

    @Test
    void nativeFactionClearsOnNoneAndRejectsOversized() {
        TestPlayer p = server.add("nomad");
        guards.declareNativeFaction(p, "Gilda Mercenarilor");
        assertTrue(guards.declareNativeFaction(p, "niciuna"));
        assertNull(players.state(p.uuid()).nativeFaction);

        assertFalse(guards.declareNativeFaction(p, "x".repeat(ctx.policies().nativeFactionMaxLength + 1)));
        assertNull(players.state(p.uuid()).nativeFaction);
    }

    @Test
    void commissionerEditsOtherPlayersNativeFaction() {
        TestPlayer c = commissioner();
        TestPlayer p = server.add("nomad");
        assertTrue(guards.setNativeFactionFor(c, p, "Clanul Nordului"));
        assertEquals("Clanul Nordului", players.state(p.uuid()).nativeFaction);

        TestPlayer random = server.add("random");
        assertFalse(guards.setNativeFactionFor(random, p, "Impostori"),
                "non-commissioners cannot edit another player's faction");
        assertEquals("Clanul Nordului", players.state(p.uuid()).nativeFaction);
    }

    // ------------------------------------------------------------ trainer

    private void passModulesUpTo(TestPlayer p, int rank) {
        var state = players.state(p.uuid());
        ctx.policies().trainingQuiz.stream()
                .filter(q -> q.minRank() <= rank)
                .forEach(q -> state.trainingPassed.put(q.id(), true));
        players.save(p.uuid(), state);
    }

    private void setBlocks(TestPlayer p, int blocks) {
        var state = players.state(p.uuid());
        state.serviceBlocks = blocks;
        players.save(p.uuid(), state);
    }

    @Test
    void trainerPromotionRequiresConfiguredBlocks() {
        TestPlayer p = recruitToStagiar();
        passModulesUpTo(p, Rank.STAGIAR.level());
        setBlocks(p, 30);
        guards.requestPromotion(p);
        assertEquals(Rank.STAGIAR.level(), players.state(p.uuid()).rank);
        assertTrue(p.told("puncte de serviciu"));
    }

    @Test
    void trainerPromotionRequiresFinishedModules() {
        TestPlayer p = recruitToStagiar();
        setBlocks(p, 500);
        guards.requestPromotion(p);
        assertEquals(Rank.STAGIAR.level(), players.state(p.uuid()).rank);
        assertTrue(p.told("module rămase"));
    }

    @Test
    void trainerPromotesWhenBlocksAndModulesComplete() {
        TestPlayer p = recruitToStagiar();
        passModulesUpTo(p, Rank.STAGIAR.level());
        setBlocks(p, ctx.policies().promotionServiceBlocks.get(2));
        guards.requestPromotion(p);
        var state = players.state(p.uuid());
        assertEquals(Rank.GUARD.level(), state.rank);
        assertEquals(0, state.kitClaimedRank, "new rank unlocks the next kit");
        assertTrue(p.told("Avansare: Străjer"));
    }

    @Test
    void trainerCannotPromoteToUnconfiguredRank() {
        TestPlayer senior = guardAtRank(Rank.SERGENT.level());
        passModulesUpTo(senior, Rank.SERGENT.level());
        setBlocks(senior, 9999);
        guards.requestPromotion(senior);
        assertEquals(Rank.SERGENT.level(), players.state(senior.uuid()).rank,
                "ranks without a configured threshold stay a commissioner decision");
        assertTrue(senior.told("decizia Comisarului"));
    }

    @Test
    void trainerPromotionRefusesCiviliansAndSuspended() {
        TestPlayer civilian = server.add("civil");
        guards.requestPromotion(civilian);
        assertTrue(civilian.told("permite avansarea"));

        TestPlayer p = recruitToStagiar();
        passModulesUpTo(p, Rank.STAGIAR.level());
        setBlocks(p, 500);
        var state = players.state(p.uuid());
        state.suspended = true;
        players.save(p.uuid(), state);
        guards.requestPromotion(p);
        assertEquals(Rank.STAGIAR.level(), players.state(p.uuid()).rank);
    }

    @Test
    void trainingViewReflectsEligibility() {
        TestPlayer p = recruitToStagiar();
        var view = guards.trainingView(p);
        assertEquals(2, view.nextRank());
        assertFalse(view.canPromote(), "pending modules block promotion");

        passModulesUpTo(p, Rank.STAGIAR.level());
        setBlocks(p, ctx.policies().promotionServiceBlocks.get(2));
        view = guards.trainingView(p);
        assertTrue(view.canPromote());
        assertEquals(ctx.policies().promotionServiceBlocks.get(2), view.requiredBlocks());
    }

    @Test
    void showProgressReportsPointsAndNextRank() {
        TestPlayer p = recruitToStagiar();
        setBlocks(p, 10);
        guards.showProgress(p);
        assertTrue(p.told("puncte de serviciu: 10"));
        assertTrue(p.told("Avansare la Străjer"));
    }

    @Test
    void trainerHandsManualOnce() {
        TestPlayer p = recruitToStagiar();
        guards.giveManual(p);
        assertEquals(1, countItem(p, ctx.policies().trainingManualItem));
        guards.giveManual(p);
        assertEquals(1, countItem(p, ctx.policies().trainingManualItem),
                "the manual is not duplicated while the player holds one");
        assertTrue(p.told("Ai deja Manualul"));
    }

    @Test
    void trainerManualGoesToInvitedRecruitsNotStrangers() {
        TestPlayer p = invitedRecruit();
        guards.giveManual(p);
        assertEquals(1, countItem(p, ctx.policies().trainingManualItem));

        TestPlayer stranger = server.add("stranger");
        guards.giveManual(stranger);
        assertEquals(0, countItem(stranger, ctx.policies().trainingManualItem));
    }

    @Test
    void trainerManualRespectsFullInventory() {
        TestPlayer p = recruitToStagiar();
        for (int i = 0; i < p.inventory.slots(); i++) {
            p.inventory.insert(new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }
        guards.giveManual(p);
        assertEquals(0, countItem(p, ctx.policies().trainingManualItem));
        assertTrue(p.told("Inventarul este plin"));
    }

    // ------------------------------------------------------------ guided setup

    private void registerAllNpcs() {
        var registry = ctx.npcs().read();
        for (String role : NpcAdminService.ROLE_ORDER) {
            var record = new com.dwurdy.straja.domain.model.NpcRegistry.Record();
            record.entityUuid = "npc-" + role;
            record.role = role;
            registry.npcs.put(record.entityUuid, record);
        }
        ctx.npcs().write(registry);
    }

    private void stampAllLocations() {
        SetupData setup = ctx.setup().read();
        for (String key : SetupData.LOCATION_KEYS) {
            var loc = new SetupData.Location();
            loc.dimension = "minecraft:overworld";
            loc.x = 1; loc.y = 64; loc.z = 1;
            setup.locations.put(key, loc);
        }
        ctx.setup().write(setup);
    }

    @Test
    void setupLocationsHereStampsEveryKey() {
        TestPlayer c = commissioner();
        c.x = 10; c.y = 64; c.z = 20;
        guards.setupLocationsHere(c);
        var setup = ctx.setup().read();
        for (String key : SetupData.LOCATION_KEYS) {
            var loc = setup.locations.get(key);
            assertNotNull(loc, key + " should be stamped");
            assertEquals(10, loc.x);
            assertEquals(20, loc.z);
        }
    }

    @Test
    void setupLocationsHereIsCommissionerOnly() {
        TestPlayer p = server.add("someone");
        guards.setupLocationsHere(p);
        assertTrue(ctx.setup().read().locations.isEmpty());
        assertTrue(p.told("Doar Comisaru'"));
    }

    @Test
    void setupPatrolPlacesSquareAndSeedsMissionTimes() {
        TestPlayer c = commissioner();
        c.x = 100; c.y = 64; c.z = 100;
        guards.setupPatrol(c);
        var setup = ctx.setup().read();
        assertEquals(4, setup.checkpoints.stream().filter(SetupData.Checkpoint::isPlaced).count());
        var first = setup.checkpoints.get(0);
        assertEquals(108, first.x);
        assertEquals(92, first.z);
        for (var point : setup.checkpoints) {
            assertTrue(setup.missionMinutes.containsKey(point.id),
                    point.id + " should get a default mission time");
        }
        // Idempotent: re-running re-centers instead of failing.
        c.x = 0; c.z = 0;
        guards.setupPatrol(c);
        assertEquals(8, ctx.setup().read().checkpoints.get(0).x);
    }

    @Test
    void setupPatrolIsCommissionerOnly() {
        TestPlayer p = server.add("someone");
        guards.setupPatrol(p);
        var setup = ctx.setup().read();
        assertTrue(setup.checkpoints.stream().noneMatch(SetupData.Checkpoint::isPlaced));
    }

    @Test
    void setupChecklistGuidesThroughMissingPieces() {
        TestPlayer c = commissioner();
        guards.showSetup(c);
        assertTrue(c.told("Locații administrative: 0/" + SetupData.LOCATION_KEYS.length));
        assertTrue(c.told("Următorul pas:"));

        stampAllLocations();
        placeCheckpoints();
        registerAllNpcs();
        guards.showSetup(c);
        assertTrue(c.told("Configurare completă"));
    }

    @Test
    void setupHintOnlyNudgesTheCommissioner() {
        TestPlayer c = commissioner();
        TestPlayer other = server.add("visitor");
        assertNotNull(players.setupHintFor(c), "commissioner sees the next step");
        assertNull(players.setupHintFor(other), "non-commissioners get no setup hints");

        stampAllLocations();
        placeCheckpoints();
        registerAllNpcs();
        assertNull(players.setupHintFor(c), "no nudge once setup is complete");
    }

    // ------------------------------------------------------------ §7 secretary gating + factions

    private void placeSecretary(double x, double y, double z) {
        SetupData setup = ctx.setup().read();
        var loc = new SetupData.Location();
        loc.dimension = "minecraft:overworld";
        loc.x = x; loc.y = y; loc.z = z;
        setup.locations.put("secretary", loc);
        ctx.setup().write(setup);
    }

    @Test
    void normalDutyStartsAndStopsOnlyAtTheSecretary() {
        placeCheckpoints();
        placeSecretary(0, 0, 0);
        TestPlayer p = guardAtRank(Rank.STAGIAR.level());

        p.x = 100; p.z = 100;
        guards.startDuty(p);
        assertFalse(players.state(p.uuid()).duty, "patrol start away from the secretary is refused");
        assertTrue(p.told("secretară"));

        p.x = 1; p.z = 1;
        guards.startDuty(p);
        assertTrue(players.state(p.uuid()).duty);
        assertEquals("NORMAL", players.state(p.uuid()).mode);

        p.x = 100; p.z = 100;
        guards.stopDuty(p);
        assertTrue(players.state(p.uuid()).duty, "stop away from the secretary is refused");
        assertTrue(p.told("secretară"));

        p.x = 2; p.z = 2;
        guards.stopDuty(p);
        assertFalse(players.state(p.uuid()).duty);
    }

    @Test
    void freeDutyStopsAtWillAwayFromSecretary() {
        placeSecretary(0, 0, 0);
        TestPlayer senior = guardAtRank(Rank.SERGENT.level());
        senior.x = 100; senior.z = 100;
        guards.startDuty(senior);
        assertEquals("FREE", players.state(senior.uuid()).mode);
        guards.stopDuty(senior);
        assertFalse(players.state(senior.uuid()).duty);
    }

    @Test
    void dutyCapturesAndRestoresScoreboardFaction() {
        placeCheckpoints();
        placeSecretary(0, 0, 0);
        var factions = (Fakes.TestFactions) ctx.factions();
        TestPlayer p = guardAtRank(Rank.STAGIAR.level());
        factions.joinTeam(p, "Vladicani");
        p.x = 1; p.z = 1;

        guards.startDuty(p);
        assertEquals("Straja", factions.teamOf(p));
        assertEquals("Vladicani", players.state(p.uuid()).dutyCapturedFaction);

        guards.stopDuty(p);
        assertEquals("Vladicani", factions.teamOf(p));
        assertFalse(players.state(p.uuid()).dutyFactionManaged);
        assertNull(players.state(p.uuid()).dutyCapturedFaction);
    }

    @Test
    void factionlessGuardReturnsToNoTeam() {
        placeCheckpoints();
        placeSecretary(0, 0, 0);
        var factions = (Fakes.TestFactions) ctx.factions();
        TestPlayer p = guardAtRank(Rank.STAGIAR.level());
        p.x = 1; p.z = 1;

        guards.startDuty(p);
        assertEquals("Straja", factions.teamOf(p));
        guards.stopDuty(p);
        assertNull(factions.teamOf(p));
    }

    // ------------------------------------------------------------ §5/§6 application flow

    private void answerAdmissionQuiz(TestPlayer p) {
        for (int i = 0; i < ctx.policies().quiz.size(); i++) {
            var prompt = guards.currentQuizPrompt(p).orElseThrow();
            var question = ctx.policies().quiz.stream()
                    .filter(q -> q.id().equals(prompt.questionId())).findFirst().orElseThrow();
            guards.answerQuiz(p, prompt.questionId(), question.answers().get(0));
        }
    }

    @Test
    void applicationFlowAuthorizesThroughRecruiterQuiz() {
        TestPlayer p = server.add("applicant");

        guards.recruit(p);
        assertFalse(players.state(p.uuid()).invited);
        assertTrue(p.told("Recepție"), "no application → directed to Recepție");

        guards.applyForStraja(p);
        var state = players.state(p.uuid());
        assertEquals("APPLIED", state.applicationState);
        assertNotNull(state.appliedAt);
        assertEquals("applicant", state.applicationRecordedBy);

        guards.applyForStraja(p);
        assertTrue(p.told("deja înregistrată"), "re-applying while APPLIED is an idempotent tell");

        answerAdmissionQuiz(p);
        state = players.state(p.uuid());
        assertEquals(Rank.STAGIAR.level(), state.rank);
        assertEquals("AUTHORIZED", state.applicationState);
        assertTrue(state.invited, "authorization enters the Straja pipeline");
    }

    @Test
    void invitedApplicantSkipsTheApplicationStep() {
        TestPlayer p = invitedRecruit();
        guards.applyForStraja(p);
        assertTrue(p.told("invitație"), "invited recruits are sent straight to the Recrutor");
        guards.recruit(p);
        answerAdmissionQuiz(p);
        assertEquals(Rank.STAGIAR.level(), players.state(p.uuid()).rank);
    }

    @Test
    void firedAndAuthorizedCannotApply() {
        TestPlayer p = guardAtRank(Rank.STAGIAR.level());
        guards.applyForStraja(p);
        assertTrue(p.told("Ești deja"), "guards cannot re-apply");

        var state = players.state(p.uuid());
        state.fired = true;
        state.rank = 0;
        players.save(p.uuid(), state);
        guards.applyForStraja(p);
        assertTrue(p.told("îndepărtat"));
        assertEquals("AUTHORIZED", players.state(p.uuid()).applicationState,
                "the recorded chain stays intact even after firing");
    }

    @Test
    void dissolvedFactionFailsSafelyOnRestore() {
        placeCheckpoints();
        placeSecretary(0, 0, 0);
        var factions = (Fakes.TestFactions) ctx.factions();
        TestPlayer p = guardAtRank(Rank.STAGIAR.level());
        factions.joinTeam(p, "Vladicani");
        p.x = 1; p.z = 1;

        guards.startDuty(p);
        factions.teams.remove("Vladicani");
        guards.stopDuty(p);
        assertNull(factions.teamOf(p), "a dissolved team is never re-joined");
        assertTrue(p.told("nu mai există"));
    }
}
