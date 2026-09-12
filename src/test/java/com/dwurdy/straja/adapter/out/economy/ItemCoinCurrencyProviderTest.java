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
 */
class ItemCoinCurrencyProviderTest {

    private static final String BRONZE = "test:bronze";
    private static final String BRASS = "test:brass";
    private static final String SILVER = "test:silver";
    private static final String GOLD = "test:gold";

    private final ItemCoinCurrencyProvider currency = new ItemCoinCurrencyProvider(
            Map.of(1, BRONZE, 10, BRASS, 100, SILVER, 1000, GOLD));

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
        var result = currency.withdraw(p, 12);
        assertTrue(result.ok());
        assertEquals(12, result.removed());
        assertEquals(20, currency.balanceOf(p));
        assertEquals(2, p.inventory.countOf(BRASS));
        assertEquals(0, p.inventory.countOf(BRONZE));
    }

    @Test
    void withdrawBreaksCoinAndReturnsChange() {
        TestPlayer p = playerWith(ItemSpec.of(SILVER, 1));
        var result = currency.withdraw(p, 30);
        assertTrue(result.ok());
        assertEquals(0, p.inventory.countOf(SILVER));
        assertEquals(7, p.inventory.countOf(BRASS));
        assertEquals(70, currency.balanceOf(p));
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
        var result = currency.withdraw(p, 15); // balance 13 < 15
        assertFalse(result.ok());
        assertEquals(0, result.removed());
        assertEquals(13, currency.balanceOf(p), "failed withdrawal must not remove coins");
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
        assertEquals(5, p.inventory.countOf(BRONZE));
        assertEquals(5, currency.balanceOf(p));
    }

    @Test
    void withdrawFullInventoryKeepsCoinsAtomic() {
        // Change of 999 needs 3 denomination stacks but only 1 slot frees up.
        TestPlayer p = playerWith(ItemSpec.of(GOLD, 1));
        for (int i = 1; i < p.inventory.slots(); i++) p.inventory.slots.set(i, stone64());
        var result = currency.withdraw(p, 1);
        assertFalse(result.ok());
        assertEquals("inventory_full", result.error());
        assertEquals(0, result.removed());
        assertEquals(1000, currency.balanceOf(p), "unfittable change must not destroy the coin");
    }

    @Test
    void withdrawRollsBackWhenChangeDeliveryFails() {
        TestPlayer p = playerWith(ItemSpec.of(BRASS, 1));
        p.failVerifiedCalls = 1; // the change deposit fails once, the rollback give succeeds
        var result = currency.withdraw(p, 5);
        assertFalse(result.ok());
        assertEquals("change_delivery_failed", result.error());
        assertEquals(0, result.removed());
        assertEquals(10, currency.balanceOf(p), "rollback must restore the extracted coin");
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
}
