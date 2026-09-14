package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Sentence;
import com.dwurdy.straja.domain.model.CustodyStatus;
import com.dwurdy.straja.domain.model.PlayerCondition;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Prison parity tests — cell assignment, online-active sentence time, release. */
class PrisonServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private TestPlayer boss;
    private TestPlayer inmate;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        boss = server.add("dwurdy");
        inmate = server.add("civ1");
        ((TestWorld) ctx.world()).room("minecraft:overworld", -1, 59, -1, 6, 66, 6,
                -1, 61, 2);
        assertTrue(prison.createCell(boss, "celula_1", "minecraft:overworld",
                0, 60, 0, 5, 65, 5));
    }

    @Test
    void cellRejectsOpenOrDoorlessGeometry() {
        assertFalse(prison.createCell(boss, "open_cell", "minecraft:overworld",
                20, 60, 20, 25, 65, 25));
        assertTrue(boss.told("Pereții") || boss.told("ușă"));
        assertNull(ctx.prison().read().cell("open_cell"));
    }

    @Test
    void arrestAssignsCellAndTeleports() {
        var s = prison.arrest(inmate, null, 1, boss, null);
        assertEquals("ACTIVE", s.status);
        assertEquals("celula_1", s.cellId);
        // teleported inside the cell
        assertTrue(inmate.x >= 0 && inmate.x <= 5);
        assertTrue(prison.insideCell("minecraft:overworld", inmate.x, inmate.y, inmate.z));
        var custodyState = ctx.custody().read().states.get(inmate.uuid().toString());
        assertEquals(CustodyStatus.JAILED, custodyState.custody);
        assertEquals(PlayerCondition.ALIVE, custodyState.condition);
    }

    @Test
    void secondInmateWaitsForFreeCell() {
        var other = server.add("civ2");
        prison.arrest(inmate, null, 1, boss, null);
        var s2 = prison.arrest(other, null, 1, boss, null);
        assertEquals("WAITING_CELL", s2.status);
        assertEquals("", s2.cellId);
    }

    @Test
    void sentenceTimeConsumesOnlyWhenActive() {
        prison.arrest(inmate, null, 1, boss, null);
        long initial = ctx.prison().read().sentences.get(0).remainingActiveMs;
        // move + tick → time consumed
        inmate.teleport("minecraft:overworld", 2, 61, 2);
        clock.advance(2_000);
        prison.tick();
        assertTrue(ctx.prison().read().sentences.get(0).remainingActiveMs < initial);
        // no movement for > grace → paused
        long beforeAfk = ctx.prison().read().sentences.get(0).remainingActiveMs;
        clock.advance(70_000);
        prison.tick();
        assertEquals(beforeAfk, ctx.prison().read().sentences.get(0).remainingActiveMs);
    }

    @Test
    void offlineTimeNeverCounts() {
        prison.arrest(inmate, null, 1, boss, null);
        inmate.online = false;
        long before = ctx.prison().read().sentences.get(0).remainingActiveMs;
        clock.advance(120_000);
        prison.tick();
        assertEquals(before, ctx.prison().read().sentences.get(0).remainingActiveMs);
    }

    @Test
    void escapeTeleportsBackInside() {
        prison.arrest(inmate, null, 1, boss, null);
        inmate.teleport("minecraft:overworld", 500, 64, 500);
        prison.tick();
        assertTrue(inmate.x <= 6);
    }

    @Test
    void servedSentenceReleasesToReleasePoint() {
        // configure release point
        var setup = ctx.setup().read();
        var loc = new com.dwurdy.straja.domain.model.SetupData.Location();
        loc.dimension = "minecraft:overworld";
        loc.x = 100; loc.y = 64; loc.z = 100;
        setup.locations.put("prisonRelease", loc);
        ctx.setup().write(setup);

        prison.arrest(inmate, null, 1, boss, null);
        var stored = ctx.prison().read();
        stored.sentences.get(0).remainingActiveMs = 1; // nearly served
        ctx.prison().write(stored);
        inmate.teleport("minecraft:overworld", 2, 61, 2);
        clock.advance(2_000);
        prison.tick();
        assertEquals("SERVED", ctx.prison().read().sentences.get(0).status);
        assertEquals(100.5, inmate.x);
        // cell freed → waitlist can proceed
        var other = server.add("civ2");
        var s2 = prison.arrest(other, null, 1, boss, null);
        assertEquals("celula_1", s2.cellId);
    }

    @Test
    void forcedReleaseFreesCell() {
        prison.arrest(inmate, null, 1, boss, null);
        assertTrue(prison.release(boss, inmate, "test"));
        assertEquals("FORCED_RELEASE", ctx.prison().read().sentences.get(0).status);
        assertTrue(ctx.prison().read().assignments.isEmpty());
        var custodyState = ctx.custody().read().states.get(inmate.uuid().toString());
        assertEquals(CustodyStatus.FREE, custodyState.custody);
        assertEquals(PlayerCondition.ALIVE, custodyState.condition);
    }

    @Test
    void malformedCanonicalJailEntryCancelsSentenceWithoutTeleporting() {
        var sentence = prison.arrest(inmate, null, 1, boss, null);
        inmate.teleport("minecraft:overworld", 20, 61, 20);
        var custodyStore = ctx.custody().read();
        custodyStore.states.get(inmate.uuid().toString()).condition = PlayerCondition.DEAD;
        ctx.custody().write(custodyStore);
        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status);
        assertEquals("celula_1", ctx.prison().read().sentences.get(0).cellId);
        assertNotNull(ctx.prison().read().assignments.get("celula_1"));
        assertEquals(sentence.id, ctx.prison().read().assignments.get("celula_1").sentenceId);
        assertEquals(PlayerCondition.DEAD,
                ctx.custody().read().states.get(inmate.uuid().toString()).condition);
        prison.recoverOnLogin(inmate);

        var after = ctx.prison().read();
        var stored = after.sentences.stream().filter(s -> sentence.id.equals(s.id)).findFirst().orElseThrow();
        assertEquals("CANCELLED", stored.status);
        assertTrue(after.assignments.isEmpty());
        assertEquals(20, inmate.x, "failed canonical delivery must not teleport the target");
    }

    @Test
    void malformedCanonicalJailReleaseFailsClosedAndKeepsSentence() {
        prison.arrest(inmate, null, 1, boss, null);
        var custodyStore = ctx.custody().read();
        custodyStore.states.get(inmate.uuid().toString()).condition = null;
        ctx.custody().write(custodyStore);

        assertFalse(prison.release(boss, inmate, "test"));

        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status);
        assertFalse(ctx.prison().read().assignments.isEmpty());
    }

    @Test
    void releaseRequiresArrestAuthority() {
        prison.arrest(inmate, null, 1, boss, null);
        TestPlayer stranger = server.add("stranger"); // civilian: no capability
        assertFalse(prison.release(stranger, inmate, "grief"));
        assertTrue(stranger.told("poate elibera"));
        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status);
        assertFalse(ctx.prison().read().assignments.isEmpty(), "cell must stay assigned");
        // a Guard-rank player holds EXECUTE_ARRESTS and may release
        TestPlayer guard = server.add("g1");
        var state = players.state(guard.uuid());
        state.rank = Rank.GUARD.level();
        players.save(guard.uuid(), state);
        assertTrue(prison.release(guard, inmate, "test"));
        assertEquals("FORCED_RELEASE", ctx.prison().read().sentences.get(0).status);
    }

    @Test
    void arrestRefusedWhenPrisonDisabled() {
        ctx.policies().prisonEnabled = false;
        assertNull(prison.arrest(inmate, null, 1, boss, null));
        assertTrue(ctx.prison().read().sentences.isEmpty());
        assertTrue(boss.told("dezactivat"));
    }

    // ------------------------------------------------------------ login recovery

    @Test
    void loginRecoveryTeleportsActiveSentenceBackToCell() {
        prison.arrest(inmate, null, 1, boss, null);
        inmate.teleport("minecraft:overworld", 500, 64, 500);
        prison.recoverOnLogin(inmate);
        assertTrue(prison.insideCell("minecraft:overworld", inmate.x, inmate.y, inmate.z));
        // idempotent across repeated calls
        prison.recoverOnLogin(inmate);
        assertTrue(prison.insideCell("minecraft:overworld", inmate.x, inmate.y, inmate.z));
        assertEquals("ACTIVE", ctx.prison().read().sentences.get(0).status);
    }

    @Test
    void loginRecoveryWithoutCellFailsClosedToSingleWaitlistEntry() {
        var s1 = prison.arrest(inmate, null, 1, boss, null);
        assertEquals("ACTIVE", s1.status);
        var inmate2 = server.add("civ2");
        // inject a second cell and sentence without running the world checks
        var data = ctx.prison().read();
        var cell2 = new com.dwurdy.straja.domain.model.Cell();
        cell2.id = "celula_2";
        cell2.dimension = "minecraft:overworld";
        cell2.minX = 20; cell2.minY = 60; cell2.minZ = 20;
        cell2.maxX = 25; cell2.maxY = 65; cell2.maxZ = 25;
        data.cells.add(cell2);
        ctx.prison().write(data);
        var s2 = prison.arrest(inmate2, null, 1, boss, null);
        assertEquals("celula_2", s2.cellId);

        // corrupt the assignment so the sentence's cell is no longer validly held
        data = ctx.prison().read();
        data.assignments.get("celula_2").sentenceId = "S-foreign";
        ctx.prison().write(data);

        prison.recoverOnLogin(inmate2);
        var after = ctx.prison().read();
        var stored = after.sentences.stream().filter(s -> s.id.equals(s2.id)).findFirst().orElseThrow();
        assertEquals("WAITING_CELL", stored.status);
        assertEquals("", stored.cellId);
        assertEquals(1, after.waitlist.stream().filter(e -> s2.id.equals(e.sentenceId)).count(),
                "exactly one waitlist entry");

        prison.recoverOnLogin(inmate2);
        after = ctx.prison().read();
        assertEquals(0, after.waitlist.stream().filter(e -> s2.id.equals(e.sentenceId)).count(),
                "the dangling foreign claim is released, so the freed cell is reclaimed");
        var converged = after.sentences.stream().filter(s -> s.id.equals(s2.id))
                .findFirst().orElseThrow();
        assertEquals("ACTIVE", converged.status);
        assertEquals("celula_2", converged.cellId);
        assertTrue(prison.insideCell("minecraft:overworld", inmate2.x, inmate2.y, inmate2.z));
    }

    @Test
    void loginRecoveryPersistsAcrossRestartAndNoOpsOnTerminal() {
        prison.arrest(inmate, null, 1, boss, null);
        assertTrue(prison.release(boss, inmate, "test"));
        assertEquals("FORCED_RELEASE", ctx.prison().read().sentences.get(0).status);
        // terminal sentence -> recovery is a no-op
        prison.recoverOnLogin(inmate);
        assertTrue(ctx.prison().read().waitlist.isEmpty());
        assertTrue(ctx.prison().read().assignments.isEmpty());
        // a fresh service instance over the same stores sees the same state
        var custody = new CustodyService(ctx, players, new AuditService(ctx));
        var restarted = new PrisonService(ctx, players, new AuditService(ctx), custody);
        inmate.teleport("minecraft:overworld", 500, 64, 500);
        restarted.recoverOnLogin(inmate);
        assertEquals(500, inmate.x, "terminal sentence must not teleport on login");
    }

    @Test
    void loginRecoveryCancelsSentenceWithInvalidProvenance() {
        var data = ctx.prison().read();
        var broken = new Sentence();
        broken.id = ""; // invalid sentence identity
        broken.target = inmate.name();
        broken.targetUuid = inmate.uuid().toString();
        broken.status = "ACTIVE";
        broken.cellId = "celula_1";
        data.sentences.add(broken);
        var assignment = new com.dwurdy.straja.domain.model.PrisonStore.Assignment();
        assignment.sentenceId = "";
        assignment.target = inmate.name();
        assignment.targetUuid = inmate.uuid().toString();
        data.assignments.put("celula_1", assignment);
        ctx.prison().write(data);

        prison.recoverOnLogin(inmate);

        var after = ctx.prison().read();
        assertEquals("CANCELLED", after.sentences.get(0).status,
                "an active sentence without valid identity must be cancelled, not teleported");
        assertTrue(after.assignments.isEmpty(), "its cell assignment is released");
        assertEquals(0, inmate.x, "no forced teleport for a cancelled sentence");
    }

    @Test
    void loginRecoveryDropsMalformedWaitlistEntriesAndRequeues() {
        var s = prison.arrest(inmate, null, 1, boss, null);
        var data = ctx.prison().read();
        // corrupt the sentence's cell pointer and poison the waitlist
        data.sentences.get(0).cellId = "ghost_cell";
        data.waitlist.add(null);
        var malformed = new com.dwurdy.straja.domain.model.PrisonStore.WaitlistEntry();
        malformed.sentenceId = "";
        data.waitlist.add(malformed);
        ctx.prison().write(data);

        prison.recoverOnLogin(inmate);

        var after = ctx.prison().read();
        var stored = after.sentences.get(0);
        assertEquals("WAITING_CELL", stored.status);
        assertEquals(1, after.waitlist.size(), "only the rebuilt entry remains");
        assertEquals(s.id, after.waitlist.get(0).sentenceId);
        assertEquals(1, after.waitlist.stream().filter(e -> s.id.equals(e.sentenceId)).count());
        // the freed cell is reclaimed on the next pass — recovery converges
        prison.recoverOnLogin(inmate);
        after = ctx.prison().read();
        assertEquals("ACTIVE", after.sentences.get(0).status);
        assertEquals("celula_1", after.sentences.get(0).cellId);
        assertTrue(after.waitlist.isEmpty());
        assertTrue(prison.insideCell("minecraft:overworld", inmate.x, inmate.y, inmate.z));
    }

    @Test
    void retentionLimitPrunesOldestClosedSentences() {
        ctx.policies().prisonRetentionLimit = 3;
        var data = ctx.prison().read();
        for (int i = 0; i < 6; i++) {
            Sentence s = new Sentence();
            s.id = "S" + i;
            s.status = "SERVED";
            s.createdAt = 1_000 + i;
            s.servedAt = 1_000L + i;
            data.sentences.add(s);
        }
        Sentence active = new Sentence();
        active.id = "ACTIVE1";
        active.status = "ACTIVE";
        active.createdAt = 1;
        data.sentences.add(active);
        ctx.prison().write(data);

        prison.tick();

        var after = ctx.prison().read();
        assertEquals(3, after.sentences.size());
        assertTrue(after.sentences.stream().anyMatch(s -> "ACTIVE1".equals(s.id)),
                "active sentences must never be pruned");
        assertTrue(after.sentences.stream().anyMatch(s -> "S5".equals(s.id)),
                "the newest closed sentence is kept");
        assertTrue(after.sentences.stream().noneMatch(s -> "S0".equals(s.id)),
                "the oldest closed sentence is pruned");
    }
}
