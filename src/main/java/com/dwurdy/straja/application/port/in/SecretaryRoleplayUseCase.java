package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;

/** Secretary-only roleplay actions. */
public interface SecretaryRoleplayUseCase {
    /**
     * Attempts to copy the book held in the player's main hand. A non-book
     * result lets the normal Secretary dialog continue.
     */
    PlayerGateway.BookCopyResult copyHeldBook(PlayerGateway player);
}
