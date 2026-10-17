package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ManhuntStore;

/** FUN-7: persistence port for the manhunt aggregate. */
public interface ManhuntRepository {
    ManhuntStore read();
    void write(ManhuntStore store);
}
