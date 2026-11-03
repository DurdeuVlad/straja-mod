package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditEntry;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * COR-1 — corupția se plătește: a taken bribe lands on the money trail,
 * a witnessed exchange marks on the spot, and a citizen denunț checks
 * the trail — evidence writes the warrant, never the accusation.
 */
class CorruptionServiceTest {
    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private BoloService bolos;
    private CorruptionService corruptions;
    private FixedClock clock;
    private TestPlayer reporter;
    private TestPlayer reporter2;
    private TestPlayer guard;
    private TestPlayer prisoner;
    private TestPlayer civilian;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        bolos = new BoloService(ctx, players, audit);
        corruptions = new CorruptionService(ctx, audit, players, bolos);
        reporter = server.add("cetatean");
        reporter2 = server.add("altul");
        prisoner = server.add("detinut");
        civilian = server.add("civil");
        guard = server.add("strajerul");
        var s = players.state(guard);
        s.rank = Rank.GUARD.level();
        s.duty = true;
        players.save(guard.uuid(), s);
    }

    private boolean guardMarked() {
        return ctx.bolos().read().records.stream()
                .anyMatch(r -> r.status == BoloStatus.ACTIVE
                        && guard.uuid().toString().equals(r.subjectUuid));
    }

    // -------------------------------------------------------- money trail

    @Test
    void witnessedBribeMarksOnTheSpot() {
        corruptions.recordBribe(guard, prisoner, 120, true);
        assertTrue(guardMarked(), "a watched exchange is conviction-ready");
        var trail = corruptions.trailFor(guard.uuid().toString());
        assertEquals(1, trail.size());
        assertEquals(120, trail.get(0).amount());
        assertTrue(trail.get(0).witnessed());
    }

    @Test
    void unwitnessedBribeOnlyRecordsTheTrail() {
        corruptions.recordBribe(guard, prisoner, 120, false);
        assertFalse(guardMarked(), "unseen money leaves evidence, not a warrant");
        assertEquals(1, corruptions.trailFor(guard.uuid().toString()).size());
    }

    @Test
    void legacyCorruptGuardAuditCountsAsTrail() {
        var entry = new AuditEntry();
        entry.action = AuditActions.PRISON_BREAK;
        entry.actor = prisoner.name();
        entry.actorUuid = prisoner.uuid().toString();
        entry.target = guard.name();
        entry.targetUuid = guard.uuid().toString();
        entry.result = "SUCCESS";
        entry.details = "bribe_accepted corrupt_guard price=80";
        entry.at = clock.nowMillis();
        ctx.audit().append(entry);
        var trail = corruptions.trailFor(guard.uuid().toString());
        assertEquals(1, trail.size(), "the PRISON_BREAK corrupt_guard record is evidence");
        assertEquals(80, trail.get(0).amount());
        assertTrue(corruptions.denunt(reporter, guard.name()));
        assertTrue(guardMarked());
    }

    @Test
    void oneBribeCountsOnceAcrossBothRecordForms() {
        // The accept path writes kind=bribe AND the legacy corrupt_guard
        // record in the same tick — the trail must count one bribe once.
        corruptions.recordBribe(guard, prisoner, 64, false);
        var entry = new AuditEntry();
        entry.action = AuditActions.PRISON_BREAK;
        entry.actor = prisoner.name();
        entry.actorUuid = prisoner.uuid().toString();
        entry.target = guard.name();
        entry.targetUuid = guard.uuid().toString();
        entry.result = "SUCCESS";
        entry.details = "bribe_accepted corrupt_guard price=64";
        entry.at = clock.nowMillis();
        ctx.audit().append(entry);
        assertEquals(1, corruptions.trailFor(guard.uuid().toString()).size(),
                "one bribe = one evidence row, not two");
    }

    // ------------------------------------------------------------- denunț

    @Test
    void denuntFindsTrailAndMarks() {
        corruptions.recordBribe(guard, prisoner, 90, false);
        assertTrue(corruptions.denunt(reporter, guard.name()));
        assertTrue(guardMarked(), "report + money trail = warrant");
        assertTrue(reporter.messages.stream().anyMatch(m -> m.contains("Denunț confirmat")));
    }

    @Test
    void offlineGuardByUuidStillAnswerable() {
        // The corrupt guard logged off — the uuid argument resolves anyway.
        var ghost = java.util.UUID.randomUUID();
        var entry = new AuditEntry();
        entry.action = AuditActions.CORRUPTION;
        entry.actor = prisoner.name();
        entry.actorUuid = prisoner.uuid().toString();
        entry.target = "fugarul";
        entry.targetUuid = ghost.toString();
        entry.result = "SUCCESS";
        entry.details = "kind=bribe amount=70 witnessed=false";
        entry.at = clock.nowMillis();
        ctx.audit().append(entry);
        assertTrue(corruptions.denunt(reporter, ghost.toString()));
        assertTrue(ctx.bolos().read().records.stream()
                        .anyMatch(r -> r.status == BoloStatus.ACTIVE
                                && ghost.toString().equals(r.subjectUuid)),
                "an offline corrupt guard gets the warrant too");
    }

    @Test
    void offlineGuardByTrailNameResolves() {
        var ghost = java.util.UUID.randomUUID();
        var entry = new AuditEntry();
        entry.action = AuditActions.CORRUPTION;
        entry.actor = prisoner.name();
        entry.actorUuid = prisoner.uuid().toString();
        entry.target = "Fugarul";
        entry.targetUuid = ghost.toString();
        entry.result = "SUCCESS";
        entry.details = "kind=bribe amount=70 witnessed=false";
        entry.at = clock.nowMillis();
        ctx.audit().append(entry);
        assertTrue(corruptions.denunt(reporter, "fugarul"),
                "the name on the money trail resolves the offline guard");
        assertTrue(ctx.bolos().read().records.stream()
                .anyMatch(r -> ghost.toString().equals(r.subjectUuid)));
    }

    @Test
    void unknownNameRefuses() {
        assertFalse(corruptions.denunt(reporter, "nimeni"),
                "a name nobody knows resolves nothing");
    }

    @Test
    void denuntCleanGuardFilesUnproven() {
        assertTrue(corruptions.denunt(reporter, guard.name()));
        assertFalse(guardMarked(), "accusation alone convicts nothing");
        assertTrue(reporter.lastMessage().contains("dosarul lui"));
    }

    @Test
    void denuntRecordsNeverBecomeTrail() {
        // The first unproven report writes kind=denunt to the ledger — a
        // second reporter must not read it back as bribe evidence.
        assertTrue(corruptions.denunt(reporter, guard.name()));
        assertTrue(corruptions.denunt(reporter2, guard.name()));
        assertFalse(guardMarked());
        assertTrue(corruptions.trailFor(guard.uuid().toString()).isEmpty());
    }

    @Test
    void denuntCivilianWithNoTrailRefuses() {
        assertFalse(corruptions.denunt(reporter, civilian.name()));
        assertTrue(reporter.lastMessage().contains("nu e angajată"));
    }

    @Test
    void selfDenuntRefuses() {
        assertFalse(corruptions.denunt(guard, guard.name()));
    }

    @Test
    void reporterCooldownBlocksSpam() {
        assertTrue(corruptions.denunt(reporter, guard.name()));
        assertFalse(corruptions.denunt(reporter, guard.name()), "immediate re-report is blocked");
        clock.advance(ctx.policies().corruptionReportCooldownSeconds * 1000L + 1);
        assertTrue(corruptions.denunt(reporter, guard.name()), "cooldown expiry restores the right");
    }

    @Test
    void policyOffRefusesAndSuppressesWitnessedMark() {
        ctx.policies().corruptionEnabled = false;
        corruptions.recordBribe(guard, prisoner, 90, true);
        assertFalse(guardMarked(), "disabled policy suppresses the witnessed warrant");
        assertFalse(corruptions.denunt(reporter, guard.name()));
    }
}
