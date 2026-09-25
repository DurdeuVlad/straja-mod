package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.FormSessionUseCase;
import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Short-lived, owner-bound, one-use form sessions. A submission is validated
 * before the session is consumed so a wrong owner or malformed payload can
 * never burn another player's session.
 */
public class FormSessionService implements FormSessionUseCase {
    private static final long TTL_MILLIS = 120_000;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private static final int MAX_FIELD_ID = 32;
    private static final Pattern SAFE_FIELD_ID =
            Pattern.compile("[A-Za-z0-9_-]{1," + MAX_FIELD_ID + "}");
    private static final int MAX_FIELDS = 4;
    private static final int MAX_TITLE = 80;
    private static final int MAX_PROMPT = 512;
    private static final int MAX_FIELD_LENGTH = 2_000;
    private static final int MAX_LABEL = 80;

    private static final Set<Action> RECORDLESS = EnumSet.of(
            Action.GIVE_UP, Action.OTHER_REQUEST, Action.FACTION_DECLARE,
            Action.MISSION_DRAFT_WRITE, Action.MISSION_DRAFT_SCOPE,
            Action.MISSION_BUDGET_ADJUST, Action.COMPLAINT_SUBMIT, Action.FINE_DRAFT,
            Action.FINE_WARRANT, Action.ARCHIVE_FOLDER_CREATE, Action.REPORT_SUBMIT,
            Action.AUDIENCE_REQUEST, Action.ADMIN_AUTHORIZE, Action.ADMIN_POLICY_SET,
            Action.ADMIN_EMERGENCY_ALERT, Action.ADMIN_EMERGENCY_START, Action.INCIDENT_REPORT,
            Action.BOLO_CREATE, Action.EVIDENCE_CASE_VIEW,
            Action.ARREST_HANDOFF, Action.REPUTATION_VIEW, Action.REPUTATION_CORRECTION);

    private final Clock clock;
    private final IdGenerator ids;
    private final DocumentService documents;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public FormSessionService(Clock clock, IdGenerator ids) {
        this(clock, ids, null);
    }

    public FormSessionService(Clock clock, IdGenerator ids, DocumentService documents) {
        this.clock = clock;
        this.ids = ids;
        this.documents = documents;
    }

    @Override
    public Optional<View> open(UUID owner, Request request) {
        if (owner == null || request == null || request.action() == null) return Optional.empty();
        String recordId = validRecordId(request.action(), request.recordId());
        if (recordId == null) return Optional.empty();
        if (request.title() == null || request.title().isBlank()
                || request.title().length() > MAX_TITLE) return Optional.empty();
        if (request.prompt() == null || request.prompt().length() > MAX_PROMPT) {
            return Optional.empty();
        }
        List<Field> fields = validFields(request.action(), request.fields());
        if (fields == null) return Optional.empty();

        String sessionId = ids.token();
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        String formRequestId = "";
        if (documents != null && isStandardBlankForm(request.action())) {
            var formRequest = documents.requestBlankForm(owner.toString(), request.action().id(),
                    "FORM_SESSION:" + sessionId);
            formRequestId = formRequest.requestId;
        }
        Session session = new Session(owner, request.action(), recordId, formRequestId, fields,
                clock.nowMillis() + TTL_MILLIS);
        if (sessions.putIfAbsent(sessionId, session) != null) return Optional.empty();
        return Optional.of(new View(sessionId, request.title(), request.prompt(), fields));
    }

    @Override
    public Optional<Submission> submit(UUID owner, String sessionId, Map<String, String> values) {
        if (owner == null || sessionId == null || values == null) return Optional.empty();
        Session session = sessions.get(sessionId);
        if (session == null || !session.owner().equals(owner) || expired(session)) {
            return Optional.empty();
        }
        Map<String, String> cleaned = validValues(session.fields(), values);
        if (cleaned == null) return Optional.empty();
        if (!sessions.remove(sessionId, session)) return Optional.empty();
        if (documents != null && !session.formRequestId().isBlank())
            documents.completeFormRequest(session.formRequestId());
        return Optional.of(new Submission(session.action(), session.recordId(), cleaned));
    }

    @Override
    public boolean cancel(UUID owner, String sessionId) {
        if (owner == null || sessionId == null) return false;
        Session session = sessions.get(sessionId);
        if (session == null || !session.owner().equals(owner) || expired(session)) return false;
        return sessions.remove(sessionId, session);
    }

    @Override
    public void purgeExpired() {
        sessions.entrySet().removeIf(entry -> expired(entry.getValue()));
    }

    private boolean expired(Session session) {
        return clock.nowMillis() >= session.expiresAt();
    }

    private static String validRecordId(Action action, String recordId) {
        if (RECORDLESS.contains(action)) {
            return recordId == null || recordId.isEmpty() ? "" : null;
        }
        return recordId != null && SAFE_ID.matcher(recordId).matches() ? recordId : null;
    }

    private static List<Field> validFields(Action action, List<Field> fields) {
        if (fields == null || fields.size() > MAX_FIELDS) return null;
        if (fields.isEmpty()) return action == Action.GIVE_UP ? List.of() : null;
        Set<String> seen = new HashSet<>();
        for (Field field : fields) {
            if (field == null || field.id() == null
                    || !SAFE_FIELD_ID.matcher(field.id()).matches()) {
                return null;
            }
            if (!seen.add(field.id())) return null;
            if (field.label() == null || field.label().isBlank()
                    || field.label().length() > MAX_LABEL) return null;
            if (field.maxLength() < 1 || field.maxLength() > MAX_FIELD_LENGTH) return null;
        }
        return List.copyOf(fields);
    }

    private static Map<String, String> validValues(List<Field> fields, Map<String, String> values) {
        if (values.size() != fields.size()) return null;
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Field field : fields) {
            if (!values.containsKey(field.id())) return null;
            String raw = values.get(field.id());
            if (raw == null) return null;
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.length() > field.maxLength()) return null;
            cleaned.put(field.id(), trimmed);
        }
        return cleaned;
    }

    private static boolean isStandardBlankForm(Action action) {
        return action == Action.COMPLAINT_SUBMIT || action == Action.REPORT_SUBMIT
                || action == Action.MISSION_DRAFT_WRITE || action == Action.FINE_DRAFT
                || action == Action.OTHER_REQUEST;
    }

    private record Session(UUID owner, Action action, String recordId, String formRequestId,
                           List<Field> fields, long expiresAt) {}
}
