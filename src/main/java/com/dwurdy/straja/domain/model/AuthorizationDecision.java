package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class AuthorizationDecision {
    public boolean allowed;
    public ReasonCode reasonCode = ReasonCode.DENIED_NOT_AUTHORIZED;
    public String effectiveAuthority = "";
    public boolean requiredApproval;
    public String escalationTarget = "";
    public List<String> constraints = new ArrayList<>();
    public Long budgetRemaining;

    public static AuthorizationDecision allow(String authority) {
        AuthorizationDecision decision = new AuthorizationDecision();
        decision.allowed = true;
        decision.reasonCode = ReasonCode.ALLOWED;
        decision.effectiveAuthority = authority == null ? "" : authority;
        return decision;
    }

    public static AuthorizationDecision deny(ReasonCode reason) {
        AuthorizationDecision decision = new AuthorizationDecision();
        decision.allowed = false;
        decision.reasonCode = reason == null ? ReasonCode.DENIED_NOT_AUTHORIZED : reason;
        return decision;
    }

    public enum ReasonCode {
        ALLOWED,
        DENIED_NOT_AUTHORIZED,
        DENIED_SUSPENDED,
        DENIED_TERMINATED,
        DENIED_GRADE,
        DENIED_APPOINTMENT,
        DENIED_MOBILIZATION_REQUIRED,
        DENIED_STATION,
        DENIED_JURISDICTION,
        DENIED_AFFILIATION_CONFLICT,
        DENIED_BUDGET,
        DENIED_SELF_APPROVAL,
        DENIED_CONFLICT_OF_INTEREST,
        DENIED_STALE_STATE
    }
}
