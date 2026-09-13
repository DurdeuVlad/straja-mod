package com.dwurdy.straja.adapter.out.persistence;

import com.dwurdy.straja.application.port.out.PlayerStateRepository;
import com.dwurdy.straja.domain.model.GuardState;
import java.util.UUID;

/** straja_players store: UUID → GuardState JSON. */
public class SavedPlayerStateRepository extends JsonBackedStore implements PlayerStateRepository {
    public static final String STORE = "players";

    public SavedPlayerStateRepository(StoreAccess access) {
        super(access, STORE);
    }

    @Override
    public GuardState read(UUID playerId) {
        String key = playerId.toString();
        String raw = store().get(key);
        if (raw == null || raw.isEmpty()) return new GuardState();
        try {
            GuardState state = GSON.fromJson(raw, GuardState.class);
            if (state == null) return new GuardState();
            // Gson skips field initializers — collections absent in old saves
            // come back null.
            if (state.specializations == null) state.specializations = new java.util.LinkedHashSet<>();
            return state;
        } catch (RuntimeException error) {
            store().put(key + "_corrupt_backup", raw);
            store().remove(key);
            return new GuardState();
        }
    }

    @Override
    public void write(UUID playerId, GuardState state) {
        store().put(playerId.toString(), GSON.toJson(state));
    }
}
