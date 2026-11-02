package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.BountyStatus;
import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.FineTask;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.RestraintStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INST-3 — the law marks criminals without a clerk: kill → incident + system
 * BOLO, lawful-kill exemption, idempotency, witness-only mode, and the
 * grace-window state bounty sweep. Pure Java, deterministic fakes.
 */
class MarkServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private IncidentService incidents;
    private BoloService bolos;
    private BountyService bounties;
    private WantedService wanted;
    private ReputationService reputation;
    private MarkService marks;
    private TestPlayer killer;
    private TestPlayer victim;
    private TestPlayer witness;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        incidents = new IncidentService(ctx, players, audit);
        bolos = new BoloService(ctx, players, audit);
        bounties = new BountyService(ctx, players, audit);
        wanted = new WantedService(ctx, audit, bolos,
                new CustodyService(ctx, players, audit));
        reputation = new ReputationService(ctx, players, audit, incidents, bolos);
        marks = new MarkService(ctx, audit, incidents, bolos, bounties, wanted,
                reputation);
        killer = server.add("killer");
        victim = server.add("victim");
        witness = server.add("witness");
    }

    private void onDuty(TestPlayer p) {
        var state = players.state(p);
        state.rank = Rank.GUARD.level();
        state.duty = true;
        state.invited = true;
        players.save(p.uuid(), state);
    }

    private long activeBolosFor(String uuid) {
        return bolos.active().stream()
                .filter(b -> b != null && b.status == BoloStatus.ACTIVE
                        && uuid.equals(b.subjectUuid))
                .count();
    }

    @Test
    void killWritesIncidentAndSystemBolo() {
        marks.recordKill(killer, victim);
        assertEquals(1, activeBolosFor(killer.uuid().toString()));
        var bolo = bolos.active().stream()
                .filter(b -> killer.uuid().toString().equals(b.subjectUuid))
                .findFirst().orElseThrow();
        assertEquals("system", bolo.issuerName);
        assertTrue(bolo.reason.contains(victim.name()));
        var incidents = ctx.incidents().read().incidents;
        String expected = "AUTOMARK:" + killer.uuid() + ":" + victim.uuid();
        assertTrue(incidents.stream().anyMatch(i -> expected.equals(i.provenance)));
        assertTrue(killer.messages.stream()
                .anyMatch(m -> m.contains("straja.mark.recorded")
                        || m.contains("dosar")));
        assertTrue(audit.tail(20).stream()
                .anyMatch(e -> "auto_mark".equals(e.action)));
    }

    @Test
    void wantedVictimMakesAKillLawful() {
        // The victim already carries a mark — killing them is the bounty
        // game, not a new crime (§15a lawful-force exemption).
        bolos.flagSystem(victim.uuid().toString(), victim.name(), "fugar");
        marks.recordKill(killer, victim);
        assertEquals(0, activeBolosFor(killer.uuid().toString()));
        assertTrue(ctx.incidents().read().incidents.stream()
                .noneMatch(i -> "AUTOMARK".equals(i.source)));
    }

    @Test
    void alreadyWantedKillerGetsNoStackedMark() {
        marks.recordKill(killer, victim);
        assertEquals(1, activeBolosFor(killer.uuid().toString()));
        // A second kill while wanted adds no second marker — but the second
        // victim still gets their own case file.
        marks.recordKill(killer, server.add("victim2"));
        assertEquals(1, activeBolosFor(killer.uuid().toString()));
        assertEquals(2, ctx.incidents().read().incidents.stream()
                .filter(i -> "AUTOMARK".equals(i.source)).count());
    }

    @Test
    void lawfulGuardKillStaysUnmarked() {
        // §15a lawful force: on-duty guard + active authoritative task on the
        // victim + recent hostility + unrestrained target. Reputation already
        // pays the death bounty for this kill — the mark must not prosecute it.
        var guard = server.add("guard");
        onDuty(guard);
        var task = new FineTask();
        task.id = "JA-1";
        task.kind = "JAILER_ASSAULT";
        task.status = "OPEN";
        task.targetUuid = victim.uuid().toString();
        var fines = ctx.fines().read();
        if (fines.tasks == null) fines.tasks = new ArrayList<>();
        fines.tasks.add(task);
        ctx.fines().write(fines);
        var rep = ctx.reputation().read();
        if (rep.recentHostileDamageAt == null) {
            rep.recentHostileDamageAt = new LinkedHashMap<>();
        }
        rep.recentHostileDamageAt.put(guard.uuid() + ":" + victim.uuid(),
                clock.nowMillis());
        ctx.reputation().write(rep);
        marks.recordKill(guard, victim);
        assertEquals(0, activeBolosFor(guard.uuid().toString()));
        assertTrue(ctx.incidents().read().incidents.stream()
                .noneMatch(i -> "AUTOMARK".equals(i.source)));
    }

    @Test
    void restrainedWantedVictimIsNotFairGame() {
        // A wanted prisoner already caught is not fair game: executing a
        // cuffed wanted target is murder, matching KILL_RESTRAINED.
        bolos.flagSystem(victim.uuid().toString(), victim.name(), "fugar");
        var custody = ctx.custody().read();
        if (custody.states == null) custody.states = new LinkedHashMap<>();
        var state = new CustodyState();
        state.restraint = RestraintStatus.CUFFED;
        custody.states.put(victim.uuid().toString(), state);
        ctx.custody().write(custody);
        marks.recordKill(killer, victim);
        assertEquals(1, activeBolosFor(killer.uuid().toString()));
    }

    @Test
    void cancelledSystemBountyIsNotReposted() {
        marks.recordKill(killer, victim);
        marks.tick();
        String uuid = killer.uuid().toString();
        var store = ctx.bounties().read();
        var bounty = store.activeFor(uuid);
        assertNotNull(bounty);
        // An Inspector withdraws the state bounty while the mark still
        // stands — the sweep must not repost over the human decision.
        bounty.status = BountyStatus.CANCELLED;
        ctx.bounties().write(store);
        clock.advance(11_000L);
        marks.tick();
        assertNull(ctx.bounties().read().activeFor(uuid));
        assertEquals(1, ctx.bounties().read().records.stream()
                .filter(r -> uuid.equals(r.targetUuid)).count());
    }

    @Test
    void policyOffLeavesKillsUnmarked() {
        ctx.policies().autoMarkKills = false;
        marks.recordKill(killer, victim);
        assertEquals(0, activeBolosFor(killer.uuid().toString()));
    }

    @Test
    void witnessOnlyMarksOnlySeenKills() {
        ctx.policies().autoMarkWitnessOnly = true;
        witness.x = 500; // out of the 40-block witness radius
        marks.recordKill(killer, victim);
        assertEquals(0, activeBolosFor(killer.uuid().toString()));
        witness.x = 10;
        marks.recordKill(killer, victim);
        assertEquals(1, activeBolosFor(killer.uuid().toString()));
    }

    @Test
    void sweepPricesUnpricedMarkOnFirstSighting() {
        marks.recordKill(killer, victim);
        marks.tick();
        var bounty = ctx.bounties().read().activeFor(killer.uuid().toString());
        assertNotNull(bounty);
        assertEquals(BountyStatus.ACTIVE, bounty.status);
        assertEquals("Straja", bounty.postedBy);
        assertEquals("emisa de Straja", bounty.reason);
        // The state bounty carries its own warrant.
        assertNotNull(bounty.linkedBoloId);
        // Idempotent: a second sweep does not stack a second bounty.
        marks.tick();
        assertEquals(1, ctx.bounties().read().records.stream()
                .filter(r -> r.status == BountyStatus.ACTIVE
                        && killer.uuid().toString().equals(r.targetUuid))
                .count());
    }

    @Test
    void sweepPostsStateBountyAfterGrace() {
        ctx.policies().autoBountyAfterHours = 1;
        // A legacy mark outlives the BOLO TTL, so the grace clock can run.
        var reg = ctx.prisonerRegister().read();
        reg.legacyWantedUntil().put(killer.uuid().toString(),
                clock.nowMillis() + 2 * 3_600_000L);
        ctx.prisonerRegister().write(reg);
        marks.tick(); // stamps the sighting — inside grace, nothing posts
        assertNull(ctx.bounties().read().activeFor(killer.uuid().toString()));
        clock.advance(3_600_000L + 10_000L);
        marks.tick();
        var bounty = ctx.bounties().read().activeFor(killer.uuid().toString());
        assertNotNull(bounty);
        assertEquals("Straja", bounty.postedBy);
        // The seeded warrant keeps the priced player huntable on its own.
        assertNotNull(bounty.linkedBoloId);
    }

    @Test
    void sweepRespectsExistingBountyAndPolicyOff() {
        marks.recordKill(killer, victim);
        // An existing hunt satisfies the state — no auto bounty stacks.
        bounties.postSystem(killer.uuid().toString(), killer.name(), 100, "test");
        clock.advance(11_000L);
        marks.tick();
        assertEquals(1, ctx.bounties().read().records.stream()
                .filter(r -> r.status == BountyStatus.ACTIVE
                        && killer.uuid().toString().equals(r.targetUuid))
                .count());
        // Policy off: the sweep posts nothing for a newly wanted player.
        var other = server.add("other");
        bolos.flagSystem(other.uuid().toString(), other.name(), "fugar");
        ctx.policies().autoBountyEnabled = false;
        clock.advance(11_000L);
        marks.tick();
        assertNull(ctx.bounties().read().activeFor(other.uuid().toString()));
    }
}
