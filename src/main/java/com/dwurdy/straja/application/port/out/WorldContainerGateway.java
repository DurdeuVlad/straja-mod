package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.SeizedStack;
import java.util.List;
import java.util.Map;

/**
 * Minimal container access the storage watch needs — deliberately narrower than
 * the generic inventory ports so the service stays runnable in unit tests.
 */
public interface WorldContainerGateway {
    boolean isContainer(String dimension, int x, int y, int z);

    /** Total value units of watched items inside; {@code -1} when the position holds no container. */
    int countUnits(String dimension, int x, int y, int z, Map<String, Integer> unitValues);

    /**
     * Merge-then-fill insert. Returns the leftover count that did not fit, or
     * {@code -1} when {@code itemId} does not resolve to a real item (callers
     * must treat that as a hard failure — nothing was inserted).
     * Implementations must mark the container changed so the write persists.
     */
    int insert(String dimension, int x, int y, int z, String itemId, int count);

    /** Spawns an item entity just above the position (overflow delivery). */
    void dropItem(String dimension, int x, int y, int z, String itemId, int count);

    /**
     * Like {@link #insert} but preserves the full serialized component data
     * ({@code snbt}, as produced by a seizure/drain) so evidence and locker
     * items keep their identity. Falls back to id+count when the SNBT does
     * not parse. Returns leftover count, or {@code -1} on hard failure.
     */
    default int insertStack(String dimension, int x, int y, int z,
                            String itemId, int count, String snbt) {
        return insert(dimension, x, y, z, itemId, count);
    }

    /**
     * Drains every slot of the container and returns the stacks (full SNBT
     * preserved) — locker restoration pulls belongings out this way.
     * Returns an empty list when the position is not a container.
     */
    default List<SeizedStack> drain(String dimension, int x, int y, int z) {
        return List.of();
    }

    /**
     * Identity key for the container at a position. For joined chests this
     * canonicalizes to one half so both halves of a double chest resolve to a
     * single watch/cache/deposit key.
     */
    default String canonicalKey(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "," + y + "," + z;
    }
}
