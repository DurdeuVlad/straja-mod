package com.dwurdy.straja.domain.model;

/**
 * A profession license granted by the garrison: who may strike authentic
 * marks (INSPECTOR) or move sealed military crates (TRANSPORTER).
 */
public class ArtifactLicense {
    public String licenseId = "";
    public String type = ArtifactLicenseType.INSPECTOR.name();
    public String holderUuid = "";
    public String holderName = "";
    public String grantedByUuid = "";
    public String grantedByName = "";
    public long grantedAt;
    public Long revokedAt;
    public String revokedByUuid = "";

    public boolean active() {
        return revokedAt == null;
    }
}
