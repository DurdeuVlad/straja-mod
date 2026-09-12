package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ArchiveStore;

public interface ArchiveRepository {
    ArchiveStore read();

    void write(ArchiveStore store);
}
