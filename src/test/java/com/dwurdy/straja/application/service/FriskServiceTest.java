package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.CustodyStore;
import com.dwurdy.straja.domain.model.SnapshotItem;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verificare corporală — consent lifecycle, read-only snapshot guarantee,
 * range/policy refusals, ledger + audit rows. Pure Java, deterministic fakes.
 */
class FriskServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private AuditService audit;
    private FriskService frisks;
    private TestPlayer alice;
    private TestPlayer bob;
    private TestPlayer carol;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        audit = new AuditService(ctx);
        frisks = new FriskService(ctx, audit);
        alice = server.add("alice");
        bob = server.add("bob");
        carol = server.add("carol");
        // All three stand at the origin — inside the default 5-block range.
    }

    @Test
    void requestSendsTimedConsentOffer() {
        assertTrue(frisks.request(alice, bob));
        assertTrue(bob.messages.stream().anyMatch(m -> m.contains("straja.frisk.offer")));
        assertTrue(alice.messages.stream().anyMatch(m -> m.contains("straja.frisk.sent")));
        assertTrue(frisks.hasPending(bob, "F1"));
        assertTrue(audit.tail(10).stream()
                .anyMatch(e -> "frisk".equals(e.action) && e.details.contains("consent_requested")));
    }

    @Test
    void requestRefusesWhenDisabled() {
        ctx.policies().friskEnabled = false;
        assertFalse(frisks.request(alice, bob));
        assertTrue(alice.lastMessage().contains("dezactivată"));
        assertFalse(frisks.hasPending(bob, "F1"));
    }

    @Test
    void requestRefusesSelf() {
        assertFalse(frisks.request(alice, alice));
        assertTrue(alice.lastMessage().contains("Nu te poți verifica pe tine însuți"));
    }

    @Test
    void requestRefusesOutOfRange() {
        bob.x = 100;
        assertFalse(frisks.request(alice, bob));
        assertTrue(alice.lastMessage().contains("prea departe"));
    }

    @Test
    void requestRefusesSecondOfferForSameTarget() {
        assertTrue(frisks.request(alice, bob));
        assertFalse(frisks.request(carol, bob));
        assertTrue(carol.lastMessage().contains("în așteptare"));
    }

    @Test
    void requestRespectsRequesterCooldown() {
        assertTrue(frisks.request(alice, bob));
        bobRefusesPending();
        assertFalse(frisks.request(alice, carol));
        assertTrue(alice.lastMessage().contains("recent"));
        clock.advance(ctx.policies().friskCooldownSeconds * 1000L + 1);
        assertTrue(frisks.request(alice, carol));
    }

    @Test
    void acceptReleasesReadOnlySnapshotIntoLedger() {
        var staged = java.util.List.of(
                new SnapshotItem("main:0", "minecraft:diamond_sword", 1, null, "Sabie"),
                new SnapshotItem("main:1", "minecraft:bread", 12, null, "Pâine"));
        ((TestDeepScan) ctx.deepScan()).inventories.put(bob.uuid(),
                new java.util.ArrayList<>(staged));

        assertTrue(frisks.request(alice, bob));
        assertTrue(frisks.accept(bob, "F1"));

        assertTrue(alice.messages.stream().anyMatch(m -> m.contains("straja.frisk.result_header")));
        assertTrue(alice.messages.stream().anyMatch(m -> m.contains("Sabie")));
        assertTrue(alice.messages.stream().anyMatch(m -> m.contains("Pâine")));
        assertTrue(bob.messages.stream().anyMatch(m -> m.contains("straja.frisk.checked")));

        var entries = ctx.inspectionLedger().read().entries();
        assertEquals(1, entries.size());
        var entry = entries.get(0);
        assertEquals(FriskService.LEDGER_SITE, entry.checkpointId);
        assertEquals("bob", entry.playerName);
        assertEquals(2, entry.inventorySnapshot.size());
        assertTrue(entry.detail.contains("verificare corporală"));

        // Read-only: the staged snapshot list is untouched by the check.
        assertEquals(staged, ((TestDeepScan) ctx.deepScan()).inventories.get(bob.uuid()));
        assertTrue(audit.tail(10).stream()
                .anyMatch(e -> "frisk".equals(e.action) && e.details.contains("consent_granted")));
    }

    @Test
    void acceptRevalidatesRangeAndPolicy() {
        assertTrue(frisks.request(alice, bob));
        bob.x = 100;
        assertFalse(frisks.accept(bob, "F1"));
        assertTrue(bob.messages.stream().anyMatch(m -> m.contains("prea departe")));
        assertTrue(ctx.inspectionLedger().read().entries().isEmpty());
    }

    @Test
    void acceptRefusesWhenRequesterLeft() {
        assertTrue(frisks.request(alice, bob));
        alice.online = false;
        assertFalse(frisks.accept(bob, "F1"));
        assertTrue(bob.messages.stream().anyMatch(m -> m.contains("nu mai este aproape")));
        assertTrue(ctx.inspectionLedger().read().entries().isEmpty());
    }

    @Test
    void acceptRefusesUnknownId() {
        assertFalse(frisks.accept(bob, "F99"));
        assertTrue(bob.lastMessage().contains("nicio cerere de verificare activă"));
    }

    @Test
    void expiredOfferCannotBeAccepted() {
        assertTrue(frisks.request(alice, bob));
        clock.advance(ctx.policies().friskOfferSeconds * 1000L + 1);
        assertFalse(frisks.hasPending(bob, "F1"));
        assertFalse(frisks.accept(bob, "F1"));
        assertTrue(ctx.inspectionLedger().read().entries().isEmpty());
    }

    @Test
    void declineIsRecordedNeverPunished() {
        assertTrue(frisks.request(alice, bob));
        assertTrue(frisks.decline(bob, "F1"));
        assertTrue(alice.messages.stream().anyMatch(m -> m.contains("straja.frisk.declined")));
        assertTrue(bob.messages.stream().anyMatch(m -> m.contains("straja.frisk.declined_self")));
        // Declining is a right: no ledger entry, no mark — only an audit row.
        assertTrue(ctx.inspectionLedger().read().entries().isEmpty());
        assertTrue(audit.tail(10).stream()
                .anyMatch(e -> "frisk".equals(e.action) && e.details.contains("consent_declined")));
    }

    @Test
    void cuffedTargetCannotConsent() {
        frisks.useCustody(new CustodyService(ctx, new PlayerService(ctx), audit));
        var store = ctx.custody().read();
        var record = new CustodyStore.CuffRecord();
        record.target = bob.name();
        record.targetUuid = bob.uuid().toString();
        record.issuer = "officer";
        store.cuffed.put(bob.uuid().toString(), record);
        ctx.custody().write(store);

        assertFalse(frisks.request(alice, bob));
        assertTrue(alice.lastMessage().contains("custodie"));
    }

    @Test
    void acceptRevalidatesCustodyOnBothSides() {
        frisks.useCustody(new CustodyService(ctx, new PlayerService(ctx), audit));
        assertTrue(frisks.request(alice, bob));
        // The target is cuffed between offer and accept — the consent dies.
        var store = ctx.custody().read();
        var record = new CustodyStore.CuffRecord();
        record.target = bob.name();
        record.targetUuid = bob.uuid().toString();
        record.issuer = "officer";
        store.cuffed.put(bob.uuid().toString(), record);
        ctx.custody().write(store);

        assertFalse(frisks.accept(bob, "F1"));
        assertTrue(bob.messages.stream().anyMatch(m -> m.contains("custodie")));
        assertTrue(ctx.inspectionLedger().read().entries().isEmpty());
    }

    private void bobRefusesPending() {
        assertTrue(frisks.decline(bob, "F1"));
    }
}
