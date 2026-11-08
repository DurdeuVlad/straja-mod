package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PosterBoardGateway;
import com.dwurdy.straja.domain.model.BountyRecord;
import com.dwurdy.straja.domain.model.BountyStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POSTER-1 — the face board: spot declare/remove via SetupData, active
 * bounty feed cycling only while unobserved, immediate takedown when the
 * hunt resolves. Headless — a recording board stands in for entities.
 */
class PosterServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private BountyService bounties;
    private PosterService posters;
    private FakeBoard board;

    private static final class FakeBoard implements PosterBoardGateway {
        int watchers;
        final List<String> calls = new ArrayList<>();

        @Override public void renderFace(String spotId, String dimension,
                double x, double y, double z, String targetUuid,
                String targetName, int reward) {
            calls.add("face:" + targetName + ":" + reward);
        }
        @Override public void renderIdle(String spotId, String dimension,
                double x, double y, double z) {
            calls.add("idle");
        }
        @Override public void remove(String spotId, String dimension,
                double x, double y, double z) {
            calls.add("remove:" + spotId);
        }
        @Override public int watchersNear(String dimension, double x,
                double y, double z, double radius) {
            return watchers;
        }
    }

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        var players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        bounties = new BountyService(ctx, players, audit);
        posters = new PosterService(ctx, bounties, audit);
        board = new FakeBoard();
        posters.useBoard(board);
    }

    private void declare(TestPlayer admin, String id) {
        assertTrue(posters.posterAdd(admin, id), "poster spot declared");
    }

    private void bounty(String id, String name, int amount) {
        var store = ctx.bounties().read();
        var record = new BountyRecord();
        record.id = id;
        record.targetUuid = java.util.UUID.nameUUIDFromBytes(name.getBytes()).toString();
        record.targetName = name;
        record.amount = amount;
        record.status = BountyStatus.ACTIVE;
        store.records.add(record);
        ctx.bounties().write(store);
    }

    private void resolveBounty(String id) {
        var store = ctx.bounties().read();
        for (var record : store.records) {
            if (id.equals(record.id)) record.status = BountyStatus.CAPTURED;
        }
        ctx.bounties().write(store);
    }

    @Test
    void declaresAndRendersFirstFace() {
        var admin = server.add("admin");
        declare(admin, "primaria");
        bounty("b1", "Banditul", 120);
        posters.tick();
        assertTrue(board.calls.contains("face:Banditul:120"),
                "the spot renders the active bounty on its first sweep");
    }

    @Test
    void cyclesToTheNextFaceWhileUnobserved() {
        var admin = server.add("admin");
        declare(admin, "piata");
        bounty("b1", "Alpha", 10);
        bounty("b2", "Beta", 20);
        posters.tick();
        assertEquals(List.of("face:Alpha:10"), board.calls);
        clock.advance(ctx.policies().posterCycleSeconds * 1_000L + 1_000L);
        posters.tick();
        assertEquals(List.of("face:Alpha:10", "face:Beta:20"), board.calls,
                "the cadence swap walks the active list");
        clock.advance(ctx.policies().posterCycleSeconds * 1_000L + 1_000L);
        posters.tick();
        assertEquals("face:Alpha:10", board.calls.getLast(),
                "the index wraps to the front of the feed");
    }

    @Test
    void observedPosterHoldsItsFace() {
        var admin = server.add("admin");
        declare(admin, "gara");
        bounty("b1", "Alpha", 10);
        bounty("b2", "Beta", 20);
        posters.tick();
        board.watchers = 1;
        clock.advance(ctx.policies().posterCycleSeconds * 1_000L + 60_000L);
        posters.tick();
        assertEquals(List.of("face:Alpha:10"), board.calls,
                "a reader freezes the swap — no flicker mid-look");
        board.watchers = 0;
        clock.advance(ctx.policies().posterCycleSeconds * 1_000L + 1_000L);
        posters.tick();
        assertEquals("face:Beta:20", board.calls.getLast(),
                "the swap resumes once the room empties — after a full quiet cadence");
    }

    @Test
    void resolvedHuntComesDownImmediately() {
        var admin = server.add("admin");
        declare(admin, "doc");
        bounty("b1", "Prins", 50);
        posters.tick();
        assertEquals(List.of("face:Prins:50"), board.calls);
        resolveBounty("b1");
        clock.advance(1_000L); // the very next sweep
        posters.tick();
        assertEquals(List.of("face:Prins:50", "idle"), board.calls,
                "a captured target is taken down at once, cadence or not");
    }

    @Test
    void emptyFeedNeverRenders() {
        var admin = server.add("admin");
        declare(admin, "templu");
        posters.tick();
        clock.advance(60_000L);
        posters.tick();
        assertTrue(board.calls.isEmpty(),
                "no bounties means the board stays dark");
    }

    @Test
    void removeTearsDownTheBoard() {
        var admin = server.add("admin");
        declare(admin, "port");
        bounty("b1", "Banditul", 120);
        posters.tick();
        assertTrue(posters.posterRemove(admin, "port"));
        assertTrue(board.calls.contains("remove:port"),
                "removal strips the entities, not just the record");
        assertNull(ctx.setup().read().posterSpots.get("port"));
    }

    @Test
    void policyOffLeavesTheBoardAlone() {
        ctx.policies().posterEnabled = false;
        var admin = server.add("admin");
        declare(admin, "far");
        bounty("b1", "Banditul", 120);
        posters.tick();
        assertTrue(board.calls.isEmpty());
    }

    @Test
    void duplicateDeclareIsRefused() {
        var admin = server.add("admin");
        declare(admin, "cladire");
        assertFalse(posters.posterAdd(admin, "cladire"),
                "a second declare on the same id is refused");
        assertEquals(1, ctx.setup().read().posterSpots.size());
    }
}
