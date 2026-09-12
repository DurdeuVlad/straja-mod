package com.dwurdy.straja.application.port.out;

/**
 * Bounded read access to world blocks plus sign placement. Implemented by the
 * Minecraft adapter; the room-discovery algorithm is bounded by policy limits.
 */
public interface WorldGateway {
    /** Block info at a position, or null when the position is unreadable. */
    BlockInfo blockAt(String dimension, int x, int y, int z);

    /** Places/updates the room status sign; returns false on failure. */
    boolean setRoomSign(String dimension, int x, int y, int z, String facing,
                      String line1, String line2, String line3);

    record BlockInfo(String id, boolean air, boolean door, boolean solid) {
        public boolean traversable() {
            return !door && (air || !solid);
        }
    }
}
