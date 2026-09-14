package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.CustodyStore;
import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.CustodyTransition;
import com.dwurdy.straja.domain.model.CustodyTransitionEngine;
import com.dwurdy.straja.domain.model.PlayerCondition;
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
    void cuffRequestCannotBeAcceptedAfterIssuerIsSuspendedWhileOffline() {
        assertTrue(custody.requestCuffs(guard, civilian));
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        guard.online = false;
        var state = players.state(guard.uuid());
        state.suspended = true;
        players.save(guard.uuid(), state);

        assertFalse(custody.accept(civilian, req.id));
        assertFalse(custody.isCuffed(civilian));
        assertTrue(ctx.custody().read().cuffRequests.isEmpty());
        assertTrue(audit.tail(20).stream().anyMatch(e -> "cuff_accept".equals(e.action)
                && "REFUSED".equals(e.result)
                && e.details.contains("issuer_no_longer_eligible")));
    }

    @Test
    void activeCuffIsRecoveredWhenIssuerIsFired() {
        assertTrue(custody.requestCuffs(guard, civilian));
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        assertTrue(custody.accept(civilian, req.id));

        var state = players.state(guard.uuid());
        state.fired = true;
        players.save(guard.uuid(), state);
        custody.tick();

        assertFalse(custody.isCuffed(civilian));
        assertTrue(civilian.told("emitentul nu mai este eligibil"));
        assertTrue(audit.tail(20).stream().anyMatch(e -> "cuff_recovery".equals(e.action)
                && "SUCCESS".equals(e.result)
                && e.details.contains("issuer_no_longer_eligible")));
    }

    @Test
    void firedIssuerRecoveryQueuesHiddenItemForOfflineTarget() {
        giveItem(civilian, "minecraft:diamond_sword");
        hold(civilian, "minecraft:diamond_sword");
        assertTrue(custody.requestCuffs(guard, civilian));
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        assertTrue(custody.accept(civilian, req.id));
        civilian.online = false;

        var state = players.state(guard.uuid());
        state.fired = true;
        players.save(guard.uuid(), state);
        custody.tick();

        assertFalse(custody.isCuffed(civilian));
        assertTrue(ctx.custody().read().pendingItems.containsKey(civilian.uuid().toString()));
        civilian.online = true;
        custody.deliverPendingItems(civilian);
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"));
    }

    @Test
    void legacyCuffWithoutIssuerUuidFailsClosedAndIsRecovered() {
        assertTrue(custody.requestCuffs(guard, civilian));
        var request = ctx.custody().read().cuffRequests.values().iterator().next();
        assertTrue(custody.accept(civilian, request.id));

        var store = ctx.custody().read();
        store.cuffed.get(civilian.uuid().toString()).issuerUuid = "";
        ctx.custody().write(store);

        custody.tick();

        assertFalse(custody.isCuffed(civilian));
        assertTrue(audit.tail(20).stream().anyMatch(e -> "cuff_recovery".equals(e.action)
                && e.details.contains("issuer_no_longer_eligible")));
    }

    @Test
    void cuffRequestRequiresCuffsItemAndRank() {
        TestPlayer junior = server.add("jr");
        setRank(junior, Rank.STAGIAR); // junior lacks useCuffs
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
        assertEquals(CustodyRoleplayUseCase.DamageAction.ALLOW_NONLETHAL, outcome.action());
        assertEquals(9, custody.capBatonDamage(10, 0)); // never below 1 hp
    }

    @Test
    void batonLethalKnockoutRequestsSurrender() {
        hold(guard, CustodyService.BATON);
        var outcome = custody.batonStrike(guard, civilian, 6, 0, 8);
        assertEquals(CustodyRoleplayUseCase.DamageAction.CANCEL, outcome.action());
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
    void batonRefuseSurrenderDiesAtCanonicalDeadline() {
        hold(guard, CustodyService.BATON);
        custody.batonStrike(guard, civilian, 6, 0, 8);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        assertTrue(custody.refuse(civilian, req.id));
        assertTrue(custody.isDowned(civilian));
        assertFalse(custody.isCuffed(civilian));
        // The canonical deadline wins over the legacy wake projection.
        civilian.teleport("minecraft:overworld", 999, 64, 999);
        clock.advance(60_000);
        custody.tick();
        assertFalse(custody.isDowned(civilian));
        assertEquals(0, civilian.health);
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

    @Test
    void canonicalDeadlineSweepRunsThroughTheExistingCustodyTick() {
        ctx.policies().downedDurationSeconds = 1;
        var state = new CustodyState();
        state.playerId = civilian.uuid().toString();
        state.playerUuid = civilian.uuid().toString();
        state.playerName = civilian.name();
        var entered = CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED,
                        clock.now, "weapon"), ctx.policies());
        assertTrue(entered.ok());
        var store = ctx.custody().read();
        store.states.put(state.playerId, state);
        ctx.custody().write(store);

        clock.advance(999);
        custody.tick();
        assertEquals(PlayerCondition.DOWNED, ctx.custody().read().states.get(state.playerId).condition);
        clock.advance(1);
        custody.tick();
        assertEquals(PlayerCondition.DEAD, ctx.custody().read().states.get(state.playerId).condition);
        custody.tick();
        assertEquals(PlayerCondition.DEAD, ctx.custody().read().states.get(state.playerId).condition);
    }

    @Test
    void canonicalDeadlineContinuesAcrossLogoutAndResolvesOnLogin() {
        ctx.policies().downedDurationSeconds = 1;
        var state = new CustodyState();
        state.playerId = civilian.uuid().toString();
        state.playerUuid = civilian.uuid().toString();
        state.playerName = civilian.name();
        assertTrue(CustodyTransitionEngine.apply(state,
                CustodyTransition.of("down-1", CustodyTransition.Action.ENTER_DOWNED,
                        clock.now, "weapon"), ctx.policies()).ok());
        var store = ctx.custody().read();
        store.states.put(state.playerId, state);
        ctx.custody().write(store);

        civilian.online = false;
        custody.recoverOnLogout(civilian); // default RETAIN does not reset the deadline
        clock.advance(1_001);
        civilian.online = true;
        custody.recoverOnLogin(civilian);

        assertEquals(PlayerCondition.DEAD,
                ctx.custody().read().states.get(state.playerId).condition);
    }

    // ------------------------------------------------------------ projection

    private void holdNothing(TestPlayer p) {
        for (int i = 0; i < p.inventory.slots(); i++) {
            if (p.inventory.stackAt(i).isEmpty()) { p.selectSlot(i); return; }
        }
    }

    private java.util.List<CustodyRoleplayUseCase.AvailableAction> actionsOf(
            TestPlayer p, CustodyRoleplayUseCase.Action action) {
        return custody.availableActions(p).stream()
                .filter(a -> a.action() == action).toList();
    }

    @Test
    void projectionEmitsRequestAcceptRefuseAndGiveCuffs() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();

        var targetIds = custody.availableActions(civilian).stream()
                .map(a -> a.action() + ":" + a.recordId()).toList();
        assertTrue(targetIds.contains("ACCEPT_REQUEST:" + req.id));
        assertTrue(targetIds.contains("REFUSE_REQUEST:" + req.id));

        var guardActions = custody.availableActions(guard).stream()
                .map(CustodyRoleplayUseCase.AvailableAction::action).toList();
        assertTrue(guardActions.contains(CustodyRoleplayUseCase.Action.GIVE_CUFFS));
        assertFalse(guardActions.contains(CustodyRoleplayUseCase.Action.ACCEPT_REQUEST));
        assertFalse(actionsOf(civilian, CustodyRoleplayUseCase.Action.GIVE_CUFFS).stream()
                .findAny().isPresent());
        // expired request disappears from the projection
        clock.advance(61_000);
        assertTrue(custody.availableActions(civilian).isEmpty());
    }

    @Test
    void projectionExposesHeadSackAndWakeDowned() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        hold(guard, CustodyService.HEAD_SACK);
        custody.applyHeadSack(guard, civilian);
        var kinds = custody.availableActions(civilian).stream()
                .map(CustodyRoleplayUseCase.AvailableAction::action).toList();
        assertTrue(kinds.contains(CustodyRoleplayUseCase.Action.REMOVE_HEAD_SACK));

        custody.startDowned(civilian, guard, "test");
        // downed + before cooldown: neither removal nor wake is offered
        kinds = custody.availableActions(civilian).stream()
                .map(CustodyRoleplayUseCase.AvailableAction::action).toList();
        assertFalse(kinds.contains(CustodyRoleplayUseCase.Action.REMOVE_HEAD_SACK));
        assertFalse(kinds.contains(CustodyRoleplayUseCase.Action.WAKE_DOWNED));
        clock.advance(61_000);
        custody.tick();
        assertTrue(actionsOf(civilian, CustodyRoleplayUseCase.Action.WAKE_DOWNED).isEmpty());
    }

    @Test
    void projectionReleaseRequiresToolOnlineRestrainedOther() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);

        // no release-capable tool in hand -> no release action
        holdNothing(guard);
        assertTrue(actionsOf(guard, CustodyRoleplayUseCase.Action.RELEASE_TARGET).isEmpty());

        hold(guard, CustodyService.CUFF_KEY);
        var release = actionsOf(guard, CustodyRoleplayUseCase.Action.RELEASE_TARGET);
        assertEquals(1, release.size());
        assertEquals(civilian.uuid().toString(), release.get(0).recordId());

        // the restrained player never sees itself as a release target
        giveItem(civilian, CustodyService.CUFF_KEY);
        hold(civilian, CustodyService.CUFF_KEY);
        assertTrue(actionsOf(civilian, CustodyRoleplayUseCase.Action.RELEASE_TARGET).isEmpty());

        // offline target -> not actionable
        civilian.online = false;
        assertTrue(actionsOf(guard, CustodyRoleplayUseCase.Action.RELEASE_TARGET).isEmpty());
        civilian.online = true;

        // malformed persisted record is dropped silently, never surfaces
        var store = ctx.custody().read();
        store.cuffed.put("ghost", new CustodyStore.CuffRecord());
        ctx.custody().write(store);
        release = assertDoesNotThrow(() ->
                actionsOf(guard, CustodyRoleplayUseCase.Action.RELEASE_TARGET));
        assertEquals(1, release.size());
        assertEquals(civilian.uuid().toString(), release.get(0).recordId());
    }

    @Test
    void releaseByIdResolvesOnlineTargetByUuid() {
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        hold(guard, CustodyService.CUFF_KEY);
        assertTrue(custody.releaseById(guard, civilian.uuid().toString()));
        assertFalse(custody.isCuffed(civilian));

        custody.requestCuffs(guard, civilian);
        var req2 = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req2.id);
        giveItem(guard, CustodyService.CUFF_KEY);
        hold(guard, CustodyService.CUFF_KEY);
        assertFalse(custody.releaseById(guard, "not-a-player"));
        assertFalse(custody.releaseById(guard, null));
        civilian.online = false;
        assertFalse(custody.releaseById(guard, civilian.uuid().toString()));
        assertTrue(custody.isCuffed(civilian), "offline target must fail closed");
    }

    // ------------------------------------------------------------ recovery

    @Test
    void loginRecoveryRevalidatesIssuerAndRestoresHiddenItemOnce() {
        giveItem(civilian, "minecraft:diamond_sword");
        hold(civilian, "minecraft:diamond_sword");
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        assertEquals(0, civilian.inventory.countOf("minecraft:diamond_sword"));

        var state = players.state(guard.uuid());
        state.fired = true;
        players.save(guard.uuid(), state);

        custody.recoverOnLogin(civilian);
        assertFalse(custody.isCuffed(civilian));
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"));
        assertTrue(audit.tail(20).stream().anyMatch(e -> "cuff_recovery".equals(e.action)
                && e.details.contains("issuer_no_longer_eligible")));

        custody.recoverOnLogin(civilian);
        assertEquals(1, civilian.inventory.countOf("minecraft:diamond_sword"),
                "login recovery must not duplicate the restored item");
    }

    @Test
    void logoutRecoveryDropsOnlyTransientRequests() {
        // durable restraint on civilian
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        // transient request issued by guard to a second player
        var civ2 = server.add("civ2");
        assertTrue(custody.requestCuffs(guard, civ2));
        assertFalse(ctx.custody().read().cuffRequests.isEmpty());

        custody.recoverOnLogout(guard);
        assertTrue(ctx.custody().read().cuffRequests.isEmpty(),
                "logout removes requests issued by the player");
        assertTrue(custody.isCuffed(civilian), "durable restraint survives logout");

        custody.recoverOnLogout(civilian);
        assertTrue(custody.isCuffed(civilian), "target logout keeps the cuff record");
    }

    @Test
    void deathRecoveryQueuesHiddenItemAndClearsStateIdempotently() {
        giveItem(civilian, "minecraft:diamond_sword");
        hold(civilian, "minecraft:diamond_sword");
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        hold(guard, CustodyService.ROPE);
        custody.applyRope(guard, civilian);
        hold(guard, CustodyService.HEAD_SACK);
        custody.applyHeadSack(guard, civilian);
        custody.startDowned(civilian, guard, "test");
        var civ2 = server.add("civ2");
        custody.requestCuffs(guard, civ2); // unrelated pending request survives

        custody.recoverAfterDeath(civilian);

        var store = ctx.custody().read();
        assertFalse(store.cuffed.containsKey(civilian.uuid().toString()));
        assertFalse(store.bound.containsKey(civilian.uuid().toString()));
        assertFalse(store.headSacks.containsKey(civilian.uuid().toString()));
        assertFalse(store.downed.containsKey(civilian.uuid().toString()));
        assertEquals(1, store.cuffRequests.size(), "unrelated request must survive");
        var pending = store.pendingItems.get(civilian.uuid().toString());
        assertNotNull(pending, "hidden item must be queued, never injected mid-death");
        assertEquals(1, pending.size());
        assertEquals("minecraft:diamond_sword", pending.get(0).itemId);
        assertEquals(0, civilian.inventory.countOf("minecraft:diamond_sword"));
        assertTrue(audit.tail(20).stream().anyMatch(e -> "custody_death_recovery".equals(e.action)));

        custody.recoverAfterDeath(civilian);
        var after = ctx.custody().read();
        assertEquals(1, after.pendingItems.get(civilian.uuid().toString()).size(),
                "second death recovery must not queue duplicates");
        assertEquals(0, civilian.inventory.countOf("minecraft:diamond_sword"));
    }

    @Test
    void tickDropsMalformedRecordsAndOrphanHeadSack() {
        var store = ctx.custody().read();
        // cuff record with no usable identity -> malformed, fail closed
        store.cuffed.put("ghost", new CustodyStore.CuffRecord());
        // bound record with no usable identity under a real key -> malformed
        store.bound.put(civilian.uuid().toString(), new CustodyStore.BoundRecord());
        // orphan head sack: valid identity but no cuff/bound record
        var sack = new CustodyStore.HeadSackRecord();
        sack.target = civilian.name();
        sack.targetUuid = civilian.uuid().toString();
        sack.issuer = guard.name();
        sack.issuerUuid = guard.uuid().toString();
        store.headSacks.put("orphan_" + civilian.uuid(), sack);
        // downed record with no identity -> malformed
        store.downed.put("ghost_downed", new CustodyStore.DownedRecord());
        // downed record with valid identity but unusable wake state -> malformed
        var brokenDowned = new CustodyStore.DownedRecord();
        brokenDowned.target = civilian.name();
        brokenDowned.targetUuid = civilian.uuid().toString();
        brokenDowned.dimension = "";
        brokenDowned.wakesAt = 0;
        store.downed.put("broken_downed", brokenDowned);
        // persisted null / blank-id requests must not NPE the expiry sweep
        store.cuffRequests.put("null_request", null);
        store.cuffRequests.put("blank_request", new CustodyStore.CuffRequest());
        ctx.custody().write(store);

        assertDoesNotThrow(() -> custody.tick());

        var after = ctx.custody().read();
        assertFalse(after.cuffed.containsKey("ghost"));
        assertFalse(after.bound.containsKey(civilian.uuid().toString()));
        assertTrue(after.headSacks.isEmpty(), "orphan head sack must be removed");
        assertFalse(after.downed.containsKey("ghost_downed"));
        assertFalse(after.downed.containsKey("broken_downed"));
        assertFalse(after.cuffRequests.containsKey("null_request"));
        assertFalse(after.cuffRequests.containsKey("blank_request"));
        assertTrue(audit.tail(40).stream().anyMatch(e ->
                e.details != null && e.details.contains("malformed")
                        || e.details != null && e.details.contains("orphan")));
    }

    @Test
    void loginAndTickDropRestraintsWithMalformedOrIneligibleIssuer() {
        // valid cuff from the real guard + bound/sack with a bogus issuer UUID
        custody.requestCuffs(guard, civilian);
        var req = ctx.custody().read().cuffRequests.values().iterator().next();
        custody.accept(civilian, req.id);
        var store = ctx.custody().read();
        var bound = new CustodyStore.BoundRecord();
        bound.target = civilian.name();
        bound.targetUuid = civilian.uuid().toString();
        bound.issuer = "ghost";
        bound.issuerUuid = "not-a-uuid";
        store.bound.put(civilian.uuid().toString(), bound);
        var sack = new CustodyStore.HeadSackRecord();
        sack.target = civilian.name();
        sack.targetUuid = civilian.uuid().toString();
        sack.issuer = "ghost";
        sack.issuerUuid = "";
        store.headSacks.put(civilian.uuid().toString(), sack);
        ctx.custody().write(store);

        custody.recoverOnLogin(civilian);

        var after = ctx.custody().read();
        assertTrue(custody.isCuffed(civilian), "valid cuff survives");
        assertFalse(after.bound.containsKey(civilian.uuid().toString()),
                "bound record with malformed issuer UUID must be dropped");
        assertFalse(after.headSacks.containsKey(civilian.uuid().toString()),
                "head sack with malformed issuer UUID must be dropped");
        assertTrue(audit.tail(30).stream().anyMatch(e -> "custody_recovery".equals(e.action)
                && e.details.contains("bound") && e.details.contains("issuer")));
        assertTrue(audit.tail(30).stream().anyMatch(e -> "custody_recovery".equals(e.action)
                && e.details.contains("head_sack") && e.details.contains("issuer")));
    }

    @Test
    void tickDropsBoundAndOrphanedSackWhenIssuerBecomesIneligible() {
        // bound + sack issued by the real guard with valid provenance
        var store = ctx.custody().read();
        var bound = new CustodyStore.BoundRecord();
        bound.target = civilian.name();
        bound.targetUuid = civilian.uuid().toString();
        bound.issuer = guard.name();
        bound.issuerUuid = guard.uuid().toString();
        store.bound.put(civilian.uuid().toString(), bound);
        var sack = new CustodyStore.HeadSackRecord();
        sack.target = civilian.name();
        sack.targetUuid = civilian.uuid().toString();
        sack.issuer = guard.name();
        sack.issuerUuid = guard.uuid().toString();
        store.headSacks.put(civilian.uuid().toString(), sack);
        ctx.custody().write(store);
        // issuer loses authority before the next tick
        var state = players.state(guard.uuid());
        state.fired = true;
        players.save(guard.uuid(), state);

        custody.tick();

        var after = ctx.custody().read();
        assertFalse(after.bound.containsKey(civilian.uuid().toString()),
                "bound record must be dropped when the issuer is no longer eligible");
        assertTrue(after.headSacks.isEmpty(),
                "dropping the bound record orphans the sack in the same pass");
    }

    @Test
    void loginRecoveryDropsMalformedDownedRecord() {
        var store = ctx.custody().read();
        var downed = new CustodyStore.DownedRecord();
        downed.target = civilian.name();
        downed.targetUuid = civilian.uuid().toString();
        downed.dimension = "";
        downed.wakesAt = 0;
        store.downed.put(civilian.uuid().toString(), downed);
        ctx.custody().write(store);

        custody.recoverOnLogin(civilian);

        assertFalse(ctx.custody().read().downed.containsKey(civilian.uuid().toString()),
                "malformed downed record must be discarded, not applied");
        assertTrue(audit.tail(20).stream().anyMatch(e -> "custody_recovery".equals(e.action)
                && e.details.contains("downed")));
    }

    @Test
    void pendingItemsSkipMalformedEntriesAndDeliverValidOnce() {
        var store = ctx.custody().read();
        var items = new java.util.ArrayList<CustodyStore.PendingItem>();
        items.add(null);
        items.add(new CustodyStore.PendingItem()); // blank itemId, count 0
        var empty = new CustodyStore.PendingItem();
        empty.itemId = "minecraft:stone";
        empty.count = 0;
        items.add(empty);
        var good = new CustodyStore.PendingItem();
        good.itemId = "minecraft:apple";
        good.count = 2;
        items.add(good);
        store.pendingItems.put(civilian.uuid().toString(), items);
        ctx.custody().write(store);

        custody.deliverPendingItems(civilian);

        assertEquals(2, civilian.inventory.countOf("minecraft:apple"),
                "valid items after malformed entries must still deliver");
        assertTrue(ctx.custody().read().pendingItems.isEmpty(),
                "malformed entries and delivered items must all be removed");
        assertTrue(audit.tail(20).stream().anyMatch(e ->
                e.details != null && e.details.contains("malformed_pending_item")));
        custody.deliverPendingItems(civilian);
        assertEquals(2, civilian.inventory.countOf("minecraft:apple"), "no duplicate delivery");
    }
}
