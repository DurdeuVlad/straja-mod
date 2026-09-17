package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.IdentityCardStatus;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdentityCardServiceTest {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private IdentityCardService cards;
    private TestPlayer commissioner;
    private TestPlayer citizen;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        cards = new IdentityCardService(ctx, players, new AuditService(ctx));
        commissioner = server.add("dwurdy");
        citizen = server.add("citizen");

        var setup = ctx.setup().read();
        var reception = new com.dwurdy.straja.domain.model.SetupData.Location();
        setup.locations.put("receptionist", reception);
        ctx.setup().write(setup);
        citizen.x = citizen.y = citizen.z = 0;
    }

    @Test
    void receptionRequestDeliversUuidBoundCardAndRejectsDuplicate() {
        assertTrue(cards.request(citizen));
        var store = ctx.identityCards().read();
        assertEquals(1, store.cards.size());
        var card = store.cards.get("ID-1");
        assertNotNull(card);
        assertEquals(citizen.uuid.toString(), card.holderUuid);
        assertEquals(1, citizen.inventory().countOf("straja:identity_card"));

        assertFalse(cards.request(citizen));
        assertEquals(1, ctx.identityCards().read().cards.size());
        assertTrue(citizen.told("deja buletinul activ"));
    }

    @Test
    void expiredCardCanBeReissuedWithoutMakingTheOldItemValidAgain() {
        ctx.policies().identityCardValidityDays = 1;
        assertTrue(cards.request(citizen));
        clock.advance(DAY_MS + 1);

        assertTrue(cards.request(citizen));
        var store = ctx.identityCards().read();
        assertEquals(2, store.cards.size());
        assertEquals("EXPIRAT", status(cards, citizen, "ID-1"));
        assertEquals("VALID", status(cards, citizen, "ID-2"));
        assertEquals(2, citizen.inventory().countOf("straja:identity_card"));
    }

    @Test
    void validityOverrideCannotOverflowTheExpiryTimestamp() {
        ctx.policies().identityCardValidityDays = Integer.MAX_VALUE;

        assertTrue(cards.request(citizen));
        var card = ctx.identityCards().read().cards.get("ID-1");
        assertEquals(clock.nowMillis() + 3650L * DAY_MS, card.expiresAt);
    }

    @Test
    void forgedOrUnauthorizedReadsDoNotExposeCardData() {
        assertTrue(cards.request(citizen));
        var outsider = server.add("outsider");
        var card = ctx.identityCards().read().cards.get("ID-1");

        assertNull(cards.view(outsider, card.id));
        cards.read(outsider, card.id);
        assertTrue(outsider.told("nu ai dreptul"));

        var state = players.state(commissioner.uuid);
        state.rank = Rank.CIVIL.level();
        players.save(commissioner.uuid, state);
        assertNotNull(cards.view(commissioner, card.id));
    }

    @Test
    void operatorCanRevokeExactlyOnceAndTheReasonIsPersisted() {
        assertTrue(cards.request(citizen));
        assertFalse(cards.revoke(citizen, "ID-1", "fraud"));
        assertTrue(cards.revoke(commissioner, "ID-1", "document fals"));

        var card = ctx.identityCards().read().cards.get("ID-1");
        assertEquals(IdentityCardStatus.REVOKED.name(), card.status);
        assertEquals("document fals", card.revocationReason);
        assertTrue(cards.revoke(commissioner, "ID-1", "another reason"));
        assertEquals("document fals", ctx.identityCards().read().cards.get("ID-1").revocationReason);
    }

    @Test
    void failedDeliveryDoesNotConsumeASequenceNumberOrCreateARecord() {
        for (int slot = 0; slot < citizen.inventory().slots(); slot++) {
            citizen.inventory.slots.set(slot,
                    new com.dwurdy.straja.application.port.out.ItemView(
                            "minecraft:stone", 64, 64, java.util.Map.of()));
        }

        assertFalse(cards.request(citizen));
        assertTrue(ctx.identityCards().read().cards.isEmpty());
        assertEquals(1, ctx.identityCards().read().nextCardNumber);
    }

    private static String status(IdentityCardService service, TestPlayer owner, String id) {
        service.read(owner, id);
        return owner.lastMessage().contains("EXPIRAT") ? "EXPIRAT" : "VALID";
    }
}
