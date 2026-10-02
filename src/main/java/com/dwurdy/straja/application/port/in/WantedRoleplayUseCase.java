package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

import java.util.UUID;

/**
 * Inbound surface for LAW-007 wanted-state decisions. Player-facing adapters
 * reach the wanted service through this port — never the concrete service or
 * the outbound NPC gateway directly.
 */
public interface WantedRoleplayUseCase {

    /** Wanted-on-sight: live BOLO, register FUGITIVE, or unexpired legacy mark. */
    boolean isWanted(UUID playerUuid);

    /** Cuffed and the cuffing officer within tether range — already caught. */
    boolean isUnderEscort(PlayerGateway target);

    /**
     * Guard-side damage hold: true when the attacker is a guard entity
     * (CustomNPCs faction member or a native guard/jailer role) AND the
     * target is any suspect under live escort — wanted or not, a cuffed
     * prisoner beside their officer is never a lawful target. Event handlers
     * cancel the hit when this returns true.
     *
     * @param attackerEntityId the damage source entity's UUID
     * @param attackerRole     the resolved NPC role id, or null when foreign
     * @param target           the player being hit
     */
    boolean guardDamageBlocked(UUID attackerEntityId, String attackerRole,
                               PlayerGateway target);
}
