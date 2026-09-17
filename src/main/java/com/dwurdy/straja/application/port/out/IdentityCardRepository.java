package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.IdentityCardStore;

/** Persistence boundary for server-authoritative identity cards. */
public interface IdentityCardRepository {
    IdentityCardStore read();

    void write(IdentityCardStore store);
}
