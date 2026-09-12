package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Custody mechanics parity tests — pure Java, deterministic fakes. */
class CustodyServiceTest {
    private TestServer server;
    private FixedClock clock;
    private StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private CustodyService custody;
    private TestPlayer boss;
    private TestPlayer guard;
    private TestPlayer civilian;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        boss = server.add("dwurdy");
        guard = server.add("g1");
        civilian = server.add("civ1");
        setRank(guard, Rank.GUARD);
        giveItem(guard, CustodyService.CUFFS);
    }

    private void setRank(TestPlayer p, Rank rank) {
        var s = players.state(p.uuid());
        s.rank = rank.level();
        players.save(p.uuid(), s);
    }

    private void giveItem(TestPlayer p, String id) {
        p.give(ItemSpec.of(id, 1));
    }

    private void hold(TestPlayer p, String id) {
        // find slot containing id and select it
        for (int i = 0; i < p.inventory.slots(); i++) {
            if (id.equals(p.inventory.stackAt(i).id())) { p.selectSlot(i); return; }
        }
        p.give(ItemSpec.of(id, 1));
        hold(p, id);
    }

    // ------------------------------------------------------------ cuff consent

    @Test
    void cuffRequestAcceptAppliesAndGrantsKey() {
        assertTrue(custody.requestCuffs(guard, civilian));
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        assertTrue(custody.accept(civilian, req.id));
        assertTrue(custody.isCuffed(civilian));
        // guard received the cuff key
        assertTrue(guard.inventory.slots.stream().anyMatch(s -> CustodyService.CUFF_KEY.equals(s.id())));
    }

    @Test
    void cuffRequestRequiresCuffsItemAndRank() {
        TestPlayer junior = server.add("jr");
        setRank(junior, Rank.JUNIOR); // junior lacks useCuffs
        giveItem(junior, CustodyService.CUFFS);
        assertFalse(custody.requestCuffs(junior, civilian));
        TestPlayer bare = server.add("g2");
        setRank(bare, Rank.GUARD); // no cuffs item
        assertFalse(custody.requestCuffs(bare, civilian));
        assertTrue(bare.told("fără Cătușe"));
    }

    @Test
    void guardCannotCuffAnotherGuardWithoutOverride() {
        TestPlayer other = server.add("g9");
        setRank(other, Rank.GUARD);
        assertFalse(custody.requestCuffs(guard, other));
        assertTrue(guard.told("membru al Străjii"));
        // commissioner can
        giveItem(boss, CustodyService.CUFFS);
        assertTrue(custody.requestCuffs(boss, other));
    }

    @Test
    void refuseRemovesRequestAndNotifies() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        assertTrue(custody.refuse(civilian, req.id));
        assertFalse(custody.isCuffed(civilian));
        assertTrue(ctx.custody().read().cuffRequests.isEmpty());
        assertTrue(guard.told("refuzat încătușarea"));
    }

    @Test
    void requestExpiresAfterTimeout() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        clock.advance(61_000);
        assertFalse(custody.accept(civilian, req.id));
        assertFalse(custody.isCuffed(civilian));
    }

    // ------------------------------------------------------------ release

    @Test
    void keyReleaseConsumesKeyAndRestoresHand() {
        giveItem(civilian, "minecraft:diamond_sword");
        hold(civilian, "minecraft:diamond_sword");
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        // held item was hidden while cuffed
        assertEquals(0, civilian.inventory.countOf("minecraft:diamond_sword"));
        hold(guard, CustodyService.CUFF_KEY);
        assertTrue(custody.release(guard, civilian));
        assertFalse(custody.isCuffed(civilian));
        // key consumed, hidden sword restored
        assertEquals(0, guard.inventory.countOf(CustodyService.CUFF_KEY));
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"));
    }

    @Test
    void crowbarCutsCuffsAndRope() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        hold(guard, CustodyService.ROPE);
        assertTrue(custody.applyRope(guard, civilian));
        hold(guard, CustodyService.BOLT_CUTTERS);
        assertTrue(custody.release(guard, civilian));
        assertFalse(custody.isCuffed(civilian));
        assertFalse(custody.isBound(civilian));
    }

    @Test
    void ropeRequiresCuffedTarget() {
        hold(guard, CustodyService.ROPE);
        assertFalse(custody.applyRope(guard, civilian));
        assertTrue(guard.told("deja încătușat"));
    }

    @Test
    void headSackRequiresRestraintAndBlinds() {
        hold(guard, CustodyService.HEAD_SACK);
        assertFalse(custody.applyHeadSack(guard, civilian)); // not restrained
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        hold(guard, CustodyService.HEAD_SACK);
        assertTrue(custody.applyHeadSack(guard, civilian));
        assertTrue(civilian.effects.stream().anyMatch(e -> e.contains("blindness")));
        assertTrue(custody.removeHeadSack(civilian));
        assertFalse(custody.hasHeadSack(civilian));
    }

    @Test
    void emergencyReleaseClearsEverything() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        hold(guard, CustodyService.ROPE);
        custody.applyRope(guard, civilian);
        hold(guard, CustodyService.HEAD_SACK);
        custody.applyHeadSack(guard, civilian);
        assertTrue(custody.emergencyRelease(boss, civilian));
        assertFalse(custody.isCuffed(civilian));
        assertFalse(custody.isBound(civilian));
        assertFalse(custody.hasHeadSack(civilian));
        assertFalse(custody.emergencyRelease(guard, civilian)); // not commissioner
    }

    // ------------------------------------------------------------ distance break

    @Test
    void cuffsBreakAfterDistanceGrace() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        civilian.teleport("minecraft:overworld", 500, 64, 500); // far from guard
        custody.tick();
        assertTrue(custody.isCuffed(civilian)); // grace period
        clock.advance(11_000);
        custody.tick();
        assertFalse(custody.isCuffed(civilian));
        assertTrue(civilian.told("s-au rupt"));
    }

    // ------------------------------------------------------------ baton / downed

    @Test
    void batonNonLethalPassesThroughCapped() {
        hold(guard, CustodyService.BATON);
        var outcome = custody.batonStrike(guard, civilian, 10, 0, 4);
        assertEquals(CustodyService.BatonOutcome.Action.ALLOW_NONLETHAL, outcome.action());
        assertEquals(9, custody.capBatonDamage(10, 0)); // never below 1 hp
    }

    @Test
    void batonLethalKnockoutRequestsSurrender() {
        hold(guard, CustodyService.BATON);
        var outcome = custody.batonStrike(guard, civilian, 6, 0, 8);
        assertEquals(CustodyService.BatonOutcome.Action.CANCEL, outcome.action());
        assertEquals("surrender_requested", outcome.reason());
        assertTrue(custody.isDowned(civilian));
        assertEquals(1, civilian.health);
        // pending surrender request exists
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        assertEquals("SURRENDER", req.kind);
        // accept → cuffed + downed resolved
        assertTrue(custody.accept(civilian, req.id));
        assertTrue(custody.isCuffed(civilian));
        assertFalse(custody.isDowned(civilian));
    }

    @Test
    void batonRefuseSurrenderKeepsDowned() {
        hold(guard, CustodyService.BATON);
        custody.batonStrike(guard, civilian, 6, 0, 8);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        assertTrue(custody.refuse(civilian, req.id));
        assertTrue(custody.isDowned(civilian));
        assertFalse(custody.isCuffed(civilian));
        // wake after cooldown returns to downed position at half health
        civilian.teleport("minecraft:overworld", 999, 64, 999);
        clock.advance(61_000);
        custody.tick();
        assertFalse(custody.isDowned(civilian));
        assertEquals(10, civilian.health); // maxHealth*0.5
    }

    @Test
    void batonCannotStrikeCuffedOrDowned() {
        hold(guard, CustodyService.BATON);
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        var outcome = custody.batonStrike(guard, civilian, 6, 0, 8);
        assertEquals("already_cuffed", outcome.reason());
    }

    @Test
    void downedBlocksActionsAndFreezes() {
        custody.startDowned(civilian, guard, "test");
        assertTrue(custody.actionBlocked(civilian, "interact"));
        civilian.teleport("minecraft:overworld", 50, 64, 50);
        custody.tick();
        assertEquals(0, civilian.x); // teleported back to downed position
    }

    @Test
    void offlineIssuerKeyGoesPendingAndDelivers() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        guard.online = false; // issuer offline at accept time
        assertTrue(custody.accept(civilian, req.id));
        assertTrue(ctx.custody().read().pendingKeys.containsKey(guard.uuid().toString()));
        guard.online = true;
        custody.deliverPendingKeys(guard);
        assertTrue(guard.inventory.slots.stream().anyMatch(s -> CustodyService.CUFF_KEY.equals(s.id())));
    }

    @Test
    void releaseWithFullInventoryQueuesHiddenItem() {
        civilian.give(new ItemSpec("minecraft:diamond_sword", 1,
                java.util.Map.of("custom", "tag"), null));
        hold(civilian, "minecraft:diamond_sword");
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        assertEquals(0, civilian.inventory.countOf("minecraft:diamond_sword")); // hidden while cuffed
        // fill every slot so the hidden item cannot be returned at release
        for (int i = 0; i < civilian.inventory.slots(); i++) {
            civilian.inventory.slots.set(i, new ItemView("minecraft:stone", 64, 64, java.util.Map.of()));
        }
        hold(guard, CustodyService.CUFF_KEY);
        assertTrue(custody.release(guard, civilian));
        assertFalse(custody.isCuffed(civilian));
        String key = civilian.uuid().toString();
        var pending = ctx.custody().read().pendingItems.get(key);
        assertNotNull(pending, "hidden item must be queued for later delivery, not lost");
        assertEquals(1, pending.size());
        assertEquals("minecraft:diamond_sword", pending.get(0).itemId);
        assertEquals("tag", pending.get(0).data.get("custom"), "item metadata must survive");
        assertEquals(0, civilian.inventory.countOf("minecraft:diamond_sword"));
        // free a slot → pending delivery restores the item exactly once
        civilian.inventory.slots.set(0, ItemView.EMPTY);
        custody.deliverPendingItems(civilian);
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"));
        assertTrue(ctx.custody().read().pendingItems.isEmpty());
        custody.deliverPendingItems(civilian);
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"), "no duplicate delivery");
    }

    @Test
    void distanceBreakWithFullInventoryQueuesHiddenItem() {
        giveItem(civilian, "minecraft:diamond_sword");
        hold(civilian, "minecraft:diamond_sword");
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        for (int i = 0; i < civilian.inventory.slots(); i++) {
            civilian.inventory.slots.set(i, new ItemView("minecraft:stone", 64, 64, java.util.Map.of()));
        }
        civilian.teleport("minecraft:overworld", 500, 64, 500);
        custody.tick();
        clock.advance(11_000);
        custody.tick();
        assertFalse(custody.isCuffed(civilian));
        assertTrue(ctx.custody().read().pendingItems.containsKey(civilian.uuid().toString()),
                "distance-break release must keep the hidden item pending");
        civilian.inventory.slots.set(0, ItemView.EMPTY);
        custody.tick(); // tick retries pending deliveries for online players
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"));
        assertTrue(ctx.custody().read().pendingItems.isEmpty());
    }

    @Test
    void emergencyReleaseWithFullInventoryQueuesHiddenItem() {
        giveItem(civilian, "minecraft:diamond_sword");
        hold(civilian, "minecraft:diamond_sword");
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        for (int i = 0; i < civilian.inventory.slots(); i++) {
            civilian.inventory.slots.set(i, new ItemView("minecraft:stone", 64, 64, java.util.Map.of()));
        }
        assertTrue(custody.emergencyRelease(boss, civilian));
        assertTrue(ctx.custody().read().pendingItems.containsKey(civilian.uuid().toString()));
        civilian.inventory.slots.set(0, ItemView.EMPTY);
        custody.deliverPendingItems(civilian);
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"));
    }

    @Test
    void custodyStateSurvivesStoreRoundTrip() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        // simulate restart: read a fresh copy
        var reloaded = ctx.custody().read();
        assertTrue(reloaded.cuffed.containsKey(civilian.uuid().toString()));
    }
}
