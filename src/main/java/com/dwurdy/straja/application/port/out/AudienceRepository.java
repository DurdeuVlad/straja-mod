package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.AudienceStore;

public interface AudienceRepository {
    AudienceStore read();

    void write(AudienceStore store);
}
