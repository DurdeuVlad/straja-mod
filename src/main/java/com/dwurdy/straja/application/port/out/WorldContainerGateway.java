package com.dwurdy.straja.application.port.out;

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
     * Identity key for the container at a position. For joined chests this
     * canonicalizes to one half so both halves of a double chest resolve to a
     * single watch/cache/deposit key.
     */
    default String canonicalKey(String dimension, int x, int y, int z) {
        return dimension + "|" + x + "," + y + "," + z;
    }
}
