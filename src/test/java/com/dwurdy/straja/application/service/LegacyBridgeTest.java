package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.BoloAuthority;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.CheckpointMode;
import com.dwurdy.straja.domain.model.CrossingOutcome;
import com.dwurdy.straja.domain.model.InspectionLedgerEntry;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.PushbackPoint;
import com.dwurdy.straja.domain.model.SnapshotItem;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import com.dwurdy.straja.support.MemoryStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KJS-1 legacy bridge coverage — the deployed KubeJS roots
 * ({@code strajacheckpoint}, {@code strajaprison}) keep working over the
 * native services: nearest-site resolution, stage verbs, system wanted
 * marks, register listing and op/console-safe release.
 */
class LegacyBridgeTest {
    private static final String DIM = "minecraft:overworld";

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private TestDeepScan deepScan;
    private PrisonService prison;
    private BoloService bolos;
    private CheckpointService checkpoints;
    private CustodyService custody;
    private FineService fines;
    private final java.util.Map<String, MemoryStore> personnelRaw = new java.util.HashMap<>();
    private TestPlayer player;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        deepScan = (TestDeepScan) ctx.deepScan();
        var players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        prison.useSeizure(new SeizureService(ctx, audit));
        prison.useBolos(bolos = new BoloService(ctx, players, audit));
        fines = new FineService(ctx, players, audit, prison);
        var storage = new StorageService(ctx, players, audit, bolos, prison);
        storage.useCustody(custody);
        deepScan.server = server;
        var personnel = new PersonnelService(
                new SavedStores.Personnel(name -> personnelRaw.computeIfAbsent(name, k -> new MemoryStore())),
                clock, new Fakes.SeqIds());
        checkpoints = new CheckpointService(ctx, audit, prison, storage, bolos, personnel, custody);
        ((TestWorld) ctx.world()).room(DIM, -1, 59, -1, 6, 66, 6, -1, 61, 2);
        var jailer = server.add("dwurdy");
        assertTrue(prison.createCell(jailer, "celula_1", DIM, 0, 60, 0, 5, 65, 5));
        player = server.add("civ");
        player.x = 5; player.y = 60; player.z = -5;
    }

    private LawCheckpointRecord site(String id, CheckpointMode mode) {
        var record = new LawCheckpointRecord();
        record.id = id;
        record.name = id;
        record.dimension = DIM;
        record.mode = mode;
        record.pushback = PushbackPoint.at(DIM, 0, 60, -10, 180f);
        return record;
    }

    private void save(LawCheckpointRecord record) {
        var store = ctx.lawCheckpoints().read();
        store.put(record);
        ctx.lawCheckpoints().write(store);
    }

    private void globalBan(String itemId) {
        var store = ctx.lawCheckpoints().read();
        store.globalIllegalItems().put(itemId, true);
        ctx.lawCheckpoints().write(store);
    }

    private void carry(String itemId, int count) {
        deepScan.inventories.put(player.uuid,
                List.of(new SnapshotItem("main:0", itemId, count, "", itemId)));
    }

    private InspectionLedgerEntry lastEntry() {
        var entries = ctx.inspectionLedger().read().entries();
        return entries.isEmpty() ? null : entries.get(entries.size() - 1);
    }

    private boolean inCell() {
        var rec = ctx.prisonerRegister().read().prisoner(player.uuid.toString());
        return rec != null && rec.status == PrisonerStatus.IN_CELL;
    }

    // ------------------------------------------------------------ strajacheckpoint

    @Test
    void inspectDeniesBannedTargetAtNearestSite() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        site.bannedPlayerUuids.add(player.name);
        save(site);

        boolean acted = checkpoints.legacyStage(player, "inspect", DIM, 5, 60, 5);

        assertTrue(acted);
        assertEquals(CrossingOutcome.DENY, lastEntry().outcome);
        assertEquals(-10, player.z, 1e-6); // repelled to the pushback point
    }

    @Test
    void arrestBooksContrabandCarrierIntoCustody() {
        var site = site("port", CheckpointMode.ARREST);
        site.stage2 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        globalBan("minecraft:tnt");
        carry("minecraft:tnt", 1);

        boolean acted = checkpoints.legacyStage(player, "arrest", DIM, 5, 60, 5);

        assertTrue(acted);
        assertEquals(CrossingOutcome.ARREST, lastEntry().outcome);
        assertTrue(inCell());
    }

    @Test
    void denyRepelsViolationAtDenySite() {
        var site = site("gate", CheckpointMode.DENY);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        site.doors.add(new StoragePoint(DIM, 2, 60, 3));
        site.bannedPlayerUuids.add(player.name);
        save(site);

        boolean acted = checkpoints.legacyStage(player, "deny", DIM, 5, 60, 5);

        assertTrue(acted);
        assertEquals(CrossingOutcome.DENY, lastEntry().outcome);
        assertEquals(-10, player.z, 1e-6);
    }

    @Test
    void cleanTargetPassesAndReportsNoAction() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);

        assertFalse(checkpoints.legacyStage(player, "inspect", DIM, 5, 60, 5));
        assertEquals(CrossingOutcome.PASS, lastEntry().outcome);
    }

    @Test
    void stageVerbFailsWhenNoSiteIsNearby() {
        assertFalse(checkpoints.legacyStage(player, "inspect", DIM, 5, 60, 5));
        assertTrue(player.told("Niciun punct de control în apropiere"));
        assertNull(lastEntry());
    }

    @Test
    void nearestSitePrefersTheClosestPostWithinRadius() {
        var near = site("near", CheckpointMode.ARREST);
        near.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        near.pushback = PushbackPoint.at(DIM, 0, 60, -10, 180f);
        save(near);
        var far = site("far", CheckpointMode.ARREST);
        far.stage1 = LawBounds.of(DIM, 0, 55, 100, 10, 65, 110);
        far.pushback = PushbackPoint.at(DIM, 0, 60, 100, 180f);
        save(far);
        near.bannedPlayerUuids.add(player.name);
        far.bannedPlayerUuids.add(player.name);
        var store = ctx.lawCheckpoints().read();
        store.put(near);
        store.put(far);
        ctx.lawCheckpoints().write(store);

        assertTrue(checkpoints.legacyStage(player, "inspect", DIM, 5, 60, 5));
        assertEquals("near", lastEntry().checkpointId);
    }

    @Test
    void legacyInfoListsContrabandWithoutStateChange() {
        var site = site("border", CheckpointMode.ARREST);
        site.stage1 = LawBounds.of(DIM, 0, 55, 0, 10, 65, 10);
        save(site);
        globalBan("minecraft:iron_ingot");
        carry("minecraft:iron_ingot", 3);

        var found = checkpoints.legacyInfo(player, DIM, 5, 60, 5);

        assertEquals(List.of("3 x minecraft:iron_ingot"), found);
        assertNull(lastEntry()); // info never writes the ledger
        assertFalse(inCell());
    }

    @Test
    void aggressiveRaisesAnArrestAuthorizedSystemBolo() {
        assertTrue(checkpoints.legacyAggressive(player));

        var active = bolos.active();
        assertEquals(1, active.size());
        var bolo = active.get(0);
        assertEquals(player.uuid.toString(), bolo.subjectUuid);
        assertEquals("system", bolo.issuerName);
        assertEquals(BoloAuthority.ARREST_AUTHORIZED, bolo.authority);
        assertEquals(BoloStatus.ACTIVE, bolo.status);
        assertTrue(bolo.expiresAt > clock.nowMillis());
    }

    @Test
    void aggressiveIsIdempotentWhileAWantedMarkIsActive() {
        assertTrue(checkpoints.legacyAggressive(player));
        assertTrue(checkpoints.legacyAggressive(player));
        assertEquals(1, bolos.active().size());
    }

    @Test
    void aggressiveRefreshesAMarkWhoseExpiryWasNeverSwept() {
        var stale = new BoloRecord();
        stale.id = "BOLO-0099";
        stale.subjectUuid = player.uuid.toString();
        stale.subjectName = player.name;
        stale.status = BoloStatus.ACTIVE;
        stale.authority = BoloAuthority.ARREST_AUTHORIZED;
        stale.expiresAt = clock.nowMillis() - 1; // expired but not yet swept
        var store = ctx.bolos().read();
        store.records.add(stale);
        ctx.bolos().write(store);

        assertTrue(checkpoints.legacyAggressive(player));

        var active = bolos.active();
        assertEquals(1, active.size());
        assertNotEquals("BOLO-0099", active.get(0).id);
        assertEquals(BoloStatus.EXPIRED, ctx.bolos().read().records.get(0).status);
    }

    // ------------------------------------------------------------ strajaprison

    @Test
    void registerLinesListDetaineesAndFilterByName() {
        var reg = ctx.prisonerRegister().read();
        var rec = new PrisonerRegisterRecord(player.uuid.toString(), player.name, "furt");
        rec.status = PrisonerStatus.IN_CELL;
        rec.assignedCellId = "celula_1";
        rec.arrestCount = 2;
        reg.put(rec);
        var other = server.add("altul");
        var rec2 = new PrisonerRegisterRecord(other.uuid.toString(), other.name, "datorie");
        rec2.status = PrisonerStatus.FUGITIVE;
        reg.put(rec2);
        ctx.prisonerRegister().write(reg);

        var all = prison.registerLines(null);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(l -> l.contains("civ") && l.contains("celula_1")));

        var filtered = prison.registerLines("CIV");
        assertEquals(1, filtered.size());
        assertTrue(filtered.get(0).contains("civ"));

        var byUuid = prison.registerLines(other.uuid.toString());
        assertEquals(1, byUuid.size());
        assertTrue(byUuid.get(0).contains("altul"));
        assertTrue(prison.registerLines("nimeni").isEmpty());
    }

    @Test
    void releaseByIdAdmitsAnOpCommandSourceForAnOnlineTarget() {
        var jailer = server.add("dwurdy");
        jailer.op = true;
        prison.arrest(player, null, 1, jailer, null);
        assertTrue(inCell());

        var console = server.add("consola");
        console.op = true; // command block / console — no arrest capability held
        assertTrue(prison.releaseById(console, player.name));

        var rec = ctx.prisonerRegister().read().prisoner(player.uuid.toString());
        assertEquals(PrisonerStatus.RELEASED, rec.status);
    }

    @Test
    void releaseByIdStillRefusesAnUnauthorizedJailer() {
        var jailer = server.add("dwurdy");
        jailer.op = true;
        prison.arrest(player, null, 1, jailer, null);

        var civilian = server.add("maria"); // no capability, not op
        assertFalse(prison.releaseById(civilian, player.name));
        assertTrue(inCell());
    }

    @Test
    void registerLinesFallsBackToUuidWhenTheNameIsMissing() {
        var reg = ctx.prisonerRegister().read();
        var rec = new PrisonerRegisterRecord(player.uuid.toString(), null, "");
        rec.status = PrisonerStatus.IN_CELL;
        reg.put(rec);
        ctx.prisonerRegister().write(reg);

        var lines = prison.registerLines(null);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains(player.uuid.toString()));
        assertFalse(lines.get(0).contains("null"));
    }

    // ------------------------------------------------------------ release fine (system issue)

    @Test
    void releaseFineIssuesAPayableSystemFine() {
        var jailer = server.add("dwurdy");
        jailer.op = true;
        prison.arrest(player, null, 1, jailer, null);
        assertTrue(inCell());

        var console = server.add("consola");
        console.op = true; // command block — no ISSUE_FINES capability, no ledger item
        assertTrue(prison.releaseById(console, player.name));
        assertTrue(fines.issueSystem(console, player, 120, "custodie", "amendă la eliberare"));

        var store = ctx.fines().read();
        var fine = store.fines.stream()
                .filter(f -> player.uuid.toString().equals(f.targetUuid))
                .findFirst().orElse(null);
        assertNotNull(fine);
        assertEquals("ISSUED", fine.status); // payable at the Receptionist
        assertEquals(120, fine.amount);
        assertTrue(player.told("Ai primit amenda " + fine.id));
        assertTrue(console.told("a fost emisă către " + player.name));
    }

    @Test
    void systemFineStaysIssuedWhenTheNoticeCannotBeDelivered() {
        var console = server.add("consola");
        console.op = true;
        player.failVerifiedCalls = 1; // inventory full — the paper doesn't fit

        assertTrue(fines.issueSystem(console, player, 40, "custodie", "amendă la eliberare"));

        var fine = ctx.fines().read().fines.get(0);
        assertEquals("ISSUED", fine.status); // the debt stands; DELIVERY_FAILED is officer-only
        assertTrue(console.told("a rămas în registru"));
    }
}
