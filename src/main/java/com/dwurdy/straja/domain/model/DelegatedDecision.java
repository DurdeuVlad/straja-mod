package com.dwurdy.straja.domain.model;

/**
 * Ledger entry for one delegated resolution: which queue, which request,
 * which mode and order resolved it, whose delegated authority acted, and
 * whether the outcome was presumed (timeout) rather than matched (standing
 * order). Revocation of an order stops future resolutions; past decisions
 * stay listed here for review.
 */
public final class DelegatedDecision {
    public String decisionId = "";
    public String action = "";
    public String requestId = "";
    public String subjectUuid = "";
    public String outcome = "";
    public String mode = "";
    public String orderId = "";
    public String authorityUuid = "";
    public String authorityName = "";
    public long decidedAt;
    public boolean presumed;
}
