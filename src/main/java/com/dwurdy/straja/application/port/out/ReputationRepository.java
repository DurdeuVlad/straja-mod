package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ReputationStore;

public interface ReputationRepository {
    ReputationStore read();
    void write(ReputationStore store);
}
