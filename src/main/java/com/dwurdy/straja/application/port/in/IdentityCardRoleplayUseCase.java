package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

/** Player-facing and administrative operations for physical identity cards. */
public interface IdentityCardRoleplayUseCase {
    boolean request(PlayerGateway applicant);

    boolean issue(PlayerGateway issuer, PlayerGateway target);

    void read(PlayerGateway viewer, String cardId);

    void list(PlayerGateway viewer);

    boolean revoke(PlayerGateway actor, String cardId, String reason);
}
