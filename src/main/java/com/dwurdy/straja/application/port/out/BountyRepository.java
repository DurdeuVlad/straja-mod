package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.BountyStore;

public interface BountyRepository {
    BountyStore read();
    void write(BountyStore store);
}
