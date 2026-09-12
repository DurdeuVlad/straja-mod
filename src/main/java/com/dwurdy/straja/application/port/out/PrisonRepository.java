package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.PrisonStore;

public interface PrisonRepository {
    PrisonStore read();

    void write(PrisonStore store);
}
