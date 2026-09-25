package com.dwurdy.straja.domain.model;

public class AffiliationRecord {
    public String affiliationId = "";
    public String playerUuid = "";
    public String externalFactionId = "";
    public String externalFactionName = "";
    public String type = "";
    public ConflictClass conflictClass = ConflictClass.NONE;
    public String source = "";
    public long startsAt;
    public Long endsAt;

    public enum ConflictClass { NONE, CONDITIONAL, INCOMPATIBLE }
}
