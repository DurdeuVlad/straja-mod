package com.dwurdy.straja.application.port.in;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Inbound port for native server-authoritative forms. Sessions are
 * short-lived, owner-bound and one-use; the adapter only carries the opaque
 * session id plus field values — never player identity or record state.
 */
public interface FormSessionUseCase {
    enum Action {
        QUIZ_ANSWER("quiz-answer"),
        FACTION_DECLARE("faction-declare"),
        MISSION_REPORT("mission-report"),
        MISSION_FAIL("mission-fail"),
        MISSION_DRAFT_WRITE("mission-draft-write"),
        MISSION_DRAFT_SCOPE("mission-draft-scope"),
        MISSION_BUDGET_ADJUST("mission-budget-adjust"),
        COMPLAINT_SUBMIT("complaint-submit"),
        COMPLAINT_REPORT("complaint-report"),
        COMPLAINT_WITHDRAW("complaint-withdraw"),
        COMPLAINT_REVIEW("complaint-review"),
        FINE_APPEAL("fine-appeal"),
        FINE_APPEAL_REVIEW("fine-appeal-review"),
        FINE_DRAFT("fine-draft"),
        FINE_WARRANT("fine-warrant"),
        ARCHIVE_FOLDER_CREATE("archive-folder-create"),
        ARCHIVE_FOLDER_ISSUE("archive-folder-issue"),
        ARCHIVE_SHEET_NEW("archive-sheet-new"),
        ARCHIVE_SHEET_EDIT("archive-sheet-edit"),
        ARCHIVE_RECIPIENTS("archive-recipients"),
        ARCHIVE_SIGN("archive-sign"),
        ARCHIVE_COPY("archive-copy"),
        ARCHIVE_ENVELOPE("archive-envelope"),
        ARCHIVE_DOCUMENT_ISSUE("archive-document-issue"),
        REPORT_SUBMIT("report-submit"),
        REPORT_REVIEW("report-review"),
        AUDIENCE_REQUEST("audience-request"),
        AUDIENCE_REVIEW("audience-review"),
        ADMIN_AUTHORIZE("admin-authorize"),
        ADMIN_POLICY_SET("admin-policy-set"),
        ADMIN_EMERGENCY_ALERT("admin-emergency-alert"),
        ADMIN_EMERGENCY_START("admin-emergency-start"),
        TOOL_NPC_NAME("tool-npc-name"),
        TOOL_NPC_SKIN("tool-npc-skin"),
        GIVE_UP("give-up"),
        INCIDENT_REPORT("incident-report"),
        BOLO_CREATE("bolo-create"),
        INCIDENT_RESOLVE("incident-resolve"),
        EVIDENCE_CONFISCATE("evidence-confiscate"),
        EVIDENCE_TRANSFER("evidence-transfer"),
        EVIDENCE_DESTROY("evidence-destroy"),
        EVIDENCE_CASE_VIEW("evidence-case-view"),
        ARREST_HANDOFF("arrest-handoff"),
        REPUTATION_VIEW("reputation-view"),
        REPUTATION_CORRECTION("reputation-correction"),
        OTHER_REQUEST("other-request");

        private final String id;

        Action(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Optional<Action> fromId(String id) {
            for (Action action : values()) {
                if (action.id.equals(id)) return Optional.of(action);
            }
            return Optional.empty();
        }
    }

    record Field(String id, String label, int maxLength, boolean multiline) {}

    record Request(Action action, String recordId, String title, String prompt, List<Field> fields) {
        public Request {
            if (fields != null) {
                fields = Collections.unmodifiableList(new ArrayList<>(fields));
            }
        }
    }

    record View(String sessionId, String title, String prompt, List<Field> fields) {
        public View {
            fields = List.copyOf(fields);
        }
    }

    record Submission(Action action, String recordId, Map<String, String> values) {
        public Submission {
            values = Map.copyOf(values);
        }
    }

    Optional<View> open(UUID owner, Request request);

    Optional<Submission> submit(UUID owner, String sessionId, Map<String, String> values);

    boolean cancel(UUID owner, String sessionId);

    void purgeExpired();
}
