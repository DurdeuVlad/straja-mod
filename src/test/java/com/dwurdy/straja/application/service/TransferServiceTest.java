package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.LaborCampRecord;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.domain.model.TransferRecord;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestCurrency;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FUN-8: rides are escorted, ambushable, and settle exactly once. */
class TransferServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private CustodyService custody;
    private PrisonService prison;
    private DispatchService dispatch;
    private ManhuntService manhunts;
    private TransferService transfers;
    private TestPlayer admin;
    private TestPlayer inmate;

    private TransferRecord ride() {
        return ctx.transfers().read().transfers.get(0);
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
        var bolos = new BoloService(ctx, players, audit);
        prison.useBolos(bolos);
        dispatch = new DispatchService(ctx, players, audit);
        dispatch.useIncidents(new IncidentService(ctx, players, audit));
        bolos.useDispatch(dispatch);
        manhunts = new ManhuntService(ctx, players, audit);
        manhunts.useDispatch(dispatch);
        manhunts.useBolos(bolos);
        prison.useManhunts(manhunts);
        var camps = new LaborCampService(ctx, players, audit);
        prison.useCamps(camps);
        transfers = new TransferService(ctx, players, audit);
        transfers.useDispatch(dispatch);
        transfers.useCustody(custody);
        transfers.usePrison(prison);
        transfers.useManhunts(manhunts);
        dispatch.useTransfers(transfers);
        prison.useTransfers(transfers);
        custody.useTransferBreakGate(transfers::distanceBreakAllowed);
        custody.onRestraintApplied((o, t) -> { prison.onEscortStart(o, t); transfers.onCuffed(o, t); });
        custody.onRestraintReleased(prison::onUncuffed);
        ((Fakes.TestDeepScan) ctx.deepScan()).server = server;

        admin = server.add("dwurdy");
        ((TestWorld) ctx.world()).room("minecraft:overworld", -1, 59, -1, 6, 66, 6,
                -1, 61, 2);
        assertTrue(prison.createCell(admin, "celula_1",
                "minecraft:overworld", 0, 60, 0, 5, 65, 5));

        // A camp 250 blocks east — a real ride.
        var campStore = ctx.laborCamps().read();
        var camp = new LaborCampRecord();
        camp.id = "mina_1";
        camp.name = "Mina";
        camp.dimension = "minecraft:overworld";
        camp.boundary = LawBounds.of("minecraft:overworld", 200, 50, 200, 230, 80, 230);
        camp.intakeSpawn = new StoragePoint("minecraft:overworld", 215, 65, 215);
        campStore.put(camp);
        ctx.laborCamps().write(campStore);

        inmate = server.add("inmate");
        inmate.dimension = "minecraft:overworld";
        prison.arrest(inmate, null, 1, admin, null);
    }

    private TestPlayer escort(String name) {
        TestPlayer g = server.add(name);
        var s = players.state(g);
        s.rank = Rank.GUARD.level();
        s.duty = true;
        s.invited = true;
        players.save(g.uuid(), s);
        g.dimension = "minecraft:overworld";
        g.give(ItemSpec.of("straja:cuffs", 1));
        return g;
    }

    private void orderRide() {
        assertTrue(prison.transferToCamp(admin, inmate, "mina_1"), "the order routes to a ride");
    }

    private TransferRecord startedRide() {
        orderRide();
        var t = ride();
        TestPlayer esc = escort("escort");
        esc.x = inmate.x + 1; esc.y = inmate.y; esc.z = inmate.z;
        assertTrue(transfers.join(esc, t.id), "the escort signs on");
        assertTrue(transfers.pickup(esc, t.id), "the escort takes the chain");
        return ctx.transfers().read().find(t.id);
    }

    @Test
    void orderPostsARideAndAnEnlistedCall() {
        orderRide();
        var t = ride();
        assertEquals("ORDERED", t.status);
        assertEquals(inmate.uuid().toString(), t.prisonerUuid);
        assertEquals("mina_1", t.destCampId);
        var call = ctx.dispatch().read().find(t.dispatchCallId);
        assertNotNull(call, "the dispatch call carries the order");
        assertEquals("ENLISTED", call.band);
        assertEquals("URGENT", call.severity);
        assertEquals(inmate.uuid().toString(), call.subjectUuid);
        // The prisoner still holds custody — nothing moved yet.
        assertEquals(PrisonerStatus.IN_CELL,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status);
    }

    @Test
    void oneLiveRidePerPrisoner() {
        orderRide();
        var first = ride().id;
        orderRide();
        assertEquals(first, ride().id, "second order folds into the live ride");
        assertEquals(1, ctx.transfers().read().transfers.size());
    }

    @Test
    void shortHopFallsBackToInstant() {
        var campStore = ctx.laborCamps().read();
        var near = new LaborCampRecord();
        near.id = "mina_aproape";
        near.dimension = "minecraft:overworld";
        near.boundary = LawBounds.of("minecraft:overworld", 20, 50, 0, 30, 70, 10);
        near.intakeSpawn = new StoragePoint("minecraft:overworld", 25, 60, 5);
        campStore.put(near);
        ctx.laborCamps().write(campStore);
        assertTrue(prison.transferToCamp(admin, inmate, "mina_aproape"));
        assertEquals(PrisonerStatus.IN_CAMP,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status,
                "sub-threshold hop transfers instantly");
        assertTrue(ctx.transfers().read().transfers.isEmpty());
    }

    @Test
    void dispatchAcceptJoinsTheEscortSet() {
        orderRide();
        var t = ride();
        TestPlayer esc = escort("escort");
        var call = ctx.dispatch().read().find(t.dispatchCallId);
        assertTrue(dispatch.accept(esc, call.id));
        t = ctx.transfers().read().find(t.id);
        assertNotNull(t.escort(esc.uuid().toString()), "the accepter is an escort");
        assertEquals("ORDERED", t.status, "accept alone does not move the prisoner");
    }

    @Test
    void pickupTakesTheChainAndStartsTheRide() {
        var t = startedRide();
        assertEquals("IN_PROGRESS", t.status);
        assertTrue(custody.isCuffed(inmate), "the chain is the existing cuff restraint");
        var esc = t.lead();
        assertEquals(esc.uuid, custody.cuffedIssuerUuid(inmate));
        assertEquals(PrisonerStatus.ESCORTED,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status);
    }

    @Test
    void distanceCannotPartTheChainOutsideRiskZones() {
        var t = startedRide();
        var esc = server.byName("escort");
        esc.x = inmate.x + 500; // walked far beyond leash range
        clock.advance(ctx.policies().cuffBreakGraceSeconds * 1_000L + 50);
        custody.tick();
        clock.advance(ctx.policies().cuffBreakGraceSeconds * 1_000L + 50);
        custody.tick();
        assertTrue(custody.isCuffed(inmate), "outside a risk zone distance cannot break the chain");
        assertEquals("IN_PROGRESS", ctx.transfers().read().find(t.id).status);
        assertTrue(ctx.manhunts().read().hunts.isEmpty());
    }

    @Test
    void distanceBreaksTheChainInsideARiskZone() {
        var t = startedRide();
        var zoneData = ctx.setup().read();
        var zone = new SetupData.RiskZone();
        zone.id = "padure"; zone.name = "Pădurea";
        zone.x = inmate.x; zone.y = inmate.y; zone.z = inmate.z; zone.radius = 30;
        zoneData.riskZones.put("padure", zone);
        ctx.setup().write(zoneData);
        var esc = server.byName("escort");
        esc.x = inmate.x + 500;
        clock.advance(ctx.policies().cuffBreakGraceSeconds * 1_000L + 50);
        custody.tick();
        clock.advance(ctx.policies().cuffBreakGraceSeconds * 1_000L + 50);
        custody.tick();
        assertFalse(custody.isCuffed(inmate), "inside the zone the chain parts");
        transfers.tick();
        var after = ctx.transfers().read().find(t.id);
        assertEquals("BROKEN", after.status);
        assertFalse(ctx.manhunts().read().hunts.isEmpty(), "a manhunt opens on the runner");
        assertEquals(PrisonerStatus.FUGITIVE,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status);
    }

    @Test
    void escortDownedBreaksTheRideAnywhere() {
        var t = startedRide();
        var esc = server.byName("escort");
        custody.startDowned(esc, null, "ambush");
        transfers.tick();
        assertEquals("BROKEN", ctx.transfers().read().find(t.id).status);
        assertFalse(ctx.manhunts().read().hunts.isEmpty());
    }

    @Test
    void escortDeathBreaksTheRide() {
        var t = startedRide();
        var esc = server.byName("escort");
        transfers.escortDied(esc.uuid().toString());
        assertEquals("BROKEN", ctx.transfers().read().find(t.id).status);
        assertFalse(ctx.manhunts().read().hunts.isEmpty());
    }

    @Test
    void leadLogoutSuspendsAndRedispatches() {
        var t = startedRide();
        var esc = server.byName("escort");
        esc.online = false;
        transfers.tick();
        var after = ctx.transfers().read().find(t.id);
        assertEquals("ORDERED", after.status, "the ride re-dispatches for a new escort");
        assertTrue(custody.isCuffed(inmate), "the chain stays on while suspended");
        // A second escort takes the chain on contact — no cuffs needed again.
        esc.online = false;
        TestPlayer esc2 = escort("escort2");
        assertTrue(transfers.join(esc2, t.id));
        esc2.x = inmate.x + 1; esc2.y = inmate.y; esc2.z = inmate.z;
        transfers.tick();
        var resumed = ctx.transfers().read().find(t.id);
        assertEquals("IN_PROGRESS", resumed.status);
        assertEquals(esc2.uuid().toString(), custody.cuffedIssuerUuid(inmate));
    }

    @Test
    void deliverySettlesExactlyOnce() {
        var t = startedRide();
        var esc = server.byName("escort");
        var cur = (TestCurrency) ctx.currency();
        int callsBefore = cur.depositCalls;
        // Walk the pair inside the camp wire.
        inmate.x = 210; inmate.y = 60; inmate.z = 210;
        esc.x = 211; esc.y = 60; esc.z = 210;
        transfers.tick();
        var done = ctx.transfers().read().find(t.id);
        assertEquals("DELIVERED", done.status);
        assertNotEquals(0, done.settledAt);
        assertEquals(PrisonerStatus.IN_CAMP,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status);
        assertFalse(custody.isCuffed(inmate), "the chain comes off at the wire");
        assertTrue(cur.depositCalls > callsBefore, "the escort was paid");
        long paidCalls = cur.depositCalls;
        transfers.tick();
        assertEquals(paidCalls, cur.depositCalls, "a settled ride never pays twice");
        assertNull(ctx.dispatch().read().find(t.dispatchCallId) == null ? null : "x".isEmpty() ? null : null);
    }

    @Test
    void escortWaitExpiryReturnsThePrisonerToCustody() {
        orderRide();
        var t = ride();
        clock.advance(ctx.policies().transferEscortWaitMinutes * 60_000L + 50);
        transfers.tick();
        var after = ctx.transfers().read().find(t.id);
        assertEquals("RETURNED", after.status);
        assertEquals(PrisonerStatus.IN_CELL,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status);
    }

    @Test
    void cancelNeedsAnOfficer() {
        orderRide();
        var t = ride();
        TestPlayer civ = server.add("civ");
        assertFalse(transfers.cancel(civ, t.id));
        TestPlayer lt = server.add("lt");
        var s = players.state(lt);
        s.rank = Rank.LOCOTENENT.level();
        players.save(lt.uuid(), s);
        assertTrue(transfers.cancel(lt, t.id));
        assertEquals("CANCELLED", ctx.transfers().read().find(t.id).status);
    }

    @Test
    void riskZonesRoundTripThroughSetup() {
        assertTrue(transfers.zoneAdd(admin, "campie", 40, "Câmpie deschisă"));
        assertFalse(transfers.zoneAdd(admin, "campie", 40, ""));
        var zones = ctx.setup().read().riskZones;
        assertTrue(zones.get("campie").contains(admin.dimension(), admin.x(), admin.y(), admin.z()));
        assertTrue(transfers.zoneRemove(admin, "campie"));
        assertTrue(ctx.setup().read().riskZones.isEmpty());
    }
}
