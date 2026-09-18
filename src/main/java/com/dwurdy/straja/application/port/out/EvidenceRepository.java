package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.EvidenceStore;

public interface EvidenceRepository {
    EvidenceStore read();
    void write(EvidenceStore store);
}
