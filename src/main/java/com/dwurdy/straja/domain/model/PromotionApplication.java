package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class PromotionApplication {
    public String applicationId = "";
    public String subjectUuid = "";
    public CareerTrack careerOrigin = CareerTrack.MILITARY;
    public CareerGrade fromGrade = CareerGrade.MILITARY_STAGIAR;
    public CareerGrade targetGrade = CareerGrade.MILITARY_STRAJER;
    public PromotionStatus status = PromotionStatus.SUBMITTED;
    /** Free-text reason on petition applications; empty when none was given. */
    public String petitionReason = "";
    public long submittedAt;
    public List<String> evidenceIds = new ArrayList<>();
    public List<String> examAttemptIds = new ArrayList<>();
    public Long readinessAt;
    public String reviewedBy = "";
    public Long decisionAt;
    public String decisionReason = "";
    /** True once the subject has been told the decision (login sweep or live). */
    public boolean outcomeDelivered;
    public long expectedPersonnelVersion;
    /** Durable cross-store approval marker used by restart reconciliation. */
    public String personnelSyncState = "PENDING";
    public Long personnelAppliedAt;
    public List<OutboxIntent> outboundIntents = new ArrayList<>();
    public long version;
}
