package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.SettlementStore;

public interface SettlementRepository {
    SettlementStore read();
    void write(SettlementStore store);
}
