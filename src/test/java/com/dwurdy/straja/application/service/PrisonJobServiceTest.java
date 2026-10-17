package com.dwurdy.straja.application.service;

import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FUN-10: quota jobs, market trades, IOU favors, standing, inspection calls. */
class PrisonJobServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private CustodyService custody;
    private PrisonService prison;
    private DispatchService dispatch;
    private PrisonJobService jobs;
    private TestPlayer admin;
    private TestPlayer inmate;

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
        prison.useCamps(new LaborCampService(ctx, players, audit));
        jobs = new PrisonJobService(ctx, players, audit);
        jobs.useDispatch(dispatch);
        ((Fakes.TestDeepScan) ctx.deepScan()).server = server;

        admin = server.add("dwurdy");
        ((TestWorld) ctx.world()).room("minecraft:overworld", -1, 59, -1, 6, 66, 6,
                -1, 61, 2);
        assertTrue(prison.createCell(admin, "celula_1",
                "minecraft:overworld", 0, 60, 0, 5, 65, 5));
        inmate = server.add("inmate");
        inmate.dimension = "minecraft:overworld";
        prison.arrest(inmate, null, 1, admin, null);
        inmate.x = 2; inmate.y = 61; inmate.z = 2;

        admin.x = 4; admin.y = 61; admin.z = 4;
        jobs.postAdd(admin, "bucatarie_1", "kitchen", 4.0, "");
    }

    private int laborOf(TestPlayer p) {
        var rec = ctx.prisonerRegister().read().prisoner(p.uuid().toString());
        return rec == null ? -1 : rec.laborAccount;
    }

    private int standingOf(TestPlayer p) {
        var rec = ctx.prisonerRegister().read().prisoner(p.uuid().toString());
        return rec == null ? Integer.MIN_VALUE : rec.standing;
    }

    // ------------------------------------------------------------ jobs

    @Test
    void clickShowsQuotaThenSettlesGoods() {
        // Empty bag: the post reads the quota, nothing moves.
        assertTrue(jobs.onPostClick(inmate, "minecraft:overworld", 4, 61, 4));
        assertEquals(0, laborOf(inmate));

        inmate.give(ItemSpec.of("minecraft:bread", 4));
        assertTrue(jobs.onPostClick(inmate, "minecraft:overworld", 4, 61, 4));
        assertEquals(6, laborOf(inmate), "kitchen pays 6 into the work account");
        assertEquals(1, standingOf(inmate));
        assertEquals(0, inmate.inventory().countOf("minecraft:bread"),
                "the quota goods are consumed");

        // Cooldown blocks the second turn-in until it lapses.
        inmate.give(ItemSpec.of("minecraft:bread", 4));
        jobs.onPostClick(inmate, "minecraft:overworld", 4, 61, 4);
        assertEquals(6, laborOf(inmate), "cooldown refuses a double pay");
        assertEquals(4, inmate.inventory().countOf("minecraft:bread"),
                "refused turn-in keeps the goods");

        clock.advance(601_000L); // past the 600s kitchen cooldown
        jobs.onPostClick(inmate, "minecraft:overworld", 4, 61, 4);
        assertEquals(12, laborOf(inmate));
        assertEquals(2, standingOf(inmate));
        assertEquals(2, ctx.jobs().read().completedCount(inmate.uuid().toString()));
    }

    @Test
    void distinctJobsHaveDistinctQuotas() {
        admin.x = 20; admin.z = 4;
        jobs.postAdd(admin, "fierarie_1", "smithing", 4.0, "");
        inmate.give(ItemSpec.of("minecraft:iron_ingot", 3));
        assertTrue(jobs.onPostClick(inmate, "minecraft:overworld", 20, 61, 4));
        assertEquals(10, laborOf(inmate), "smithing pays 10 — heavier quota, longer cooldown");
        assertEquals(2, standingOf(inmate));
        // A different post runs a different quota — wool, not ingots.
        admin.x = 30; admin.z = 4;
        jobs.postAdd(admin, "spalatorie_1", "laundry", 4.0, "");
        assertTrue(jobs.onPostClick(inmate, "minecraft:overworld", 30, 61, 4));
        assertEquals(10, laborOf(inmate),
                "laundry refuses iron — each post reads its own quota");
        var defs = jobs.jobDefs();
        assertTrue(defs.size() >= 3, "at least three jobs beyond mining");
        assertTrue(defs.stream().map(d -> d.id()).distinct().count() >= 3);
    }

    @Test
    void jobRunsPersistAcrossServiceRestart() {
        inmate.give(ItemSpec.of("minecraft:bread", 4));
        jobs.onPostClick(inmate, "minecraft:overworld", 4, 61, 4);
        var again = new PrisonJobService(ctx, players, audit);
        assertEquals(1, ctx.jobs().read().completedCount(inmate.uuid().toString()),
                "the durable store survives the service instance");
        // The same run token is never paid twice.
        assertEquals(6, laborOf(inmate));
        again.postList(admin); // smoke — no throw on the fresh instance
    }

    @Test
    void freePlayersCannotTurnIn() {
        var civilian = server.add("civ");
        civilian.dimension = "minecraft:overworld";
        civilian.x = 4; civilian.y = 61; civilian.z = 4;
        civilian.give(ItemSpec.of("minecraft:bread", 4));
        assertTrue(jobs.onPostClick(civilian, "minecraft:overworld", 4, 61, 4),
                "the post claims the click");
        assertEquals(4, civilian.inventory().countOf("minecraft:bread"),
                "a civilian's bag stays put — jobs are custody work");
    }

    // ------------------------------------------------------------ trades

    private TestPlayer secondPrisoner() {
        TestPlayer p = server.add("coleg");
        p.dimension = "minecraft:overworld";
        prison.arrest(p, null, 1, admin, null);
        p.x = 3; p.y = 61; p.z = 3;
        return p;
    }

    @Test
    void tradeSettlesGoodsForLaborCredit() {
        var buyer = secondPrisoner();
        // Give the buyer a work balance first.
        var reg = ctx.prisonerRegister().read();
        reg.prisoner(buyer.uuid().toString()).laborAccount = 50;
        ctx.prisonerRegister().write(reg);

        inmate.give(ItemSpec.of("straja:lockpick", 2));
        inmate.selectedSlot = 1; // slot 0 is the arrest book
        jobs.tradeOffer(inmate, "coleg", 20);
        jobs.tradeAnswer(buyer, true);

        assertEquals(0, inmate.inventory().countOf("straja:lockpick"),
                "the goods left the seller's hand");
        assertEquals(2, buyer.inventory().countOf("straja:lockpick"),
                "the buyer holds the goods");
        assertEquals(30, laborOf(buyer), "buyer paid 20");
        assertEquals(20, laborOf(inmate), "seller received 20");
        var trades = ctx.jobs().read().trades;
        assertEquals(1, trades.size());
        assertEquals("straja:lockpick", trades.get(0).itemId);
        assertFalse(trades.get(0).favor);
    }

    @Test
    void tradeDeclineReturnsNothing() {
        var buyer = secondPrisoner();
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        inmate.selectedSlot = 1;
        jobs.tradeOffer(inmate, "coleg", 5);
        jobs.tradeAnswer(buyer, false);
        assertEquals(1, inmate.inventory().countOf("straja:lockpick"),
                "a declined offer keeps the goods with the seller");
    }

    @Test
    void tradeExpiryNeedsAFridge() {
        var buyer = secondPrisoner();
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        inmate.selectedSlot = 1;
        jobs.tradeOffer(inmate, "coleg", 5);
        clock.advance(ctx.policies().tradeOfferMs + 1000);
        jobs.tradeAnswer(buyer, true);
        assertEquals(1, inmate.inventory().countOf("straja:lockpick"),
                "an expired offer settles nothing");
        assertTrue(ctx.jobs().read().trades.isEmpty());
    }

    @Test
    void tradeCannotSpendBeyondTheAccount() {
        var buyer = secondPrisoner();
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        inmate.selectedSlot = 1;
        jobs.tradeOffer(inmate, "coleg", 500);
        jobs.tradeAnswer(buyer, true);
        assertEquals(1, inmate.inventory().countOf("straja:lockpick"),
                "an unaffordable trade moves nothing");
        assertEquals(0, laborOf(inmate));
        assertEquals(0, buyer.inventory().countOf("straja:lockpick"));
    }

    @Test
    void favorTradeIssuesAPhysicalIou() {
        var buyer = secondPrisoner();
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        inmate.selectedSlot = 1;
        jobs.tradeOffer(inmate, "coleg", 0); // price 0 — a favor
        jobs.tradeAnswer(buyer, true);
        assertEquals(1, buyer.inventory().countOf("straja:lockpick"));
        assertEquals(1, inmate.inventory().countOf("straja:iou_note"),
                "the seller holds a signed note instead of credit");
        assertTrue(ctx.jobs().read().trades.get(0).favor);
    }

    @Test
    void iouSignsAPhysicalNote() {
        var creditor = secondPrisoner();
        jobs.iou(inmate, "coleg", "un rând de curățenie");
        assertEquals(1, creditor.inventory().countOf("straja:iou_note"),
                "the creditor holds the debtor's signed note");
        var stack = findIou(creditor);
        assertNotNull(stack);
        assertTrue(stack.data("snbt") != null
                        && stack.data("snbt").contains("inmate"),
                "the note names its debtor in its data");
    }

    private com.dwurdy.straja.application.port.out.ItemView findIou(TestPlayer p) {
        for (int i = 0; i < p.inventory().slots(); i++) {
            var s = p.inventory().stackAt(i);
            if (s != null && "straja:iou_note".equals(s.id())) return s;
        }
        return null;
    }

    // ------------------------------------------------------------ standing

    @Test
    void infractionsDropStanding() {
        inmate.give(ItemSpec.of("minecraft:bread", 4));
        jobs.onPostClick(inmate, "minecraft:overworld", 4, 61, 4);
        assertEquals(1, standingOf(inmate));
        jobs.penalizeStanding(inmate.uuid().toString(),
                ctx.policies().standingTunnelPenalty, "tunnel_found");
        assertEquals(1 - ctx.policies().standingTunnelPenalty, standingOf(inmate));
        // A fresh read sees the durable value — parole reads the same ledger.
        assertEquals(standingOf(inmate), jobs.standingOf(inmate.uuid().toString()));
    }

    // ------------------------------------------------------------ inspection calls

    @Test
    void inspectionCallRaisesAndSearchCloses() {
        var escapes = new EscapeService(ctx, players, audit);
        escapes.usePrison(prison);
        escapes.useCustody(custody);
        escapes.useDispatch(dispatch);
        escapes.useJobs(jobs);
        prison.useEscapes(escapes);

        ctx.policies().inspectionCallIntervalMinutes = 1;
        jobs.tick();
        assertTrue(ctx.dispatch().read().calls.stream().anyMatch(
                c -> "ESCAPE".equals(c.sourceType)
                        && c.sourceId.startsWith("inspect:")
                        && "OPEN".equals(c.status)),
                "the quota sweep raises a routine inspection call");

        // An on-duty guard searching that cell closes the call.
        var g = server.add("gardian");
        var s = players.state(g);
        s.rank = com.dwurdy.straja.domain.model.Rank.GUARD.level();
        s.duty = true;
        s.invited = true;
        players.save(g.uuid(), s);
        g.dimension = "minecraft:overworld";
        g.x = 2; g.y = 61; g.z = 2;
        assertTrue(escapes.inspect(g));
        assertTrue(ctx.dispatch().read().calls.stream().noneMatch(
                c -> c.sourceId.startsWith("inspect:")
                        && ("OPEN".equals(c.status) || "ACCEPTED".equals(c.status))),
                "the search closes the open inspection call");
    }

    @Test
    void inspectionCallSkipsWhenNoCells() {
        // No cells registered → nothing to point at → no call.
        var data = ctx.prison().read();
        data.cells.clear();
        ctx.prison().write(data);
        ctx.policies().inspectionCallIntervalMinutes = 1;
        jobs.tick();
        assertTrue(ctx.dispatch().read().calls.stream().noneMatch(
                c -> c.sourceId.startsWith("inspect:")));
    }
}
