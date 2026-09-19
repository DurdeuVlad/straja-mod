package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcProviderResult;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.Set;

/**
 * Outbound port implemented by a player-facing NPC provider.
 *
 * <p>Implementations own provider-specific entities and UI. They do not own
 * Straja progression, permissions, rewards, or persistent gameplay state.</p>
 */
public interface NpcSurfaceProvider {
    NpcProviderId providerId();

    Set<NpcCapability> capabilities();

    NpcProviderResult bind(NpcBinding binding);

    NpcProviderResult unbind(NpcBinding binding);

    NpcProviderResult publish(NpcSurfaceSnapshot surface);

    /**
     * Resolves an operation whose external result was unknown. Implementations
     * must report accepted only after confirming that the binding is owned.
     */
    default NpcProviderResult reconcile(NpcBinding binding) {
        return NpcProviderResult.unknown("provider does not support reconciliation");
    }
}
