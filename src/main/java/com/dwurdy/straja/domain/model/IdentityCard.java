package com.dwurdy.straja.domain.model;

/** Server-authoritative identity record behind a physical buletin item. */
public class IdentityCard {
    public String id = "";
    public String holderUuid = "";
    public String holderName = "";
    public String issuerUuid = "";
    public String issuerName = "";
    public long issuedAt;
    public long expiresAt;
    public String status = IdentityCardStatus.VALID.name();
    public String authenticity = IdentityCardAuthenticity.AUTHENTIC.name();
    public String forgeryClue = "";
    /** Blank for authentic cards; "N1".."N5" when staged as a tiered forgery (#247). */
    public String forgeryTier = "";
    public Long revokedAt;
    public String revokedByUuid = "";
    public String revokedByName = "";
    public String revocationReason = "";

    public boolean validAt(long now) {
        return IdentityCardStatus.VALID.name().equals(status)
                && holderUuid != null && !holderUuid.isBlank()
                && expiresAt > now;
    }
}
