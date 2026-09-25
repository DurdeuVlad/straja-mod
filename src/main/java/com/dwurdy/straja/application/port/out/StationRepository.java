package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.StationStore;

public interface StationRepository {
    StationStore read();
    void write(StationStore store);
}
