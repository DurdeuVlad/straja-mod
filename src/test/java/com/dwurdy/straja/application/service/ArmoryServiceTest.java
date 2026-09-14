package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestCurrency;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** EQ-004/EQ-005: the Armorer NPC — coin stock and requisition-point reserves. */
class ArmoryServiceTest {
    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private GuardService guards;
    private ArmoryService armory;

    @BeforeEach
    void setUp() {
        server = new TestServer();
        StrajaPolicies policies = Fakes.policies();
        ctx = Fakes.context(server, new FixedClock(0), policies);
        players = new PlayerService(ctx);
        AuditService audit = new AuditService(ctx);
        guards = new GuardService(ctx, players, audit, new EquipmentService(ctx));
        armory = new ArmoryService(ctx, players, audit);
    }

    private TestPlayer guardAtRank(int rank) {
        TestPlayer c = server.add("dwurdy");
        TestPlayer p = server.add("guard" + rank);
        assertTrue(guards.invite(c, p));
        guards.recruit(p);
        for (int i = 0; i < ctx.policies().quiz.size(); i++) {
            var state = players.state(p.uuid());
            String questionId = state.quizOrder.get(state.quizIndex);
            var question = ctx.policies().quiz.stream()
                    .filter(q -> q.id().equals(questionId)).findFirst().orElseThrow();
            guards.quiz(p, question.answers().get(0));
        }
        var state = players.state(p.uuid());
        state.rank = rank;
        players.save(p.uuid(), state);
        return p;
    }

    private int countItem(TestPlayer p, String itemId) {
        return p.inventory.countOf(itemId);
    }

    // ---------------------------------------------------------------- offers

    @Test
    void offersAreFilteredByRankAndEligibility() {
        TestPlayer stagiar = guardAtRank(Rank.STAGIAR.level());
        var offers = armory.offers(stagiar);
        assertTrue(offers.stream().allMatch(o -> o.minRank() <= Rank.STAGIAR.level()),
                "a Stagiar sees only rank-1 offers");
        assertTrue(offers.stream().anyMatch(o -> o.reserve() && o.key().equals("sword")));
        assertTrue(offers.stream().noneMatch(o -> o.key().equals("armor_diamond")),
                "rank-4 stock is hidden from a Stagiar");

        TestPlayer inspector = guardAtRank(Rank.INSPECTOR.level());
        assertTrue(armory.offers(inspector).stream().anyMatch(o -> o.key().equals("armor_diamond")));

        TestPlayer civil = server.add("civil");
        assertTrue(armory.offers(civil).isEmpty(), "civilians see no armory");

        var state = players.state(stagiar.uuid());
        state.suspended = true;
        players.save(stagiar.uuid(), state);
        assertTrue(armory.offers(stagiar).isEmpty(), "suspended guards see no armory");
    }

    // ---------------------------------------------------------------- coin stock

    @Test
    void buyChargesCoinsAndDeliversRankGatedGear() {
        TestPlayer guard = guardAtRank(Rank.GUARD.level());
        var currency = (TestCurrency) ctx.currency();
        currency.balance = 500;

        assertTrue(armory.buy(guard, "baton"));
        assertEquals(1, countItem(guard, "straja:baton"));
        assertEquals(300, currency.balance, "the listed price was withdrawn");
        assertTrue(guard.told("Ai cumpărat"));
    }

    @Test
    void buyRefusesLowRankUnknownKeyAndEmptyPurse() {
        TestPlayer stagiar = guardAtRank(Rank.STAGIAR.level());
        var currency = (TestCurrency) ctx.currency();
        currency.balance = 10_000;

        assertFalse(armory.buy(stagiar, "armor_diamond"), "rank-4 gear is gated");
        assertFalse(armory.buy(stagiar, "no-such-key"));
        currency.balance = 0;
        assertFalse(armory.buy(stagiar, "sword"), "no funds, no sale");
        assertEquals(0, countItem(stagiar, "minecraft:diamond_chestplate"));
    }

    @Test
    void buyRefundsWhenDeliveryFails() {
        TestPlayer guard = guardAtRank(Rank.GUARD.level());
        var currency = (TestCurrency) ctx.currency();
        currency.balance = 500;
        guard.failVerifiedCalls = 1;

        assertFalse(armory.buy(guard, "baton"));
        assertEquals(500, currency.balance, "a failed delivery refunds the withdrawal");
        assertEquals(0, countItem(guard, "straja:baton"));
        assertTrue(guard.told("monedele au fost returnate"));
    }

    // ---------------------------------------------------------------- requisition reserves

    @Test
    void reserveSpendsRequisitionPointsNotServiceBlocks() {
        TestPlayer stagiar = guardAtRank(Rank.STAGIAR.level());
        var state = players.state(stagiar.uuid());
        state.requisitionPoints = 5;
        state.serviceBlocks = 42;
        players.save(stagiar.uuid(), state);

        int shieldsBefore = countItem(stagiar, "minecraft:shield");
        assertTrue(armory.buyReserve(stagiar, "shield"));
        state = players.state(stagiar.uuid());
        assertEquals(2, state.requisitionPoints, "the point price was spent");
        assertEquals(42, state.serviceBlocks,
                "promotion progress is never spendable");
        assertEquals(shieldsBefore + 1, countItem(stagiar, "minecraft:shield"));
    }

    @Test
    void reserveRefusesInsufficientPointsAndLowRank() {
        TestPlayer stagiar = guardAtRank(Rank.STAGIAR.level());
        var state = players.state(stagiar.uuid());
        state.requisitionPoints = 2; // sword costs 3
        players.save(stagiar.uuid(), state);

        assertFalse(armory.buyReserve(stagiar, "sword"));
        assertFalse(armory.buyReserve(stagiar, "baton"), "baton is a rank-2 reserve");
        assertEquals(2, players.state(stagiar.uuid()).requisitionPoints,
                "refusals never deduct points");
    }

    @Test
    void reserveRestoresPointsWhenDeliveryFails() {
        TestPlayer stagiar = guardAtRank(Rank.STAGIAR.level());
        var state = players.state(stagiar.uuid());
        state.requisitionPoints = 5;
        players.save(stagiar.uuid(), state);
        stagiar.failVerifiedCalls = 1;

        assertFalse(armory.buyReserve(stagiar, "shield"));
        assertEquals(5, players.state(stagiar.uuid()).requisitionPoints,
                "a failed delivery restores the spent points");
    }

    @Test
    void reserveChecksCapacityBeforeSpending() {
        TestPlayer stagiar = guardAtRank(Rank.STAGIAR.level());
        var state = players.state(stagiar.uuid());
        state.requisitionPoints = 5;
        players.save(stagiar.uuid(), state);
        for (int i = 0; i < stagiar.inventory.slots(); i++) {
            stagiar.inventory.slots.set(i, new com.dwurdy.straja.application.port.out.ItemView(
                    "minecraft:stone", 64, 64, java.util.Map.of()));
        }

        assertFalse(armory.buyReserve(stagiar, "shield"));
        assertEquals(5, players.state(stagiar.uuid()).requisitionPoints,
                "a full inventory never burns points");
    }
}
