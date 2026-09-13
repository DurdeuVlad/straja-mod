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

    private TestPlayer recruitToJunior() {
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
        TestPlayer p = recruitToJunior();
        var state = players.state(p.uuid());
        assertEquals(Rank.JUNIOR.level(), state.rank);
        assertTrue(state.quizPassed);
        assertTrue(p.told("Străjer Junior"));
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
        assertTrue(p.told("invitație"));
        assertEquals(0, players.state(p.uuid()).quizIndex);
    }

    @Test
    void stopDutyReclaimsAllServiceEquipment() {
        placeCheckpoints();
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
    void partialEquipmentIssueChargesNoDebtForUndeliveredItems() {
        placeCheckpoints();
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
        guards.startDuty(p);
        assertFalse(players.state(p.uuid()).duty);
        assertTrue(p.told("Checkpoint-urile nu sunt configurate"));
    }

    @Test
    void startDutyBeginsPatrolAndIssuesEquipment() {
        placeCheckpoints();
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
        standAt(p, 1);
        guards.startDuty(p);
        // move every ~30s for 20 minutes -> one paid Minecraft duty day
        for (int i = 0; i < 40; i++) {
            p.x += 1.0;
            clock.advance(30_000);
            guards.tickPlayerDuty(p);
        }
        var state = players.state(p.uuid());
        assertTrue(state.duty);
        assertEquals(28, state.unpaidSalary); // Junior: 28 bronze / 20-minute paid day
    }

    // ---------------------------------------------------------------- salary

    @Test
    void salaryPayoutIsIdempotent() {
        placeCheckpoints();
        TestPlayer p = recruitToJunior();
        standAt(p, 1);
        guards.startDuty(p);
        for (int i = 0; i < 40; i++) {
            p.x += 1.0;
            clock.advance(30_000);
            guards.tickPlayerDuty(p);
        }
        guards.salary(p);
        var currency = (Fakes.TestCurrency) ctx.currency();
        assertEquals(28, currency.balance);
        // replay: the same payout must not double-deliver
        guards.salary(p);
        assertEquals(28, currency.balance);
        assertTrue(p.told("Nu ai salariu disponibil"));
    }

    @Test
    void salaryInProgressWithoutReceiptLocksForReview() {
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
        guards.food(p);
        assertTrue(p.told("numai în timpul unei ture active"));
    }

    @Test
    void kitClaimsOncePerRank() {
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
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
        TestPlayer p = recruitToJunior();
        var calls = new java.util.ArrayList<String>();
        guards.onStatusChange((target, reason) -> calls.add(target.uuid() + ":" + reason));
        guards.resign(p, null);
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        guards.resign(p, "confirm");
        assertEquals(java.util.List.of(p.uuid() + ":demisie semnată"), calls,
                "resignation must release the room and cancel missions like other status changes");
    }

    @Test
    void rejoinCapsAtSenior() {
        TestPlayer p = recruitToJunior();
        var state = players.state(p.uuid());
        state.rank = Rank.LIEUTENANT.level();
        players.save(p.uuid(), state);
        guards.resign(p, null);
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        guards.resign(p, "confirm");
        clock.advance(ctx.policies().resignationCooldownDays * 24L * 3600_000L + 1);
        guards.rejoin(p);
        state = players.state(p.uuid());
        assertEquals(Rank.SENIOR.level(), state.rank);
        assertFalse(state.resigned);
    }

    // ---------------------------------------------------------------- admin

    @Test
    void promoteRequiresServiceBlocks() {
        TestPlayer c = commissioner();
        TestPlayer p = recruitToJunior();
        guards.promote(c, p); // 60 blocks required for GUARD
        assertEquals(Rank.JUNIOR.level(), players.state(p.uuid()).rank);
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
        TestPlayer p = recruitToJunior();
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
        state.rank = Rank.LIEUTENANT.level();
        players.save(lt.uuid(), state);
        assertEquals(PermissionLevel.LIEUTENANT, players.permissionLevel(lt, players.state(lt.uuid())));
        assertFalse(players.isCommissioner(lt));
    }

    private int countItem(TestPlayer p, String itemId) {
        return p.inventory.countOf(itemId);
    }
}
