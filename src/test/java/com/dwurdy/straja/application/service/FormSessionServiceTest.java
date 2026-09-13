package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.application.port.in.FormSessionUseCase.Action;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Field;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Request;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.Submission;
import com.dwurdy.straja.application.port.in.FormSessionUseCase.View;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.MutableClock;
import com.dwurdy.straja.support.Fakes.SeqIds;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Form session security: owner binding, allowlist, expiry, one-use, bounds. */
class FormSessionServiceTest {
    private MutableClock clock;
    private FormSessionService forms;
    private UUID owner;
    private UUID other;

    @BeforeEach
    void setup() {
        clock = new MutableClock();
        forms = new FormSessionService(clock, new SeqIds());
        owner = UUID.randomUUID();
        other = UUID.randomUUID();
    }

    private static Request request(Action action, String recordId) {
        return new Request(action, recordId, "Report", "Describe what happened",
                List.of(new Field("details", "Details", 200, true)));
    }

    private static Request recordless() {
        return request(Action.OTHER_REQUEST, "");
    }

    // ------------------------------------------------------------ open/submit

    @Test
    void ownerSubmitsAndConsumes() {
        View view = forms.open(owner, recordless()).orElseThrow();
        assertFalse(view.sessionId().isBlank());
        Optional<Submission> result =
                forms.submit(owner, view.sessionId(), Map.of("details", "  hello  "));
        assertTrue(result.isPresent());
        assertEquals(Action.OTHER_REQUEST, result.get().action());
        assertEquals("", result.get().recordId());
        assertEquals("hello", result.get().values().get("details"));
    }

    @Test
    void wrongOwnerDoesNotConsume() {
        View view = forms.open(owner, recordless()).orElseThrow();
        assertTrue(forms.submit(other, view.sessionId(), Map.of("details", "x")).isEmpty());
        assertTrue(forms.submit(owner, view.sessionId(), Map.of("details", "x")).isPresent());
    }

    @Test
    void sessionIsOneUse() {
        View view = forms.open(owner, recordless()).orElseThrow();
        assertTrue(forms.submit(owner, view.sessionId(), Map.of("details", "x")).isPresent());
        assertTrue(forms.submit(owner, view.sessionId(), Map.of("details", "x")).isEmpty());
    }

    @Test
    void unknownSessionIdRejected() {
        assertTrue(forms.submit(owner, "no-such-session", Map.of("details", "x")).isEmpty());
        assertFalse(forms.cancel(owner, "no-such-session"));
    }

    // ------------------------------------------------------------ expiry

    @Test
    void submissionBeforeDeadlineAccepted() {
        View view = forms.open(owner, recordless()).orElseThrow();
        clock.advance(120_000 - 30_000);
        assertTrue(forms.submit(owner, view.sessionId(), Map.of("details", "x")).isPresent());
    }

    @Test
    void submissionAtDeadlineRejected() {
        View view = forms.open(owner, recordless()).orElseThrow();
        clock.advance(120_000);
        assertTrue(forms.submit(owner, view.sessionId(), Map.of("details", "x")).isEmpty());
        assertFalse(forms.cancel(owner, view.sessionId()));
    }

    @Test
    void purgeExpiredRemovesSessions() {
        View view = forms.open(owner, recordless()).orElseThrow();
        clock.advance(120_000);
        forms.purgeExpired();
        assertFalse(forms.cancel(owner, view.sessionId()));
    }

    // ------------------------------------------------------------ allowlist / records

    @Test
    void unknownActionIdRejected() {
        assertTrue(Action.fromId("not-an-action").isEmpty());
        assertEquals(Action.MISSION_REPORT, Action.fromId("mission-report").orElseThrow());
        assertTrue(forms.open(owner, request(null, "")).isEmpty());
    }

    @Test
    void recordlessActionsRejectRecordId() {
        for (Action action : List.of(Action.COMPLAINT_SUBMIT, Action.MISSION_DRAFT_WRITE,
                Action.MISSION_DRAFT_SCOPE, Action.FINE_DRAFT, Action.FINE_WARRANT,
                Action.ARCHIVE_FOLDER_CREATE, Action.OTHER_REQUEST)) {
            assertTrue(forms.open(owner, request(action, "R-1")).isEmpty(),
                    action + " must not accept a record id");
        }
    }

