package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.DiscordWebhookGateway;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.OutboxRepository;
import com.dwurdy.straja.domain.model.OutboxEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;

/** Persistent, allowlisted, redacted outbound notification queue. */
public final class OutboxService {
    private static final Set<String> ALLOWLIST = Set.of(
            "PERSONNEL_AUTHORIZED", "IMPORTANT_MISSION_CREATED", "PROMOTION_COMPLETED",
            "CAMPAIGN_STARTED", "CAMPAIGN_ENDED", "MAJOR_INCIDENT");
    private static final Set<String> SAFE_FIELDS = Set.of(
            "eventType", "aggregateId", "publicId", "subject", "station", "title", "severity");
    private static final Gson SAFE_GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final OutboxRepository repository;
    private final Clock clock;
    private final IdGenerator ids;

    public OutboxService(OutboxRepository repository, Clock clock, IdGenerator ids) {
        this.repository = repository; this.clock = clock; this.ids = ids;
    }

    public synchronized OutboxEvent enqueue(String eventType, String dedupeKey, SafePayload payload) {
        if (!ALLOWLIST.contains(eventType)) throw new IllegalArgumentException("event type is not allowlisted");
        List<OutboxEvent> events = repository.read();
        String effectiveDedupeKey = dedupeKey == null || dedupeKey.isBlank() ? eventType : dedupeKey;
        for (OutboxEvent existing : events) if (existing != null && effectiveDedupeKey.equals(existing.dedupeKey)) return existing;
        OutboxEvent event = new OutboxEvent(); event.eventId = ids.newId("OUT"); event.eventType = eventType;
        event.dedupeKey = effectiveDedupeKey; event.safePayload = payload == null ? "{}" : redact(payload.json());
        event.createdAt = clock.nowMillis(); event.nextAttemptAt = event.createdAt; events.add(event); repository.write(events); return event;
    }

    public synchronized int dispatchDue(DiscordWebhookGateway gateway, int maxEvents) {
        if (gateway == null || maxEvents <= 0) return 0;
        List<OutboxEvent> events = repository.read(); int sent = 0; long now = clock.nowMillis();
        for (OutboxEvent event : events) {
            if (sent >= maxEvents) break;
            if (event == null || (event.status != OutboxEvent.OutboxStatus.PENDING && event.status != OutboxEvent.OutboxStatus.RETRY)
                    || event.nextAttemptAt > now) continue;
            event.status = OutboxEvent.OutboxStatus.SENDING; event.attempts++;
            boolean delivered = false;
            try { delivered = gateway.send(event.safePayload); }
            catch (RuntimeException error) { event.lastError = error.getClass().getSimpleName(); }
            if (delivered) { event.status = OutboxEvent.OutboxStatus.SENT; event.sentAt = now; sent++; }
            else if (event.attempts >= 5) event.status = OutboxEvent.OutboxStatus.DEAD_LETTER;
            else { event.status = OutboxEvent.OutboxStatus.RETRY; event.nextAttemptAt = now + Math.min(300_000L, 1_000L << Math.min(8, event.attempts)); }
        }
        repository.write(events); return sent;
    }

    /** Converts an interrupted send back to a retryable state after restart. */
    public synchronized int recoverInFlight() {
        List<OutboxEvent> events = repository.read(); int changed = 0; long now = clock.nowMillis();
        for (OutboxEvent event : events) {
            if (event != null && event.status == OutboxEvent.OutboxStatus.SENDING) {
                event.status = OutboxEvent.OutboxStatus.RETRY;
                event.nextAttemptAt = now;
                changed++;
            }
        }
        if (changed > 0) repository.write(events);
        return changed;
    }

    public synchronized java.util.List<OutboxEvent> pending() {
        return repository.read().stream().filter(event -> event != null
                && event.status != OutboxEvent.OutboxStatus.SENT).toList();
    }

    public synchronized java.util.List<OutboxEvent> all() {
        return java.util.List.copyOf(repository.read());
    }

    public synchronized boolean retry(String eventId) {
        List<OutboxEvent> events = repository.read();
        for (OutboxEvent event : events) {
            if (event != null && event.eventId.equals(eventId)
                    && event.status != OutboxEvent.OutboxStatus.SENT) {
                event.status = OutboxEvent.OutboxStatus.RETRY;
                event.nextAttemptAt = clock.nowMillis();
                event.lastError = "";
                repository.write(events);
                return true;
            }
        }
        return false;
    }

    public synchronized java.util.List<OutboxEvent> deadLetters() {
        return repository.read().stream().filter(event -> event != null
                && event.status == OutboxEvent.OutboxStatus.DEAD_LETTER).toList();
    }

    public record SafePayload(String json) {
        public static SafePayload projection(String eventType, String aggregateId, String subjectUuid) {
            String safeType = sanitize(eventType); String safeAggregate = sanitize(aggregateId);
            String safeSubject = sanitize(subjectUuid);
            return new SafePayload("{\"eventType\":\"" + safeType + "\",\"aggregateId\":\""
                    + safeAggregate + "\",\"subject\":\"" + safeSubject + "\"}");
        }
        private static String sanitize(String value) {
            if (value == null) return "";
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", " ").replace("\r", " ");
        }
    }

    private static String redact(String payload) {
        if (payload == null || payload.isBlank()) return "{}";
        try {
            JsonElement parsed = JsonParser.parseString(payload);
            if (!parsed.isJsonObject()) return "{}";
            JsonObject safe = new JsonObject();
            for (var entry : parsed.getAsJsonObject().entrySet()) {
                if (!SAFE_FIELDS.contains(entry.getKey())) continue;
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonPrimitive()
                        && (value.getAsJsonPrimitive().isString()
                        || value.getAsJsonPrimitive().isBoolean()
                        || value.getAsJsonPrimitive().isNumber())) {
                    safe.add(entry.getKey(), value.deepCopy());
                }
            }
            return SAFE_GSON.toJson(safe);
        } catch (RuntimeException ignored) {
            return "{}";
        }
    }
}
