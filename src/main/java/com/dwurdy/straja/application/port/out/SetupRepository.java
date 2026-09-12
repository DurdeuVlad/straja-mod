package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.SetupData;

/** World-dependent setup: checkpoints, named locations, per-checkpoint times. */
public interface SetupRepository {
    SetupData read();

    void write(SetupData data);
}
