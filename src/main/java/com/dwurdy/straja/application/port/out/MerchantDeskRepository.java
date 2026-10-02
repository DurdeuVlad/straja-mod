package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.MerchantDeskStore;

/** Persistence boundary for merchant desks and the trade ledger. */
public interface MerchantDeskRepository {
    MerchantDeskStore read();
    void write(MerchantDeskStore store);
}
