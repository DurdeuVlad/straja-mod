package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.MissionStore;

public interface MissionRepository {
    MissionStore read();

    void write(MissionStore store);
}
