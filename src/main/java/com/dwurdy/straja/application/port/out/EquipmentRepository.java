package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.EquipmentStore;

public interface EquipmentRepository {
    EquipmentStore read();
    void write(EquipmentStore store);
}
