package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ArrestRecordStore;

public interface ArrestRecordRepository {
    ArrestRecordStore read();
    void write(ArrestRecordStore store);
}
