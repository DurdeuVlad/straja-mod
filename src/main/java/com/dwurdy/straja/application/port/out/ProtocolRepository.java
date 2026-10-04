package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ProtocolStore;

public interface ProtocolRepository {
    ProtocolStore read();
    void write(ProtocolStore store);
}
