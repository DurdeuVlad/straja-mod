package com.dwurdy.straja.adapter.in.test;

import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * In-memory player-shaped inventory (41 slots) for virtual test players.
 * Stack merge rules match vanilla: same item id + same custom data merge up
 * to the item's max stack size.
 */
public final class VirtualInventory implements InventoryView {
    public static final int SLOTS = 41;
    private static final int DEFAULT_MAX = 64;

    private final ItemView[] stacks = new ItemView[SLOTS];

    public VirtualInventory() {
        Arrays.fill(stacks, ItemView.EMPTY);
    }

    @Override public int slots() { return SLOTS; }

    @Override public ItemView stackAt(int slot) {
        return slot >= 0 && slot < SLOTS ? stacks[slot] : ItemView.EMPTY;
    }

    @Override public ItemView extract(int slot, int amount) {
        if (slot < 0 || slot >= SLOTS || amount <= 0) return ItemView.EMPTY;
        ItemView stack = stacks[slot];
        if (stack.isEmpty()) return ItemView.EMPTY;
        int taken = Math.min(amount, stack.count());
        stacks[slot] = stack.count() - taken <= 0 ? ItemView.EMPTY : stack.withCount(stack.count() - taken);
        return stack.withCount(taken);
    }

    @Override public boolean canReceive(List<ItemSpec> items) {
        // Per (id+data) group, count the free room.
        var needed = new java.util.LinkedHashMap<String, int[]>();
        var room = new java.util.LinkedHashMap<String, Integer>();
        for (ItemSpec spec : items) {
            String key = spec.id() + "|" + spec.customData();
            needed.computeIfAbsent(key, k -> new int[]{0})[0] += spec.count();
            room.putIfAbsent(key, freeRoomFor(spec.id(), spec.customData()));
        }
        for (var entry : needed.entrySet()) {
            if (entry.getValue()[0] > room.get(entry.getKey())) return false;
        }
        return true;
    }

    private int freeRoomFor(String id, java.util.Map<String, String> data) {
        int room = 0;
        int emptySlots = 0;
        for (ItemView stack : stacks) {
            if (stack.isEmpty()) {
                emptySlots++;
            } else if (stack.id().equals(id) && stack.customData().equals(data)) {
                room += stack.maxStackSize() - stack.count();
            }
        }
        return room + emptySlots * DEFAULT_MAX;
    }

    /** Inserts as much as fits; returns the count that did NOT fit. */
    public int insert(ItemSpec spec) {
        int remaining = spec.count();
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            ItemView stack = stacks[i];
            if (!stack.isEmpty() && stack.id().equals(spec.id())
                    && stack.customData().equals(spec.customData())
                    && stack.count() < stack.maxStackSize()) {
                int add = Math.min(remaining, stack.maxStackSize() - stack.count());
                stacks[i] = stack.withCount(stack.count() + add);
                remaining -= add;
            }
        }
        for (int i = 0; i < SLOTS && remaining > 0; i++) {
            if (!stacks[i].isEmpty()) continue;
            int add = Math.min(remaining, DEFAULT_MAX);
            stacks[i] = new ItemView(spec.id(), add, DEFAULT_MAX, spec.customData());
            remaining -= add;
        }
        return remaining;
    }

    /** Removes up to amount of the item id; returns the removed count. */
    public int removeItem(String id, int amount) {
        int removed = 0;
        for (int i = 0; i < SLOTS && removed < amount; i++) {
            ItemView stack = stacks[i];
            if (stack.isEmpty() || !stack.id().equals(id)) continue;
            ItemView taken = extract(i, amount - removed);
            removed += taken.count();
        }
        return removed;
    }

    public List<ItemView> contents() {
        List<ItemView> out = new ArrayList<>();
        for (ItemView stack : stacks) {
            if (!stack.isEmpty()) out.add(stack);
        }
        return out;
    }
}
