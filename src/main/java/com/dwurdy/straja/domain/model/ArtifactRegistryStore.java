package com.dwurdy.straja.domain.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Central registry of serial-marked artifacts and profession licenses. */
public class ArtifactRegistryStore {
    public static final int CURRENT_SCHEMA = 1;
    public int schemaVersion = CURRENT_SCHEMA;
    public long storeRevision;
    public Map<String, ArtifactRecord> artifacts = new LinkedHashMap<>();
    /** key = holderUuid + ":" + license type */
    public Map<String, ArtifactLicense> licenses = new LinkedHashMap<>();
    public long nextSerial = 1;
    public long nextLicense = 1;
    /** Forge-shadow sequence: forged records key as "FRG-n" so they can never
     * collide with authentic serials of any prefix. */
    public long nextForgery = 1;
}
