package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.BoloStore;

public interface BoloRepository {
    BoloStore read();
    void write(BoloStore store);
}
