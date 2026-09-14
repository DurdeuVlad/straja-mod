package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.AdminToolStore;

/** SavedData-backed pending state for the physical admin tools. */
public interface AdminToolRepository {
    AdminToolStore read();

    void write(AdminToolStore store);
}
