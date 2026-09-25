package com.dwurdy.straja.domain.model;

/** One append-only audit record. */
public class AuditEntry {
    public long at;
    public String action = "";
    public String actor = "";
    public String actorUuid = "";
    public String target = "";
    public String targetUuid = "";
    public String result = "";
    public String reason = "";
    public String details = "";
    public String eventId = "";
    public String eventType = "";
    public String actorType = "";
    public String aggregateType = "";
    public String aggregateId = "";
    public long aggregateVersion;
    public String stationId = "";
    public String capability = "";
    public String decision = "";
    public String reasonCode = "";
    public String correlationId = "";
    public String causationId = "";
    public String operationId = "";

    public AuditEntry() {}
}
