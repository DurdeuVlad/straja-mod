package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.CustodyStatus;
import com.dwurdy.straja.domain.model.CustodyTransition;
import com.dwurdy.straja.domain.model.PlayerCondition;
import com.dwurdy.straja.domain.model.StateProvider;
import com.dwurdy.straja.domain.model.TransportStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CustodyDeadlineServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext context;
    private CustodyService custody;
    private TestPlayer target;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        context = Fakes.context(server, clock);
        custody = new CustodyService(context, new PlayerService(context), new AuditService(context));
        target = server.add("target");
    }

    @Test
    void tickPersistsExactDeadlineAndRepeatedTicksDoNotKillAgain() {
        var state = state();
        state.condition = PlayerCondition.DOWNED;
        state.downedDeadlineAt = clock.now + 1_000;
        put(state);

        clock.advance(999);
        custody.tick();
        assertEquals(PlayerCondition.DOWNED, custody.canonicalState(target).condition);
        clock.advance(1);
        custody.tick();
        assertEquals(PlayerCondition.DEAD, custody.canonicalState(target).condition);
        assertEquals(0, target.health);
        int auditCount = context.audit().tail(20).stream()
                .filter(e -> "custody_deadline".equals(e.action)).toList().size();
        custody.tick();
        assertEquals(auditCount, context.audit().tail(20).stream()
                .filter(e -> "custody_deadline".equals(e.action)).toList().size());
    }

    @Test
    void reconnectReadsPersistedDeadlineWithoutResettingIt() {
        var state = state();
        state.condition = PlayerCondition.DOWNED;
        state.downedDeadlineAt = clock.now + 2_000;
        put(state);

        clock.advance(1_000);
        custody.recoverOnLogin(target);
        assertEquals(clock.now + 1_000, custody.canonicalState(target).downedDeadlineAt);
        clock.advance(1_000);
        new CustodyService(context, new PlayerService(context), new AuditService(context))
                .recoverOnLogin(target);
        assertEquals(PlayerCondition.DEAD, custody.canonicalState(target).condition);
    }

    @Test
    void logoutRecoveryCanReleaseTransportWithoutResettingPausedDownedTime() {
        context.policies().logoutRecoveryBehavior = "RELEASE_TRANSPORT";
        var state = state();
        put(state);
        assertTrue(custody.applyCanonicalTransition(target,
                CustodyTransition.of("down", CustodyTransition.Action.ENTER_DOWNED,
                        clock.now, "weapon")).ok());
        assertTrue(custody.applyCanonicalTransition(target,
                new CustodyTransition("carry", CustodyTransition.Action.START_CARRY,
                        clock.now + 1_000, "carrier", "carrier", "", StateProvider.SYSTEM,
                        "carry", 0)).ok());
        long remaining = custody.canonicalState(target).pausedDownedRemainingMs;
        clock.advance(5_000);
        custody.recoverOnLogout(target);

        var recovered = custody.canonicalState(target);
        assertEquals(TransportStatus.NONE, recovered.transport);
        assertEquals(clock.now + remaining, recovered.downedDeadlineAt);
        assertNull(recovered.pausedDownedRemainingMs);
        assertEquals(CustodyStatus.FREE, recovered.custody);
    }

    private CustodyState state() {
        var state = new CustodyState();
        state.playerId = target.uuid.toString();
        state.playerUuid = target.uuid.toString();
        state.playerName = target.name;
        return state;
    }

    private void put(CustodyState state) {
        var store = context.custody().read();
        store.states.put(target.uuid.toString(), state);
        context.custody().write(store);
    }
}
