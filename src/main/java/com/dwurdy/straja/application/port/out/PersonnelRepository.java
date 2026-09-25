package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.PersonnelStore;

public interface PersonnelRepository {
    PersonnelStore read();
    void write(PersonnelStore store);
}
