package com.dwurdy.straja.domain.model;

public class DocumentInstrument {
    public String instrumentId = "";
    public DocumentType instrumentType = DocumentType.REQUISITION_TICKET;
    public String issuer = "";
    public String holder = "";
    public String scope = "";
    public String stationId = "hq";
    public long initialQuantity;
    public long remainingQuantity;
    public long issuedAt;
    public Long expiresAt;
    public String requestFingerprint = "";
    public DocumentStatus status = DocumentStatus.ISSUED;
    public long version;

    public boolean activeAt(long now) {
        return (status == DocumentStatus.ISSUED || status == DocumentStatus.ACTIVE
                || status == DocumentStatus.PARTIALLY_REDEEMED)
                && remainingQuantity > 0 && (expiresAt == null || now < expiresAt);
    }
}
