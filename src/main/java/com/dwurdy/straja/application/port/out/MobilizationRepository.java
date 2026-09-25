package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.MobilizationStore;

public interface MobilizationRepository {
    MobilizationStore read();
    void write(MobilizationStore store);
}
