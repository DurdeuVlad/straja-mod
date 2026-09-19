package com.dwurdy.straja.adapter.out.persistence;

import com.dwurdy.straja.application.port.out.NpcBindingRepository;
import com.dwurdy.straja.domain.model.NpcBindingStore;

/** SavedData-backed adapter for the provider-neutral NPC binding aggregate. */
public final class NbtNpcBindingRepository extends JsonBackedStore implements NpcBindingRepository {
    public NbtNpcBindingRepository(StoreAccess access) {
        super(access, "npc_bindings");
    }

    @Override
    public NpcBindingStore read() {
        return readJsonVersioned(
                NpcBindingStore.class,
                NpcBindingStore::new,
                NpcBindingStore.CURRENT_SCHEMA_VERSION);
    }

    @Override
    public void write(NpcBindingStore store) {
        writeJson(store);
    }
}
