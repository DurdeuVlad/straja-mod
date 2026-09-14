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
    void stopDutyKeepsAllPermanentGear() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        int swordBefore = countItem(p, "minecraft:iron_sword");
        assertEquals(1, swordBefore, "the rank kit sword is already owned gear");
        standAt(p, 1);
        guards.startDuty(p);
        guards.stopDuty(p);
        var state = players.state(p.uuid());
        assertFalse(state.duty);
        assertEquals(swordBefore, countItem(p, "minecraft:iron_sword"),
                "stopping a shift never takes owned gear back");
        long serviceMarked = 0;
        for (int i = 0; i < p.inventory.slots(); i++) {
            var stack = p.inventory.stackAt(i);
            if (!stack.isEmpty() && "1".equals(stack.data("StrajaService"))) serviceMarked++;
        }
        assertEquals(0, serviceMarked, "no leased service markers exist in the new model");
    }

    @Test
    void patrolTimeCapEndsDutyGracefully() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        var state = players.state(p.uuid());
        assertTrue(state.duty);
        // age the patrol past the real-time ceiling (default 60 min)
        state.dutyStartedAt =
                clock.nowMillis() - (ctx.policies().patrolMaxMinutes + 1L) * 60_000L;
        players.save(p.uuid(), state);
        guards.tickPlayerDuty(p);
        var after = players.state(p.uuid());
        assertFalse(after.duty);
        assertEquals("patrol_time_cap", after.lastEndReason);
        assertTrue(p.told("Serviciul s-a încheiat"));
        assertEquals(1, countItem(p, "minecraft:iron_sword"), "gear is kept at the cap");
    }

    @Test
    void patrolTimeCapDoesNotEndEarly() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        var state = players.state(p.uuid());
        state.dutyStartedAt =
                clock.nowMillis() - (ctx.policies().patrolMaxMinutes - 1L) * 60_000L;
        // keep the checkpoint deadline far out so only the cap could end it
        state.deadlineAt = clock.nowMillis() + 60 * 60_000L;
        players.save(p.uuid(), state);
        guards.tickPlayerDuty(p);
        var after = players.state(p.uuid());
        assertTrue(after.duty);
    }

    @Test
    void recoverOnLoginClosesDutyLeftOverFromPreviousBoot() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        var boot1 = new GuardService(ctx, players, new AuditService(ctx),
                new EquipmentService(ctx), () -> "boot-1");
        boot1.recoverOnLogin(p);
        standAt(p, 1);
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
        assertEquals(1, countItem(p, "minecraft:iron_sword"),
                "owned gear survives a restart-forced duty close");
        boot2.recoverOnLogin(p); // recovery is idempotent
        assertFalse(players.state(p.uuid()).duty);
    }

    @Test
    void dutyStartNeverIssuesOrRequiresGear() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        // a full inventory no longer blocks or aborts duty — nothing is issued at start
        for (int i = 0; i < p.inventory.slots(); i++) {
            p.inventory.slots.set(i, new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }
        standAt(p, 1);
        guards.startDuty(p);
        var state = players.state(p.uuid());
        assertTrue(state.duty, "duty starts regardless of inventory space");
        assertEquals("NORMAL", state.mode);
    }

    @Test
    void kitStaysClaimableWhenInventoryIsFullAtRankUp() {
        TestPlayer c = commissioner();
        TestPlayer p = server.add("recruit");
        assertTrue(guards.invite(c, p));
        guards.recruit(p);
        // answer all but the last question, then fill the inventory
        for (int i = 0; i < ctx.policies().quiz.size() - 1; i++) {
            var state = players.state(p.uuid());
            String questionId = state.quizOrder.get(state.quizIndex);
            var question = ctx.policies().quiz.stream()
                    .filter(q -> q.id().equals(questionId)).findFirst().orElseThrow();
            guards.quiz(p, question.answers().get(0));
        }
        for (int i = 0; i < p.inventory.slots(); i++) {
            p.inventory.slots.set(i, new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }
        var last = players.state(p.uuid());
        var lastQuestion = ctx.policies().quiz.stream()
                .filter(q -> q.id().equals(last.quizOrder.get(last.quizIndex))).findFirst().orElseThrow();
        guards.quiz(p, lastQuestion.answers().get(0));
        var state = players.state(p.uuid());
        assertEquals(Rank.STAGIAR.level(), state.rank);
        assertEquals(0, state.kitClaimedRank, "undeliverable kit stays claimable");
        assertTrue(p.told("Kitul nu încape"));
        // free slots -> /straja kit delivers it
        for (int i = 0; i < p.inventory.slots(); i++) p.inventory.extract(i, 64);
        guards.kit(p);
        assertEquals(Rank.STAGIAR.level(), players.state(p.uuid()).kitClaimedRank);
        assertEquals(1, countItem(p, "minecraft:iron_sword"));
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
    void startDutyBeginsPatrolWithoutIssuingGear() {
        placeCheckpoints();
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        int swordsBefore = countItem(p, "minecraft:iron_sword");
        guards.startDuty(p);
        var state = players.state(p.uuid());
        assertTrue(state.duty);
        assertEquals("ACTIVE", state.patrolState);
        assertEquals(swordsBefore, countItem(p, "minecraft:iron_sword"),
                "duty start issues nothing — the rank kit is already owned");
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
        long bonus = ctx.policies().promotionBonusHours * ctx.policies().salaryPerHour(1);
        assertEquals(bonus + 2, state.unpaidSalary); // 600s x 16/3600 = 2.67 -> 2 coins, fraction carried
        assertEquals(1, state.serviceBlocks); // 10 minutes = 1 service point
        assertEquals(ctx.policies().requisitionPointsPerBlock, state.requisitionPoints,
                "each service block also accrues spendable requisition points");
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
        long expected = ctx.policies().promotionBonusHours * ctx.policies().salaryPerHour(1) + 2;
        assertEquals(expected, currency.balance); // rank-up bonus + 10min at Stagiar 16/h
        // replay: the same payout must not double-deliver
        guards.salary(p);
        assertEquals(expected, currency.balance);
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
    void salaryPaysTheFullBalance() {
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        state.unpaidSalary = 100;
        players.save(p.uuid(), state);
        guards.salary(p);
        state = players.state(p.uuid());
        assertEquals(0, state.unpaidSalary);
        assertEquals(100, ((Fakes.TestCurrency) ctx.currency()).balance,
                "no equipment debt skims the payout — the whole balance is paid");
    }

    // ---------------------------------------------------------------- food/kit

    @Test
    void foodRequiresActiveDuty() {
        TestPlayer p = recruitToStagiar();
        guards.food(p);
        assertTrue(p.told("numai în timpul unei ture active"));
    }

    @Test
    void kitIsGrantedAtRankUpAndClaimsOncePerRank() {
        TestPlayer p = recruitToStagiar();
        // the Stagiar kit (6 items incl. the sword) was delivered at quiz pass
        assertEquals(6, p.inventory.slots.stream().filter(s -> !s.isEmpty()).count());
        assertEquals(1, countItem(p, "minecraft:iron_sword"));
        var state = players.state(p.uuid());
        assertEquals(Rank.STAGIAR.level(), state.kitClaimedRank);
        long bonus = ctx.policies().promotionBonusHours * ctx.policies().salaryPerHour(1);
        assertEquals(bonus, state.unpaidSalary, "rank-up pays the configured salary bonus");
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
        assertTrue(view.canClaimSalary(), "the rank-up bonus is already in the unpaid balance");
        assertTrue(view.canViewCoins());
        assertFalse(view.canClaimFood(), "food requires an active duty");
        assertFalse(view.canClaimKit(), "the rank kit was already granted at quiz pass");
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
        assertFalse(view.canClaimKit(), "the rank kit was already granted at quiz pass");
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
        assertTrue(p.told("puncte de patrulare"));
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
        guards.stopDuty(p);
        assertTrue(audited("duty_stop"));
        guards.meritDock(commissioner(), p, 1);
        assertTrue(audited("merit_dock"));
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
        assertTrue(junior.told("puncte de patrulare"));
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
        long bonus = ctx.policies().promotionBonusHours * ctx.policies().salaryPerHour(1);
        assertEquals(ctx.policies().salaryPerHour(Rank.SERGENT.level()) + bonus,
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
        assertTrue(countItem(senior, "minecraft:iron_sword") > 0 || countItem(senior, "minecraft:diamond_sword") > 0,
                "owned gear stays after a free shift ends");
    }

    @Test
    void freeDutyIsNotCappedByThePatrolTimer() {
        TestPlayer senior = guardAtRank(Rank.SERGENT.level());
        guards.startDuty(senior);
        var state = players.state(senior.uuid());
        state.dutyStartedAt =
                clock.nowMillis() - (ctx.policies().patrolMaxMinutes + 1L) * 60_000L;
        players.save(senior.uuid(), state);
        guards.tickPlayerDuty(senior);
        assertTrue(players.state(senior.uuid()).duty,
                "the real-time cap applies to patrols, not free shifts");
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
        assertEquals(Rank.GUARD.level(), state.kitClaimedRank,
                "the new rank kit is granted automatically at promotion");
        long bonus = ctx.policies().promotionBonusHours * ctx.policies().salaryPerHour(2);
        assertTrue(state.unpaidSalary >= bonus, "rank-up credits the salary bonus");
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
        assertEquals(100, first.z);
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

    // ------------------------------------------------------------ PAT-001 variable routes

    @Test
    void checkpointAddGrowsTheRoute() {
        TestPlayer c = commissioner();
        String id = guards.addCheckpoint(c);
        assertEquals("checkpoint_5", id, "the next free slot id is allocated");
        var setup = ctx.setup().read();
        assertEquals(5, setup.checkpoints.size());
        assertFalse(setup.checkpoints.get(4).isPlaced(), "a new slot starts unplaced");

        TestPlayer random = server.add("random");
        assertNull(guards.addCheckpoint(random), "checkpoint add is commissioner-only");
        assertEquals(5, ctx.setup().read().checkpoints.size());
    }

    @Test
    void checkpointRemoveShrinksRouteAndDropsOverrides() {
        placeCheckpoints();
        TestPlayer c = commissioner();
        var setup = ctx.setup().read();
        setup.missionMinutes.put("checkpoint_2", 45);
        ctx.setup().write(setup);

        assertTrue(guards.removeCheckpoint(c, "checkpoint_2"));
        setup = ctx.setup().read();
        assertEquals(3, setup.checkpoints.size());
        assertFalse(setup.missionMinutes.containsKey("checkpoint_2"),
                "the mission-time override goes with the slot");
        assertFalse(guards.removeCheckpoint(c, "checkpoint_2"), "double-remove refuses");

        TestPlayer random = server.add("random");
        assertFalse(guards.removeCheckpoint(random, "checkpoint_1"),
                "checkpoint remove is commissioner-only");
    }

    @Test
    void variableLengthRouteStartsPatrol() {
        TestPlayer c = commissioner();
        // shrink to the configured minimum: 2 placed checkpoints
        placeCheckpoints();
        guards.removeCheckpoint(c, "checkpoint_3");
        guards.removeCheckpoint(c, "checkpoint_4");
        TestPlayer p = recruitToStagiar();
        standAt(p, 1);
        guards.startDuty(p);
        var state = players.state(p.uuid());
        assertTrue(state.duty, "a two-checkpoint route satisfies the minimum");
        assertEquals(2, state.route.size());

        // grow past the legacy four
        for (int i = 0; i < 3; i++) guards.addCheckpoint(c);
        placeAllCheckpointPositions(c);
        var all = ctx.setup().read().checkpoints;
        assertEquals(5, all.size());
        TestPlayer q = server.add("recruit2");
        assertTrue(guards.invite(c, q));
        guards.recruit(q);
        for (int i = 0; i < ctx.policies().quiz.size(); i++) {
            var qs = players.state(q.uuid());
            String qid = qs.quizOrder.get(qs.quizIndex);
            var question = ctx.policies().quiz.stream()
                    .filter(x -> x.id().equals(qid)).findFirst().orElseThrow();
            guards.quiz(q, question.answers().get(0));
        }
        standAt(q, 1);
        guards.startDuty(q);
        assertEquals(5, players.state(q.uuid()).route.size(),
                "routes longer than the legacy four start normally");
    }

    private void placeAllCheckpointPositions(TestPlayer c) {
        SetupData setup = ctx.setup().read();
        for (int i = 0; i < setup.checkpoints.size(); i++) {
            var cp = setup.checkpoints.get(i);
            cp.x = (double) (i + 1);
            cp.y = 64d;
            cp.z = 0d;
        }
        ctx.setup().write(setup);
    }

    @Test
    void definePatrolRouteReplacesTheWholeRoute() {
        TestPlayer c = commissioner();
        assertTrue(guards.definePatrolRoute(c, "minecraft:overworld",
                java.util.List.of(new double[]{1, 64, 1}, new double[]{5, 64, 5},
                        new double[]{9, 64, 9})));
        var setup = ctx.setup().read();
        assertEquals(3, setup.checkpoints.size());
        assertEquals("checkpoint_1", setup.checkpoints.get(0).id);
        assertEquals(9, setup.checkpoints.get(2).x.intValue());

        assertFalse(guards.definePatrolRoute(c, "minecraft:overworld",
                java.util.List.of(new double[]{1, 64, 1})),
                "below the configured minimum refuses");
        assertFalse(guards.definePatrolRoute(server.add("random"), "minecraft:overworld",
                java.util.List.of(new double[]{1, 64, 1}, new double[]{2, 64, 2})),
                "route definition is commissioner-only");
    }

    // ------------------------------------------------------------ EQ-006 punishment docking

    @Test
    void demotionDocksServiceBlocks() {
        TestPlayer c = commissioner();
        TestPlayer p = guardAtRank(Rank.GUARD.level());
        setBlocks(p, 100);
        guards.demote(c, p);
        var state = players.state(p.uuid());
        assertEquals(Rank.STAGIAR.level(), state.rank);
        assertEquals(100 - ctx.policies().demotionServiceBlockCost, state.serviceBlocks,
                "demotion docks promotion progress, not spendable points");
    }

    @Test
    void suspensionDocksRequisitionPoints() {
        TestPlayer c = commissioner();
        TestPlayer p = guardAtRank(Rank.GUARD.level());
        var state = players.state(p.uuid());
        state.requisitionPoints = 50;
        state.serviceBlocks = 42;
        players.save(p.uuid(), state);
        guards.suspend(c, p);
        state = players.state(p.uuid());
        assertEquals(50 - ctx.policies().suspensionRequisitionCost, state.requisitionPoints);
        assertEquals(42, state.serviceBlocks,
                "suspension docks the spendable balance, not promotion progress");
    }

    @Test
    void meritDockClampsAtZeroAndAudits() {
        TestPlayer c = commissioner();
        TestPlayer p = recruitToStagiar();
        var state = players.state(p.uuid());
        state.requisitionPoints = 3;
        players.save(p.uuid(), state);

        assertTrue(guards.meritDock(c, p, 10));
        assertEquals(0, players.state(p.uuid()).requisitionPoints, "docking clamps at zero");
        assertTrue(audited("merit_dock"));

        TestPlayer random = server.add("random");
        state = players.state(p.uuid());
        state.requisitionPoints = 5;
        players.save(p.uuid(), state);
        assertFalse(guards.meritDock(random, p, 5), "merit dock is commissioner-only");
        assertEquals(5, players.state(p.uuid()).requisitionPoints);
        assertFalse(guards.meritDock(c, p, 0), "non-positive amounts refuse");
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
