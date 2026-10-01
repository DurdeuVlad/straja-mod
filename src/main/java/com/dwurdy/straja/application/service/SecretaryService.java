package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.SecretaryRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;

/** Server-authoritative actions owned by the Secretary NPC. */
public final class SecretaryService implements SecretaryRoleplayUseCase {
    @Override
    public PlayerGateway.BookCopyResult copyHeldBook(PlayerGateway player) {
        if (player == null || !player.isOnline()) return PlayerGateway.BookCopyResult.NOT_A_BOOK;

        PlayerGateway.BookCopyResult result = player.copyMainHandBook();
        switch (result) {
            case COPIED -> player.tell("Secretarul ți-a dat o copie a cărții. Originalul a rămas la tine.");
            case NO_SPACE -> player.refuse("straja.secretary.copy_full", "straja.remedy.retry");
            case NOT_A_BOOK -> { }
        }
        return result;
    }
}
