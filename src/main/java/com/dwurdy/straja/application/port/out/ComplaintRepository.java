package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ComplaintStore;

public interface ComplaintRepository {
    ComplaintStore read();

    void write(ComplaintStore store);
}
