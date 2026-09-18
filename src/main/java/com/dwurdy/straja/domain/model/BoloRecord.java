package com.dwurdy.straja.domain.model;

public class BoloRecord {
    public String id = "";
    public String subjectUuid = "";
    public String subjectName = "";
    public String reason = "";
    public String notes = "";
    public String issuerUuid = "";
    public String issuerName = "";
    public int issuerRank;
    public long createdAt;
    public long expiresAt;
    public BoloStatus status = BoloStatus.ACTIVE;
    public String linkedIncidentId = "";
    public String linkedComplaintId = "";
    public String linkedFineId = "";
    public String linkedCaseId = "";
    public String linkedArrestTaskId = "";
    public BoloAuthority authority = BoloAuthority.INFORMATION_ONLY;
}
