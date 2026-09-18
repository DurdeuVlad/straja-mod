package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class ArrestRecord {
    public String id = "";
    public String detaineeUuid = "";
    public String detaineeName = "";
    public String arrestingGuardUuid = "";
    public String arrestingGuardName = "";
    public List<String> assistingGuardUuids = new ArrayList<>();
    public long startedAt;
    public Long jailedAt;
    public String detentionReason = "";
    public int sentenceDays;
    public String sentenceId = "";
    public String incidentId = "";
    public String boloId = "";
    public String warrantTaskId = "";
    public String fineId = "";
    public List<String> evidenceIds = new ArrayList<>();
    public String complaintId = "";
    public List<String> reputationEventIds = new ArrayList<>();
    public String guardNotes = "";
    public ArrestRecordStatus status = ArrestRecordStatus.STARTED;
}
