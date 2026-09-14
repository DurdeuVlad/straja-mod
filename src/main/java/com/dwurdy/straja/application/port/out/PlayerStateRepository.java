package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.GuardState;
import java.util.UUID;

/**
 * Per-player Straja state. Implementations must validate on read, keep a
 * corrupt-state backup, and never return null.
 */
public interface PlayerStateRepository {
    GuardState read(UUID playerId);

    void write(UUID playerId, GuardState state);

    /** UUIDs with persisted state — backs the Comisar's personnel roster. */
    java.util.Set<UUID> knownIds();
}
