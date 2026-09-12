package com.dwurdy.straja.application.port.out;

import java.util.List;

/**
 * Physical currency operations. Implemented by Ady's Decorations. All amounts
 * are in base units (bronze = 1). Every withdrawal/deposit is receipt-scoped
 * for idempotency.
 */
public interface CurrencyProvider {
    /** Provider identity for readiness reports. */
    String name();

    /** True when the backing mod and all registry items are present. */
    boolean available();

    /** Currency map value->itemId, e.g. 1 -> some_mod:bronze_coin. */
    java.util.Map<Integer, String> denominations();

    /** Total value of currency items currently held by the player. */
    int balanceOf(PlayerGateway player);

    /**
     * Removes exactly {@code amount} from the player inventory.
     * Atomic in the provider contract: either the full amount leaves the
     * inventory or nothing does.
     */
    Withdrawal withdraw(PlayerGateway player, int amount);

    /**
     * Delivers {@code amount} as physical coins with a payout receipt.
     * Replays of the same payoutId must not double-deliver.
     */
    Deposit deposit(PlayerGateway player, int amount, String payoutId);

    /** True when a stack carries the receipt for the given payout id. */
    boolean hasReceipt(PlayerGateway player, String payoutId);

    record Withdrawal(boolean ok, int removed, String error) {
        public static Withdrawal ok(int removed) { return new Withdrawal(true, removed, null); }
        public static Withdrawal failed(String error) { return new Withdrawal(false, 0, error); }
    }

    record Deposit(boolean ok, int delivered, String error) {
        public static Deposit ok(int delivered) { return new Deposit(true, delivered, null); }
        public static Deposit failed(String error) { return new Deposit(false, 0, error); }
    }
}
