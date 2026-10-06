package com.dwurdy.straja.application.service;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.ArtifactLicenseType;
import com.dwurdy.straja.domain.model.ArtifactRecord;
import com.dwurdy.straja.domain.model.ArtifactRegistryStore;
import com.dwurdy.straja.domain.model.ArtifactStatus;
import com.dwurdy.straja.domain.model.BoloAuthority;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.SnapshotItem;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestDeepScan;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.MemoryStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #248 / #245 M3 — the keen-eye ladder: gate machine reads only the physical
 * mark (N4/N5/unregistered fail, N3+ pass by design), while the inspector's
 * registry cross-check burns N3 at JUNIOR, N2 at VETERAN, and N1 at EXPERT —
 * mirrored by the on-duty officer parity (guard/quiz-trained/commissioner).
 */
class ForgeryDetectionServiceTest {

    private static final String GUN = "minecraft:iron_sword";
    private static final String CARD = "straja:identity_card";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private StrajaPolicies policies;
    private PlayerService players;
    private ArtifactRegistryService registry;
    private SavedStores.ArtifactRegistry repo;
    private AuditService audit;
    private ForgeryDetectionService detection;
    private SeizureService seizure;
    private BoloService bolos;
    private TestPlayer traveler;

    private static SnapshotItem item(String slot, String id, Map<String, String> data) {
        var s = new SnapshotItem(slot, id, 1, "", "");
        s.data.putAll(data);
        return s;
    }

    private static Map<String, String> marked(String serial) {
        var m = new HashMap<String, String>();
        m.put(ArtifactRegistryService.SERIAL_KEY, serial);
        m.put(ArtifactRegistryService.MARK_KEY, "#" + serial);
        return m;
    }

