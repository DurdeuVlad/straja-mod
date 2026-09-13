package com.dwurdy.straja.application.port.in;

/**
 * Inbound port for the NPC registry lifecycle surface used by entity events:
 * reading a persisted registration and re-adopting entities that outlived the
 * registry. Admin mutations (role assignment, naming, skins, removal) stay on
 * the permission-gated command surface.
 */
public interface NpcRegistryUseCase {

    /** Persisted registry data for an entity UUID, or null when unregistered. */
    Registration registration(String entityUuid);

    /**
     * Re-adopts a persisted entity into the registry. Idempotent: no-op when a
     * record already exists or when the role is unknown.
     */
    void adopt(String entityUuid, String roleId);

    record Registration(String role, String skin, String displayName) {}
}
