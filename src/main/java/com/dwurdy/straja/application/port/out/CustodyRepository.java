package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.CustodyStore;

/** Cuff requests, cuffed/bound/sacked/downed records, pending keys. */
public interface CustodyRepository {
    CustodyStore read();

    void write(CustodyStore store);
}
