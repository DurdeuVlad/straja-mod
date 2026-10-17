package com.dwurdy.straja.domain.model;

/** The single audit-trail result vocabulary, shared by every audited service. */
public final class AuditResults {
    private AuditResults() {}

    public static final String SUCCESS = "SUCCESS";
    public static final String REFUSED = "REFUSED";
    public static final String PENDING = "PENDING";
    public static final String CONFLICT = "CONFLICT";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String FAIL = "FAIL";
    public static final String STARTED = "STARTED";
    public static final String ARRESTED = "ARRESTED";
    public static final String RESET = "RESET";
    public static final String REFUNDED = "REFUNDED";
    public static final String ARREST = "ARREST";
    public static final String CREATED = "CREATED";
    public static final String DENY = "DENY";
    public static final String DUPLICATED = "DUPLICATED";
    public static final String FLAGGED = "FLAGGED";
    public static final String OVERRIDDEN = "OVERRIDDEN";
    public static final String PARTIAL = "PARTIAL";
    public static final String POLICY = "POLICY";
    public static final String UPDATED = "UPDATED";
}
