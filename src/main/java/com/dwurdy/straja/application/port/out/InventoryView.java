package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ItemSpec;
import java.util.List;

/** Inventory operations needed by the domain, implemented by the MC adapter. */
public interface InventoryView {
    int slots();

    ItemView stackAt(int slot);

    /** Removes up to {@code amount} from the slot; returns what was removed. */
    ItemView extract(int slot, int amount);

    /** True when the whole batch fits without dropping anything. */
    boolean canReceive(List<ItemSpec> items);

    /** Total count of a registry item across the inventory. */
    default int countOf(String itemId) {
        int total = 0;
        for (int i = 0; i < slots(); i++) {
            ItemView stack = stackAt(i);
            if (itemId.equals(stack.id())) total += stack.count();
        }
        return total;
    }

    /** True when at least one stack carries the registry id. */
    default boolean contains(String itemId) {
        return countOf(itemId) > 0;
    }

    /**
     * A vacant inventory: no slots, extracts nothing, receives nothing.
     * Returned for offline players so callers never see null.
     */
    static InventoryView empty() {
        return new InventoryView() {
            @Override public int slots() { return 0; }
            @Override public ItemView stackAt(int slot) { return ItemView.EMPTY; }
            @Override public ItemView extract(int slot, int amount) { return ItemView.EMPTY; }
            @Override public boolean canReceive(List<ItemSpec> items) { return items.isEmpty(); }
        };
    }
}
