package com.dwurdy.straja.adapter.out.economy;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Atomicity contract of {@link ItemCoinCurrencyProvider#withdraw}:
 * either the full amount leaves the inventory or nothing does.
 * Denominations follow the 64:1 ladder (§10): 1 / 64 / 4096 / 262144.
 */
class ItemCoinCurrencyProviderTest {

    private static final String BRONZE = "test:bronze";
    private static final String BRASS = "test:brass";
    private static final String SILVER = "test:silver";
    private static final String GOLD = "test:gold";

    private final ItemCoinCurrencyProvider currency = new ItemCoinCurrencyProvider(
            () -> Map.of(1, BRONZE, 64, BRASS, 4096, SILVER, 262144, GOLD));

    private TestPlayer playerWith(Object... slotSpecs) {
        TestPlayer player = new TestPlayer("payer", 36);
        for (int i = 0; i < slotSpecs.length; i++) {
            if (slotSpecs[i] instanceof ItemSpec spec) {
                player.inventory.slots.set(i, new ItemView(spec.id(), spec.count(), 64, spec.customData()));
            }
        }
        return player;
    }

    private static ItemView stone64() {
        return new ItemView("minecraft:stone", 64, 64, Map.of());
    }

    @Test
    void withdrawExactChangeSucceeds() {
        TestPlayer p = playerWith(ItemSpec.of(BRASS, 3), ItemSpec.of(BRONZE, 2));
        var result = currency.withdraw(p, 130); // 2×brass + 2×bronze
        assertTrue(result.ok());
        assertEquals(130, result.removed());
        assertEquals(64, currency.balanceOf(p));
        assertEquals(1, p.inventory.countOf(BRASS));
        assertEquals(0, p.inventory.countOf(BRONZE));
    }

    @Test
    void withdrawBreaksCoinAndReturnsChange() {
        TestPlayer p = playerWith(ItemSpec.of(SILVER, 1));
        var result = currency.withdraw(p, 64);
        assertTrue(result.ok());
        assertEquals(0, p.inventory.countOf(SILVER));
        assertEquals(63, p.inventory.countOf(BRASS));
        assertEquals(4032, currency.balanceOf(p));
    }

    @Test
    void withdrawInsufficientFunds() {
        TestPlayer p = playerWith();
        var result = currency.withdraw(p, 5);
        assertFalse(result.ok());
        assertEquals("insufficient_funds", result.error());
        assertEquals(0, result.removed());
    }

    @Test
    void withdrawFailsClosedWithoutRemovingCoins() {
        TestPlayer p = playerWith(ItemSpec.of(BRASS, 1), ItemSpec.of(BRONZE, 3));
        var result = currency.withdraw(p, 70); // balance 67 < 70
        assertFalse(result.ok());
        assertEquals(0, result.removed());
        assertEquals(67, currency.balanceOf(p), "failed withdrawal must not remove coins");
    }

    @Test
    void withdrawChangeReusesFreedSlot() {
        // Full inventory: breaking the brass coin frees its own slot for change.
        TestPlayer p = playerWith(ItemSpec.of(BRASS, 1));
        for (int i = 1; i < p.inventory.slots(); i++) p.inventory.slots.set(i, stone64());
        var result = currency.withdraw(p, 5);
        assertTrue(result.ok());
        assertEquals(5, result.removed());
        assertEquals(0, p.inventory.countOf(BRASS));
        assertEquals(59, p.inventory.countOf(BRONZE));
        assertEquals(59, currency.balanceOf(p));
    }

    @Test
    void withdrawFullInventoryKeepsCoinsAtomic() {
        // Change of 262143 needs several denomination stacks but only 1 slot frees up.
        TestPlayer p = playerWith(ItemSpec.of(GOLD, 1));
        for (int i = 1; i < p.inventory.slots(); i++) p.inventory.slots.set(i, stone64());
        var result = currency.withdraw(p, 1);
        assertFalse(result.ok());
        assertEquals("inventory_full", result.error());
        assertEquals(0, result.removed());
        assertEquals(262144, currency.balanceOf(p), "unfittable change must not destroy the coin");
    }

    @Test
    void withdrawRollsBackWhenChangeDeliveryFails() {
        TestPlayer p = playerWith(ItemSpec.of(BRASS, 1));
        p.failVerifiedCalls = 1; // the change deposit fails once, the rollback give succeeds
        var result = currency.withdraw(p, 5);
        assertFalse(result.ok());
        assertEquals("change_delivery_failed", result.error());
        assertEquals(0, result.removed());
        assertEquals(64, currency.balanceOf(p), "rollback must restore the extracted coin");
        assertEquals(1, p.inventory.countOf(BRASS));
    }

    @Test
    void withdrawOnEmptyInventoryViewFailsClosed() {
        InventoryView empty = InventoryView.empty();
        assertEquals(0, empty.slots());
        assertEquals(0, empty.countOf(BRASS));
        assertFalse(empty.canReceive(List.of(ItemSpec.of(BRONZE, 1))));
        assertTrue(empty.extract(0, 1).isEmpty());
    }

    // §10 acceptance: 64:1 decomposition pays largest-first with no truncation.

    @Test
    void depositPaysTwoBrassFor128() {
        TestPlayer p = playerWith();
        var result = currency.deposit(p, 128, null);
        assertTrue(result.ok());
        assertEquals(2, p.inventory.countOf(BRASS));
        assertEquals(0, p.inventory.countOf(BRONZE));
        assertEquals(128, currency.balanceOf(p));
    }

    @Test
    void depositPaysMixedDenominationsFor72() {
        TestPlayer p = playerWith();
        var result = currency.deposit(p, 72, null);
        assertTrue(result.ok());
        assertEquals(1, p.inventory.countOf(BRASS), "1×brass");
        assertEquals(8, p.inventory.countOf(BRONZE), "8×bronze");
        assertEquals(72, currency.balanceOf(p));
    }

    @Test
    void depositCrossesDenominationBoundaries() {
        TestPlayer p = playerWith();
        // 262144 + 4096 + 64 + 1 = 266305 → 1 gold, 1 silver, 1 brass, 1 bronze.
        var result = currency.deposit(p, 262144 + 4096 + 64 + 1, null);
        assertTrue(result.ok());
        assertEquals(1, p.inventory.countOf(GOLD));
        assertEquals(1, p.inventory.countOf(SILVER));
        assertEquals(1, p.inventory.countOf(BRASS));
        assertEquals(1, p.inventory.countOf(BRONZE));
    }
}
