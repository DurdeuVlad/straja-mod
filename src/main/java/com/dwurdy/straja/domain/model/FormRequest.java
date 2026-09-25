package com.dwurdy.straja.domain.model;

/** Persistent, non-authorizing request for a standard blank official form. */
public class FormRequest {
    public String requestId = "";
    public String requesterUuid = "";
    public String formType = "";
    public String idempotencyKey = "";
    public String requestFingerprint = "";
    public String status = "ISSUED"; // ISSUED | SUBMITTED | CANCELLED
    public long createdAt;
    public long updatedAt;
    public long version;
}
