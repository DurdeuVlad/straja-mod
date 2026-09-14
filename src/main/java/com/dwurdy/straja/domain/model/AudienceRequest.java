package com.dwurdy.straja.domain.model;

/** A member's request for an audience with the Comisar, filed at the Secretary (§12). */
public class AudienceRequest {
    public static final String PENDING = "PENDING";
    public static final String RESOLVED = "RESOLVED";
    public static final String DISMISSED = "DISMISSED";

    public String id = "";               // A<n>
    public String requesterUuid = "";
    public String requesterName = "";
    public String reason = "";
    public String status = PENDING;
    public long createdAt;
    public long updatedAt;
    // resolution
    public String resolvedBy = "";
    public String resolutionNote = "";
    public Long resolvedAt;
    /** Cleared once the requester has been told the outcome (login/status). */
    public boolean outcomeDelivered = true;
}
