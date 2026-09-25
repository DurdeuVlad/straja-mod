package com.dwurdy.straja.domain.model;

public class OutboxEvent {
    public String eventId = "";
    public String eventType = "";
    public String dedupeKey = "";
    public String safePayload = "";
    public OutboxStatus status = OutboxStatus.PENDING;
    public int attempts;
    public long nextAttemptAt;
    public long createdAt;
    public Long sentAt;
    public String lastError = "";

    public enum OutboxStatus { PENDING, SENDING, SENT, RETRY, DEAD_LETTER }
}
