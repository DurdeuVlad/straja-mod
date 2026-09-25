package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.OperationStore;

public interface OperationRepository {
    OperationStore read();
    void write(OperationStore store);
}
