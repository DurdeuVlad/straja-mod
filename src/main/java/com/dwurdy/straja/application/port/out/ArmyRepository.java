package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ArmyStore;

public interface ArmyRepository {
    ArmyStore read();
    void write(ArmyStore store);
}
