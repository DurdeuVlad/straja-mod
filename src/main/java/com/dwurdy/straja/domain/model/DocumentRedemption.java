package com.dwurdy.straja.domain.model;

public class DocumentRedemption {
    public String redemptionId = "";
    public String instrumentId = "";
    public long quantity;
    public String actor = "";
    public String stationId = "";
    public String idempotencyKey = "";
    public String fulfillmentOperationId = "";
    public DocumentStatus status = DocumentStatus.ISSUED;
    public long createdAt;
    public Long completedAt;
}
