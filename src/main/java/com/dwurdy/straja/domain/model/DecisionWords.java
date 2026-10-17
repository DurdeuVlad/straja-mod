package com.dwurdy.straja.domain.model;

import java.util.Set;

/**
 * The single vocabulary for review-decision words. Each surface declares its
 * accepted set here instead of hardcoding inline synonym lists that drift —
 * adding a word changes every surface that shares the set.
 */
public final class DecisionWords {
    /** Petition and join-request review: the approve half of the pair. */
    public static final Set<String> APPROVE = Set.of(
            "aproba", "aprobă", "approve", "accept", "accepta", "acceptă");
    /** Petition and join-request review: the deny half of the pair. */
    public static final Set<String> DENY = Set.of(
            "respinge", "reject", "deny", "refuza", "refuză");
    /** Complainant's own choice: withdraw the complaint. */
    public static final Set<String> COMPLAINT_WITHDRAW = Set.of(
            "retrage", "withdraw", "anuleaza", "anulează");
    /** Complainant's own choice: confirm the resolution satisfies them. */
    public static final Set<String> COMPLAINT_SATISFY = Set.of(
            "satisfy", "satisfacut", "satisfăcut", "satisfied", "accept", "confirm");
    /** Complaint review: send the case back for more information. */
    public static final Set<String> COMPLAINT_RETURN = Set.of(
            "return", "trimite-inapoi", "corectie");
    /** Complaint review: close the case as unfounded. */
    public static final Set<String> COMPLAINT_DISMISS = Set.of(
            "dismiss", "respinge", "nefondat");
    /** Complaint review: close the case as resolved. */
    public static final Set<String> COMPLAINT_RESOLVE = Set.of(
            "approve", "aproba", "rezolva", "resolve");
    /** Weekly report review: accept the report. */
    public static final Set<String> REPORT_ACCEPT = Set.of("accept", "accepta", "acceptă");
    /** Weekly report review: return it to the author for fixes. */
    public static final Set<String> REPORT_RETURN = Set.of(
            "return", "returneaza", "returnează");
    /** Weekly report review: summon the author to an audience. */
    public static final Set<String> REPORT_SUMMON = Set.of("call", "cheama", "cheamă");
    /** Audience request: mark it resolved. */
    public static final Set<String> AUDIENCE_RESOLVE = Set.of(
            "resolve", "rezolva", "rezolvă", "accept", "accepta", "acceptă");
    /** Audience request: dismiss it. */
    public static final Set<String> AUDIENCE_DISMISS = Set.of(
            "dismiss", "respinge", "reject");
    /** Fine appeal review: keep the fine as issued. */
    public static final Set<String> FINE_UPHOLD = Set.of(
            "uphold", "mentine", "menține", "confirm");
    /** Fine appeal review: reduce the amount. */
    public static final Set<String> FINE_REDUCE = Set.of(
            "reduce", "redu", "micsoreaza", "micșorează");
    /** Fine appeal review: void the fine entirely. */
    public static final Set<String> FINE_VOID = Set.of(
            "void", "anuleaza", "anulează", "iertare");
    /** Payment-boundary review: allow the payer to retry the payment. */
    public static final Set<String> FINE_PAYMENT_RETRY = Set.of("retry", "reincearca");
    /** Payment-boundary review: mark the fine paid despite the mismatch. */
    public static final Set<String> FINE_PAYMENT_PAID = Set.of("paid", "platita");

    private DecisionWords() {}

    /** Normalizes a raw decision word for set lookup. */
    public static String key(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** True when the raw word belongs to the given surface's set. */
    public static boolean is(String raw, Set<String> words) {
        return words.contains(key(raw));
    }
}
