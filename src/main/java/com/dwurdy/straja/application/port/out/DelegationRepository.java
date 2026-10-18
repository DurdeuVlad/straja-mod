package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.DelegationStore;

public interface DelegationRepository {
    DelegationStore read();
    void write(DelegationStore store);
}
