package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ParoleStore;

/** FUN-11 durable store boundary for the hearing docket and parole conditions. */
public interface ParoleRepository {
    ParoleStore read();
    void write(ParoleStore data);
}
