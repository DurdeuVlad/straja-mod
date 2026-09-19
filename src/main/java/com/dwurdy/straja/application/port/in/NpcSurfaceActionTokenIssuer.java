package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcContentId;
import java.util.UUID;

/** Mints a short-lived token for a currently visible NPC action. */
public interface NpcSurfaceActionTokenIssuer {
    String issueToken(UUID playerId, NpcBinding binding, NpcContentId actionId);
}
