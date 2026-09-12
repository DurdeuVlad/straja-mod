package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Sentence;
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
        assertTrue(prison.createCell(boss, "celula_1", "minecraft:overworld",
                0, 60, 0, 5, 65, 5));
    }

    @Test
    void arrestAssignsCellAndTeleports() {
        var s = prison.arrest(inmate, null, 1, boss, null);
        assertEquals("ACTIVE", s.status);
        assertEquals("celula_1", s.cellId);
        // teleported inside the cell
        assertTrue(inmate.x >= 0 && inmate.x <= 5);
        assertTrue(prison.insideCell("minecraft:overworld", inmate.x, inmate.y, inmate.z));
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
