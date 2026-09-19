package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.UUID;

/** Mints a short-lived token for a currently visible NPC action. */
public interface NpcSurfaceActionTokenIssuer {
    String issueToken(UUID playerId, NpcBinding binding, NpcContentId actionId);

    /**
     * Mints a token against the exact provider-neutral surface rendered for
     * this player. Dynamic surfaces are player-specific and therefore cannot
     * be validated through the binding's shared published profile alone.
     * Implementations that do not support dynamic surfaces may use the
     * three-argument method through this default.
     */
    default String issueToken(
            UUID playerId,
            NpcBinding binding,
            NpcContentId actionId,
            NpcSurfaceSnapshot surface) {
        return issueToken(playerId, binding, actionId);
    }
}
