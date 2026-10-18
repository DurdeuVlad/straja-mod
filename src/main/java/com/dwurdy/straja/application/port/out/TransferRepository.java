package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.TransferStore;

public interface TransferRepository {
    TransferStore read();
    void write(TransferStore store);
}
