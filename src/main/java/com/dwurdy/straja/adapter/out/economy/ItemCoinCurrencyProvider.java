package com.dwurdy.straja.adapter.out.economy;

import com.dwurdy.straja.application.port.out.CurrencyProvider;
import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Physical coin economy backed by configured item IDs ([economy] config).
 * Default denominations (64:1 ladder, §10): bronze=1, brass=64,
 * silver=4096, gold=262144 (Ady's Decorations items), but any
 * registered item IDs work.
 * Withdrawals are exact-change aware; deposits carry payout receipts.
 */
public class ItemCoinCurrencyProvider implements CurrencyProvider {
    public static final String RECEIPT_TAG = "StrajaPayoutId";

    private final java.util.function.Supplier<Map<Integer, String>> denominationsSource;

    /**
     * The denomination table is resolved per call so runtime policy overrides
     * ({@code /straja policy set economy.coinItems …}) reach payouts without a
     * restart.
     */
    public ItemCoinCurrencyProvider(java.util.function.Supplier<Map<Integer, String>> denominationsSource) {
        this.denominationsSource = denominationsSource;
    }

    private TreeMap<Integer, String> denominationsTable() {
        return new TreeMap<>(denominationsSource.get());
    }

    @Override public String name() { return "configured coin items"; }

    @Override public boolean available() {
        for (String id : denominationsTable().values()) {
            ResourceLocation location = ResourceLocation.parse(id);
            var item = BuiltInRegistries.ITEM.getOptional(location);
            if (item.isEmpty() || "minecraft:air".equals(item.get().toString())) return false;
        }
        return true;
    }

    @Override public Map<Integer, String> denominations() { return denominationsTable(); }

    @Override public int balanceOf(PlayerGateway player) {
        InventoryView inventory = player.inventory();
        if (inventory == null) return 0;
        int total = 0;
        for (var entry : denominationsTable().entrySet()) {
            total += inventory.countOf(entry.getValue()) * entry.getKey();
        }
        return total;
    }

    /**
     * Exact-change withdrawal: descending denominations, then breaks one
     * smallest larger coin and returns change. Fails closed — either the full
     * amount leaves or nothing does. The plan (whole coins + break coin) is
     * computed without mutating; after extraction the change is checked against
     * the freed slots, and any delivery failure reclaims the change and rolls
     * the extracted coins back.
     */
    @Override public Withdrawal withdraw(PlayerGateway player, int amount) {
        InventoryView inventory = player.inventory();
        if (inventory == null) return Withdrawal.failed("unavailable");
        if (amount <= 0) return Withdrawal.ok(0);
        if (balanceOf(player) < amount) return Withdrawal.failed("insufficient_funds");

        // Plan pass 1 without mutating: whole coins descending, never overpaying.
        int remaining = amount;
        var planned = new java.util.LinkedHashMap<Integer, Integer>();
        for (var entry : denominationsTable().descendingMap().entrySet()) {
            int value = entry.getKey();
            int take = Math.min(inventory.countOf(entry.getValue()), remaining / value);
            if (take > 0) {
                planned.merge(value, take, Integer::sum);
                remaining -= take * value;
            }
        }

        // Plan the break coin: smallest denomination that covers the remainder,
        // accounting for coins already planned for removal.
        Integer breakValue = null;
        int change = 0;
        if (remaining > 0) {
            for (var entry : denominationsTable().entrySet()) {
                int unplanned = inventory.countOf(entry.getValue())
                        - planned.getOrDefault(entry.getKey(), 0);
                if (entry.getKey() >= remaining && unplanned > 0) {
                    breakValue = entry.getKey();
                    break;
                }
            }
            if (breakValue == null) return Withdrawal.failed("exact_change_unavailable");
            change = breakValue - remaining;
        }
        List<ItemSpec> changeStacks = change > 0 ? stacksFor(change, null) : List.of();

        // Execute the plan. A shortfall or failed change delivery rolls every
        // extracted coin back so the atomic contract always holds.
        var extracted = new java.util.ArrayList<ItemSpec>();
        for (var entry : planned.entrySet()) {
            String itemId = denominationsTable().get(entry.getKey());
            int toRemove = entry.getValue();
            for (int slot = 0; slot < inventory.slots() && toRemove > 0; slot++) {
                ItemView stack = inventory.stackAt(slot);
                if (stack.isEmpty() || !itemId.equals(stack.id())) continue;
                int took = inventory.extract(slot, Math.min(stack.count(), toRemove)).count();
                if (took > 0) extracted.add(ItemSpec.of(itemId, took));
                toRemove -= took;
            }
            if (toRemove > 0) {
                restore(player, extracted);
                return Withdrawal.failed("exact_change_unavailable");
            }
        }
        if (breakValue != null) {
            String itemId = denominationsTable().get(breakValue);
            boolean broke = false;
            for (int slot = 0; slot < inventory.slots(); slot++) {
                ItemView stack = inventory.stackAt(slot);
                if (stack.isEmpty() || !itemId.equals(stack.id())) continue;
                if (inventory.extract(slot, 1).count() == 1) {
                    extracted.add(ItemSpec.of(itemId, 1));
                    broke = true;
                }
                break;
            }
            if (!broke) {
                restore(player, extracted);
                return Withdrawal.failed("exact_change_unavailable");
            }
        }
        // The change may reuse slots just freed by extraction.
        if (!inventory.canReceive(changeStacks)) {
            restore(player, extracted);
            return Withdrawal.failed("inventory_full");
        }
        var delivered = new java.util.ArrayList<ItemSpec>();
        for (ItemSpec stack : changeStacks) {
            if (!player.giveVerified(stack)) {
                reclaimDelivered(inventory, delivered);
                restore(player, extracted);
                return Withdrawal.failed("change_delivery_failed");
            }
            delivered.add(stack);
        }
        return Withdrawal.ok(amount);
    }

    /** Returns previously extracted coins; the freed space still exists. */
    private void restore(PlayerGateway player, List<ItemSpec> extracted) {
        for (ItemSpec spec : extracted) player.giveVerified(spec);
    }

    /** Takes back partially delivered change stacks (fungible, tag-free). */
    private void reclaimDelivered(InventoryView inventory, List<ItemSpec> delivered) {
        for (ItemSpec spec : delivered) {
            int remaining = spec.count();
            for (int slot = 0; slot < inventory.slots() && remaining > 0; slot++) {
                ItemView stack = inventory.stackAt(slot);
                if (stack.isEmpty() || !spec.id().equals(stack.id())
                        || !spec.customData().equals(stack.customData())) continue;
                remaining -= inventory.extract(slot, Math.min(stack.count(), remaining)).count();
            }
        }
    }

    /** Denomination stacks needed to pay out {@code amount}. */
    private List<ItemSpec> stacksFor(int amount, String payoutId) {
        int remaining = amount;
        List<ItemSpec> stacks = new java.util.ArrayList<>();
        for (var entry : denominationsTable().descendingMap().entrySet()) {
            int count = remaining / entry.getKey();
            if (count <= 0) continue;
            ItemSpec spec = ItemSpec.of(entry.getValue(), count);
            if (payoutId != null) spec = spec.withData(RECEIPT_TAG, payoutId);
            stacks.add(spec);
            remaining %= entry.getKey();
        }
        return stacks;
    }

    @Override public Deposit deposit(PlayerGateway player, int amount, String payoutId) {
        if (amount < 0) return Deposit.failed("invalid_amount");
        if (payoutId != null && hasReceipt(player, payoutId, amount)) {
            return Deposit.ok(0); // replay: already delivered
        }
        if (payoutId != null && hasAnyReceipt(player, payoutId)) {
            return Deposit.failed("partial_receipt_requires_review");
        }
        List<ItemSpec> stacks = stacksFor(amount, payoutId);
        InventoryView inventory = player.inventory();
        if (inventory == null || !inventory.canReceive(stacks)) return Deposit.failed("inventory_full");
        int delivered = 0;
        for (ItemSpec stack : stacks) {
            if (!player.giveVerified(stack)) {
                reclaimDelivered(player.inventory(), payoutId);
                return new Deposit(false, 0, "delivery_failed_at_denomination");
            }
            delivered += stack.count() * denominationsTable().entrySet().stream()
                    .filter(entry -> entry.getValue().equals(stack.id()))
                    .mapToInt(Map.Entry::getKey).findFirst().orElse(0);
        }
        return Deposit.ok(delivered);
    }

    @Override public boolean hasReceipt(PlayerGateway player, String payoutId) {
        return hasAnyReceipt(player, payoutId);
    }

    @Override public boolean hasReceipt(PlayerGateway player, String payoutId, int expectedAmount) {
        if (expectedAmount < 0) return false;
        InventoryView inventory = player.inventory();
        if (inventory == null) return false;
        int total = 0;
        for (var entry : denominationsTable().entrySet()) {
            for (int slot = 0; slot < inventory.slots(); slot++) {
                ItemView stack = inventory.stackAt(slot);
                if (entry.getValue().equals(stack.id()) && payoutId.equals(stack.data(RECEIPT_TAG)))
                    total += stack.count() * entry.getKey();
            }
        }
        return total == expectedAmount;
    }

    private boolean hasAnyReceipt(PlayerGateway player, String payoutId) {
        InventoryView inventory = player.inventory();
        if (inventory == null) return false;
        for (int slot = 0; slot < inventory.slots(); slot++) {
            if (payoutId.equals(inventory.stackAt(slot).data(RECEIPT_TAG))) return true;
        }
        return false;
    }

    /** Removes only this payout's tagged stacks after a non-atomic provider failure. */
    private void reclaimDelivered(InventoryView inventory, String payoutId) {
        if (inventory == null || payoutId == null) return;
        for (int slot = 0; slot < inventory.slots(); slot++) {
            ItemView stack = inventory.stackAt(slot);
            if (payoutId.equals(stack.data(RECEIPT_TAG))) inventory.extract(slot, stack.count());
        }
    }
}
