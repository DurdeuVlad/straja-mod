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

    public AuditEntry() {}
}
