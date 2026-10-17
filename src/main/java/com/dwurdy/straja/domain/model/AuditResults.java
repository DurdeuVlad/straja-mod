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
}
