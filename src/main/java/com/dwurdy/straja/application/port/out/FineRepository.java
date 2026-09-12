package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.FineStore;

public interface FineRepository {
    FineStore read();

    void write(FineStore store);
}
