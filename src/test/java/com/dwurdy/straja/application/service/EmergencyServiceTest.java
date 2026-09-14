package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmergencyServiceTest {
    private TestServer server;
    private StrajaPolicies policies;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private EmergencyService emergency;
    private GuardService guards;
    private TestPlayer comisar;
    private TestPlayer member;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        policies = Fakes.policies();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock, policies);
        players = new PlayerService(ctx);
        emergency = new EmergencyService(ctx, players, new AuditService(ctx));
        guards = new GuardService(ctx, players, new AuditService(ctx), new EquipmentService(ctx));
        comisar = server.add("dwurdy");
        member = server.add("guard1");
        var st = ctx.players().read(member.uuid());
        st.rank = 1;
        ctx.players().write(member.uuid(), st);
    }

    private void setRank(TestPlayer player, int rank) {
        var st = ctx.players().read(player.uuid());
        st.rank = rank;
        ctx.players().write(player.uuid(), st);
    }

    @Test
    void alertBroadcastsToEveryOnlineMemberOnAndOffDuty() {
        TestPlayer offDuty = server.add("guard2");
        setRank(offDuty, 3);
        TestPlayer civilian = server.add("civil");

        emergency.alert(comisar, "corupție la nord");

        assertTrue(member.told("URGENȚĂ"), "on-duty-rank member must be told");
        assertTrue(offDuty.told("URGENȚĂ"), "off-duty member must be told");
        assertTrue(comisar.told("URGENȚĂ"), "the Comisar is a member too");
        assertFalse(civilian.told("URGENȚĂ"), "civilians must not receive the call");
        assertEquals("corupție la nord", ctx.emergency().read().urgencyMessage);
    }

    @Test
    void alertIsRefusedForNonComisarNonOp() {
        emergency.alert(member, "fake alarm");

        assertTrue(member.told("Doar Comisarul"));
        assertNull(ctx.emergency().read().urgencyMessage);
    }

    @Test
    void lateJoiningMemberReceivesLiveUrgencyOnLogin() {
        emergency.alert(comisar, "toți la sediu");
        TestPlayer late = server.add("late1");
        setRank(late, 1);

        emergency.deliverUrgency(late);

        assertTrue(late.told("toți la sediu"));
    }

    @Test
    void urgencyLapsesSilentlyAfterTtl() {
        emergency.alert(comisar, "raid");
        clock.advance((policies.emergencyUrgencyTtlMinutes + 1) * 60_000L);
        TestPlayer late = server.add("late2");
        setRank(late, 1);

        emergency.deliverUrgency(late);

        assertFalse(late.told("raid"), "expired urgency must not be delivered");
    }

    @Test
    void clearDismissesUrgencyEarly() {
        emergency.alert(comisar, "raid");
        emergency.clearUrgency(comisar);

        assertFalse(ctx.emergency().read().urgencyLive(clock.nowMillis(),
                policies.emergencyUrgencyTtlMinutes));
        assertTrue(member.told("ridicată"));
    }

    @Test
    void startClampsMultiplierAndRoundsToPolicyBounds() {
        emergency.start(comisar, 99.0, 50, "corupție");
        var state = ctx.emergency().read();

        assertTrue(state.active);
        assertEquals(policies.emergencyMaxPayMultiplier, state.payMultiplier);
        assertEquals(policies.emergencyMaxPatrolRounds, state.requiredRounds);
        assertEquals("corupție", state.reason);
        assertTrue(member.told("STARE DE URGENȚĂ"), "members must hear the activation");
    }

    @Test
    void endStopsEmergencyAndKeepsAccruedSalary() {
        emergency.start(comisar, 2.0, 2, "raid");
        emergency.end(comisar);

        var state = ctx.emergency().read();
        assertFalse(state.active);
        assertEquals(1.0, state.payMultiplier);
        assertEquals(0, state.requiredRounds);
        assertTrue(member.told("încheiată"));
    }

    @Test
    void hazardPayDoublesHourlyAccrual() {
        emergency.start(comisar, 2.0, 2, "raid");
        guards.startDuty(comisar); // commissioner → free duty
        assertTrue(ctx.players().read(comisar.uuid()).duty,
                "free duty must start: " + comisar.lastMessage());
        // move every 30s for 10 minutes so the anti-AFK rule stays satisfied
        for (int i = 0; i < 20; i++) {
            comisar.x += 1.0;
            clock.advance(30_000);
            guards.tickPlayerDuty(comisar);
        }
        guards.stopDuty(comisar);

        var st = ctx.players().read(comisar.uuid());
        // 600 paid seconds at 256/h (128 × 2.0) = 42.67 → 42 coins, fraction carried
        assertEquals(42, st.unpaidSalary,
                "hazard pay must double the hourly accrual — tells: " + comisar.messages);
        assertTrue(comisar.told("primă de urgență"), "the accrual tell notes hazard pay");
    }

    @Test
    void patrolShiftEndsAfterRequiredRounds() {
        emergency.start(comisar, 2.0, 1, "raid");
        // place all four checkpoints
        var setup = ctx.setup().read();
        for (int i = 1; i <= 4; i++) {
            final String id = "checkpoint_" + i;
            var cp = setup.checkpoints.stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow();
            cp.x = (double) i; cp.y = 64d; cp.z = 0d;
            setup.missionMinutes.put(id, 30);
        }
        ctx.setup().write(setup);

        guards.startDuty(member);
        var st = ctx.players().read(member.uuid());
        assertTrue(st.duty, "patrol duty must start");
        assertEquals(1, st.requiredRounds, "emergency rounds snapshot into the shift");

        for (int i = 1; i <= 4; i++) {
            member.x = i; member.y = 64; member.z = 0;
            guards.checkpoint(member, "checkpoint_" + i);
            clock.advance(11 * 60_000L); // past the 10-min unlock pause
            guards.tickPlayerDuty(member); // WAITING → ACTIVE unlock transition
        }

        st = ctx.players().read(member.uuid());
        assertFalse(st.duty, "patrol completes after the required rounds");
        assertTrue(member.told("runde complete"), "patrol_complete tell shown");
    }

    @Test
    void statusIsReadableByMembers() {
        emergency.status(member);
        assertTrue(member.told("Nicio urgență"));
        assertTrue(member.told("nu este activă"));
    }
}
