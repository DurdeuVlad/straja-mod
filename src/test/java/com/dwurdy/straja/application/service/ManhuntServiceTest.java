package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.ManhuntRecord;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** FUN-7: breaks are a playable loop — tamper, alarm, hunt, end states. */
class ManhuntServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private CustodyService custody;
    private PrisonService prison;
    private BoloService bolos;
    private IncidentService incidents;
    private DispatchService dispatch;
    private ManhuntService manhunts;

    private com.dwurdy.straja.domain.model.Cell cell() {
        return ctx.prison().read().cell("celula_1");
    }

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        prison.useSeizure(new SeizureService(ctx, audit));
        bolos = new BoloService(ctx, players, audit);
        prison.useBolos(bolos);
        incidents = new IncidentService(ctx, players, audit);
        dispatch = new DispatchService(ctx, players, audit);
        dispatch.useIncidents(incidents);
        incidents.useDispatch(dispatch);
        bolos.useDispatch(dispatch);
        manhunts = new ManhuntService(ctx, players, audit);
        manhunts.useDispatch(dispatch);
        manhunts.useBolos(bolos);
        prison.useManhunts(manhunts);
        ((Fakes.TestDeepScan) ctx.deepScan()).server = server;
        server.add("dwurdy");
        ((TestWorld) ctx.world()).room("minecraft:overworld", -1, 59, -1, 6, 66, 6,
                -1, 61, 2);
        assertTrue(prison.createCell(server.onlinePlayers().get(0), "celula_1",
                "minecraft:overworld", 0, 60, 0, 5, 65, 5));
    }

    private TestPlayer guard(String name) {
        TestPlayer g = server.add(name);
        var s = players.state(g);
        s.rank = Rank.GUARD.level();
        s.duty = true;
        s.invited = true;
        players.save(g.uuid(), s);
        g.dimension = "minecraft:overworld";
        return g;
    }

    private TestPlayer breakerWith(String item) {
        TestPlayer b = server.add("breaker");
        b.give(ItemSpec.of(item, 1));
        b.selectedSlot = 0;
        b.dimension = "minecraft:overworld";
        return b;
    }

    private void clickDoor(TestPlayer b) {
        var c = cell();
        prison.onCellTamper(b, c.dimension, c.doorX, c.doorY, c.doorZ);
    }

    @Test
    void tamperNeedsAToolAndADoor() {
        TestPlayer bare = server.add("bare");
        var c = cell();
        assertFalse(prison.onCellTamper(bare, c.dimension, c.doorX, c.doorY, c.doorZ),
                "empty hands are ignored — the click stays free");
        TestPlayer cutters = breakerWith("straja:bolt_cutters");
        assertFalse(prison.onCellTamper(cutters, "minecraft:overworld", 500, 60, 500),
                "cutters on a random wall are not a tamper attempt");
    }

    @Test
    void tamperRefusesWhileAGuardIsPresent() {
        TestPlayer g = guard("patroller");
        var c = cell();
        g.x = c.doorX + 5; g.y = c.doorY; g.z = c.doorZ; // inside the detection radius
        TestPlayer b = breakerWith("straja:bolt_cutters");
        b.x = c.doorX + 1; b.y = c.doorY; b.z = c.doorZ;
        clickDoor(b);
        assertNull(ctx.manhunts().read().hunts.stream().findFirst().orElse(null));
        assertTrue(b.told("străjer") || b.told("guard"));
    }

    @Test
    void crowbarOnlyOpensWeakLocks() {
        TestPlayer b = breakerWith("straja:crowbar");
        clickDoor(b);
        assertNull(ctx.manhunts().read().hunts.stream().findFirst().orElse(null),
                "a STANDARD lock shrugs the crowbar off");
        // flip the tier — the crowbar channel now starts
        var data = ctx.prison().read();
        var c = data.cell("celula_1");
        c.lockTier = "WEAK";
        ctx.prison().write(data);
        b.x = c.doorX + 1; b.y = c.doorY; b.z = c.doorZ;
        clickDoor(b);
        clock.advance(ctx.policies().cellTamperQuickSeconds * 1_000L + 50);
        prison.tick();
        assertTrue(cell().brokenUntil > clock.nowMillis(), "weak lock fell to the crowbar");
    }

    @Test
    void completedBreakOpensAManhuntWithEveryParticipant() {
        // lock up a prisoner first
        TestPlayer inmate = server.add("inmate");
        prison.arrest(inmate, null, 1, server.onlinePlayers().get(0), null);
        TestPlayer b = breakerWith("straja:bolt_cutters");
        var c = cell();
        b.x = c.doorX + 1; b.y = c.doorY; b.z = c.doorZ;
        clickDoor(b);
        clock.advance(ctx.policies().cellTamperSeconds * 1_000L + 50);
        prison.tick();
        assertTrue(cell().brokenUntil > clock.nowMillis());
        var hunt = ctx.manhunts().read().hunts.get(0);
        assertEquals("ACTIVE", hunt.status);
        assertTrue(hunt.runner(b.uuid().toString()), "the breaker is a runner");
        assertTrue(hunt.runner(inmate.uuid().toString()), "the cell's prisoner joins the hunt");
        assertFalse(hunt.dispatchCallId.isBlank(), "a dispatch call carries the alarm");
    }

    @Test
    void driftingFromTheDoorCancelsTheChannel() {
        TestPlayer b = breakerWith("straja:bolt_cutters");
        var c = cell();
        b.x = c.doorX; b.y = c.doorY; b.z = c.doorZ;
        clickDoor(b);
        b.x = c.doorX + 40; // walked away mid-channel
        clock.advance(ctx.policies().cellTamperSeconds * 1_000L + 50);
        prison.tick();
        assertTrue(cell().brokenUntil <= clock.nowMillis(), "no break — the breaker left");
        assertTrue(ctx.manhunts().read().hunts.isEmpty());
    }

    @Test
    void aGuardArrivingMidChannelInterruptsTheBreak() {
        TestPlayer b = breakerWith("straja:bolt_cutters");
        var c = cell();
        b.x = c.doorX; b.y = c.doorY; b.z = c.doorZ;
        clickDoor(b);
        TestPlayer g = guard("responder");
        g.x = c.doorX + 2; g.y = c.doorY; g.z = c.doorZ;
        clock.advance(ctx.policies().cellTamperSeconds * 1_000L + 50);
        prison.tick();
        assertTrue(cell().brokenUntil <= clock.nowMillis(), "presence interrupts the cut");
        assertTrue(ctx.manhunts().read().hunts.isEmpty());
    }

    @Test
    void hintsReachOnDutyHuntersAndDecay() {
        TestPlayer runner = server.add("runner");
        runner.dimension = "minecraft:overworld";
        runner.x = 100; runner.z = 100;
        TestPlayer hunter = guard("hunter");
        hunter.dimension = "minecraft:overworld";
        var r = new ManhuntRecord.Runner();
        r.uuid = runner.uuid().toString(); r.name = "runner";
        manhunts.begin(List.of(r), "minecraft:overworld", 0, 64, 0, "celula 1", "cell:c1");
        clock.advance(ctx.policies().manhuntHintSeconds * 1_000L + 50);
        manhunts.tick();
        assertTrue(hunter.told("Vânătoare") || hunter.told("semnalat"),
                "the hunter got a position hint");
    }

    @Test
    void coolingConvertsRunnersToStandingWarrants() {
        TestPlayer runner = server.add("runner");
        runner.dimension = "minecraft:overworld";
        var r = new ManhuntRecord.Runner();
        r.uuid = runner.uuid().toString(); r.name = "runner";
        manhunts.begin(List.of(r), "minecraft:overworld", 0, 64, 0, "", "cell:c1");
        clock.advance(ctx.policies().manhuntMinutes * 60_000L + 50);
        manhunts.tick();
        var hunt = ctx.manhunts().read().find("MH-1");
        assertEquals("COOLED", hunt.status);
        assertFalse(ctx.bolos().read().records.isEmpty(),
                "the cooled hunt became a standing arrest warrant");
    }

    @Test
    void capturingEveryRunnerClosesTheHunt() {
        TestPlayer runner = server.add("runner");
        var r = new ManhuntRecord.Runner();
        r.uuid = runner.uuid().toString(); r.name = "runner";
        manhunts.begin(List.of(r), "minecraft:overworld", 0, 64, 0, "", "cell:c1");
        manhunts.runnerCaptured(runner.uuid().toString());
        manhunts.tick();
        var hunt = ctx.manhunts().read().find("MH-1");
        assertEquals("CAPTURED", hunt.status);
    }

    @Test
    void callOffNeedsAnOfficer() {
        TestPlayer runner = server.add("runner");
        var r = new ManhuntRecord.Runner();
        r.uuid = runner.uuid().toString(); r.name = "runner";
        manhunts.begin(List.of(r), "minecraft:overworld", 0, 64, 0, "", "cell:c1");
        TestPlayer civ = server.add("civ");
        assertFalse(manhunts.callOff(civ, "MH-1", "bored"));
        TestPlayer officer = server.add("lt");
        var s = players.state(officer);
        s.rank = Rank.LOCOTENENT.level();
        players.save(officer.uuid(), s);
        assertTrue(manhunts.callOff(officer, "MH-1", "comisar a grațiat"));
        assertEquals("CALLED_OFF", ctx.manhunts().read().find("MH-1").status);
    }

    @Test
    void huntsSurviveRestartReads() {
        var r = new ManhuntRecord.Runner();
        r.uuid = "u1"; r.name = "runner";
        manhunts.begin(List.of(r), "minecraft:overworld", 0, 64, 0, "", "cell:c1");
        var reread = ctx.manhunts().read();
        assertEquals(1, reread.hunts.size());
        assertEquals("MH-1", reread.hunts.get(0).id);
        assertNotNull(ctx.manhunts().read().activeForRunner("u1"));
    }
}
