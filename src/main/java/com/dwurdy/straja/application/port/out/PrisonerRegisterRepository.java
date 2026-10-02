package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.PrisonerRegisterStore;

/** Persistence boundary for the physical-custody prisoner register. */
public interface PrisonerRegisterRepository {
    PrisonerRegisterStore read();
    void write(PrisonerRegisterStore store);
}
