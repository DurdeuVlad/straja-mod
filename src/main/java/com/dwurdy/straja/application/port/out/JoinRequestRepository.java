package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.JoinRequestStore;

public interface JoinRequestRepository {
    JoinRequestStore read();
    void write(JoinRequestStore store);
}
