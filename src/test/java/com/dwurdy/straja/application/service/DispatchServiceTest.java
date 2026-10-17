package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.DispatchCall;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FUN-6: offense events route to on-duty members as bounded, expiring calls. */
class DispatchServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private IncidentService incidents;
    private DispatchService dispatch;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock, Fakes.policies());
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        incidents = new IncidentService(ctx, players, audit);
        dispatch = new DispatchService(ctx, players, audit);
        dispatch.useIncidents(incidents);
        incidents.useDispatch(dispatch);
    }

    private TestPlayer onDuty(String name, Rank rank) {
        TestPlayer player = server.add(name);
        var state = players.state(player);
        state.rank = rank.level();
        state.duty = true;
        state.invited = true;
        players.save(player.uuid(), state);
        return player;
    }

    private DispatchCall raiseUrgent() {
        return dispatch.raise(DispatchService.SOURCE_OFFENSE, "I-1", "Tălhărie", "detalii",
                "minecraft:overworld", 10, 64, 10, "Piața", "u1", "Suspect",
                "reporter-1", "URGENT", "ENLISTED");
    }

    @Test
    void raiseCreatesOneLiveCallPerSource() {
        var first = raiseUrgent();
        var again = raiseUrgent();
        assertEquals(first.id, again.id, "the same offense never stacks calls");
        assertEquals(1, ctx.dispatch().read().calls.size());
    }

    @Test
    void urgentCallPushesToOnDutyEnlistedNearestFirst() {
        TestPlayer near = onDuty("near", Rank.STAGIAR);
        near.x = 12; near.z = 12; near.dimension = "minecraft:overworld";
        TestPlayer far = onDuty("far", Rank.GUARD);
        far.x = 500; far.z = 500; far.dimension = "minecraft:overworld";
        TestPlayer officer = onDuty("lt", Rank.LOCOTENENT);
        officer.x = 11; officer.z = 11; officer.dimension = "minecraft:overworld";
        TestPlayer offDuty = server.add("civ");

        ctx.policies().dispatchMaxPushTargets = 2;
        var call = raiseUrgent();
        assertEquals(2, call.offeredTo.size(), "bounded responder set");
        assertTrue(call.offeredTo.contains(near.uuid().toString()));
        assertTrue(call.offeredTo.contains(far.uuid().toString()));
        assertFalse(call.offeredTo.contains(officer.uuid().toString()),
                "ENLISTED band excludes officers before widening");
        assertFalse(call.offeredTo.contains(offDuty.uuid().toString()),
                "offline/off-duty members are never offered");
    }

    @Test
    void routineCallQueuesOnTheBoardWithoutPushing() {
        onDuty("guard", Rank.GUARD);
        var call = dispatch.raise(DispatchService.SOURCE_OFFENSE, "I-2", "Certă", "x",
                "minecraft:overworld", 0, 64, 0, "", "", "", "", "ROUTINE", "ENLISTED");
        assertTrue(call.offeredTo.isEmpty(), "quiet severity stays on the board");
        assertEquals(1, dispatch.board().size());
        ctx.policies().dispatchPushMinSeverity = "ROUTINE";
        var pushed = dispatch.raise(DispatchService.SOURCE_OFFENSE, "I-3", "Alarma", "x",
                "minecraft:overworld", 0, 64, 0, "", "", "", "", "ROUTINE", "ENLISTED");
        assertFalse(pushed.offeredTo.isEmpty(), "threshold lowered — ROUTINE pushes");
    }

    @Test
    void callNeverPushesAcrossDimensions() {
        TestPlayer wrong = onDuty("nether", Rank.GUARD);
        wrong.dimension = "minecraft:the_nether";
        raiseUrgent();
        var call = dispatch.board().get(0);
        assertFalse(call.offeredTo.contains(wrong.uuid().toString()));
    }

    @Test
    void acceptRecordsResponderAndGrantsCredit() {
        TestPlayer guard = onDuty("guard", Rank.GUARD);
        guard.dimension = "minecraft:overworld";
        var call = raiseUrgent();
        assertTrue(dispatch.accept(guard, call.id));
        var stored = ctx.dispatch().read().find(call.id);
        assertEquals("ACCEPTED", stored.status);
        assertEquals(guard.uuid().toString(), stored.acceptedByUuid);
        assertEquals(clock.nowMillis() + ctx.policies().dutyResponseCreditMinutes * 60_000L,
                players.state(guard.uuid()).responseCreditUntil);
    }

    @Test
    void acceptClaimsTheSourceIncidentToo() {
        TestPlayer citizen = server.add("citizen");
        var incident = incidents.createCitizenReport(citizen, "furt", "Ceva a dispărut.");
        assertNotNull(incident);
        TestPlayer guard = onDuty("guard", Rank.GUARD);
        guard.dimension = incident.dimension;
        // the incident raise is ROUTINE → board; accept from the board.
        var call = dispatch.board().stream()
                .filter(c -> incident.id.equals(c.sourceId)).findFirst().orElseThrow();
        assertTrue(dispatch.accept(guard, call.id));
        var stored = ctx.incidents().read().find(incident.id);
        assertEquals(guard.uuid().toString(), stored.leadGuardUuid,
                "accepting the call claims the underlying incident");
    }

    @Test
    void officerBandRefusesEnlistedUntilWidened() {
        TestPlayer junior = onDuty("junior", Rank.STAGIAR);
        junior.dimension = "minecraft:overworld";
        var call = dispatch.raise(DispatchService.SOURCE_BOUNTY, "B-1", "Recompensă", "x",
                "minecraft:overworld", 0, 64, 0, "", "u1", "Target", "poster-1",
                "URGENT", "OFFICER");
        assertFalse(dispatch.accept(junior, call.id));
        clock.advance(ctx.policies().dispatchCallTtlMinutes * 60_000L + 1);
        dispatch.tick();
        assertEquals("ALL", ctx.dispatch().read().find(call.id).band);
        assertTrue(dispatch.accept(junior, call.id), "widened ALL band accepts anyone on duty");
    }

    @Test
    void unacceptedCallExpiresAfterTheLastBand() {
        onDuty("guard", Rank.GUARD).dimension = "minecraft:overworld";
        var call = raiseUrgent();
        clock.advance(ctx.policies().dispatchCallTtlMinutes * 60_000L + 1);
        dispatch.tick();
        assertEquals("ALL", ctx.dispatch().read().find(call.id).band);
        clock.advance(ctx.policies().dispatchCallTtlMinutes * 60_000L + 1);
        dispatch.tick();
        assertEquals("EXPIRED", ctx.dispatch().read().find(call.id).status);
    }

    @Test
    void declinedMembersAreExcludedFromEveryLaterPush() {
        TestPlayer guard = onDuty("guard", Rank.GUARD);
        guard.dimension = "minecraft:overworld";
        var call = raiseUrgent();
        assertTrue(dispatch.decline(guard, call.id));
        clock.advance(ctx.policies().dispatchCallTtlMinutes * 60_000L + 1);
        dispatch.tick(); // widened to ALL — the declined member stays out
        var stored = ctx.dispatch().read().find(call.id);
        assertTrue(stored.declined.contains(guard.uuid().toString()));
        assertFalse(stored.offeredTo.contains(guard.uuid().toString()));
    }

    @Test
    void resolvingTheSourceClosesTheCall() {
        TestPlayer citizen = server.add("citizen");
        var incident = incidents.createCitizenReport(citizen, "furt", "Raport.");
        assertNotNull(incident);
        TestPlayer guard = onDuty("guard", Rank.GUARD);
        guard.dimension = incident.dimension;
        // claim the call (which claims the incident) so the resolver is the lead
        var call = dispatch.board().stream()
                .filter(c -> incident.id.equals(c.sourceId)).findFirst().orElseThrow();
        assertTrue(dispatch.accept(guard, call.id));
        assertTrue(incidents.resolve(guard, incident.id, "RESOLVED_NO_ACTION", "verificat"));
        call = ctx.dispatch().read().calls.stream()
                .filter(c -> incident.id.equals(c.sourceId)).findFirst().orElseThrow();
        assertEquals("CLOSED", call.status);
        assertTrue(dispatch.board().isEmpty());
    }

    @Test
    void callsSurviveRestartReads() {
        raiseUrgent();
        var reread = ctx.dispatch().read();
        assertEquals(1, reread.calls.size());
        assertEquals("DC-1", reread.calls.get(0).id);
    }

    @Test
    void theCallerEarnsNoResponseCreditFromTheirOwnCall() {
        TestPlayer guard = onDuty("guard", Rank.GUARD);
        guard.dimension = "minecraft:overworld";
        var call = dispatch.raise(DispatchService.SOURCE_OFFENSE, "I-9", "Ajutor", "x",
                "minecraft:overworld", 0, 64, 0, "", "", "",
                guard.uuid().toString(), "URGENT", "ENLISTED");
        assertTrue(dispatch.accept(guard, call.id),
                "accepting your own call is cleanup, not a refusal");
        assertNull(players.state(guard.uuid()).responseCreditUntil,
                "no self-credit — FUN-5 precedent applied to dispatch");
    }

    @Test
    void theSubjectCannotAcceptTheirOwnCall() {
        TestPlayer wanted = onDuty("wanted", Rank.GUARD);
        wanted.dimension = "minecraft:overworld";
        var call = dispatch.raise(DispatchService.SOURCE_BOLO, "B-9", "BOLO", "x",
                "minecraft:overworld", 0, 64, 0, "",
                wanted.uuid().toString(), "wanted", "system", "URGENT", "ENLISTED");
        assertFalse(wanted.uuid().toString().isEmpty());
        assertFalse(dispatch.accept(wanted, call.id), "you cannot hunt yourself");
        assertTrue(call.offeredTo.isEmpty()
                        || !call.offeredTo.contains(wanted.uuid().toString()),
                "the subject is never pushed their own call");
    }

    @Test
    void acceptRefusesAcrossDimensions() {
        TestPlayer far = onDuty("far", Rank.GUARD);
        far.dimension = "minecraft:the_end";
        var call = raiseUrgent();
        assertFalse(dispatch.accept(far, call.id),
                "a call never demands a cross-dimension response");
    }

    @Test
    void anAbandonedAcceptanceReOpensTheCall() {
        TestPlayer guard = onDuty("guard", Rank.GUARD);
        guard.dimension = "minecraft:overworld";
        var call = raiseUrgent();
        assertTrue(dispatch.accept(guard, call.id));
        clock.advance(ctx.policies().dispatchCallTtlMinutes * 60_000L + 1);
        dispatch.tick();
        var stored = ctx.dispatch().read().find(call.id);
        assertEquals("OPEN", stored.status, "a quiet responder hands the call back");
        assertEquals("ALL", stored.band);
        assertEquals("", stored.acceptedByUuid);
    }

    @Test
    void terminalCallsArePrunedPastTheRetentionLimit() {
        ctx.policies().dispatchRetentionLimit = 10;
        for (int i = 0; i < 15; i++) {
            var call = dispatch.raise(DispatchService.SOURCE_OFFENSE, "I-" + i, "x", "x",
                    "minecraft:overworld", 0, 64, 0, "", "", "", "", "ROUTINE", "ALL");
            call.status = "EXPIRED";
            ctx.dispatch().write(storeKeeping(call));
        }
        dispatch.tick();
        var reread = ctx.dispatch().read();
        reread.ensureCalls();
        assertTrue(reread.calls.size() <= 10,
                "terminal records are pruned, oldest first: " + reread.calls.size());
    }

    private com.dwurdy.straja.domain.model.DispatchStore storeKeeping(DispatchCall call) {
        var data = ctx.dispatch().read();
        data.ensureCalls();
        var stored = data.find(call.id);
        if (stored != null) stored.status = call.status;
        return data;
    }
}
