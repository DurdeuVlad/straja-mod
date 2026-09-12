package com.dwurdy.straja.application.port.out;

import java.util.List;
import java.util.UUID;

/** The domain's view of the running server. */
public interface ServerGateway {
    List<PlayerGateway> onlinePlayers();

    /** Finds an online player by case-insensitive name or UUID; null when offline. */
    PlayerGateway findPlayer(String nameOrUuid);

    default PlayerGateway findPlayer(UUID uuid) {
        return uuid == null ? null : findPlayer(uuid.toString());
    }

    long tickCount();
}
