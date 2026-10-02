package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.StorageWatchStore;

/** Persistence boundary for the storage-watch aggregate. */
public interface StorageRepository {
    StorageWatchStore read();
    void write(StorageWatchStore store);
}
