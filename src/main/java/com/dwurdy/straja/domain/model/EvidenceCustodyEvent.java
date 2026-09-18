package com.dwurdy.straja.domain.model;

public class EvidenceCustodyEvent {
    public long at;
    public String evidenceId = "";
    public EvidenceStatus previousStatus;
    public EvidenceStatus newStatus;
    public String previousCustodianUuid = "";
    public String newCustodianUuid = "";
    public String actorUuid = "";
    public String actorName = "";
    public String reason = "";
}
