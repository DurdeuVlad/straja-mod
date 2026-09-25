package com.dwurdy.straja.domain.model;

/**
 * Domain-local notification intent. It is persisted with its aggregate so a
 * later outbox projection cannot lose an event between two repositories.
 */
public class OutboxIntent {
    public String eventType = "";
    public String dedupeKey = "";
    public String aggregateId = "";
    public String subject = "";
    public long createdAt;

    public static OutboxIntent of(String eventType, String dedupeKey,
                                  String aggregateId, String subject, long createdAt) {
        OutboxIntent intent = new OutboxIntent();
        intent.eventType = eventType == null ? "" : eventType;
        intent.dedupeKey = dedupeKey == null ? "" : dedupeKey;
        intent.aggregateId = aggregateId == null ? "" : aggregateId;
        intent.subject = subject == null ? "" : subject;
        intent.createdAt = createdAt;
        return intent;
    }
}