    private static Map<String, String> rawMark(String marking) {
        var m = new HashMap<String, String>();
        m.put(ArtifactRegistryService.MARK_KEY, marking);
        return m;
    }

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        policies = ctx.policies();
        policies.artifactRegulatedItemIds = new ArrayList<>(List.of(GUN, CARD));
        policies.artifactSerialPrefix = "RC-";
        players = new PlayerService(ctx);
        var memory = new HashMap<String, MemoryStore>();
        repo = new SavedStores.ArtifactRegistry(
                name -> memory.computeIfAbsent(name, k -> new MemoryStore()));
        audit = new AuditService(ctx);
        registry = new ArtifactRegistryService(repo, clock, new Fakes.SeqIds(),
                policies, players, audit);
        var rng = new java.util.Random(7L);
        var forgery = new ForgeryService(policies,
                new com.dwurdy.straja.application.port.out.RollSource() {
                    @Override public int nextInt(int bound) { return rng.nextInt(bound); }
                    @Override public double nextDouble() { return rng.nextDouble(); }
                }, clock, registry, players, audit);
        detection = new ForgeryDetectionService(ctx, forgery, registry, players, audit);
        seizure = new SeizureService(ctx, audit);
        bolos = new BoloService(ctx, players, audit);
        traveler = server.add("traveler");
    }

    /** Allocates an authentic serial by writing the record directly. */
    private ArtifactRecord plant(String serial, String itemId, String holderUuid) {
        ArtifactRegistryStore store = repo.read();
        var rec = new ArtifactRecord();
        rec.serial = serial;
        rec.marking = "#" + serial;
        rec.claimedSerial = serial;
        rec.itemId = itemId;
        rec.holderUuid = holderUuid;
        rec.holderName = "owner";
        rec.registeredAt = clock.nowMillis();
        rec.pendingUntil = 0; // matured
        store.artifacts.put(serial, rec);
        long number = Long.parseLong(serial.substring(policies.artifactSerialPrefix.length()));
        store.nextSerial = Math.max(store.nextSerial, number + 1);
        repo.write(store);
        return rec;
    }

    /** Registers an FRG shadow for a claimed serial — the KNOWN_FORGED path. */
    private void plantShadow(String claimedSerial) {
        ArtifactRegistryStore store = repo.read();
        var rec = new ArtifactRecord();
        rec.serial = "FRG-" + store.nextForgery;
        rec.claimedSerial = claimedSerial;
        rec.itemId = CARD;
        rec.status = ArtifactStatus.FORGED.name();
        rec.forgeryTier = "N3";
        store.artifacts.put(rec.serial, rec);
        store.claimedIndex.put(claimedSerial, rec.serial);
        store.nextForgery++;
        repo.write(store);
    }

    // ------------------------------------------------------------ the machine

    @Test
    void machineFlagsUnregisteredRegulatedStock() {
        var scan = detection.machineScan(List.of(
                item("main:3", GUN, Map.of())));
        // worst() collapses to FLAGGED (seize+BOLO, not arrest) — the
        // per-finding verdict keeps the finer UNREGISTERED classification.
        assertEquals(ForgeryDetectionService.Verdict.UNREGISTERED,
                scan.findings().get(0).verdict());
        assertEquals(ForgeryDetectionService.Verdict.FLAGGED, scan.worst());
        assertEquals("artifact_forgery", scan.findings().get(0).offense());
    }

    @Test
    void machineShredsAbsurdMarksAsCrude() {
        var scan = detection.machineScan(List.of(
                item("main:1", GUN, rawMark("#GUNS4U-13"))));
        assertEquals(ForgeryDetectionService.Verdict.CRUDE, scan.worst());
    }

    @Test
    void machineFlagsMalformedButNotAsArrest() {
        var scan = detection.machineScan(List.of(
                item("main:2", CARD, rawMark("#RC-15_"))));
        assertEquals(ForgeryDetectionService.Verdict.FLAGGED, scan.worst());
        assertEquals("document_forgery", scan.findings().get(0).offense());
    }

    @Test
    void machinePassesPlausibleMarksByDesign() {
        // N1..N3 all carry format-plausible "#RC-<digits>" marks — the machine
        // can never see them; that is the whole point of the inspector role.
        for (String serial : List.of("RC-7", "RC-1042", "RC-999999")) {
            var scan = detection.machineScan(List.of(
                    item("main:0", CARD, marked(serial))));
            assertTrue(scan.clean(), serial + " should walk past the machine");
        }
    }

    @Test
    void unregulatedItemsNeverConcernTheScanner() {
        var scan = detection.machineScan(List.of(
                item("main:0", "minecraft:bread", Map.of()),
                item("main:1", "minecraft:apple", Map.of())));
        assertTrue(scan.clean());
    }

    @Test
    void aMarkOnUnregulatedStockIsItselfEvidence() {
        // ArtifactMark only ever lands via registration or the forge — a
        // marked apple is tampered evidence, not lunch: the scanner burns it.
        var scan = detection.machineScan(List.of(
                item("main:1", "minecraft:apple", rawMark("#whatever"))));
        assertEquals(ForgeryDetectionService.Verdict.CRUDE, scan.worst());
    }

    // ------------------------------------------------------------ the inspector ladder

    @Test
    void juniorCatchesFarFetchedClaims() {
        // N3: format-plausible serial far beyond allocation.
        var out = detection.inspect(List.of(
                item("main:0", CARD, marked("RC-999999"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.JUNIOR);
        assertEquals(1, out.findings().size());
        assertEquals(ForgeryDetectionService.Verdict.SUSPECT_CLAIM,
                out.findings().get(0).verdict());
    }

    @Test
    void juniorPassesNearMissClaims() {
        plant("RC-1", CARD, "someone-else");
        // N2: serial just ahead of the registry's allocation — a veteran tell.
        String nearMiss = "RC-" + (registry.nextSerialNumber() + 3);
        assertTrue(nearMiss.length() < 12, "stay inside the near-miss band");
        var junior = detection.inspect(List.of(
                item("main:0", CARD, marked(nearMiss))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.JUNIOR);
        assertTrue(junior.clean(), "a junior cannot read the near-miss");
        var veteran = detection.inspect(List.of(
                item("main:0", CARD, marked(nearMiss))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.VETERAN);
        assertEquals(1, veteran.findings().size());
    }

    @Test
    void veteranReadsTheVeryNextSerialAsNearMiss() {
        // Claiming the exact serial the office is about to allocate is the
        // strongest N2-shaped tell — it must not slip through on an
        // exclusive bound.
        plant("RC-1", CARD, "someone-else");
        String next = "RC-" + registry.nextSerialNumber();
        var veteran = detection.inspect(List.of(
                item("main:0", CARD, marked(next))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.VETERAN);
        assertEquals(1, veteran.findings().size(),
                "the exact next serial is inside the veteran's near-miss band");
        var junior = detection.inspect(List.of(
                item("main:0", CARD, marked(next))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.JUNIOR);
        assertTrue(junior.clean(), "a junior still cannot read it");
    }

    @Test
    void expertReadsRetiredSerials() {
        var rec = plant("RC-11", CARD, traveler.uuid().toString());
        rec.status = com.dwurdy.straja.domain.model.ArtifactStatus.REVOKED.name();
        var store = repo.read();
        store.artifacts.put(rec.serial, rec);
        repo.write(store);
        var veteran = detection.inspect(List.of(
                item("main:0", CARD, marked("RC-11"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.VETERAN);
        assertTrue(veteran.clean(), "a veteran does not read revoked marks");
        var expert = detection.inspect(List.of(
                item("main:0", CARD, marked("RC-11"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.EXPERT);
        assertEquals(1, expert.findings().size(),
                "a presented dead mark burns at the expert ceiling");
    }

    @Test
    void markOnlyItemsStillGetCrossChecked() {
        // A forged item whose serial key was stripped keeps a well-formed
        // mark — the implied claim must still reach the registry check.
        plant("RC-5", GUN, "the-real-owner");
        var expert = detection.inspect(List.of(
                item("main:0", CARD, rawMark("#RC-5"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.EXPERT);
        assertEquals(1, expert.findings().size(),
                "the mark's implied serial resolves to the conflicting record");
    }

    @Test
    void expertReadsLedgerConflicts() {
        // N1: a real allocated serial — but the card claims it while the
        // record binds a weapon and a different holder.
        plant("RC-5", GUN, "the-real-owner");
        var veteran = detection.inspect(List.of(
                item("main:0", CARD, marked("RC-5"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.VETERAN);
        assertTrue(veteran.clean(), "veterans do not read holder conflicts");
        var expert = detection.inspect(List.of(
                item("main:0", CARD, marked("RC-5"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.EXPERT);
        assertEquals(1, expert.findings().size());
    }

    @Test
    void anyInspectorCatchesKnownForgedClaims() {
        plantShadow("RC-4242");
        var out = detection.inspect(List.of(
                item("main:0", CARD, marked("RC-4242"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.JUNIOR);
        assertEquals(ForgeryDetectionService.Verdict.SUSPECT_CLAIM,
                out.findings().get(0).verdict());
    }

    @Test
    void authenticMarksSurviveEveryCeiling() {
        plant("RC-9", CARD, traveler.uuid().toString());
        for (var expertise : ForgeryDetectionService.Expertise.values()) {
            var out = detection.inspect(List.of(
                    item("main:0", CARD, marked("RC-9"))),
                    traveler.uuid().toString(), expertise);
            assertTrue(out.clean(), "authentic must survive " + expertise);
        }
    }

    @Test
    void noneExpertiseIsTheMachineOnly() {
        var out = detection.inspect(List.of(
                item("main:0", CARD, marked("RC-999999"))),
                traveler.uuid().toString(),
                ForgeryDetectionService.Expertise.NONE);
        assertTrue(out.clean());
    }

    // ------------------------------------------------------------ officer parity

    @Test
    void officerLadderMirrorsTheNpcCeilings() {
        policies.commissionerName = "comisar";
        var duty = server.add("dutyguard");
        var trained = server.add("trained");
        var comisar = server.add("comisar");
        var civilian = server.add("civilian");
        enlist(duty, com.dwurdy.straja.domain.model.Rank.STAGIAR.level(), false);
        enlist(trained, com.dwurdy.straja.domain.model.Rank.STAGIAR.level(), true);
        enlist(comisar, com.dwurdy.straja.domain.model.Rank.INSPECTOR.level(), true);
        assertEquals(ForgeryDetectionService.Expertise.JUNIOR,
                detection.officerExpertise(duty));
        assertEquals(ForgeryDetectionService.Expertise.VETERAN,
                detection.officerExpertise(trained));
        assertEquals(ForgeryDetectionService.Expertise.EXPERT,
                detection.officerExpertise(comisar));
        assertEquals(ForgeryDetectionService.Expertise.NONE,
                detection.officerExpertise(civilian));
    }

    @Test
    void commissionerReadsAtExpertEvenOffDuty() {
        // Rank authority, not duty state: the Comisar inspects at EXPERT
        // whenever they run the check.
        policies.commissionerName = "comisar";
        var comisar = server.add("comisar");
        enlist(comisar, com.dwurdy.straja.domain.model.Rank.INSPECTOR.level(), true);
        var state = ctx.players().read(comisar.uuid);
        state.duty = false;
        ctx.players().write(comisar.uuid, state);
        assertEquals(ForgeryDetectionService.Expertise.EXPERT,
                detection.officerExpertise(comisar));
    }

    @Test
    void inspectionRefusesWhenTheTravelerIsOutOfReach() {
        policies.commissionerName = "comisar";
        var comisar = server.add("comisar");
        enlist(comisar, com.dwurdy.straja.domain.model.Rank.INSPECTOR.level(), true);
        comisar.x = 0; comisar.z = 0;
        traveler.x = 500; traveler.z = 500;
        var outcome = detection.officerInspect(comisar, traveler, seizure, bolos);
        assertEquals("too_far", outcome.narration());
        assertTrue(bolos.active().isEmpty(), "no enforcement fires across the map");
    }

    /** Enrolls a member at rank, on duty, optionally quiz-trained. */
    private void enlist(TestPlayer p, int rank, boolean quizPassed) {
        var state = ctx.players().read(p.uuid());
        state.rank = rank;
        state.duty = true;
        state.quizPassed = quizPassed;
        ctx.players().write(p.uuid(), state);
    }

    // ------------------------------------------------------------ enforcement

    @Test
    void npcInspectionSeizesOnlyTheFlaggedStockAndHunts() {
        plant("RC-9", CARD, traveler.uuid().toString());    // authentic → stays
        var deepScan = (TestDeepScan) ctx.deepScan();
        deepScan.inventories.put(traveler.uuid(), List.of(
                item("main:0", GUN, Map.of()),              // unregistered → caught
                item("main:1", CARD, marked("RC-9"))));
        traveler.inventory.slots.set(0, new ItemView(GUN, 1, 64, Map.of()));
        traveler.inventory.slots.set(1, new ItemView(CARD, 1, 64, marked("RC-9")));

        var outcome = detection.npcInspect(traveler,
                ForgeryDetectionService.Expertise.EXPERT, "Inspectorul",
                seizure, bolos);

        assertTrue(outcome.flagged());
        assertEquals(1, outcome.seized(), "only the flagged stack leaves");
        assertTrue(traveler.inventory.stackAt(0).isEmpty());
        assertEquals(CARD, traveler.inventory.stackAt(1).id());
        assertFalse(bolos.active().isEmpty());
        assertEquals(BoloAuthority.ARREST_AUTHORIZED,
                bolos.active().get(0).authority);
        assertTrue(ctx.audit().tail(20).stream()
                .anyMatch(r -> r.action.equals("forgery_detected")
                        && r.details.contains("artifact_forgery")));
    }

    @Test
    void cleanInspectionNarratesAndTouchesNothing() {
        plant("RC-9", CARD, traveler.uuid().toString());
        var deepScan = (TestDeepScan) ctx.deepScan();
        deepScan.inventories.put(traveler.uuid(), List.of(
                item("main:0", CARD, marked("RC-9"))));
        var outcome = detection.npcInspect(traveler,
                ForgeryDetectionService.Expertise.EXPERT, "Inspectorul",
                seizure, bolos);
        assertFalse(outcome.flagged());
        assertTrue(bolos.active().isEmpty());
        assertTrue(traveler.told("în regulă"));
    }

    @Test
    void staleScanNeverSeizesAMovedStack() {
        var deepScan = (TestDeepScan) ctx.deepScan();
        deepScan.inventories.put(traveler.uuid(), List.of(
                item("main:0", GUN, Map.of())));
        // The flagged gun already left slot 0 — a different item sits there.
        traveler.inventory.slots.set(0, new ItemView("minecraft:bread", 4, 64, Map.of()));
        var outcome = detection.npcInspect(traveler,
                ForgeryDetectionService.Expertise.EXPERT, "Inspectorul",
                seizure, bolos);
        assertEquals(0, outcome.seized(), "the scan is stale — nothing moves");
        assertEquals("minecraft:bread", traveler.inventory.stackAt(0).id());
    }

    @Test
    void officerInspectionRefusesCiviliansAndReadsAtRank() {
        var civilian = server.add("civilian2");
        var out = detection.officerInspect(civilian, traveler, seizure, bolos);
        assertFalse(out.flagged());
        assertEquals("not_on_duty", out.narration());
        assertTrue(civilian.told("ofițer aflat în serviciu"));
    }

    @Test
    void flagForgeryIsIdempotentAndArrestAuthorized() {
        bolos.flagForgery(traveler.uuid().toString(), traveler.name(),
                "falsificare: document_forgery");
        bolos.flagForgery(traveler.uuid().toString(), traveler.name(),
                "falsificare: document_forgery");
        assertEquals(1, bolos.active().size());
        assertEquals("system", bolos.active().get(0).issuerName);
    }
}