    @Test
    void missionDraftActionsRequireEmptyRecordId() {
        for (Action action : List.of(Action.MISSION_DRAFT_WRITE, Action.MISSION_DRAFT_SCOPE)) {
            assertTrue(forms.open(owner, request(action, "M-1")).isEmpty(),
                    action + " rejects a bound record");
            assertTrue(forms.open(owner, request(action, "")).isPresent(),
                    action + " opens with an empty record id");
        }
    }

    @Test
    void quizAnswerBindsServerQuestionId() {
        var view = forms.open(owner, request(Action.QUIZ_ANSWER, "juramant")).orElseThrow();
        var submission = forms.submit(owner, view.sessionId(), Map.of("details", "x")).orElseThrow();
        assertEquals(Action.QUIZ_ANSWER, submission.action());
        assertEquals("juramant", submission.recordId());
    }

    @Test
    void fineAppealReviewRequiresBoundRecord() {
        assertTrue(forms.open(owner, request(Action.FINE_APPEAL_REVIEW, "")).isEmpty(),
                "appeal-review sessions bind the fine id server-side");
        assertTrue(forms.open(owner, request(Action.FINE_APPEAL_REVIEW, "F-1")).isPresent());
        assertTrue(forms.open(owner, request(Action.FINE_APPEAL_REVIEW, "bad id")).isEmpty());
    }

    @Test
    void complaintReviewRequiresBoundRecord() {
        assertTrue(forms.open(owner, request(Action.COMPLAINT_REVIEW, "")).isEmpty(),
                "review sessions bind the case id server-side");
        assertTrue(forms.open(owner, request(Action.COMPLAINT_REVIEW, "C-1")).isPresent());
        assertTrue(forms.open(owner, request(Action.COMPLAINT_REVIEW, "bad id")).isEmpty());
    }

    @Test
    void recordActionsRequireSafeRecordId() {
        for (Action action : List.of(Action.QUIZ_ANSWER, Action.MISSION_REPORT, Action.MISSION_FAIL,
                Action.COMPLAINT_REPORT, Action.COMPLAINT_WITHDRAW, Action.COMPLAINT_REVIEW,
                Action.FINE_APPEAL, Action.FINE_APPEAL_REVIEW,
                Action.ARCHIVE_FOLDER_ISSUE, Action.ARCHIVE_SHEET_NEW, Action.ARCHIVE_SHEET_EDIT,
                Action.ARCHIVE_RECIPIENTS, Action.ARCHIVE_SIGN, Action.ARCHIVE_COPY,
                Action.ARCHIVE_ENVELOPE, Action.ARCHIVE_DOCUMENT_ISSUE)) {
            assertTrue(forms.open(owner, request(action, "M-7")).isPresent(),
                    action + " must accept a safe record id");
            assertTrue(forms.open(owner, request(action, null)).isEmpty());
            assertTrue(forms.open(owner, request(action, "")).isEmpty());
            assertTrue(forms.open(owner, request(action, "has space")).isEmpty());
            assertTrue(forms.open(owner, request(action, "x".repeat(81))).isEmpty());
        }
    }

    // ------------------------------------------------------------ request validation

