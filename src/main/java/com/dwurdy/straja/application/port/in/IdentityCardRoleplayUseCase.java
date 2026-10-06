package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

/** Player-facing and administrative operations for physical identity cards. */
public interface IdentityCardRoleplayUseCase {
    boolean request(PlayerGateway applicant);

    boolean issue(PlayerGateway issuer, PlayerGateway target);

    /** Creates a deliberately imperfect but usable counterfeit for roleplay. */
    boolean forge(PlayerGateway actor, PlayerGateway target);

    /** #247 — tier-selectable staging forge; default keeps untiered behaviour. */
    default boolean forge(PlayerGateway actor, PlayerGateway target,
                          com.dwurdy.straja.domain.model.ForgeryTier tier) {
        return forge(actor, target);
    }

    void read(PlayerGateway viewer, String cardId);

    /** Reads a physical item, including the item-side authenticity marker. */
    default void read(PlayerGateway viewer, String cardId, String itemHolderUuid,
                      String itemAuthenticity) {
        read(viewer, cardId);
    }

    void list(PlayerGateway viewer);

    boolean revoke(PlayerGateway actor, String cardId, String reason);
}
