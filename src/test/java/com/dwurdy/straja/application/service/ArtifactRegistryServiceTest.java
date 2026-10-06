package com.dwurdy.straja.application.service;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.ArtifactLicenseType;
import com.dwurdy.straja.domain.model.ArtifactStatus;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #246 / #245 M1 — central artifact registry: serial-marked registrations with
 * a 24h pending maturation window, Inspector/Transporter licensing, and the
 * sealed-military-crate verbs that feed the checkpoint scanner milestone.
 */
class ArtifactRegistryServiceTest {
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final String CRATE = ArtifactRegistryService.CRATE_ITEM;

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private ArtifactRegistryService registry;
    private SavedStores.ArtifactRegistry repo;
    private TestPlayer admin;
    private TestPlayer inspector;
    private TestPlayer transporter;
    private TestPlayer citizen;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        var memory = new HashMap<String, MemoryStore>();
        repo = new SavedStores.ArtifactRegistry(name -> memory.computeIfAbsent(name, k -> new MemoryStore()));
        registry = new ArtifactRegistryService(repo, clock, new Fakes.SeqIds(),
                ctx.policies(), players, new AuditService(ctx));
        admin = server.add("admin");
        admin.op = true;
        inspector = server.add("inspector");
        transporter = server.add("transporter");
        citizen = server.add("citizen");
    }

    // ---------------------------------------------------------------- licenses

    @Test
    void grantPersistsLicenseAndBlocksDuplicatesUntilRevoked() {
        assertTrue(registry.grantLicense(admin, inspector, "INSPECTOR"));
        assertTrue(registry.isLicensed(inspector, ArtifactLicenseType.INSPECTOR));
        assertFalse(registry.isLicensed(inspector, ArtifactLicenseType.TRANSPORTER));
        assertTrue(inspector.told("licența de Inspector"));

        assertFalse(registry.grantLicense(admin, inspector, "INSPECTOR"));
        assertEquals(1, repo.read().licenses.size());

        assertTrue(registry.revokeLicense(admin, inspector, "INSPECTOR"));
        assertFalse(registry.isLicensed(inspector, ArtifactLicenseType.INSPECTOR));
        assertTrue(registry.grantLicense(admin, inspector, "INSPECTOR"));
        assertEquals("LIC-2", latestLicenseId());
    }

    @Test
    void nonAuthorityCannotGrantOrRevoke() {
        assertFalse(registry.grantLicense(citizen, inspector, "INSPECTOR"));
        assertFalse(registry.isLicensed(inspector, ArtifactLicenseType.INSPECTOR));
        assertFalse(registry.revokeLicense(citizen, inspector, "INSPECTOR"));
    }

    @Test
    void licenseSurfacesRespectTheDisabledGateAndTargetPresence() {
        ctx.policies().artifactRegistryEnabled = false;
        assertFalse(registry.grantLicense(admin, inspector, "INSPECTOR"));
        assertFalse(registry.revokeLicense(admin, inspector, "INSPECTOR"));

        ctx.policies().artifactRegistryEnabled = true;
        inspector.online = false;
        assertFalse(registry.grantLicense(admin, inspector, "INSPECTOR"));

        assertFalse(registry.grantLicense(admin, inspector, "MAYOR"));
    }

    @Test
    void ownLicensesListsOnlyTheCallersActiveLicenses() {
        registry.grantLicense(admin, inspector, "INSPECTOR");
        registry.grantLicense(admin, inspector, "TRANSPORTER");
        registry.grantLicense(admin, transporter, "TRANSPORTER");

        registry.ownLicenses(inspector);
        assertTrue(inspector.told("LIC-1"));
        assertTrue(inspector.told("LIC-2"));
        assertFalse(inspector.told("LIC-3"));

        registry.ownLicenses(citizen);
        assertTrue(citizen.told("Nu deții nicio licență"));
    }

    // ---------------------------------------------------------------- registry

    @Test
    void licensedInspectorRegistersPendingArtifactWithAllocatedSerial() {
        ctx.policies().artifactRegulatedItemIds.add("examplemod:rifle");
        registry.grantLicense(admin, inspector, "INSPECTOR");

        String serial = registry.register(inspector, citizen, "examplemod:rifle");
        assertEquals("RC-1", serial);
        var record = repo.read().artifacts.get("RC-1");
        assertNotNull(record);
        assertEquals("#RC-1", record.marking);
        assertEquals("weapon", record.artifactKind);
        assertEquals(citizen.uuid.toString(), record.holderUuid);
        assertEquals(inspector.uuid.toString(), record.issuerUuid);
        assertEquals(ArtifactStatus.PENDING.name(), record.statusAt(clock.nowMillis()));
        assertFalse(registry.isLegal("RC-1"));
        assertEquals(clock.nowMillis() + ctx.policies().artifactPendingHours * HOUR_MS,
                record.pendingUntil);
    }

    @Test
    void registrationIsGatedOnInspectorLicenseUnlessAdmin() {
        assertNull(registry.register(citizen, citizen, "minecraft:iron_sword"));
        assertTrue(repo.read().artifacts.isEmpty());

        String serial = registry.register(admin, citizen, "minecraft:iron_sword");
        assertEquals("RC-1", serial);
        assertNotNull(repo.read().artifacts.get("RC-1"));
    }

    @Test
    void pendingArtifactMaturesExactlyAtTheWindowEdge() {
        String serial = registry.register(admin, citizen, "straja:identity_card");
        var record = repo.read().artifacts.get(serial);
        assertEquals("document", record.artifactKind);

        clock.advance(ctx.policies().artifactPendingHours * HOUR_MS - 1);
        assertEquals(ArtifactStatus.PENDING.name(), record.statusAt(clock.nowMillis()));
        assertFalse(registry.isLegal(serial));

        clock.advance(1);
        assertEquals(ArtifactStatus.ACTIVE.name(), record.statusAt(clock.nowMillis()));
        assertTrue(registry.isLegal(serial));
    }

    @Test
    void zeroMaturationWindowRegistersStraightToLegal() {
        ctx.policies().artifactPendingHours = 0;
        String serial = registry.register(admin, citizen, "minecraft:stone");
        assertEquals("other", repo.read().artifacts.get(serial).artifactKind);
        assertTrue(registry.isLegal(serial));
    }

    @Test
    void serialsAllocateSequentiallyAndSurviveRoundTrip() {
        registry.register(admin, citizen, "minecraft:iron_sword");
        registry.register(admin, citizen, "minecraft:bow");
        assertNotNull(repo.read().artifacts.get("RC-2"));

        var reloaded = repo.read();
        assertEquals(3, reloaded.nextSerial);
        assertEquals(2, reloaded.artifacts.size());
        assertEquals("#RC-2", reloaded.artifacts.get("RC-2").marking);
    }

    @Test
    void revokedArtifactStopsBeingLegalButKeepsItsRecord() {
        ctx.policies().artifactPendingHours = 0;
        String serial = registry.register(admin, citizen, "minecraft:iron_sword");
        assertTrue(registry.isLegal(serial));
        assertTrue(registry.revokeArtifact(admin, serial));
        assertFalse(registry.isLegal(serial));
        assertEquals(ArtifactStatus.REVOKED.name(),
                repo.read().artifacts.get(serial).statusAt(clock.nowMillis()));

        assertFalse(registry.revokeArtifact(citizen, serial));
        assertFalse(registry.revokeArtifact(admin, "RC-99"));
    }

    @Test
    void infoIsRegistryTruthForAuthorityAndHolderOnly() {
        String serial = registry.register(admin, citizen, "minecraft:iron_sword");

        registry.info(admin, serial);
        assertTrue(admin.told("#RC-1"));
        assertTrue(admin.told("PENDING"));

        registry.info(citizen, serial);
        assertTrue(citizen.told("#RC-1"));

        var outsider = server.add("outsider");
        registry.info(outsider, serial);
        assertFalse(outsider.told("#RC-1"));

        registry.info(admin, "RC-99");
        assertTrue(admin.told("nu există în registru"));
    }

    // ---------------------------------------------------------------- crate seals

    @Test
    void licensedTransporterSealsAndUnsealsTheirOwnCrate() {
        registry.grantLicense(admin, transporter, "TRANSPORTER");
        hold(transporter, new ItemView(CRATE, 1, 1, Map.of()));

        assertTrue(registry.sealHeld(transporter));
        ItemView sealed = transporter.mainHand();
        assertEquals(transporter.uuid.toString(), sealed.data(ArtifactRegistryService.SEAL_BY));
        assertEquals("transporter", sealed.data(ArtifactRegistryService.SEAL_NAME));
        assertNotNull(sealed.data(ArtifactRegistryService.SEAL_ID));
        assertEquals(1, transporter.inventory().countOf(CRATE));

        assertTrue(registry.unsealHeld(transporter));
        ItemView plain = transporter.mainHand();
        assertNull(plain.data(ArtifactRegistryService.SEAL_BY));
        assertEquals(1, transporter.inventory().countOf(CRATE));
    }

    @Test
    void sealRequiresTransporterLicenseOrAuthority() {
        hold(citizen, new ItemView(CRATE, 1, 1, Map.of()));
        assertFalse(registry.sealHeld(citizen));
        assertNull(citizen.mainHand().data(ArtifactRegistryService.SEAL_BY));

        hold(admin, new ItemView(CRATE, 1, 1, Map.of()));
        assertTrue(registry.sealHeld(admin));
    }

    @Test
    void cannotSealWhatIsNotACrateOrAlreadySealed() {
        registry.grantLicense(admin, transporter, "TRANSPORTER");
        hold(transporter, new ItemView("minecraft:stone", 1, 64, Map.of()));
        assertFalse(registry.sealHeld(transporter));

        hold(transporter, new ItemView(CRATE, 1, 1,
                Map.of(ArtifactRegistryService.SEAL_BY, "someone",
                        ArtifactRegistryService.SEAL_NAME, "Someone")));
        assertFalse(registry.sealHeld(transporter));
        assertEquals("someone", transporter.mainHand().data(ArtifactRegistryService.SEAL_BY));
    }

    @Test
    void unsealIsLimitedToTheSealerOrAuthority() {
        registry.grantLicense(admin, transporter, "TRANSPORTER");
        var sealedData = Map.of(ArtifactRegistryService.SEAL_BY, transporter.uuid.toString(),
                ArtifactRegistryService.SEAL_NAME, "transporter",
                ArtifactRegistryService.SEAL_ID, "SEAL-1",
                ArtifactRegistryService.SEAL_AT, "1000");

        var thief = server.add("thief");
        hold(thief, new ItemView(CRATE, 1, 1, new HashMap<>(sealedData)));
        assertFalse(registry.unsealHeld(thief));
        assertNotNull(thief.mainHand().data(ArtifactRegistryService.SEAL_BY));

        hold(admin, new ItemView(CRATE, 1, 1, new HashMap<>(sealedData)));
        assertTrue(registry.unsealHeld(admin));
        assertNull(admin.mainHand().data(ArtifactRegistryService.SEAL_BY));
    }

    @Test
    void sealFailureRollsTheHeldCrateBack() {
        registry.grantLicense(admin, transporter, "TRANSPORTER");
        hold(transporter, new ItemView(CRATE, 1, 1, Map.of()));
        transporter.failVerifiedCalls = 1;

        assertFalse(registry.sealHeld(transporter));
        assertEquals(1, transporter.inventory().countOf(CRATE));
        assertNull(transporter.mainHand().data(ArtifactRegistryService.SEAL_BY));
    }

    @Test
    void registerHeldStampsTheSerialOntoTheItemAndBlocksARepeat() {
        hold(admin, new ItemView("minecraft:iron_sword", 1, 1, Map.of()));

        String serial = registry.registerHeld(admin, citizen);
        assertEquals("RC-1", serial);
        ItemView marked = admin.mainHand();
        assertEquals("RC-1", marked.data(ArtifactRegistryService.SERIAL_KEY));
        assertEquals("#RC-1", marked.data(ArtifactRegistryService.MARK_KEY));
        assertEquals(1, admin.inventory().countOf("minecraft:iron_sword"));

        assertNull(registry.registerHeld(admin, citizen));
        assertEquals(1, repo.read().artifacts.size(),
                "an already-marked item must not mint a second serial");
        assertTrue(admin.told("deja marca"));
    }

    @Test
    void registerHeldRequiresTheInspectorLicenseForNonAdmins() {
        hold(citizen, new ItemView("minecraft:iron_sword", 1, 1, Map.of()));
        assertNull(registry.registerHeld(citizen, citizen));
        assertTrue(repo.read().artifacts.isEmpty());
        assertNull(citizen.mainHand().data(ArtifactRegistryService.SERIAL_KEY));

        registry.grantLicense(admin, inspector, "INSPECTOR");
        hold(inspector, new ItemView("minecraft:iron_sword", 1, 1, Map.of()));
        assertEquals("RC-1", registry.registerHeld(inspector, citizen));
        assertEquals("RC-1", inspector.mainHand().data(ArtifactRegistryService.SERIAL_KEY));
    }

    @Test
    void aFailedMarkRollsTheItemBackAndBurnsTheSerial() {
        hold(admin, new ItemView("minecraft:iron_sword", 1, 1, Map.of()));
        admin.failVerifiedCalls = 1;

        assertNull(registry.registerHeld(admin, citizen));
        assertEquals(1, admin.inventory().countOf("minecraft:iron_sword"));
        assertNull(admin.mainHand().data(ArtifactRegistryService.SERIAL_KEY));
        var record = repo.read().artifacts.get("RC-1");
        assertNotNull(record, "the allocated serial stays in the ledger");
        assertEquals(ArtifactStatus.REVOKED.name(), record.statusAt(clock.nowMillis()),
                "an unmarkable item must leave its serial revoked, never unbound");
        assertFalse(registry.isLegal("RC-1"));
    }

    @Test
    void registerHeldOnEmptyHandRefusesWithoutTouchingTheStore() {
        assertNull(registry.registerHeld(admin, citizen));
        assertTrue(repo.read().artifacts.isEmpty());
    }

    private void hold(TestPlayer player, ItemView item) {
        player.inventory.slots.set(0, item);
        player.selectedSlot = 0;
    }

    private String latestLicenseId() {
        String latest = "";
        for (var license : repo.read().licenses.values()) {
            if (license.licenseId.compareTo(latest) > 0) latest = license.licenseId;
        }
        return latest;
    }
}
