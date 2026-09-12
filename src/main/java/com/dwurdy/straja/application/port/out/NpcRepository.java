package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.NpcRegistry;

public interface NpcRepository {
    NpcRegistry read();

    void write(NpcRegistry registry);
}
