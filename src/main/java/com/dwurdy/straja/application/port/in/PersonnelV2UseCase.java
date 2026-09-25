package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.PersonnelRecord;

public interface PersonnelV2UseCase {
    PersonnelRecord authorize(String actorUuid, String subjectUuid, CareerGrade grade,
                              EmploymentMode employment, String source, String stationId, String idempotencyKey);
    PersonnelRecord find(String playerUuid);
}
