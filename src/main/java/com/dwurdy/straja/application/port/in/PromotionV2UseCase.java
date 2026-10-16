package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.PromotionApplication;

public interface PromotionV2UseCase {
    PromotionApplication submit(String subjectUuid, CareerGrade targetGrade);
    PromotionApplication submitPetition(String subjectUuid, CareerGrade targetGrade, String reason);
    PromotionApplication approve(String actorUuid, String applicationId, long expectedVersion);
    PromotionApplication reject(String actorUuid, String applicationId, String reason);
    PromotionApplication find(String applicationId);
    java.util.List<PromotionApplication> pendingPetitions();
    void deliverOutcome(com.dwurdy.straja.application.port.out.PlayerGateway player);
}
