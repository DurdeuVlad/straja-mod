package com.dwurdy.straja.domain.model;

/**
 * A published delegation rule: "resolve every matching request under this
 * policy automatically." Authored once by the delegating authority; every
 * resolution under it records this order id so the decision traces back to a
 * human act. {@code filter} is the queue's single match key — a profession
 * for work petitions, a target grade for promotion petitions, {@code "*"} for
 * "any". Vocabulary is deliberately narrow.
 */
public final class StandingOrder {
    public String orderId = "";
    public String authorUuid = "";
    public String authorName = "";
    public String filter = "*";
    public long createdAt;
    public boolean active = true;

    public boolean matches(String value) {
        if (!active) return false;
        if ("*".equals(filter)) return true;
        return filter.equalsIgnoreCase(value == null ? "" : value.trim());
    }
}
