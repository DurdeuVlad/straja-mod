package com.dwurdy.straja.domain.model;

/**
 * FUN-11 parole/appeal docket entry. A filed hearing is durable and ordered
 * by {@link #filedAt}; it moves FILED → HEARING → a decided outcome
 * (PAROLED | DENIED | CONDITIONAL) or CANCELLED. The record itself is the
 * hearing artifact — even a delegation-swept decision lands here first, so
 * no parole happens without one.
 */
public class HearingRecord {
    public String id = "";
    /** PAROLE | APPEAL — appeals reuse the same docket ordering. */
    public String kind = "PAROLE";
    public String prisonerUuid = "";
    public String prisonerName = "";
    /** FILED | HEARING | PAROLED | DENIED | CONDITIONAL | CANCELLED */
    public String status = "FILED";
    public String reason = "";
    public long filedAt;
    public long hearingOpenedAt;
    /** Bounded victim window — decision blocked until this passes. */
    public long objectionUntil;
    public int objections;
    /** Victims who already objected — a repeat click records nothing twice. */
    public java.util.List<String> objectorUuids;
    public String advocateUuid = "";
    public String advocateName = "";
    /** "" | ASKED | AGREED | DECLINED — consensual, no authority. */
    public String advocateStatus = "";
    public String decidedByUuid = "";
    public String decidedByName = "";
    public long decidedAt;
    /** DENIED cooldown — the prisoner cannot refile before this. */
    public long refileAfter;
    /** CONDITIONAL bail window and labor-account price of freedom. */
    public long conditionalUntil;
    public int bailAmount;
    public long version;
}
