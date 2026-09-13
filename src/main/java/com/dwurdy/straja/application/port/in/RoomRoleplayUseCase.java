package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Room;

/** Player-facing room surface: marker selection, assignment, protection. */
public interface RoomRoleplayUseCase {
    void markerSelect(PlayerGateway player, String dimension, int x, int y, int z);

    String assignAutomatically(PlayerGateway player);

    void processWaitlist();

    boolean releaseFor(PlayerGateway player);

    /** True when the player holds a room assignment or waitlist spot they can release. */
    boolean canRelease(PlayerGateway player);

    void status(PlayerGateway player);

    boolean protectBlock(
            PlayerGateway player,
            String dimension,
            int x,
            int y,
            int z);

    boolean isProtectedBlock(
            String dimension,
            int x,
            int y,
            int z);

    Room roomAtSign(
            String dimension,
            int x,
            int y,
            int z);
}
