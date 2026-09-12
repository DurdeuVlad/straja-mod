package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.RoomStore;

public interface RoomRepository {
    RoomStore read();

    void write(RoomStore store);
}
