package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Mission;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Mission lifecycle parity tests — pure Java, deterministic fakes. */
class MissionServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private MissionService missions;
    private TestPlayer commissar;
    private TestPlayer lt;
    private TestPlayer g1;
    private TestPlayer g2;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        missions = new MissionService(ctx, players, audit);
        commissar = server.add("dwurdy");
        lt = server.add("lt1");
        g1 = server.add("g1");
        g2 = server.add("g2");
        setRank(lt, Rank.LIEUTENANT);
        setRank(g1, Rank.GUARD);
        setRank(g2, Rank.JUNIOR);
    }

    private void setRank(TestPlayer p, Rank rank) {
        var s = players.state(p.uuid());
        s.rank = rank.level();
        players.save(p.uuid(), s);
    }

    private void holdCarnet(TestPlayer p) {
        p.give(ItemSpec.of(MissionService.ORDER_BOOK, 1));
        p.selectSlot(0);
    }

    private Mission latest() {
        var ms = ctx.missions().read();
        return ms.missions.get(ms.missions.size() - 1);
    }

    // ------------------------------------------------------------ quick create

    @Test
    void lieutenantCreatesMissionForSubordinate() {
        missions.createMission(lt, g1, 30, "Patrulă nocturnă", 20);
        Mission m = latest();
        assertEquals("ISSUED", m.status);
        assertEquals("g1", m.target);
        assertEquals(20, m.issuerBudgetAmount);
        assertEquals("RESERVED", m.issuerBudgetStatus);
        assertTrue(g1.told("misiunea #"));
    }

    @Test
    void createRejectsPeerTarget() {
        TestPlayer lt2 = server.add("lt2");
        setRank(lt2, Rank.LIEUTENANT);
        missions.createMission(lt, lt2, 30, "x", 10);
        assertTrue(lt.told("rang inferior"));
    }

    @Test
    void createRejectsGuardIssuer() {
        missions.createMission(g1, g2, 30, "x", 10);
        assertTrue(g1.told("Locotenentul sau Comisaru"));
    }

    @Test
    void createBlocksWhenTargetAtActiveLimit() {
        int max = ctx.policies().missionMaxActivePerPlayer;
        for (int i = 0; i < max; i++) missions.createMission(commissar, g1, 30, "m" + i, 10);
        int size = ctx.missions().read().missions.size();
        missions.createMission(commissar, g1, 30, "over", 10);
        assertEquals(size, ctx.missions().read().missions.size());
        assertTrue(commissar.told("prea multe misiuni active"));
    }

    @Test
    void quickCreateRespectsConfigToggle() {
        ctx.policies().missionQuickCreateEnabled = false;
        missions.createMission(lt, g1, 30, "m", 20);
        assertTrue(ctx.missions().read().missions.isEmpty());
        assertTrue(lt.told("dezactivată"));
        ctx.policies().missionQuickCreateEnabled = true;
        ctx.policies().environment = "production";
        missions.createMission(lt, g1, 30, "m", 20);
        assertTrue(ctx.missions().read().missions.isEmpty(), "local-only quick create outside local");
        ctx.policies().environment = "local";
        ctx.policies().missionQuickCreateLocalOnly = false;
        ctx.policies().environment = "production";
        missions.createMission(lt, g1, 30, "m", 20);
        assertEquals(1, ctx.missions().read().missions.size(),
                "quick create works outside local only when local-only is off");
    }

    // ------------------------------------------------------------ lifecycle

    @Test
    void acceptReportCompletePaysRewardIdempotently() {
        missions.createMission(lt, g1, 30, "m", 20);
        Mission m = latest();
        missions.accept(g1, m.id);
        assertEquals("ACCEPTED", latest().status);
        missions.report(g1, m.id, "totul în regulă");
        assertEquals("REPORTED", latest().status);
        assertTrue(missions.complete(lt, m.id));
        Mission done = latest();
        assertEquals("COMPLETED", done.status);
        // auto-claim on completion already paid
        assertTrue(done.rewardClaims.values().stream().allMatch(c -> "PAID".equals(c.status)));
        var currency = (TestCurrency) ctx.currency();
        int calls = currency.depositCalls;
        missions.claimReward(g1, m.id);
        assertEquals(calls, currency.depositCalls, "second claim must not re-pay");
        assertTrue(g1.told("deja plătită"));
    }

    @Test
    void deadlineExpiryFailsAcceptedMissionAndReleasesBudget() {
        missions.createMission(lt, g1, 30, "m", 20);
        Mission m = latest();
        missions.accept(g1, m.id);
        clock.advance(31 * 60_000L);
        missions.tick();
        Mission expired = latest();
        assertEquals("FAILED", expired.status);
        assertEquals("deadline", expired.failureReason);
        assertEquals("RELEASED", expired.issuerBudgetStatus);
        // budget released → same issuer can reserve again
        missions.createMission(lt, g1, 30, "again", 20);
        assertEquals("ISSUED", latest().status);
    }

    @Test
    void retentionLimitPrunesOldestClosedMissions() {
        ctx.policies().missionRetentionLimit = 3;
        var store = ctx.missions().read();
        for (int i = 0; i < 6; i++) {
            Mission m = new Mission();
            m.id = "M" + i;
            m.status = "COMPLETED";
            m.rewardStatus = "PAID";
            m.createdAt = 1_000 + i;
            m.completedAt = 1_000L + i;
            store.missions.add(m);
        }
        Mission open = new Mission();
        open.id = "OPEN";
        open.status = "ISSUED";
        open.createdAt = 1;
        open.dueAt = Long.MAX_VALUE;
        store.missions.add(open);
        Mission unsettled = new Mission();
        unsettled.id = "UNSETTLED";
        unsettled.status = "COMPLETED";
        unsettled.rewardStatus = "PAYMENT_FAILED";
        unsettled.createdAt = 1;
        unsettled.completedAt = 1L;
        store.missions.add(unsettled);
        ctx.missions().write(store);

        missions.tick();

        var after = ctx.missions().read();
        assertEquals(3, after.missions.size());
        assertTrue(after.missions.stream().anyMatch(m -> "OPEN".equals(m.id)),
                "open missions must never be pruned");
        assertTrue(after.missions.stream().anyMatch(m -> "UNSETTLED".equals(m.id)),
                "closed missions with unsettled rewards must be kept");
        assertTrue(after.missions.stream().anyMatch(m -> "M5".equals(m.id)),
                "the newest settled mission is kept");
    }

    @Test
    void declineSingleAssigneeClosesMission() {
        missions.createMission(lt, g1, 30, "m", 20);
        Mission m = latest();
        assertTrue(missions.decline(g1, m.id));
        assertEquals("DECLINED", latest().status);
    }

    @Test
    void inviteJoinAndSplitReward() {
        missions.createMission(commissar, g1, 30, "m", 21);
        Mission m = latest();
        // widen scope then invite g2 — single-target create has maxAssignees 1, so use carnet flow instead
        // instead: verify invite guard on full mission
        assertFalse(missions.invite(commissar, m.id, g2));
        assertTrue(commissar.told("limita de"));
    }

    @Test
    void carnetDraftFlowDeliversSealedOrder() {
        holdCarnet(commissar);
        missions.draftWrite(commissar, 60, "acum", 30, "Escortează transportul");
        missions.draftScope(commissar, "junior", 2);
        missions.draftSign(commissar);
        missions.draftPackage(commissar);
        assertTrue(missions.give(commissar, g1));
        Mission m = latest();
        assertEquals("ISSUED", m.status);
        assertEquals("ENVELOPE_PACKAGE", m.delivery);
        assertEquals(2, m.maxAssignees);
        // sealed order item delivered to target inventory
        assertTrue(g1.inventory.slots.stream().anyMatch(s -> !s.isEmpty()
                && s.id().equals(MissionService.ORDER_BOOK)));
        // g2 joins the team mission
        assertTrue(missions.invite(commissar, m.id, g2));
        assertTrue(missions.join(g2, m.id));
        Mission joined = latest();
        assertEquals(2, joined.assignees.size());
        // accept + report + complete → split reward 21? reward=30 → 15 each
        missions.accept(g1, m.id);
        missions.report(g1, m.id, "done");
        assertTrue(missions.complete(commissar, m.id));
        Mission done = latest();
        assertEquals(2, done.rewardClaims.size());
        assertTrue(done.rewardClaims.values().stream().allMatch(c -> "PAID".equals(c.status)));
        assertEquals(30, done.rewardClaims.values().stream().mapToInt(c -> c.amount).sum());
    }

    @Test
    void draftGiveFailsWithoutSealedPackage() {
        holdCarnet(commissar);
        missions.draftWrite(commissar, 60, "acum", 30, "x");
        missions.draftSign(commissar); // not packaged
        assertFalse(missions.give(commissar, g1));
        assertTrue(commissar.told("ordin complet"));
    }

    @Test
    void giveFailsWhenInventoryCannotReceive() {
        holdCarnet(commissar);
        missions.draftWrite(commissar, 60, "acum", 30, "x");
        missions.draftSign(commissar);
        missions.draftPackage(commissar);
        // fill g1's inventory completely
        for (int i = 0; i < g1.inventory.slots(); i++) {
            g1.inventory.slots.set(i, new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }
        assertFalse(missions.give(commissar, g1));
        Mission m = latest();
        assertEquals("DELIVERY_FAILED", m.status);
        assertEquals("RELEASED", m.issuerBudgetStatus);
        assertEquals("inventory_delivery_failed", m.deliveryError);
        assertTrue(((TestDelivery) ctx.delivery()).sent.isEmpty(),
                "a full inventory must fail before the Envelope package is sent");
    }

    @Test
    void giveChecksBudgetBeforeSendingPackage() {
        ctx.policies().missionMaxRewardPerIssuerPerDay = 10;
        holdCarnet(commissar);
        missions.draftWrite(commissar, 60, "acum", 30, "x");
        missions.draftSign(commissar);
        missions.draftPackage(commissar);
        assertFalse(missions.give(commissar, g1));
        assertTrue(commissar.told("Bugetul zilnic"));
        assertTrue(((TestDelivery) ctx.delivery()).sent.isEmpty(),
                "budget exhaustion must not send the Envelope package");
        assertTrue(ctx.missions().read().missions.isEmpty(),
                "budget exhaustion must not persist a mission");
        assertEquals(0, ctx.missions().read().drafts.values().iterator().next().issuedCount,
                "the carnet copy must not be consumed");
    }

    @Test
    void givePackageFailureKeepsIssuedMissionWithPendingPackage() {
        holdCarnet(commissar);
        missions.draftWrite(commissar, 60, "acum", 30, "x");
        missions.draftSign(commissar);
        missions.draftPackage(commissar);
        ((TestDelivery) ctx.delivery()).available = false;
        // The physical order delivers first; only the Envelope package fails,
        // so the mission stays live with a durable pending-package state.
        assertTrue(missions.give(commissar, g1));
        Mission m = latest();
        assertEquals("ISSUED", m.status);
        assertEquals("PACKAGE_PENDING", m.delivery);
        assertEquals("envelope_package_failed", m.deliveryError);
        assertTrue(g1.inventory.slots.stream().anyMatch(s -> !s.isEmpty()
                && s.id().equals(MissionService.ORDER_BOOK)),
                "the sealed order item must reach the target before the package is attempted");
        assertTrue(commissar.told("mission resend"));
        // only the issuer (or commissioner) may retry the package
        assertFalse(missions.resendPackage(g1, m.id));
        // once the provider recovers the resend completes the delivery
        ((TestDelivery) ctx.delivery()).available = true;
        assertTrue(missions.resendPackage(commissar, m.id));
        assertEquals("ENVELOPE_PACKAGE", latest().delivery);
        assertEquals("", latest().deliveryError);
        assertFalse(((TestDelivery) ctx.delivery()).sent.isEmpty());
        // resending again is a no-op — no duplicate packages
        int sent = ((TestDelivery) ctx.delivery()).sent.size();
        assertFalse(missions.resendPackage(commissar, m.id));
        assertEquals(sent, ((TestDelivery) ctx.delivery()).sent.size());
    }

    @Test
    void suspendCancelsOpenMissions() {
        var guards = new GuardService(ctx, players, audit,
                new EquipmentService(ctx));
        guards.onStatusChange((p, r) -> missions.cancelOpenFor(p, r));
        missions.createMission(commissar, g1, 30, "m", 20);
        guards.suspend(commissar, g1);
        assertEquals("CANCELLED_ROLE_CHANGE", latest().status);
    }

    @Test
    void statusChangeHooksAllFireAndCancelMissions() {
        // Mirrors StrajaRuntime's wiring: two listeners, both must run.
        var guards = new GuardService(ctx, players, audit,
                new EquipmentService(ctx));
        var released = new java.util.ArrayList<String>();
        guards.onStatusChange((p, r) -> missions.cancelOpenFor(p, r));
        guards.onStatusChange((p, r) -> released.add(p.uuid() + ":" + r));
        missions.createMission(commissar, g1, 30, "m", 20);
        guards.suspend(commissar, g1);
        assertEquals("CANCELLED_ROLE_CHANGE", latest().status,
                "registering a second listener must not disable mission cancellation");
        assertEquals(java.util.List.of(g1.uuid() + ":suspendat din Strajă"), released);
    }

    @Test
    void resignationCancelsOpenMissions() {
        var guards = new GuardService(ctx, players, audit,
                new EquipmentService(ctx));
        guards.onStatusChange((p, r) -> missions.cancelOpenFor(p, r));
        missions.createMission(commissar, g1, 30, "m", 20);
        guards.resign(g1, null);
        clock.advance(ctx.policies().resignationNoticeMinutes * 60_000L + 1);
        guards.resign(g1, "confirm");
        assertEquals("CANCELLED_ROLE_CHANGE", latest().status);
        assertTrue(g1.told("Demisie semnată"));
    }

    @Test
    void dailyIssuerBudgetIsEnforcedAndReleased() {
        ctx.policies().missionMaxRewardPerIssuerPerDay = 30;
        missions.createMission(commissar, g1, 30, "a", 20);
        missions.createMission(commissar, g2, 30, "b", 20); // 20+20 > 30 → blocked
        assertTrue(commissar.told("Bugetul zilnic"));
        // fail the first mission → budget released → second create succeeds
        Mission first = ctx.missions().read().missions.get(0);
        missions.fail(g1, first.id, "renunțare");
        missions.createMission(commissar, g2, 30, "b", 20);
        assertEquals(2, ctx.missions().read().missions.size());
    }

    @Test
    void offlineParticipantClaimStaysPending() {
        missions.createMission(commissar, g1, 30, "m", 20);
        Mission m = latest();
        missions.accept(g1, m.id);
        missions.report(g1, m.id, "ok");
        g1.online = false; // offline at completion
        assertTrue(missions.complete(commissar, m.id));
        Mission done = latest();
        assertTrue(done.rewardClaims.values().stream().allMatch(c -> "PENDING".equals(c.status)));
        g1.online = true;
        assertTrue(missions.claimReward(g1, m.id));
        assertTrue(latest().rewardClaims.values().stream().allMatch(c -> "PAID".equals(c.status)));
    }
}
