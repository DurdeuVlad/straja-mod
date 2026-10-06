package com.dwurdy.straja.application.service;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.RollSource;
import com.dwurdy.straja.domain.model.ArtifactLicenseType;
import com.dwurdy.straja.domain.model.ArtifactRecord;
import com.dwurdy.straja.domain.model.ArtifactStatus;
import com.dwurdy.straja.domain.model.ForgeryMarking;
import com.dwurdy.straja.domain.model.ForgeryMarking.MarkClass;
import com.dwurdy.straja.domain.model.ForgeryTier;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #247 / #245 M2 — the black-market forging engine: seeded RNG rolls against
 * the locked pyramid, per-tier malformed markings, the licensed/unlicensed
 * split, and the FORGED shadow records that keep registry truth server-side
 * while forged items lie about their serial.
 */
class ForgeryServiceTest {
    /** Deterministic draw source — java.util.Random behind the RollSource port. */
    private static RollSource seeded(long seed) {
        java.util.Random random = new java.util.Random(seed);
        return new RollSource() {
            @Override public int nextInt(int bound) { return random.nextInt(bound); }
            @Override public double nextDouble() { return random.nextDouble(); }
        };
    }

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private ArtifactRegistryService registry;
    private SavedStores.ArtifactRegistry repo;
    private ForgeryService forgery;
    private TestPlayer admin;
    private TestPlayer inspector;
    private TestPlayer forger;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        var memory = new HashMap<String, MemoryStore>();
        repo = new SavedStores.ArtifactRegistry(
                name -> memory.computeIfAbsent(name, k -> new MemoryStore()));
        registry = new ArtifactRegistryService(repo, clock, new Fakes.SeqIds(),
                ctx.policies(), players, new AuditService(ctx));
        forgery = new ForgeryService(ctx.policies(), seeded(42L), clock,
                registry, players, new AuditService(ctx));
        admin = server.add("admin");
        admin.op = true;
        inspector = server.add("inspector");
        forger = server.add("forger");
    }

    // ------------------------------------------------------------ tier roll

    @Test
    void monteCarloRollHitsTheLockedPyramid() {
        // 10,000 seeded draws must land within tolerance of 45/30/15/7/3.
        ForgeryService svc = new ForgeryService(ctx.policies(), seeded(7L), clock,
                registry, players, new AuditService(ctx));
        Map<ForgeryTier, Integer> counts = new HashMap<>();
        for (ForgeryTier t : ForgeryTier.values()) counts.put(t, 0);
        int draws = 10_000;
        for (int i = 0; i < draws; i++) {
            ForgeryTier t = svc.rollTier();
            counts.put(t, counts.get(t) + 1);
        }
        assertWithin(counts.get(ForgeryTier.N1), 300, draws);
        assertWithin(counts.get(ForgeryTier.N2), 700, draws);
        assertWithin(counts.get(ForgeryTier.N3), 1500, draws);
        assertWithin(counts.get(ForgeryTier.N4), 3000, draws);
        assertWithin(counts.get(ForgeryTier.N5), 4500, draws);
    }

    private static void assertWithin(int actual, int expected, int draws) {
        // ±1.5% of total draws — wide enough for any sane seed, tight enough
        // that a broken pyramid (e.g. inverted or uniform) can never pass.
        int tolerance = (int) (draws * 0.015);
        assertTrue(Math.abs(actual - expected) <= tolerance,
                "expected ~" + expected + " got " + actual);
    }

    @Test
    void malformedWeightsFallBackToThePyramid() {
        ctx.policies().forgeryTierWeights = List.of(0, 0, 0, 0, 0);
        ForgeryService svc = new ForgeryService(ctx.policies(), seeded(3L), clock,
                registry, players, new AuditService(ctx));
        // A zero-weight config can never starve the engine — it reverts to
        // 45/30/15/7/3 rather than producing authentic-grade junk.
        Map<ForgeryTier, Integer> counts = new HashMap<>();
        for (ForgeryTier t : ForgeryTier.values()) counts.put(t, 0);
        for (int i = 0; i < 2000; i++) {
            ForgeryTier t = svc.rollTier();
            counts.put(t, counts.get(t) + 1);
        }
        assertTrue(counts.get(ForgeryTier.N5) > counts.get(ForgeryTier.N1));
        assertTrue(counts.get(ForgeryTier.N4) > counts.get(ForgeryTier.N2));
    }

    // ---------------------------------------------------------- marking shapes

    @Test
    void everyTierProducesItsDefectClass() {
        RollSource rng = seeded(11L);
        List<String> real = List.of("RC-7", "RC-12", "RC-31");
        for (int i = 0; i < 500; i++) {
            assertEquals(MarkClass.PLAUSIBLE, ForgeryMarking.classify(
                    ForgeryMarking.markingFor(ForgeryTier.N1, rng::nextInt, "RC-", 40, real), "RC-"));
            assertEquals(MarkClass.PLAUSIBLE, ForgeryMarking.classify(
                    ForgeryMarking.markingFor(ForgeryTier.N2, rng::nextInt, "RC-", 40, real), "RC-"));
            assertEquals(MarkClass.PLAUSIBLE, ForgeryMarking.classify(
                    ForgeryMarking.markingFor(ForgeryTier.N3, rng::nextInt, "RC-", 40, real), "RC-"));
            assertEquals(MarkClass.MALFORMED, ForgeryMarking.classify(
                    ForgeryMarking.markingFor(ForgeryTier.N4, rng::nextInt, "RC-", 40, real), "RC-"));
            assertEquals(MarkClass.ABSURD, ForgeryMarking.classify(
                    ForgeryMarking.markingFor(ForgeryTier.N5, rng::nextInt, "RC-", 40, real), "RC-"));
        }
    }

    @Test
    void n1PassesCasualChecksButFailsExpertOnes() {
        RollSource rng = seeded(17L);
        List<String> real = List.of("RC-7", "RC-12", "RC-31");
        boolean spoofedReal = false;
        for (int i = 0; i < 200; i++) {
            String mark = ForgeryMarking.markingFor(ForgeryTier.N1, rng::nextInt, "RC-", 40, real);
            // Casual: the mark is indistinguishable from an authentic #RC-n.
            assertEquals(MarkClass.PLAUSIBLE, ForgeryMarking.classify(mark, "RC-"));
            String claimed = ForgeryMarking.claimedSerialFor(mark);
            if (real.contains(claimed)) {
                spoofedReal = true;
                // Expert: the claimed serial exists — but describes a different
                // item than the forger struck, so a registry cross-check burns it.
            } else {
                // Fresh-registry fallback: zero-padded — never allocatable.
                assertEquals("RC-041", claimed);
            }
        }
        assertTrue(spoofedReal, "N1 should sometimes spoof a genuine serial");
    }

    @Test
    void everyN4VariantClassifiesMalformedNotAbsurd() {
        // A mangled-but-recognizable prefix is a format error, not a crude mark.
        assertEquals(MarkClass.MALFORMED, ForgeryMarking.classify("#RC-15_", "RC-"));
        assertEquals(MarkClass.MALFORMED, ForgeryMarking.classify("#RC15", "RC-"));
        assertEquals(MarkClass.MALFORMED, ForgeryMarking.classify("#RC-15A", "RC-"));
        assertEquals(MarkClass.MALFORMED, ForgeryMarking.classify("#rc-15", "RC-"));
        assertEquals(MarkClass.MALFORMED, ForgeryMarking.classify("#RC--15", "RC-"));
        assertEquals(MarkClass.ABSURD, ForgeryMarking.classify("#RUSTY-GUN-99", "RC-"));
        assertEquals(MarkClass.PLAUSIBLE, ForgeryMarking.classify("#RC-15", "RC-"));
    }

    @Test
    void forgedClaimsCanNeverRipenIntoRealSerials() {
        // Every forged claim is zero-padded or absurd: register() only ever
        // emits plain "<prefix><number>" serials, so no future authentic
        // allocation can validate a forged claim.
        RollSource rng = seeded(31L);
        for (ForgeryTier tier : ForgeryTier.values()) {
            if (tier == ForgeryTier.N1) continue; // N1 spoofs real serials by design
            for (int i = 0; i < 200; i++) {
                String mark = ForgeryMarking.markingFor(tier, rng::nextInt,
                        "RC-", 40, List.of());
                String claimed = ForgeryMarking.claimedSerialFor(mark);
                if (claimed.startsWith("RC-")) {
                    String digits = claimed.substring(3);
                    assertTrue(digits.startsWith("0") || !digits.chars().allMatch(Character::isDigit),
                            "forged claim must never be a plain allocatable serial: " + claimed);
                }
            }
        }
    }

    @Test
    void n5MarksAreCuratedAbsurdities() {
        RollSource rng = seeded(23L);
        for (int i = 0; i < 100; i++) {
            String mark = ForgeryMarking.markingFor(ForgeryTier.N5, rng::nextInt, "RC-", 5, List.of());
            assertEquals(MarkClass.ABSURD, ForgeryMarking.classify(mark, "RC-"));
            assertFalse(mark.startsWith("#RC-"), "absurd mark: " + mark);
        }
    }

    // --------------------------------------------------------------- forging

    @Test
    void unlicensedForgeWritesShadowRecordAndLiesAboutSerial() {
        ForgeryService.ForgeOutcome out = forgery.forge(forger, "minecraft:iron_sword");
        assertNotNull(out);
        assertNotNull(out.tier());
        assertTrue(out.shadowSerial().startsWith("FRG-"));
        assertFalse(out.marking().isBlank());
        assertFalse(out.claimedSerial().isBlank());

        ArtifactRecord shadow = repo.read().artifacts.get(out.shadowSerial());
        assertNotNull(shadow);
        assertEquals(ArtifactStatus.FORGED.name(), shadow.status);
        assertEquals(out.tier().name(), shadow.forgeryTier);
        assertEquals("forger", shadow.holderName);
        // A shadow record is never a legal registration, at any hour.
        assertFalse(shadow.legalAt(clock.nowMillis() + 10L * 365 * 24 * 3600 * 1000));
        // …and never leaks into the authentic serial space.
        assertFalse(shadow.serial.startsWith(ctx.policies().artifactSerialPrefix));
    }

    @Test
    void forgeAuditsEveryAttempt() {
        forgery.forge(forger, "minecraft:iron_sword");
        forgery.forge(forger, "minecraft:bow");
        long forged = ctx.audit().tail(50).stream()
                .filter(e -> "artifact_forge".equals(e.action)).count();
        assertEquals(2, forged);
    }

    @Test
    void licensedStrikeNeverRolls() {
        assertTrue(registry.grantLicense(admin, inspector, "INSPECTOR"));
        assertTrue(forgery.strikeIsAuthentic(inspector));
        assertFalse(forgery.strikeIsAuthentic(forger));
        assertTrue(forgery.strikeIsAuthentic(admin));
    }

    @Test
    void checkClaimReadsRegistryTruth() {
        // Authentic record claims itself.
        var holder = citizen();
        String serial = registry.register(admin, holder, "minecraft:iron_sword");
        assertNotNull(serial);
        assertEquals(ForgeryService.RegistryCheck.AUTHENTIC,
                forgery.checkClaim(serial, "minecraft:iron_sword"));
        assertEquals(ForgeryService.RegistryCheck.CONFLICT,
                forgery.checkClaim(serial, "minecraft:bow"));
        // The holder-mismatch conflict — the tell that burns N1 spoofs.
        assertEquals(ForgeryService.RegistryCheck.CONFLICT,
                forgery.checkClaim(serial, "minecraft:iron_sword", "someone-else"));
        assertEquals(ForgeryService.RegistryCheck.AUTHENTIC,
                forgery.checkClaim(serial, "minecraft:iron_sword", holder.uuid().toString()));
        assertEquals(ForgeryService.RegistryCheck.ABSENT,
                forgery.checkClaim("RC-99999", "minecraft:bow"));

        // A claim that only shadows ever presented is evidence, not absence.
        ForgeryService.ForgeOutcome out = forgery.forge(forger, "minecraft:bow");
        assertEquals(ForgeryService.RegistryCheck.KNOWN_FORGED,
                forgery.checkClaim(out.claimedSerial(), "minecraft:bow"));
        assertEquals(ForgeryService.RegistryCheck.KNOWN_FORGED,
                forgery.checkClaim(out.shadowSerial(), "minecraft:bow"));
    }

    @Test
    void oversizedWeightsFallBackToThePyramid() {
        // In-game policy edits can hold values the TOML validator would reject;
        // a sum that overflows int must never throw inside a take.
        ctx.policies().forgeryTierWeights =
                List.of(Integer.MAX_VALUE, 1, 1, 1, 1);
        ForgeryService svc = new ForgeryService(ctx.policies(), seeded(5L), clock,
                registry, players, new AuditService(ctx));
        // Falls back to the locked pyramid instead of throwing.
        assertDoesNotThrow(svc::rollTier);
    }

    @Test
    void enabledRequiresBothEngineAndRegistry() {
        assertTrue(forgery.enabled());
        ctx.policies().forgeryEnabled = false;
        assertFalse(forgery.enabled());
        ctx.policies().forgeryEnabled = true;
        ctx.policies().artifactRegistryEnabled = false;
        assertFalse(forgery.enabled());
    }

    private TestPlayer citizen() {
        return server.add("citizen" + System.nanoTime());
    }
}
