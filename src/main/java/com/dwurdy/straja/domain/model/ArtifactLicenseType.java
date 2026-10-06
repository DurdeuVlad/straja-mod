package com.dwurdy.straja.domain.model;

/**
 * Licensed professions in the artifact-authenticity system. INSPECTOR strikes
 * authentic serial marks; TRANSPORTER moves sealed military crates through
 * checkpoints (#246 / #245 M1).
 */
public enum ArtifactLicenseType {
    INSPECTOR,
    TRANSPORTER;

    public static ArtifactLicenseType parse(String value) {
        if (value == null) return null;
        try {
            return ArtifactLicenseType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
