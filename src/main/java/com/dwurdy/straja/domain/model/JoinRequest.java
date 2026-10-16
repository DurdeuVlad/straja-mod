package com.dwurdy.straja.domain.model;

public class JoinRequest {
    public String requestId = "";
    public String subjectUuid = "";
    public String profession = "";
    /** Free-text reason the applicant filed; empty when none was given. */
    public String reason = "";
    public JoinRequestStatus status = JoinRequestStatus.PENDING;
    public long submittedAt;
    public String reviewedBy = "";
    public Long decisionAt;
    public String decisionReason = "";
    /** True once the subject has been told the decision (login sweep or live). */
    public boolean outcomeDelivered;
    public long version;
}
