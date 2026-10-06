package com.dwurdy.straja.application.service;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.ArtifactLicenseType;
import com.dwurdy.straja.domain.model.CheckpointMode;
import com.dwurdy.straja.domain.model.CrossingOutcome;
import com.dwurdy.straja.domain.model.InspectionLedgerEntry;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.PushbackPoint;
import com.dwurdy.straja.domain.model.SnapshotItem;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestDeepScan;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #248 / #245 M3 — the gate contract at crossing level: a crude mark arrests
 * on sight, a malformed/unregistered one is seized to evidence + offense +
 * system BOLO, and anything format-plausible walks the machine. The scan is
 * policy-gated and sealed transporter stock is exempt.
 */
class CheckpointArtifactScanTest {

    private static final String DIM = "minecraft:overworld";
    private static final String GUN = "minecraft:iron_sword";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private TestDeepScan deepScan;
    private BoloService bolos;
    private SeizureService seizure;
    private CheckpointService checkpoints;
    private TestPlayer player;

    private static SnapshotItem marked(String slot, String id, String mark, String serial) {
        var s = new SnapshotItem(slot, id, 1, "", "");
        if (serial != null) s.data.put(ArtifactRegistryService.SERIAL_KEY, serial);
        if (mark != null) s.data.put(ArtifactRegistryService.MARK_KEY, mark);
        return s;
    }

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        deepScan = (TestDeepScan) ctx.deepScan();
        deepScan.server = server;
        var players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        var prison = new PrisonService(ctx, players, audit, custody);
        seizure = new SeizureService(ctx, audit);
        prison.useSeizure(seizure);
        bolos = new BoloService(ctx, players, audit);
        prison.useBolos(bolos);
        var storage = new StorageService(ctx, players, audit, bolos, prison);
        storage.useCustody(custody);
        var personnel = new PersonnelService(new SavedStores.Personnel(
                name -> new MemoryStore()), clock, new Fakes.SeqIds());
        checkpoints = new CheckpointService(ctx, audit, prison, storage, bolos,
                personnel, custody);
        checkpoints.useSeizure(seizure);
        // Wire the detection surface the runtime normally late-binds.
        var memory = new HashMap<String, MemoryStore>();
        var repo = new SavedStores.ArtifactRegistry(
                name -> memory.computeIfAbsent(name, k -> new MemoryStore()));
        var registry = new ArtifactRegistryService(repo, clock, new Fakes.SeqIds(),
                ctx.policies(), players, audit);
        var forgery = new ForgeryService(ctx.policies(),
                com.dwurdy.straja.application.port.out.RollSource.system(), clock,
                registry, players, audit);
        checkpoints.useForgeryDetection(new ForgeryDetectionService(
                ctx, forgery, registry, players, audit));
        ctx.policies().artifactRegulatedItemIds =
                new java.util.ArrayList<>(List.of(GUN));
        ctx.policies().artifactScanAtGates = true;
        player = server.add("traveler");
        player.x = 5; player.y = 60; player.z = -5;
    }

    private void scan() {
        server.tick = (server.tick / 5 + 1) * 5;
        checkpoints.tick();
    }

    private void move(double x, double y, double z) {
        player.x = x; player.y = y; player.z = z;
        scan();
    }

    private LawCheckpointRecord saveSite(String id, CheckpointMode mode) {
        var record = new LawCheckpointRecord();
        record.id = id;
        record.name = id;
        record.dimension = DIM;
        record.mode = mode;
        record.pushback = PushbackPoint.at(DIM, 0, 60, -10, 180f);
        record.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        record.stage2 = LawBounds.of(DIM, 0, 55, 11, 10, 65, 20);
        var store = ctx.lawCheckpoints().read();
        store.put(record);
        ctx.lawCheckpoints().write(store);
        scan();
        return record;
    }

    private InspectionLedgerEntry lastEntry() {
        var entries = ctx.inspectionLedger().read().entries();
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    private boolean inCell() {
        var rec = ctx.prisonerRegister().read().prisoner(player.uuid.toString());
        return rec != null && rec.status == PrisonerStatus.IN_CELL;
    }

    @Test
    void crudeMarkArrestsOnSightWithForgeryAudit() {
        saveSite("border", CheckpointMode.ARREST);
        deepScan.inventories.put(player.uuid, List.of(
                marked("main:0", GUN, "#GUNS4U-13", null)));
        player.inventory.slots.set(0, new ItemView(GUN, 1, 64, Map.of()));

        move(5, 60, 5);

        assertTrue(inCell(), "a crude carrier must be arrested at the lane");
        assertTrue(ctx.audit().tail(30).stream().anyMatch(
                        e -> "forgery_detected".equals(e.action)
                                && e.details.contains("artifact_forgery")),
                "the arrest still records the forgery offense in audit");
    }

    @Test
    void malformedMarkSeizesFlagsAndHuntsTheCarrier() {
        saveSite("border", CheckpointMode.ARREST);
        deepScan.inventories.put(player.uuid, List.of(
                marked("main:0", "straja:identity_card", "#RC-15_", null)));
        player.inventory.slots.set(0, new ItemView("straja:identity_card", 1, 64, Map.of()));
        player.inventory.slots.set(1, new ItemView("minecraft:bread", 2, 64, Map.of()));

        move(5, 60, 5);

        var entry = lastEntry();
        assertEquals(CrossingOutcome.WARN, entry.outcome,
                "a flagged (non-crude) carrier is warned through stage 1");
        assertTrue(player.inventory.stackAt(0).isEmpty(),
                "the flagged card is seized to evidence");
        assertEquals("minecraft:bread", player.inventory.stackAt(1).id(),
                "unrelated stock stays");
        assertFalse(bolos.active().isEmpty(),
                "the carrier leaves the gate wanted");
        assertTrue(ctx.audit().tail(30).stream().anyMatch(
                        e -> "forgery_detected".equals(e.action)
                                && e.details.contains("document_forgery")),
                "document forgeries record the document offense");
        assertFalse(inCell());
    }

    @Test
    void plausibleMarksWalkTheMachineByDesign() {
        saveSite("border", CheckpointMode.ARREST);
        deepScan.inventories.put(player.uuid, List.of(
                marked("main:0", GUN, "#RC-999999", "RC-999999")));

        move(5, 60, 5);

        assertEquals(CrossingOutcome.PASS, lastEntry().outcome);
        assertTrue(bolos.active().isEmpty());
        assertFalse(inCell());
    }

    @Test
    void unregisteredRegulatedStockFlagsWithoutArrest() {
        saveSite("border", CheckpointMode.ARREST);
        deepScan.inventories.put(player.uuid, List.of(
                new SnapshotItem("main:0", GUN, 1, "", "")));
        player.inventory.slots.set(0, new ItemView(GUN, 1, 64, Map.of()));

        move(5, 60, 5);

        assertEquals(CrossingOutcome.WARN, lastEntry().outcome);
        assertTrue(player.inventory.stackAt(0).isEmpty());
        assertFalse(bolos.active().isEmpty());
        assertFalse(inCell());
    }

    @Test
    void sealedTransporterStockIsNotUnregistered() {
        saveSite("border", CheckpointMode.ARREST);
        ctx.policies().artifactRegulatedItemIds.add(
                ArtifactRegistryService.CRATE_ITEM);
        var crate = new SnapshotItem("main:0",
                ArtifactRegistryService.CRATE_ITEM, 1, "", "");
        crate.data.put(ArtifactRegistryService.SEAL_BY, "some-transporter-uuid");
        crate.data.put(ArtifactRegistryService.SEAL_ID, "SEAL-0001");
        deepScan.inventories.put(player.uuid, List.of(crate));

        move(5, 60, 5);

        assertEquals(CrossingOutcome.PASS, lastEntry().outcome,
                "a properly sealed crate is not unregistered stock");
    }

    @Test
    void theGateScanHonoursItsPolicySwitch() {
        saveSite("border", CheckpointMode.ARREST);
        ctx.policies().artifactScanAtGates = false;
        deepScan.inventories.put(player.uuid, List.of(
                marked("main:0", GUN, "#GUNS4U-13", null)));

        move(5, 60, 5);

        assertEquals(CrossingOutcome.PASS, lastEntry().outcome);
        assertTrue(bolos.active().isEmpty());
    }
}
