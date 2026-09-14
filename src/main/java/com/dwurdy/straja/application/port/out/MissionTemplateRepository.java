package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.MissionTemplateStore;

public interface MissionTemplateRepository {
    MissionTemplateStore read();

    void write(MissionTemplateStore store);
}
