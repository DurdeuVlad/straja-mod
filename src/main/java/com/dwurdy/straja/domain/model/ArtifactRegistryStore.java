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
    /** claimedSerial -> latest FRG shadow that presented it (#247 detection). */
    public Map<String, String> claimedIndex = new LinkedHashMap<>();
    public long nextSerial = 1;
    public long nextLicense = 1;
    /** Forge-shadow sequence: forged records key as "FRG-n" so they can never
     * collide with authentic serials of any prefix. */
    public long nextForgery = 1;
    /** Lazily re-creates {@code artifacts} after a corrupt or partial read. */
    public Map<String, ArtifactRecord> ensureArtifacts() {
        if (artifacts == null) artifacts = new LinkedHashMap<>();
        return artifacts;
    }

    /** Lazily re-creates {@code licenses} after a corrupt or partial read. */
    public Map<String, ArtifactLicense> ensureLicenses() {
        if (licenses == null) licenses = new LinkedHashMap<>();
        return licenses;
    }

    /** Lazily re-creates {@code claimedIndex} after a corrupt or partial read. */
    public Map<String, String> ensureClaimedIndex() {
        if (claimedIndex == null) claimedIndex = new LinkedHashMap<>();
        return claimedIndex;
    }

}
