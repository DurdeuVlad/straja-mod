package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.NpcBindingStore;

/** Persistence port for logical NPC bindings and pending provider operations. */
public interface NpcBindingRepository {
    NpcBindingStore read();

    void write(NpcBindingStore store);
}
