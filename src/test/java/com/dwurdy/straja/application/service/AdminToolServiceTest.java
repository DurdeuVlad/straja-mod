package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.AdminToolsUseCase;
import com.dwurdy.straja.domain.model.AdminToolStore;
import com.dwurdy.straja.domain.model.SetupChecklist;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AT-001..006: physical admin tools — holder gate, per-player pending state,
 * logout clearing, and convergence with the canonical command/service writes.
 */
class AdminToolServiceTest {
    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private GuardService guards;
    private NpcAdminService npcs;
    private AdminToolService tools;
    private TestPlayer comisar;
    private TestPlayer civil;
    private String npcUuid;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(1_000_000L), Fakes.policies());
        players = new PlayerService(ctx);
        AuditService audit = new AuditService(ctx);
        EquipmentService equipment = new EquipmentService(ctx);
        CustodyService custody = new CustodyService(ctx, players, audit);
        guards = new GuardService(ctx, players, audit, equipment);
        PrisonService prison = new PrisonService(ctx, players, audit, custody);
        npcs = new NpcAdminService(ctx);
        tools = new AdminToolService(ctx, players, npcs, guards, prison);
        comisar = server.add("dwurdy");
        civil = server.add("civil1");
        npcUuid = UUID.randomUUID().toString();
    }

    private String registerNpc(String role) {
        assertTrue(npcs.register(npcUuid, role).ok());
        return npcUuid;
    }

    private AdminToolStore.HolderState state(TestPlayer player) {
        return ctx.adminTools().read().holders.get(player.uuid().toString());
    }

    // ---------------------------------------------------------------- AT-001 foundation

    @Test
    void wrongHolderIsRefusedEverywhereWithATell() {
        registerNpc("secretary");
        tools.giveToolKit(civil);
        assertNull(tools.npcWandMenu(civil, npcUuid));
        tools.npcAssign(civil, npcUuid, "jailer");
        tools.npcRename(civil, npcUuid, "x");
        tools.npcSetSkin(civil, npcUuid, "x");
        tools.npcRemove(civil, npcUuid);
        tools.npcShowRecord(civil, npcUuid);
        tools.patrolClick(civil, "minecraft:overworld", 1, 2, 3);
        tools.patrolFinish(civil);
        tools.patrolStatus(civil);
        assertNull(tools.surveyMenu(civil, "minecraft:overworld", 1, 2, 3));
        tools.surveyStamp(civil, "hq");
        tools.surveyStampAllMissing(civil);
        assertFalse(tools.cellClick(civil, "minecraft:overworld", 1, 2, 3));
        tools.cellConfirm(civil);
        tools.cloneCapture(civil, npcUuid);
        assertNull(tools.cloneSpawnAt(civil, "minecraft:overworld", 1, 2, 3));
        tools.registerClone(civil, npcUuid);
        tools.cloneClear(civil);

        assertTrue(civil.told("Instrumentele administrative"),
                "every tool entry point must deny a normal player with a tell");
        assertTrue(ctx.adminTools().read().holders.isEmpty(),
                "a denied holder must never accumulate pending state");
        assertEquals("secretary", ctx.npcs().read().npcs.get(npcUuid).role,
                "a stolen wand cannot mutate the registry");
        assertFalse(civil.inventory.slots.stream().anyMatch(s -> !s.isEmpty()),
                "the kit is never handed to a normal player");
    }

    @Test
    void opHolderPassesTheGate() {
        TestPlayer op = server.add("op1");
        op.op = true;

        tools.giveToolKit(op);

        long given = op.inventory.slots.stream().filter(s -> !s.isEmpty()).count();
        assertEquals(6, given, "the kit is all six admin tools");
    }

    @Test
    void pendingStateIsPerHolderAndClearsOnLogout() {
        TestPlayer other = server.add("op2");
        other.op = true;
        tools.patrolClick(comisar, "minecraft:overworld", 1, 64, 1);
        tools.patrolClick(comisar, "minecraft:overworld", 2, 64, 1);
        tools.patrolClick(other, "minecraft:overworld", 9, 64, 9);

        assertEquals(2, state(comisar).route.size());
        assertEquals(1, state(other).route.size());

        tools.clearState(comisar);
        assertNull(state(comisar), "logout drops the holder's pending state");
        assertEquals(1, state(other).route.size(), "other holders keep their state");

        tools.clearState(comisar); // idempotent, no-op when nothing pending
        assertNull(state(comisar));
    }

    @Test
    void kitGivesEveryAdminTool() {
        tools.giveToolKit(comisar);
        var ids = comisar.inventory.slots.stream().filter(s -> !s.isEmpty()).map(s -> s.id()).toList();
        assertTrue(ids.containsAll(java.util.List.of(
                "straja:npc_wand", "straja:patrol_wand", "straja:survey_rod",
                "straja:npc_cloner", "straja:prison_marker", "straja:room_marker")));
    }

    // ---------------------------------------------------------------- AT-002 NPC wand

    @Test
    void wandMenuOnlyForRegisteredNpcs() {
        assertNull(tools.npcWandMenu(comisar, npcUuid));
        assertTrue(comisar.told("NPC Straja înregistrat"));

        registerNpc("secretary");
        var menu = tools.npcWandMenu(comisar, npcUuid);
        assertNotNull(menu);
        var ids = menu.actions().stream().map(AdminToolsUseCase.MenuAction::actionId).toList();
        for (String role : NpcAdminService.ROLE_ORDER) {
            assertTrue(ids.contains("tool-npc-assign:" + npcUuid + "-" + role),
                    "menu offers assign for role " + role);
        }
        assertTrue(ids.contains("tool-npc-rename:" + npcUuid));
        assertTrue(ids.contains("tool-npc-skin:" + npcUuid));
        assertTrue(ids.contains("tool-npc-remove:" + npcUuid));
        assertTrue(ids.contains("tool-npc-record:" + npcUuid));
    }

    @Test
    void wandMutationsConvergeOnTheRegistry() {
        registerNpc("secretary");

        tools.npcAssign(comisar, npcUuid, "jailer");
        assertEquals("jailer", ctx.npcs().read().npcs.get(npcUuid).role);

        tools.npcRename(comisar, npcUuid, "Gardianul");
        assertEquals("Gardianul", ctx.npcs().read().npcs.get(npcUuid).displayName);

        tools.npcSetSkin(comisar, npcUuid, "jailer_alt");
        assertEquals("jailer_alt", ctx.npcs().read().npcs.get(npcUuid).skin);

        tools.npcShowRecord(comisar, npcUuid);
        assertTrue(comisar.told("Fișa NPC " + npcUuid));

        // remove runs only behind the second confirm token — service-side the
        // confirm path is just npcRemove; a stale second click refuses cleanly.
        tools.npcRemove(comisar, npcUuid);
        assertFalse(ctx.npcs().read().npcs.containsKey(npcUuid));
        assertFalse(tools.npcStillRegistered(npcUuid));

        comisar.messages.clear();
        tools.npcRemove(comisar, npcUuid);
        assertTrue(comisar.told("nu mai este înregistrat"),
                "a stale confirm never reaches the repository");
    }

    @Test
    void wandMutationsRefuseUnregisteredEntities() {
        tools.npcAssign(comisar, npcUuid, "jailer");
        tools.npcRename(comisar, npcUuid, "x");
        tools.npcSetSkin(comisar, npcUuid, "x");
        assertTrue(comisar.told("nu mai este înregistrat"));
        assertTrue(ctx.npcs().read().npcs.isEmpty());
    }

    // ---------------------------------------------------------------- AT-003 patrol wand

    @Test
    void patrolWaypointsToggleAndFinishWritesThroughCheckpointPath() {
        String dim = "minecraft:overworld";
        tools.patrolClick(comisar, dim, 1, 64, 1);
        tools.patrolClick(comisar, dim, 2, 64, 1);
        tools.patrolClick(comisar, dim, 1, 64, 1); // re-click removes
        assertTrue(comisar.told("Punct eliminat"));
        assertEquals(1, state(comisar).route.size());

        tools.patrolClick(comisar, dim, 1, 64, 1);
        tools.patrolClick(comisar, dim, 3, 64, 3);
        tools.patrolClick(comisar, dim, 4, 64, 4);
        assertEquals(4, state(comisar).route.size());

        tools.patrolClick(comisar, dim, 5, 64, 5); // full — refused
        assertTrue(comisar.told("Traseul este complet"));
        assertEquals(4, state(comisar).route.size());

        tools.patrolFinish(comisar);
        var setup = ctx.setup().read();
        assertTrue(state(comisar) == null || state(comisar).route.isEmpty(),
                "finish clears the pending route");
        for (int i = 0; i < 4; i++) {
            var point = setup.checkpoints.get(i);
            assertTrue(point.isPlaced(), "checkpoint " + i + " written");
        }
        assertEquals(2, setup.checkpoints.get(0).x.intValue(),
                "waypoint order maps to checkpoint order");
        assertEquals(dim, setup.checkpoints.get(0).dimension);
        assertTrue(comisar.told("Traseul de patrulare a fost salvat"));
    }

    @Test
    void patrolFinishRequiresExactlyTheCheckpointSlots() {
        String dim = "minecraft:overworld";
        tools.patrolClick(comisar, dim, 1, 64, 1);
        tools.patrolFinish(comisar);
        assertTrue(comisar.told("exact 4 puncte"),
                "short routes are refused");
        assertFalse(ctx.setup().read().checkpoints.get(0).isPlaced(),
                "a refused finish writes nothing");
    }

    @Test
    void patrolFinishRejectsCrossDimensionRoutes() {
        String dim = "minecraft:overworld";
        for (int i = 1; i <= 4; i++) tools.patrolClick(comisar, dim, i, 64, i);
        comisar.dimension = "minecraft:nether";

        tools.patrolFinish(comisar);

        assertTrue(comisar.told("dimensiuni"));
        assertFalse(ctx.setup().read().checkpoints.get(0).isPlaced());
        assertEquals(4, state(comisar).route.size(), "route survives a refused finish");
    }

    // ---------------------------------------------------------------- AT-004 survey rod

    @Test
    void surveyStampsPendingTargetAndConvergesWithChecklist() {
        String dim = "minecraft:overworld";
        var missing0 = SetupChecklist.missingLocations(ctx.setup().read());
        assertEquals(SetupData.LOCATION_KEYS.length, missing0.size());

        var menu = tools.surveyMenu(comisar, dim, 10, 65, 10);
        assertNotNull(menu);
        assertEquals(SetupData.LOCATION_KEYS.length + 1, menu.actions().size(),
                "one button per location key plus stamp-all-missing");
        assertTrue(menu.actions().stream()
                .anyMatch(a -> a.label().contains("secretary (lipsește)")));
        assertTrue(tools.surveyPending(comisar));

        tools.surveyStamp(comisar, "secretary");
        var setup = ctx.setup().read();
        var loc = setup.locations.get("secretary");
        assertNotNull(loc);
        assertEquals(10, (int) loc.x);
        assertEquals(65, (int) loc.y);
        assertEquals(dim, loc.dimension);
        assertFalse(SetupChecklist.missingLocations(setup).contains("secretary"),
                "the checklist and the rod can never disagree");

        tools.surveyMenu(comisar, dim, 20, 66, 20);
        tools.surveyStamp(comisar, "secretary"); // re-stamp overwrites
        assertEquals(20, (int) ctx.setup().read().locations.get("secretary").x);
    }

    @Test
    void surveyStampAllMissingStampsEveryGapAtTheTarget() {
        String dim = "minecraft:overworld";
        tools.surveyMenu(comisar, dim, 30, 64, 30);
        tools.surveyStampAllMissing(comisar);

        var setup = ctx.setup().read();
        assertTrue(SetupChecklist.missingLocations(setup).isEmpty(),
                "stamp-all-missing is the setup-here behavior");
        assertEquals(30, (int) setup.locations.get("hq").x);
    }

    @Test
    void surveyStampFailsClosedWithoutAPendingTarget() {
        assertFalse(tools.surveyPending(comisar));
        tools.surveyStamp(comisar, "hq");
        assertTrue(comisar.told("Nicio poziție"));
        assertNull(ctx.setup().read().locations.get("hq"));
    }

    @Test
    void surveyTargetInAnotherDimensionIsRejected() {
        tools.surveyMenu(comisar, "minecraft:overworld", 10, 65, 10);
        comisar.dimension = "minecraft:nether";
        tools.surveyStamp(comisar, "hq");
        assertTrue(comisar.told("altă dimensiune"));
        assertNull(ctx.setup().read().locations.get("hq"));
    }

    // ---------------------------------------------------------------- AT-005 boundary marker

    private void solidCellShell() {
        // Interior 2×3×3 at (1..2, 1..3, 1..3); shell at (0..3, 0..4, 0..4)
        // with a two-block door at (0, 1..2, 0).
        TestWorld world = (TestWorld) ctx.world();
        world.room("minecraft:overworld", 0, 0, 0, 3, 4, 4, 0, 1, 0);
    }

    @Test
    void cellTwoClickFlowConfirmsAndNormalizes() {
        solidCellShell();
        String dim = "minecraft:overworld";

        assertFalse(tools.cellClick(comisar, dim, 2, 3, 3)); // corner A (reversed)
        assertFalse(tools.cellSelectionReady(comisar));
        assertTrue(tools.cellClick(comisar, dim, 1, 1, 1));  // corner B
        assertTrue(tools.cellSelectionReady(comisar));

        tools.cellConfirm(comisar);

        var cells = ctx.prison().read().cells;
        assertEquals(1, cells.size(), "confirm registers the cell");
        var cell = cells.get(0);
        assertEquals(1, cell.minX);
        assertEquals(2, cell.maxX);
        assertEquals(1, cell.minY);
        assertEquals(3, cell.maxY, "corner order is normalized");
        assertNull(state(comisar) == null ? null : state(comisar).cellCornerA,
                "the selection is consumed by the confirm");
    }

    @Test
    void cellConfirmWithoutBothCornersIsStale() {
        tools.cellConfirm(comisar);
        assertTrue(comisar.told("cele două colțuri"));
        assertTrue(ctx.prison().read().cells.isEmpty());
    }

    @Test
    void cellCornersCannotCrossDimensions() {
        String dim = "minecraft:overworld";
        tools.cellClick(comisar, dim, 1, 1, 1);
        assertFalse(tools.cellClick(comisar, "minecraft:nether", 2, 3, 3));
        assertTrue(comisar.told("dimensiuni"));
        assertFalse(tools.cellSelectionReady(comisar));
        assertNull(state(comisar).cellCornerA, "cross-dimension resets the selection");
    }

    // ---------------------------------------------------------------- AT-006 NPC cloner

    @Test
    void clonerCaptureSpawnRegisterRoundTrip() {
        registerNpc("jailer");
        npcs.setName(npcUuid, "Temnițarul");
        npcs.setSkin(npcUuid, "jailer_alt");

        tools.cloneCapture(comisar, npcUuid);
        assertTrue(comisar.told("Șablon capturat"));
        assertNotNull(state(comisar).cloneTemplate);

        var template = tools.cloneSpawnAt(comisar, "minecraft:overworld", 5, 64, 5);
        assertNotNull(template);
        assertEquals("jailer", template.role());
        assertEquals("Temnițarul", template.displayName());
        assertEquals("jailer_alt", template.skin());

        String cloneUuid = UUID.randomUUID().toString();
        tools.registerClone(comisar, cloneUuid);
        var record = ctx.npcs().read().npcs.get(cloneUuid);
        assertNotNull(record, "the copy lands in the same registry as npc spawn");
        assertEquals("jailer", record.role);
        assertEquals("Temnițarul", record.displayName);
        assertEquals("jailer_alt", record.skin);

        tools.cloneClear(comisar);
        assertNull(state(comisar).cloneTemplate);
        assertNull(tools.cloneSpawnAt(comisar, "minecraft:overworld", 6, 64, 6));
        assertTrue(comisar.told("Niciun șablon"));
    }

    @Test
    void clonerRejectsCrossDimensionSpawnTargets() {
        registerNpc("jailer");
        tools.cloneCapture(comisar, npcUuid);
        assertNotNull(state(comisar).cloneTemplate);

        comisar.dimension = "minecraft:nether";
        assertNull(tools.cloneSpawnAt(comisar, "minecraft:overworld", 5, 64, 5));
        assertTrue(comisar.told("altă dimensiune"),
                "a spawn target outside the holder's dimension fails closed");
        assertNotNull(state(comisar).cloneTemplate,
                "a refused spawn keeps the captured template");
    }

    @Test
    void nullHolderIsRefusedWithoutThrowing() {
        assertFalse(tools.isToolHolder(null));
        assertDoesNotThrow(() -> {
            tools.giveToolKit(null);
            tools.npcWandMenu(null, npcUuid);
            tools.patrolClick(null, "minecraft:overworld", 1, 2, 3);
            tools.patrolFinish(null);
            tools.surveyMenu(null, "minecraft:overworld", 1, 2, 3);
            tools.surveyStampAllMissing(null);
            tools.cellClick(null, "minecraft:overworld", 1, 2, 3);
            tools.cellConfirm(null);
            tools.cloneCapture(null, npcUuid);
            tools.cloneSpawnAt(null, "minecraft:overworld", 1, 2, 3);
            tools.registerClone(null, npcUuid);
            tools.cloneClear(null);
        }, "a missing holder must fail closed, never NPE");
        assertTrue(ctx.adminTools().read().holders.isEmpty());
    }

    @Test
    void clonerRejectsUnregisteredTargetsAndEmptySlots() {
        tools.cloneCapture(comisar, npcUuid);
        assertTrue(comisar.told("NPC Straja înregistrat"));
        assertNull(state(comisar));

        assertNull(tools.cloneSpawnAt(comisar, "minecraft:overworld", 5, 64, 5));
        tools.registerClone(comisar, UUID.randomUUID().toString());
        assertTrue(ctx.npcs().read().npcs.isEmpty(),
                "a forged register call without a captured template writes nothing");

        tools.cloneClear(comisar);
        assertTrue(comisar.told("Niciun șablon"));
    }
}
