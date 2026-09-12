package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** Recovery (FM-*), hearing warrant (AW-*) or jailer-assault (JA-*) task. */
public class FineTask {
    public String id = "";               // FM-F<n>[-Rk] | AW-* | JA-*
    public String kind = "FINE_RECOVERY"; // FINE_RECOVERY | HEARING_WARRANT | JAILER_ASSAULT
    public String fineId;
    public String target = "";
    public String targetUuid = "";
    /** OPEN | PRESENTED | REFUSED | ARREST_PENDING | ARRESTED | COMPLETED | CANCELLED */
    public String status = "OPEN";
    public long createdAt;
    public int missionMinutes = 30;
    public String assignee;              // first assignee key
    public String assigneeName;
    public List<String> assignees = new ArrayList<>(); // player keys
    public int maxAssignees = 4;
    public int refusalCount;
    public Long presentedAt;
    public String presentedBy;
    public Long refusedAt;
    public String refusedBy;
    public String refusalReason;
    public String arrestedBy;
    public String arrestedByUuid;
    public Long arrestedAt;
    public Long completedAt;
    public Long updatedAt;
    // hearing warrant
    public String destination;
    public String warrantReason = "";
    public String signedBy;
    public String signedByUuid;
    // jailer assault
    public String incidentKey;
    public String jailerOutcome;         // WOUNDED | KILLED
    public int injuryAmount;
    public int suggestedSentenceDays;
}
