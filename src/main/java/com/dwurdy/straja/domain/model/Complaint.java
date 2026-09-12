package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** A citizen complaint case file. */
public class Complaint {
    public String id = "";               // C<n>
    public String complainant = "";
    public String complainantUuid = "";
    public String accused = "";
    public String category = "";
    public String description = "";
    public int severity = 1;
    public String status = "SUBMITTED";  // SUBMITTED|CLAIMED|INVESTIGATING|REPORT_SUBMITTED|UNDER_REVIEW|CLOSED|WITHDRAWN|CANCELLED
    public long createdAt;
    public Long claimedAt;
    public String lead = "";
    public String leadUuid = "";
    public List<Participant> participants = new ArrayList<>();
    public String report = "";
    public Long reportAt;
    public String complainantDecision = "PENDING"; // PENDING | SATISFIED | WITHDRAWN
    public Long satisfiedAt;
    public Long withdrawnAt;
    public String withdrawReason = "";
    // review
    public String reviewedBy = "";
    public String reviewedByUuid = "";
    public String reviewNote = "";
    public String resolution = "";       // RESOLVED | UNFOUNDED
    public Long closedAt;
    // rewards
    public String rewardStatus = "NONE"; // NONE | PENDING | PAID | FAILED
    public int rewardTotal;
    public Long rewardApprovedAt;
    public String rewardApprovedBy = "";
    public String rewardBudgetKey;
    public int rewardBudgetAmount;
    public String rewardBudgetStatus = "NONE"; // NONE | RESERVED
    public java.util.Map<String, Integer> rewardShares = new java.util.LinkedHashMap<>();
    public java.util.Map<String, String> rewardClaims = new java.util.LinkedHashMap<>();

    public static class Participant {
        public String uuid = "";
        public String name = "";
        public int rank;
        public String status = "INVITED"; // INVITED | JOINED | LEFT
        public Long invitedAt;
        public Long joinedAt;
        public Long leftAt;
    }

    public boolean isOpen() {
        return !("CLOSED".equals(status) || "WITHDRAWN".equals(status) || "CANCELLED".equals(status));
    }
}
