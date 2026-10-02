package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.LawCheckpointStore;

/** Persistence boundary for law checkpoints + global crossing policy. */
public interface LawCheckpointRepository {
    LawCheckpointStore read();
    void write(LawCheckpointStore store);
}
