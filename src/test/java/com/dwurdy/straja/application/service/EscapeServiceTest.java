package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.RollSource;
import com.dwurdy.straja.application.port.out.WorldGateway;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestCurrency;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FUN-9: dig, pick, bribe, blend — every path preps, detects, funnels. */
class EscapeServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private CustodyService custody;
    private PrisonService prison;
    private DispatchService dispatch;
    private ManhuntService manhunts;
    private EscapeService escapes;
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
        bolos.useDispatch(dispatch);
        manhunts = new ManhuntService(ctx, players, audit);
        manhunts.useDispatch(dispatch);
        manhunts.useBolos(bolos);
        prison.useManhunts(manhunts);
        prison.useCamps(new LaborCampService(ctx, players, audit));
        escapes = new EscapeService(ctx, players, audit);
        escapes.usePrison(prison);
        escapes.useCustody(custody);
        escapes.useDispatch(dispatch);
        escapes.useManhunts(manhunts);
        prison.useEscapes(escapes);
        prison.useRolls(new RollSource() { // always the top of the range — jam fires
            @Override public int nextInt(int bound) { return 0; }
            @Override public double nextDouble() { return 0.0; }
        });
        ((Fakes.TestDeepScan) ctx.deepScan()).server = server;

        admin = server.add("dwurdy");
        ((TestWorld) ctx.world()).room("minecraft:overworld", -1, 59, -1, 6, 66, 6,
                -1, 61, 2);
        assertTrue(prison.createCell(admin, "celula_1",
                "minecraft:overworld", 0, 60, 0, 5, 65, 5));
        inmate = server.add("inmate");
        inmate.dimension = "minecraft:overworld";
        prison.arrest(inmate, null, 1, admin, null);
        inmate.x = 2; inmate.y = 61; inmate.z = 2; // inside the cell
    }

    private TestPlayer guard(String name, double x, double z) {
        TestPlayer g = server.add(name);
        var s = players.state(g);
        s.rank = Rank.GUARD.level();
        s.duty = true;
        s.invited = true;
        players.save(g.uuid(), s);
        g.dimension = "minecraft:overworld";
        g.x = x; g.y = 61; g.z = z;
        return g;
    }

    private void swingDig() {
        // A tunnel-eligible wall block inside the cell's padded bounds.
        ((TestWorld) ctx.world()).set("minecraft:overworld", 0, 61, 2,
                new WorldGateway.BlockInfo("minecraft:dirt", false, false, true));
        clock.advance(ctx.policies().escapeDigCooldownMs + 10);
        escapes.onDigSwing(inmate, "minecraft:overworld", 0, 61, 2);
    }

    @Test
    void digProgressPersistsAndCompletes() {
        swingDig();
        var store = ctx.escapes().read();
        assertEquals(1, store.tunnels.size());
        assertEquals(1, store.tunnels.get(0).progress);
        // Cooldown eats an instant second swing.
        escapes.onDigSwing(inmate, "minecraft:overworld", 0, 61, 2);
        assertEquals(1, ctx.escapes().read().tunnels.get(0).progress);
        // A fresh service over the same store sees the same progress —
        // restart persistence, not a memory map.
        var again = new EscapeService(ctx, players, audit);
        again.usePrison(prison);
        again.useCustody(custody);
        again.useDispatch(dispatch);
        again.useManhunts(manhunts);
        while (ctx.escapes().read().tunnels.get(0).progress
                < ctx.policies().escapeDigClicks) {
            clock.advance(ctx.policies().escapeDigCooldownMs + 10);
            again.onDigSwing(inmate, "minecraft:overworld", 0, 61, 2);
        }
        var world = (TestWorld) ctx.world();
        assertTrue(world.removedBlocks.contains("minecraft:overworld,0,61,2"),
                "the finished tunnel removes the block");
        var calls = ctx.dispatch().read().calls;
        assertTrue(calls.stream().anyMatch(c -> "ESCAPE".equals(c.sourceType)),
                "the completed dig pings the dispatch board");
    }

    @Test
    void digRefusesWithAGuardPatrolling() {
        guard("patrula", 3, 2); // inside the detect radius of the door
        swingDig();
        assertTrue(ctx.escapes().read().tunnels.isEmpty(),
                "a nearby guard interrupts the dig before it starts");
    }

    @Test
    void digIgnoresForeignBlocksAndFreePlayers() {
        ((TestWorld) ctx.world()).set("minecraft:overworld", 0, 61, 2,
                new WorldGateway.BlockInfo("minecraft:stone", false, false, true));
        assertFalse(escapes.onDigSwing(inmate, "minecraft:overworld", 0, 61, 2),
                "stone is not diggable");
        TestPlayer free = server.add("free");
        free.dimension = "minecraft:overworld";
        ((TestWorld) ctx.world()).set("minecraft:overworld", 0, 61, 2,
                new WorldGateway.BlockInfo("minecraft:dirt", false, false, true));
        assertFalse(escapes.onDigSwing(free, "minecraft:overworld", 0, 61, 2),
                "only the cell's own prisoner digs it");
    }

    @Test
    void inspectionFindsTunnelAndConfiscatesContraband() {
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        swingDig();
        clock.advance(ctx.policies().escapeDigCooldownMs + 10);
        escapes.onDigSwing(inmate, "minecraft:overworld", 0, 61, 2);
        var g = guard("inspector", 20, 20);
        g.x = 0; g.y = 61; g.z = 2; // at the cell door
        long before = ctx.prison().read()
                .activeSentenceFor(inmate.uuid().toString()).remainingActiveMs;
        assertTrue(escapes.inspect(g));
        var store = ctx.escapes().read();
        assertEquals(1, store.tunnels.size());
        assertNotEquals(0, store.tunnels.get(0).discoveredAt, "the tunnel is marked found");
        long after = ctx.prison().read()
                .activeSentenceFor(inmate.uuid().toString()).remainingActiveMs;
        assertEquals(before + ctx.policies().tunnelFoundPenaltyMinutes * 60_000L, after,
                "the digger earned time on the sentence");
        assertEquals(0, inmate.inventory().countOf("straja:lockpick"),
                "contraband leaves with the inspector");
        assertTrue(ctx.dispatch().read().calls.stream()
                .anyMatch(c -> "ESCAPE".equals(c.sourceType)));
    }

    @Test
    void prisonerCannotFillOwnTunnel() {
        swingDig();
        assertFalse(escapes.onBlockPlaced(inmate, "minecraft:overworld", 0, 61, 2),
                "the digger covering the hole hides the block, not the record");
        assertEquals(1, ctx.escapes().read().tunnels.size(),
                "the tunnel stays findable on inspection");
    }

    @Test
    void blendBackInsideCostsNothing() {
        inmate.give(ItemSpec.of("straja:guard_uniform", 1));
        inmate.x = 50; inmate.z = 50;
        prison.tick(); // breach suppressed — blend armed
        assertTrue(escapes.isBlending(inmate.uuid().toString()));
        // The prisoner slips back into the cell inside the grace window.
        inmate.x = 2; inmate.z = 2;
        guard("rond", inmate.x + 2, inmate.z); // a guard passes the cell
        escapes.tick();
        assertFalse(escapes.isBlending(inmate.uuid().toString()),
                "the cover dissolves once the prisoner is back");
        assertEquals(PrisonerStatus.IN_CELL,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status,
                "no warrant for a prisoner who made it back");
    }

    @Test
    void reinforcedLockAlsoJamsAPick() {
        var data = ctx.prison().read();
        data.cell("celula_1").lockTier = "REINFORCED";
        ctx.prison().write(data);
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        inmate.selectedSlot = 1;
        var cell = ctx.prison().read().cell("celula_1");
        prison.onCellTamper(inmate, cell.dimension, cell.doorX, cell.doorY, cell.doorZ);
        clock.advance(ctx.policies().cellPickSeconds * 1_000L + 50);
        prison.tick();
        cell = ctx.prison().read().cell("celula_1");
        assertTrue(cell.jammedUntil > clock.nowMillis(),
                "reinforced locks jam too — the pick never skips risk");
    }

    @Test
    void fillClearsTheTunnel() {
        swingDig();
        var g = guard("mester", 40, 40);
        assertTrue(escapes.onBlockPlaced(g, "minecraft:overworld", 0, 61, 2));
        assertTrue(ctx.escapes().read().tunnels.isEmpty());
    }

    @Test
    void lockpickOpensTheDoorOnAWeakLock() {
        var data = ctx.prison().read();
        data.cell("celula_1").lockTier = "WEAK";
        ctx.prison().write(data);
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        inmate.selectedSlot = 1; // slot 0 holds the arrest book
        var cell = ctx.prison().read().cell("celula_1");
        assertTrue(prison.onCellTamper(inmate, cell.dimension, cell.doorX, cell.doorY, cell.doorZ),
                "the lockpick opens a pick channel on the door");
        clock.advance(ctx.policies().cellPickSeconds * 1_000L + 50);
        prison.tick();
        cell = ctx.prison().read().cell("celula_1");
        assertTrue(cell.brokenUntil > clock.nowMillis(), "the picked lock stands open");
        assertEquals(0, cell.jammedUntil);
    }

    @Test
    void lockpickJamsAStandardLock() {
        inmate.give(ItemSpec.of("straja:lockpick", 1));
        inmate.selectedSlot = 1; // slot 0 holds the arrest book
        var cell = ctx.prison().read().cell("celula_1");
        prison.onCellTamper(inmate, cell.dimension, cell.doorX, cell.doorY, cell.doorZ);
        clock.advance(ctx.policies().cellPickSeconds * 1_000L + 50);
        prison.tick();
        cell = ctx.prison().read().cell("celula_1");
        assertTrue(cell.jammedUntil > clock.nowMillis(), "the botched pick jams the lock");
        assertEquals(0, cell.brokenUntil, "a jam never opens the door");
        // And the jam refuses the next tool outright.
        TestPlayer c = server.add("breaker");
        c.give(ItemSpec.of("straja:bolt_cutters", 1));
        c.selectedSlot = 0;
        assertTrue(prison.onCellTamper(c, cell.dimension, cell.doorX, cell.doorY, cell.doorZ));
        assertTrue(c.told("Broasca e blocată"), "the jam is flagged to the next tamperer");
        assertTrue(ctx.dispatch().read().calls.stream()
                .anyMatch(c2 -> "ESCAPE".equals(c2.sourceType)), "the jam pings dispatch");
    }

    @Test
    void bribePostTakesCoinsAndOpensTheDoor() {
        escapes.postAdd(admin, "poarta", 25, 6, "Poarta de serviciu");
        inmate.x = admin.x; inmate.y = admin.y; inmate.z = admin.z; // at the post
        ((TestCurrency) ctx.currency()).balance = 100;
        assertTrue(escapes.bribe(inmate));
        assertEquals(75, ((TestCurrency) ctx.currency()).balance, "the post took the price");
        var cell = ctx.prison().read().cell("celula_1");
        assertTrue(cell.brokenUntil > clock.nowMillis(), "the bribed guard opens the door");
        assertTrue(ctx.dispatch().read().calls.stream()
                .noneMatch(c -> "ESCAPE".equals(c.sourceType)),
                "an unwatched bribe stays quiet");
    }

    @Test
    void bribePostWitnessedRaisesACall() {
        escapes.postAdd(admin, "poarta", 25, 6, "Poarta");
        inmate.x = admin.x; inmate.y = admin.y; inmate.z = admin.z;
        var w = guard("martor", admin.x + 2, admin.z); // a real guard watches the exchange
        w.y = admin.y; // 3D distance — same height as the post
        ((TestCurrency) ctx.currency()).balance = 100;
        assertTrue(escapes.bribe(inmate));
        assertTrue(ctx.dispatch().read().calls.stream()
                .anyMatch(c -> "ESCAPE".equals(c.sourceType)),
                "a witnessed bribe pings the board");
    }

    @Test
    void bribeOfferToAGuardAcceptsAndPays() {
        var g = guard("strajer", inmate.x + 2, inmate.z);
        ((TestCurrency) ctx.currency()).balance = 100;
        assertTrue(escapes.bribe(inmate));
        assertEquals(1, escapes.pendingOffers());
        assertTrue(escapes.bribeAnswer(g, true));
        var cell = ctx.prison().read().cell("celula_1");
        assertTrue(cell.brokenUntil > clock.nowMillis(), "the corrupt guard opens the door");
        assertTrue(g.told("§6Ai luat") || g.told("B"), "the guard is paid");
    }

    @Test
    void bribeDeclineReportsThePrisoner() {
        var g = guard("cinstit", inmate.x + 2, inmate.z);
        ((TestCurrency) ctx.currency()).balance = 100;
        assertTrue(escapes.bribe(inmate));
        assertTrue(escapes.bribeAnswer(g, false));
        assertTrue(ctx.dispatch().read().calls.stream()
                .anyMatch(c -> "ESCAPE".equals(c.sourceType)
                        && c.subjectUuid.equals(inmate.uuid().toString())),
                "a declined bribe reports the attempt");
        assertEquals(100, ((TestCurrency) ctx.currency()).balance, "no coins moved");
    }

    @Test
    void bribeExpiresUnanswered() {
        guard("instruc", inmate.x + 2, inmate.z);
        ((TestCurrency) ctx.currency()).balance = 100;
        assertTrue(escapes.bribe(inmate));
        clock.advance(ctx.policies().bribeOfferMs + 50);
        escapes.tick();
        assertEquals(0, escapes.pendingOffers());
    }

    @Test
    void blendDelaysTheAlarmButGuardsDetect() {
        inmate.give(ItemSpec.of("straja:guard_uniform", 1));
        // Walk out of the cell bounds — the bounds check meets the uniform.
        inmate.x = 50; inmate.z = 50;
        prison.tick();
        assertEquals(PrisonerStatus.IN_CELL,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status,
                "the uniform delays the alarm");
        assertTrue(escapes.isBlending(inmate.uuid().toString()));
        // A real guard walks up to the blended prisoner.
        guard("vigilent", inmate.x + 3, inmate.z);
        escapes.tick();
        assertEquals(PrisonerStatus.FUGITIVE,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status,
                "detection ends the cover — warrant lands");
        assertEquals(0, inmate.inventory().countOf("straja:guard_uniform"),
                "the uniform is confiscated on detection");
        assertFalse(ctx.manhunts().read().hunts.isEmpty(), "a spotted blend opens a hunt");
    }

    @Test
    void blendGraceExpiresIntoAWarrant() {
        inmate.give(ItemSpec.of("straja:guard_uniform", 1));
        inmate.x = 500; inmate.z = 500; // far past any detect radius
        prison.tick();
        assertTrue(escapes.isBlending(inmate.uuid().toString()));
        clock.advance(ctx.policies().blendGraceSeconds * 1_000L + 50);
        escapes.tick();
        assertEquals(PrisonerStatus.FUGITIVE,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status,
                "the grace ends in a standing warrant");
    }

    @Test
    void noUniformMeansInstantWarrant() {
        inmate.x = 50; inmate.z = 50;
        prison.tick();
        assertEquals(PrisonerStatus.FUGITIVE,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status,
                "without the uniform the alarm is immediate");
    }

    @Test
    void campEscapeFlagsWantedDirectly() {
        // Camp custody: uniform or not, walking out of the perimeter is a
        // straight warrant — blend cover applies to cells only.
        var campStore = ctx.laborCamps().read();
        var camp = new com.dwurdy.straja.domain.model.LaborCampRecord();
        camp.id = "mina_1";
        camp.name = "Mina";
        camp.dimension = "minecraft:overworld";
        camp.boundary = com.dwurdy.straja.domain.model.LawBounds.of(
                "minecraft:overworld", 200, 50, 200, 230, 80, 230);
        camp.intakeSpawn = new com.dwurdy.straja.domain.model.StoragePoint(
                "minecraft:overworld", 215, 65, 215);
        campStore.put(camp);
        ctx.laborCamps().write(campStore);
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(inmate.uuid().toString());
        rec.status = PrisonerStatus.IN_CAMP;
        rec.assignedCampId = "mina_1";
        ctx.prisonerRegister().write(reg);
        var pData = ctx.prison().read();
        var sent = pData.activeSentenceFor(inmate.uuid().toString());
        pData.assignments.remove(sent.cellId);
        sent.cellId = "";
        ctx.prison().write(pData);
        inmate.give(ItemSpec.of("straja:guard_uniform", 1));
        inmate.x = 215; inmate.y = 65; inmate.z = 215; // inside the camp
        prison.tick();
        assertEquals(PrisonerStatus.IN_CAMP,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status);
        inmate.x = 500; inmate.z = 500; // past the perimeter
        clock.advance(1_100); // the sentence sweep runs at ~1s cadence
        prison.tick();
        assertEquals(PrisonerStatus.FUGITIVE,
                ctx.prisonerRegister().read().prisoner(inmate.uuid().toString()).status,
                "a camp breach is an immediate warrant — no blend window");
        assertFalse(escapes.isBlending(inmate.uuid().toString()));
    }

    @Test
    void postsRoundTripThroughSetup() {
        assertTrue(escapes.postAdd(admin, "turn", 40, 8, "Turnul"));
        assertFalse(escapes.postAdd(admin, "turn", 40, 8, ""));
        var posts = ctx.setup().read().bribePosts;
        assertTrue(posts.get("turn").contains(admin.dimension(), admin.x(), admin.y(), admin.z()));
        assertTrue(escapes.postRemove(admin, "turn"));
        assertTrue(ctx.setup().read().bribePosts.isEmpty());
    }
}
