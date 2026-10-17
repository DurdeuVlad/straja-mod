package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delegation policies per {@link DelegableAction} plus the decision ledger.
 * Decisions are insertion-ordered and capped — the ledger is a review
 * surface, not the audit of record (audit entries carry the same facts).
 */
public final class DelegationStore {
    /** Kept decisions per action queue, oldest evicted first. */
    public static final int DECISION_CAP = 128;
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public Map<String, DelegationPolicy> policies;
    public LinkedHashMap<String, DelegatedDecision> decisions;
    public long storeRevision;

    public Map<String, DelegationPolicy> ensurePolicies() {
        if (policies == null) policies = new LinkedHashMap<>();
        return policies;
    }

    public LinkedHashMap<String, DelegatedDecision> ensureDecisions() {
        if (decisions == null) decisions = new LinkedHashMap<>();
        return decisions;
    }

    public DelegationPolicy policy(DelegableAction action) {
        if (action == null) return null;
        return ensurePolicies().get(action.name());
    }

    /** Records a decision, evicting the oldest entries for that action past
     *  the cap. Returns the recorded entry. */
    public DelegatedDecision record(DelegatedDecision decision) {
        LinkedHashMap<String, DelegatedDecision> map = ensureDecisions();
        map.put(decision.decisionId, decision);
        var sameAction = new ArrayList<String>();
        for (var entry : map.entrySet()) {
            if (entry.getValue() != null && decision.action.equals(entry.getValue().action)) {
                sameAction.add(entry.getKey());
            }
        }
        while (sameAction.size() > DECISION_CAP) {
            map.remove(sameAction.remove(0));
        }
        storeRevision++;
        return decision;
    }
}