    @Test
    void malformedRequestsRejected() {
        assertTrue(forms.open(null, recordless()).isEmpty());
        assertTrue(forms.open(owner, null).isEmpty());
        assertTrue(forms.open(owner, request(Action.OTHER_REQUEST, null)).isPresent(),
                "null record id normalizes to empty for recordless actions");

        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", null,
                "Prompt", List.of(new Field("a", "A", 10, false)))).isEmpty());
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "   ",
                "Prompt", List.of(new Field("a", "A", 10, false)))).isEmpty());
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "t".repeat(81),
                "Prompt", List.of(new Field("a", "A", 10, false)))).isEmpty());
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "Title",
                null, List.of(new Field("a", "A", 10, false)))).isEmpty());
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "Title",
                "p".repeat(513), List.of(new Field("a", "A", 10, false)))).isEmpty());
    }

    @Test
    void malformedFieldSpecsRejected() {
        Field ok = new Field("details", "Details", 200, true);
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                null)).isEmpty());
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                List.of())).isEmpty());
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                List.of(ok, ok, ok, ok, new Field("e", "E", 10, false)))).isEmpty(),
                "five fields exceeds the bound");
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                listWithNull())).isEmpty());
        for (Field bad : List.of(
                new Field(null, "L", 10, false),
                new Field("", "L", 10, false),
                new Field("has space", "L", 10, false),
                new Field("f".repeat(33), "L", 10, false),
                new Field("a", null, 10, false),
                new Field("a", "", 10, false),
                new Field("a", "l".repeat(81), 10, false),
                new Field("a", "L", 0, false),
                new Field("a", "L", -5, false),
                new Field("a", "L", 2_001, false))) {
            assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                    List.of(bad))).isEmpty(), "field should be rejected: " + bad);
        }
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                List.of(new Field("dup", "A", 10, false), new Field("dup", "B", 10, false))))
                .isEmpty(), "duplicate field ids rejected");
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                List.of(new Field("a", "L", 2_000, false)))).isPresent(),
                "field at max bound accepted");
        assertTrue(forms.open(owner, new Request(Action.OTHER_REQUEST, "", "T", "P",
                List.of(new Field("f".repeat(32), "L", 10, false)))).isPresent(),
                "32-char field id accepted");
    }

    private static List<Field> listWithNull() {
        List<Field> fields = new ArrayList<>();
        fields.add(null);
        return fields;
    }

    // ------------------------------------------------------------ submission validation

    @Test
    void malformedSubmissionDoesNotConsume() {
        View view = forms.open(owner, recordless()).orElseThrow();
        String id = view.sessionId();

        assertTrue(forms.submit(owner, id, Map.of()).isEmpty(), "missing field key");
        assertTrue(forms.submit(owner, id, Map.of("details", "x", "extra", "y")).isEmpty(),
                "unknown extra key");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("details", null);
        assertTrue(forms.submit(owner, id, nullValue).isEmpty(), "null value");
        assertTrue(forms.submit(owner, id, Map.of("details", "   ")).isEmpty(), "blank value");
        assertTrue(forms.submit(owner, id, Map.of("details", "v".repeat(201))).isEmpty(),
                "value over field max length");
        assertTrue(forms.submit(owner, id, Map.of("details", "v".repeat(200))).isPresent(),
                "value at field max length consumes the session");
    }

    // ------------------------------------------------------------ cancel

    @Test
    void cancelIsOwnerChecked() {
        View view = forms.open(owner, recordless()).orElseThrow();
        assertFalse(forms.cancel(other, view.sessionId()));
        assertTrue(forms.submit(owner, view.sessionId(), Map.of("details", "x")).isPresent(),
                "foreign cancel must not consume");
    }

    @Test
    void ownerCancelReleasesSession() {
        View view = forms.open(owner, recordless()).orElseThrow();
        assertTrue(forms.cancel(owner, view.sessionId()));
        assertFalse(forms.cancel(owner, view.sessionId()), "second cancel reports false");
        assertTrue(forms.submit(owner, view.sessionId(), Map.of("details", "x")).isEmpty());
    }

    // ------------------------------------------------------------ tokens / concurrency

    @Test
    void repeatedTokenRejected() {
        IdGenerator stuck = new IdGenerator() {
            @Override public String newId(String prefix) { return prefix + "-1"; }
            @Override public String token() { return "same-token"; }
        };
        FormSessionService service = new FormSessionService(clock, stuck);
        assertTrue(service.open(owner, recordless()).isPresent());
        assertTrue(service.open(other, recordless()).isEmpty(),
                "a repeated session token must be rejected");
        assertTrue(service.submit(other, "same-token", Map.of("details", "x")).isEmpty(),
                "rejected session must not overwrite the existing owner binding");
        assertTrue(service.submit(owner, "same-token", Map.of("details", "x")).isPresent(),
                "the original session survives the token collision");
    }

    @Test
    void concurrentSubmissionsConsumeExactlyOnce() throws Exception {
        View view = forms.open(owner, recordless()).orElseThrow();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (forms.submit(owner, view.sessionId(), Map.of("details", "x")).isPresent()) {
                    successes.incrementAndGet();
                }
            }));
        }
        assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
        go.countDown();
        for (Future<?> future : futures) future.get();
        pool.shutdown();
        assertEquals(1, successes.get());
    }
}
