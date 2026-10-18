package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The configured delegation mode for one request queue, plus the standing
 * orders published under it. The authority that last set the policy is the
 * recorded approver for timeout presumptions — their name and uuid persist so
 * resolution stays attributable even when they are offline.
 */
public final class DelegationPolicy {
    public DelegationMode mode = DelegationMode.MANUAL;
    public long timeoutMillis;
    public String setByUuid = "";
    public String setByName = "";
    public long updatedAt;
    public List<StandingOrder> orders = new ArrayList<>();

    public List<StandingOrder> ensureOrders() {
        if (orders == null) orders = new ArrayList<>();
        return orders;
    }

    public StandingOrder firstMatching(String value, String subjectUuid) {
        for (StandingOrder order : ensureOrders()) {
            if (order == null || !order.active) continue;
            // Anti-self-approval: an order never resolves its author's own
            // request, independent of the downstream conflict-set checks.
            if (subjectUuid != null && subjectUuid.equals(order.authorUuid)) continue;
            if (order.matches(value)) return order;
        }
        return null;
    }

    public boolean hasActiveOrder() {
        for (StandingOrder order : ensureOrders()) {
            if (order != null && order.active) return true;
        }
        return false;
    }
}
