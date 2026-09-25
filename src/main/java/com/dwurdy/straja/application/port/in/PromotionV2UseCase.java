package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.PromotionApplication;

public interface PromotionV2UseCase {
    PromotionApplication submit(String subjectUuid, CareerGrade targetGrade);
    PromotionApplication approve(String actorUuid, String applicationId, long expectedVersion);
}
