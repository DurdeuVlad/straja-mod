package com.dwurdy.straja.application.service;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.BountyStatus;
import com.dwurdy.straja.domain.model.BountyRecord;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import com.dwurdy.straja.support.MemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #242 — /straja protocol guided tester dossier: enrollment, chapter books,
 * once-only scripted hooks (fine, rank grant, suspect spawn, suspect fine,
 * bounty prep), manual actor toggle, teardown purging of suspect records,
 * and the config gate that keeps it a tester-build surface.
 */
class ProtocolServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private ProtocolService protocol;
    private SavedStores.Protocol repo;
    private TestPlayer tester;
    private final List<UUID> spawned = new ArrayList<>();
    private final List<UUID> dismissed = new ArrayList<>();
    private UUID nextSpawn;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        var policies = Fakes.policies();
        policies.protocolEnabled = true;
        ctx = Fakes.context(server, clock, policies);
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        var memory = new java.util.HashMap<String, MemoryStore>();
        repo = new SavedStores.Protocol(name -> memory.computeIfAbsent(name, k -> new MemoryStore()));
        protocol = new ProtocolService(ctx, repo, players, audit);
        protocol.usePrison(prison);
        protocol.useSpawner(new ProtocolService.ActorSpawner() {
            @Override public UUID spawn(PlayerGateway anchor, String name) {
                nextSpawn = UUID.nameUUIDFromBytes(
                        ("straja:protocol:" + anchor.uuid()).getBytes());
                spawned.add(nextSpawn);
                return nextSpawn;
            }
            @Override public void dismiss(UUID actorUuid) { dismissed.add(actorUuid); }
        });
        tester = server.add("tester1");
    }

    private int chapter() {
        return repo.read().find(tester.uuid().toString()).chapter;
    }

    @Test
    void disabledRefusesStart() {
        ctx.policies().protocolEnabled = false;
        protocol.start(tester);
        assertTrue(tester.messages.contains("straja.protocol.disabled"));
        assertNull(repo.read().find(tester.uuid().toString()));
    }

    @Test
    void startEnrollsAndIssuesCover() {
        protocol.start(tester);
        var entry = repo.read().find(tester.uuid().toString());
        assertNotNull(entry);
        assertEquals(0, entry.chapter);
        assertEquals(1, tester.books.size());
        assertTrue(tester.books.get(0).startsWith("Dosar de testare"));
        // re-start while running re-issues the dossier instead of duplicating
        protocol.start(tester);
        assertTrue(tester.messages.contains("straja.protocol.already_running"));
        assertEquals(2, tester.books.size());
    }

    @Test
    void nextAdvancesChaptersAndFinishes() {
        protocol.start(tester);
        for (int i = 0; i < 10; i++) protocol.next(tester); // cover + 9 chapters + seal
        var entry = repo.read().find(tester.uuid().toString());
        assertTrue(entry.finished);
        // cover + 9 chapter books + finish book
        assertEquals(11, tester.books.size());
        assertTrue(tester.messages.contains("straja.protocol.finished"));
    }

    @Test
    void scriptedBeatsFireOnce() {
        protocol.start(tester);
        protocol.next(tester); // ch1 orientarea
        protocol.next(tester); // ch2 checkpoint
        protocol.next(tester); // ch3 — scripted fine on tester + coin kit
        var fines = ctx.fines().read();
        assertEquals(1, fines.fines.size());
        assertEquals(tester.uuid().toString(), fines.fines.get(0).targetUuid);
        assertEquals(32, fines.fines.get(0).amount);
        assertEquals("ISSUED", fines.fines.get(0).status);

        protocol.next(tester); // ch4 — rank grant
        var state = players.state(tester);
        assertEquals(Rank.INSPECTOR.level(), state.rank);
        assertTrue(state.duty);

        protocol.next(tester); // ch5 — suspect spawn + restraint kit
        assertEquals(1, spawned.size());
        var entry = repo.read().find(tester.uuid().toString());
        assertEquals(spawned.get(0).toString(), entry.actorUuid);

        // back + next must not re-fire any hook
        protocol.back(tester);
        protocol.back(tester);
        protocol.next(tester);
        protocol.next(tester);
        assertEquals(1, ctx.fines().read().fines.size());
        assertEquals(1, spawned.size());
        assertEquals(5, chapter());
    }

    @Test
    void suspectFineAndBountyPrepLand() {
        protocol.start(tester);
        for (int i = 0; i < 5; i++) protocol.next(tester); // through ch5
        var entry = repo.read().find(tester.uuid().toString());
        String actorUuid = entry.actorUuid;
        protocol.next(tester); // ch6 — scripted fine on the suspect
        var fines = ctx.fines().read();
        assertTrue(fines.fines.stream().anyMatch(f -> actorUuid.equals(f.targetUuid)
                && f.amount == 96));
        protocol.next(tester); // ch7 — surrender flag + release attempt
        var bounties = ctx.bounties().read();
        assertTrue(bounties.surrenders.containsKey(actorUuid));
    }

    @Test
    void stopPurgesSuspectRecords() {
        protocol.start(tester);
        for (int i = 0; i < 6; i++) protocol.next(tester); // through ch6
        var entry = repo.read().find(tester.uuid().toString());
        String actorUuid = entry.actorUuid;
        // plant records the teardown must strip
        var bounties = ctx.bounties().read();
        var bounty = new BountyRecord();
        bounty.id = "BNT-0001";
        bounty.targetUuid = actorUuid;
        bounty.targetName = "Suspectul";
        bounty.status = BountyStatus.ACTIVE;
        bounties.records.add(bounty);
        ctx.bounties().write(bounties);

        protocol.stop(tester);
        assertNull(repo.read().find(tester.uuid().toString()));
        assertTrue(dismissed.contains(UUID.fromString(actorUuid)));
        assertTrue(ctx.bounties().read().records.isEmpty());
        assertTrue(ctx.fines().read().fines.stream()
                .noneMatch(f -> actorUuid.equals(f.targetUuid)));
        assertTrue(tester.messages.contains("straja.protocol.stopped"));
    }

    @Test
    void resetReturnsToCover() {
        protocol.start(tester);
        for (int i = 0; i < 5; i++) protocol.next(tester);
        protocol.reset(tester);
        var entry = repo.read().find(tester.uuid().toString());
        assertEquals(0, entry.chapter);
        assertEquals(0, entry.hooksDone);
        assertFalse(entry.finished);
        // hooks re-arm on reset: the tester fine issues again on ch3
        protocol.next(tester); protocol.next(tester); protocol.next(tester);
        assertEquals(2, ctx.fines().read().fines.size());
    }

    @Test
    void actorCommandTogglesSuspect() {
        protocol.start(tester);
        for (int i = 0; i < 5; i++) protocol.next(tester);
        protocol.actor(tester); // dismiss
        assertEquals(1, dismissed.size());
        assertTrue(repo.read().find(tester.uuid().toString()).actorUuid.isEmpty());
        protocol.actor(tester); // re-summon
        assertEquals(2, spawned.size());
    }

    @Test
    void unenrolledCommandsRefuse() {
        protocol.status(tester);
        protocol.next(tester);
        protocol.actor(tester);
        assertTrue(tester.messages.stream()
                .allMatch(m -> m.equals("straja.protocol.not_enrolled")
                        || m.equals("straja.protocol.disabled")));
    }

    @Test
    void progressSurvivesReload() {
        protocol.start(tester);
        protocol.next(tester);
        protocol.next(tester);
        // simulate relog: same repo (SavedData-backed), entry intact
        var entry = repo.read().find(tester.uuid().toString());
        assertEquals(2, entry.chapter);
        assertEquals("tester1", entry.playerName);
        assertTrue(entry.startedAt > 0);
    }
}
