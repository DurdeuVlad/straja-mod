package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Sentence;

/** Player-facing prison surface: sentences, cells, waitlist and recovery. */
public interface PrisonRoleplayUseCase {
    Sentence activeSentence(PlayerGateway target);

    Sentence arrest(
            PlayerGateway target,
            String fineId,
            int days,
            PlayerGateway actor,
            String missionId);

    boolean release(PlayerGateway actor, PlayerGateway target, String reason);

    void status(PlayerGateway player);

    boolean insideCell(String dimension, double x, double y, double z);

    void recoverOnLogin(PlayerGateway player);

    void processWaitlist();

    void tick();
}
