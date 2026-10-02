package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.LaborCampStore;

/** Persistence boundary for labor camp definitions. */
public interface LaborCampRepository {
    LaborCampStore read();
    void write(LaborCampStore store);
}
