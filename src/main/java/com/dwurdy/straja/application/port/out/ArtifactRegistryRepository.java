package com.dwurdy.straja.application.port.out;

import com.dwurdy.straja.domain.model.ArtifactRegistryStore;

/** Persistence boundary for the regulated-artifact registry (#246). */
public interface ArtifactRegistryRepository {
    ArtifactRegistryStore read();

    void write(ArtifactRegistryStore store);
}
