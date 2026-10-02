package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.InspectionLedgerStore;

/** Persistence boundary for the crossing inspection ledger. */
public interface InspectionLedgerRepository {
    InspectionLedgerStore read();
    void write(InspectionLedgerStore store);
}
