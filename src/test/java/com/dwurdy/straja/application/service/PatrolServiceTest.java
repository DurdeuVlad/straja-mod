package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Cell;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * INST-4 — the patrulier: post-radius scan, nearest-target engagement,
 * stand-down on the already-caught, cuff-and-book on contact, hold when no
 * cell is free, leash drop and policy-off no-op. Headless: the Officer port
 * is a recording fake — the entity goal only adapts the live NPC to it.
 */
class PatrolServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private BoloService bolos;
    private CustodyService custody;
    private PrisonService prison;
    private ReputationService reputation;
    private WantedService wanted;
    private PatrolService patrols;

    private static final class FakeOfficer implements PatrolService.Officer {
        double postX, postY, postZ;
        double x, y, z;
        final List<String> calls = new ArrayList<>();

        FakeOfficer(double px, double py, double pz) {
            this.postX = px; this.postY = py; this.postZ = pz;
            this.x = px; this.y = py; this.z = pz;
        }

        @Override public String entityUuid() { return "patrol-1"; }
        @Override public String dimension() { return "minecraft:overworld"; }
        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double postX() { return postX; }
        @Override public double postY() { return postY; }
        @Override public double postZ() { return postZ; }

        @Override public double distanceTo(PlayerGateway t) {
            double dx = t.x() - x, dy = t.y() - y, dz = t.z() - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        @Override public void moveTo(PlayerGateway t) { calls.add("moveTo:" + t.name()); }
        @Override public void moveToPost() { calls.add("moveToPost"); }
        @Override public void melee(PlayerGateway t) { calls.add("melee:" + t.name()); }
        @Override public void stopMoving() { calls.add("stop"); }
    }

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        var incidents = new IncidentService(ctx, players, audit);
        bolos = new BoloService(ctx, players, audit);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        reputation = new ReputationService(ctx, players, audit, incidents, bolos);
        wanted = new WantedService(ctx, audit, bolos, custody);
        patrols = new PatrolService(ctx, audit, wanted, custody, prison,
                reputation);
    }

    private void flagWanted(TestPlayer p) {
        bolos.flagSystem(p.uuid().toString(), p.name(), "fugar");
    }

    private void place(TestPlayer p, double x, double y, double z) {
        p.x = x; p.y = y; p.z = z;
    }

    private void addCell(String id, int cx) {
        var data = ctx.prison().read();
        var cell = new Cell();
        cell.id = id;
        cell.dimension = "minecraft:overworld";
        cell.minX = cx - 2; cell.maxX = cx + 2;
        cell.minY = -2; cell.maxY = 4;
        cell.minZ = -2; cell.maxZ = 2;
        data.cells.add(cell);
        ctx.prison().write(data);
    }

    @Test
    void engagesNearestWantedInsidePostRadius() {
        var officer = new FakeOfficer(0, 0, 0);
        var far = server.add("farSuspect");
        var near = server.add("nearSuspect");
        flagWanted(far);
        flagWanted(near);
        place(far, 20, 0, 0);
        place(near, 6, 0, 0);
        var bystander = server.add("bystander");
        place(bystander, 3, 0, 0);
        patrols.tick(officer);
        assertTrue(officer.calls.contains("moveTo:nearSuspect"),
                "nearest wanted suspect in the post radius is engaged");
        // Still engaged next beat; the innocent bystander is never a target.
        patrols.tick(officer);
        assertTrue(officer.calls.stream().noneMatch(c -> c.contains("bystander")));
    }

    @Test
    void ignoresSuspectsOutsideJurisdiction() {
        var officer = new FakeOfficer(0, 0, 0);
        var suspect = server.add("distant");
        flagWanted(suspect);
        place(suspect, 100, 0, 0); // beyond postRadius + scanRange
        patrols.tick(officer);
        assertTrue(officer.calls.isEmpty()
                || officer.calls.contains("stop")
                || officer.calls.contains("moveToPost"),
                "no engagement outside the post's jurisdiction");
    }

    @Test
    void standsDownOnAlreadyCaught() {
        var officer = new FakeOfficer(0, 0, 0);
        var suspect = server.add("cuffed");
        flagWanted(suspect);
        place(suspect, 5, 0, 0);
        // Cuffed = already caught, even before anyone books them.
        assertTrue(custody.applyCuffsSystem(suspect, "test").ok());
        patrols.tick(officer);
        assertTrue(officer.calls.stream().noneMatch(c -> c.contains("cuffed")),
                "a restrained suspect is not engaged");
        // An escorted prisoner under a guard's tether is likewise caught.
        var escorted = server.add("escorted");
        flagWanted(escorted);
        place(escorted, 8, 0, 0);
        assertTrue(custody.applyCuffsSystem(escorted, "test").ok());
        var guard = server.add("guard");
        place(guard, 8, 0, 0);
        var store = ctx.custody().read();
        var record = store.cuffed.get(escorted.uuid().toString());
        record.issuerUuid = guard.uuid().toString();
        ctx.custody().write(store);
        patrols.tick(officer);
        assertTrue(officer.calls.stream().noneMatch(c -> c.contains("escorted")),
                "an escorted suspect is not engaged");
    }

    @Test
    void contactCuffsAndBooksNearestCell() {
        var suspect = server.add("runner");
        flagWanted(suspect);
        place(suspect, 20, 0, 0);   // inside postRadius of the post
        // The arrest point (target position) sits between two cells.
        addCell("celula_departe", 120);
        addCell("celula_apropiata", 28);
        var officer = new FakeOfficer(0, 0, 0);
        officer.x = 18.2; officer.y = 0; officer.z = 0; // in cuff range
        patrols.tick(officer);
        var data = ctx.prison().read();
        var sentence = data.sentences.stream()
                .filter(s -> suspect.uuid().toString().equals(s.targetUuid))
                .findFirst().orElseThrow();
        assertEquals("celula_apropiata", sentence.cellId,
                "booking picks the nearest free cell to the arrest point");
        assertTrue(data.assignments.containsKey("celula_apropiata"));
        assertTrue(ctx.custody().read().cuffed
                .containsKey(suspect.uuid().toString()));
        // The custody sweep must not reap an institutional restraint — the
        // cuff record's issuer is the system sentinel, always eligible.
        custody.tick();
        assertTrue(ctx.custody().read().cuffed
                .containsKey(suspect.uuid().toString()),
                "institutional cuffs survive the issuer-eligibility sweep");
        // Booked: engagement cleared — the officer stands down to its post.
        patrols.tick(officer);
        assertTrue(officer.calls.stream().noneMatch(c -> c.contains("runner")));
    }

    @Test
    void noFreeCellHoldsTheCuffedPrisoner() {
        var suspect = server.add("holder");
        flagWanted(suspect);
        place(suspect, 2, 0, 0);
        var officer = new FakeOfficer(0, 0, 0);
        patrols.tick(officer);
        var data = ctx.prison().read();
        var sentence = data.sentences.stream()
                .filter(s -> suspect.uuid().toString().equals(s.targetUuid))
                .findFirst().orElseThrow();
        assertEquals("WAITING_CELL", sentence.status);
        assertTrue(ctx.custody().read().cuffed
                .containsKey(suspect.uuid().toString()));
        // The held prisoner's restraint must survive the custody sweep —
        // a reaped cuff would re-mark the waitlisted prisoner a fugitive.
        custody.tick();
        assertTrue(ctx.custody().read().cuffed
                .containsKey(suspect.uuid().toString()),
                "institutional cuffs survive the issuer-eligibility sweep");
        // The officer holds station over the cuffed prisoner until a bunk
        // frees — off-post it still does not walk back while holding.
        officer.calls.clear();
        officer.x = 10; officer.z = 10;
        patrols.tick(officer);
        assertTrue(officer.calls.contains("stop"));
        assertTrue(officer.calls.stream().noneMatch(c -> c.startsWith("moveTo")),
                "the officer holds the prisoner instead of returning to post");
    }

    @Test
    void fleeingBeyondLeashDropsTheEngagement() {
        var officer = new FakeOfficer(0, 0, 0);
        var suspect = server.add("fugitive");
        flagWanted(suspect);
        place(suspect, 10, 0, 0);
        patrols.tick(officer);
        assertTrue(officer.calls.contains("moveTo:fugitive"));
        officer.calls.clear();
        officer.x = 9; // officer gave chase
        place(suspect, 200, 0, 0); // escaped the engagement region
        patrols.tick(officer);
        assertTrue(officer.calls.contains("moveToPost"),
                "the officer returns to post when the target escapes the leash");
    }

    @Test
    void meleeStrikesRespectingCooldown() {
        var officer = new FakeOfficer(0, 0, 0);
        var suspect = server.add("fighter");
        flagWanted(suspect);
        place(suspect, 8, 0, 0); // in postRadius but beyond melee reach? close it
        officer.x = 5.4; // distanceTo ≈ 2.6 → melee band (2.5 < d ≤ 3.0)
        patrols.tick(officer);
        assertTrue(officer.calls.contains("melee:fighter"));
        officer.calls.clear();
        patrols.tick(officer); // inside engage cooldown — approach only
        assertFalse(officer.calls.contains("melee:fighter"));
        assertTrue(officer.calls.contains("moveTo:fighter"));
        clock.advance(4_000L);
        patrols.tick(officer);
        assertTrue(officer.calls.contains("melee:fighter"),
                "the strike repeats once the cooldown lapses");
    }

    @Test
    void policyOffDisablesAllEnforcement() {
        ctx.policies().patrolOfficerEnabled = false;
        var officer = new FakeOfficer(0, 0, 0);
        var suspect = server.add("untouched");
        flagWanted(suspect);
        place(suspect, 2, 0, 0);
        patrols.tick(officer);
        assertTrue(officer.calls.isEmpty());
        assertTrue(ctx.prison().read().sentences.isEmpty());
    }
}
