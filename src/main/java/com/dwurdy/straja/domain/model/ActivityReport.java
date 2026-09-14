package com.dwurdy.straja.domain.model;

/**
 * A member's periodic activity report filed through the Secretary (§11).
 * The stored report is authoritative; any physical item is RP flavor.
 */
public class ActivityReport {
    public static final String SUBMITTED = "SUBMITTED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String RETURNED = "RETURNED";
    public static final String COMISAR_REVIEW = "COMISAR_REVIEW";

    public String id = "";               // R<n>
    public String authorUuid = "";
    public String authorName = "";
    public long periodStart;
    public long periodEnd;
    public String activity = "";
    public String missions = "";
    public String incidents = "";
    public String notes = "";            // notes for the Comisar
    public String status = SUBMITTED;
    public long submittedAt;
    public int revision = 1;
    // review
    public String reviewedBy = "";
    public String reviewNote = "";
    public Long reviewedAt;
}
