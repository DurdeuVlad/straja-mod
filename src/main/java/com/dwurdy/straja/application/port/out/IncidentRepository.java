package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.IncidentStore;

public interface IncidentRepository {
    IncidentStore read();
    void write(IncidentStore store);
}
