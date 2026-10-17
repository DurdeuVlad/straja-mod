package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.DispatchStore;

public interface DispatchRepository {
    DispatchStore read();
    void write(DispatchStore store);
}
