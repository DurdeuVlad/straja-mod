package com.dwurdy.straja.domain.model;

public class DocumentRecord {
    public String documentId = "";
    public DocumentType type = DocumentType.OFFICIAL_RESPONSE;
    public String issuer = "";
    public String subject = "";
    public String scope = "";
    public Long quantity;
    public String stationId = "hq";
    public String jurisdiction = "";
    public long issuedAt;
    public Long expiresAt;
    public DocumentStatus status = DocumentStatus.DRAFT;
    public String payloadRef = "";
    public String correlationId = "";
    public String predecessorId = "";
    public String supersedesId = "";
    public long version;

    public boolean validAt(long now) {
        return (status == DocumentStatus.ISSUED || status == DocumentStatus.ACTIVE
                || status == DocumentStatus.PARTIALLY_REDEEMED)
                && (expiresAt == null || now < expiresAt);
    }
}
