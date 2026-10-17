package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.JoinRequestRepository;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.JoinRequest;
import com.dwurdy.straja.domain.model.JoinRequestStatus;
import com.dwurdy.straja.domain.model.JoinRequestStore;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import com.dwurdy.straja.domain.model.PersonnelStatus;
import com.dwurdy.straja.domain.model.Text;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;

/**
 * Petition-gated entry into the trades: a civilian files a work petition at
 * the Receptionist, the Comisar reads and stamps it, and approval enrolls the
 * applicant as a part-time Zilier. One open request per subject; re-filing
 * with the same profession and reason is idempotent, a changed petition
 * updates the open record and bumps its version so a stale review form
 * fails closed.
 */
public final class JoinRequestService implements com.dwurdy.straja.application.port.in.JoinRequestUseCase {
    private final JoinRequestRepository requests;
    private final PersonnelService personnel;
    private final AuthorizationService authorization;
    private final java.util.function.Supplier<java.util.Set<String>> professions;
    private final Clock clock;
    private final IdGenerator ids;
    private AuditService audit = AuditService.NONE;

    public JoinRequestService(JoinRequestRepository requests, PersonnelService personnel,
                              AuthorizationService authorization,
                              java.util.function.Supplier<java.util.Set<String>> professions,
                              Clock clock, IdGenerator ids) {
        this.requests = requests;
        this.personnel = personnel;
        this.authorization = authorization;
        this.professions = professions;
        this.clock = clock;
        this.ids = ids;
    }

    /** Installed by the composition root; {@link AuditService#NONE} in unit contexts. */
    public void useAuditService(AuditService audit) {
        this.audit = audit == null ? AuditService.NONE : audit;
    }

    @Override
    public synchronized JoinRequest submit(String subjectUuid, String profession, String reason) {
        if (subjectUuid == null || subjectUuid.isBlank())
            throw new IllegalArgumentException("subject required");
        String normalizedProfession = Text.normalize(profession);
        java.util.Set<String> allowed = professions == null ? java.util.Set.of() : professions.get();
        if (normalizedProfession.isEmpty() || allowed == null || !allowed.contains(normalizedProfession))
            throw new IllegalArgumentException("JOIN_UNKNOWN_PROFESSION");
        requireEligibleSubject(subjectUuid);
        String normalizedReason = Text.bounded(reason, Text.REASON_LIMIT);

        JoinRequestStore store = requests.read();
        store.ensureRequests();
        JoinRequest open = null;
        for (JoinRequest request : store.requests.values()) {
            if (request != null && subjectUuid.equals(request.subjectUuid)
                    && request.status == JoinRequestStatus.PENDING) {
                open = request;
                break;
            }
        }
        if (open != null) {
            if (normalizedProfession.equals(open.profession) && normalizedReason.equals(open.reason))
                return open; // identical open petition — idempotent
            open.profession = normalizedProfession;
            open.reason = normalizedReason;
            open.version++;
            store.storeRevision++;
            requests.write(store);
            audit.record(AuditActions.JOIN_REQUEST, subjectUuid, subjectUuid,
                    open.requestId, subjectUuid, AuditResults.SUCCESS,
                    "updated profession=" + normalizedProfession);
            return open;
        }
        JoinRequest request = new JoinRequest();
        request.requestId = ids.newId("JOIN");
        request.subjectUuid = subjectUuid;
        request.profession = normalizedProfession;
        request.reason = normalizedReason;
        request.submittedAt = clock.nowMillis();
        store.requests.put(request.requestId, request);
        store.storeRevision++;
        requests.write(store);
        audit.record(AuditActions.JOIN_REQUEST, subjectUuid, subjectUuid,
                request.requestId, subjectUuid, AuditResults.SUCCESS,
                "filed profession=" + normalizedProfession);
        return request;
    }

    @Override
    public synchronized java.util.List<JoinRequest> pending() {
        JoinRequestStore store = requests.read();
        return store.ensureRequests().values().stream()
                .filter(request -> request != null && request.status == JoinRequestStatus.PENDING)
                .sorted(java.util.Comparator.comparingLong(request -> request.submittedAt))
                .toList();
    }

    @Override
    public synchronized JoinRequest find(String requestId) {
        JoinRequestStore store = requests.read();
        return store.ensureRequests().get(requestId);
    }

    @Override
    public synchronized JoinRequest findOpen(String subjectUuid) {
        JoinRequestStore store = requests.read();
        return store.ensureRequests().values().stream()
                .filter(request -> request != null && subjectUuid.equals(request.subjectUuid)
                        && request.status == JoinRequestStatus.PENDING)
                .findFirst().orElse(null);
    }

    @Override
    public synchronized JoinRequest approve(String actorUuid, String requestId, long expectedVersion) {
        JoinRequest request = requireDecideable(actorUuid, requestId, expectedVersion);
        // Enroll first: the personnel write is idempotent, so a crash or a
        // retried approval converges instead of leaving a half-stamped
        // request.
        personnel.enrollProfessional(actorUuid, request.subjectUuid, request.profession,
                "JOIN_PETITION", "join-request:" + request.requestId);
        JoinRequestStore store = requests.read();
        JoinRequest persisted = requireRequest(store, requestId);
        persisted.status = JoinRequestStatus.APPROVED;
        persisted.reviewedBy = actorUuid;
        persisted.decisionAt = clock.nowMillis();
        persisted.version++;
        store.storeRevision++;
        requests.write(store);
        audit.record(AuditActions.JOIN_REQUEST_REVIEW, actorUuid, actorUuid,
                persisted.requestId, persisted.subjectUuid, AuditResults.APPROVED,
                "profession=" + persisted.profession);
        return persisted;
    }

    @Override
    public synchronized JoinRequest reject(String actorUuid, String requestId, long expectedVersion,
                                           String reason) {
        JoinRequest request = requireDecideable(actorUuid, requestId, expectedVersion);
        JoinRequestStore store = requests.read();
        JoinRequest persisted = requireRequest(store, requestId);
        persisted.status = JoinRequestStatus.REJECTED;
        persisted.reviewedBy = actorUuid;
        persisted.decisionAt = clock.nowMillis();
        persisted.decisionReason = Text.bounded(reason, Text.REASON_LIMIT);
        persisted.version++;
        store.storeRevision++;
        requests.write(store);
        audit.record(AuditActions.JOIN_REQUEST_REVIEW, actorUuid, actorUuid,
                persisted.requestId, persisted.subjectUuid, AuditResults.REJECTED,
                persisted.decisionReason);
        return persisted;
    }

    /**
     * Delivers the subject's latest undelivered join decision. Called on
     * login and right after a live review so the applicant learns the
     * outcome — and on denial, the reason.
     */
    @Override
    public synchronized void deliverOutcome(
            com.dwurdy.straja.application.port.out.PlayerGateway player) {
        if (player == null) return;
        JoinRequestStore store = requests.read();
        JoinRequest decided = null;
        for (JoinRequest request : store.ensureRequests().values()) {
            if (request == null || request.outcomeDelivered
                    || !player.uuid().toString().equals(request.subjectUuid)) continue;
            if (request.status == JoinRequestStatus.APPROVED
                    || request.status == JoinRequestStatus.REJECTED) {
                if (decided == null || request.decisionAt == null
                        || decided.decisionAt == null
                        || request.decisionAt > decided.decisionAt) {
                    decided = request;
                }
            }
        }
        if (decided == null) return;
        if (decided.status == JoinRequestStatus.APPROVED) {
            player.tell(Text.STRAJA_PREFIX + "Cererea ta de muncă " + decided.requestId
                    + " a fost aprobată — ești acum Zilier ("
                    + Text.displayLower(decided.profession) + ").");
        } else {
            player.tell(Text.STRAJA_PREFIX + "Cererea ta de muncă " + decided.requestId + " a fost respinsă"
                    + (decided.decisionReason.isBlank() ? "." : ": " + decided.decisionReason));
        }
        decided.outcomeDelivered = true;
        store.storeRevision++;
        requests.write(store);
    }

    /**
     * Civilians, resigned members, and active trades members may petition;
     * sworn members and disciplined records cannot file work petitions.
     */
    private void requireEligibleSubject(String subjectUuid) {
        PersonnelRecord record = personnel.find(subjectUuid);
        if (record == null) return;
        if (record.membershipStatus == PersonnelStatus.SUSPENDED
                || record.membershipStatus == PersonnelStatus.TERMINATED) {
            throw new IllegalStateException("JOIN_DENIED_" + record.membershipStatus);
        }
        if (record.active() && record.careerGrade != null && !record.careerGrade.isProfessional()) {
            throw new IllegalStateException("JOIN_DENIED_SWORN_MEMBER");
        }
    }

    private JoinRequest requireDecideable(String actorUuid, String requestId, long expectedVersion) {
        JoinRequest request = find(requestId);
        if (request == null || request.status != JoinRequestStatus.PENDING)
            throw new IllegalStateException("JOIN_REQUEST_NOT_PENDING");
        if (request.version != expectedVersion)
            throw new IllegalStateException("STALE_STATE");
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "AUTHORIZE_PERSONNEL");
        context.subjectUuid = request.subjectUuid;
        context.beneficiaryUuid = request.subjectUuid;
        context.requiresIndependentApproval = true;
        if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        return request;
    }

    private static JoinRequest requireRequest(JoinRequestStore store, String requestId) {
        JoinRequest request = store.requests == null ? null : store.requests.get(requestId);
        if (request == null) throw new IllegalArgumentException("unknown join request: " + requestId);
        return request;
    }
}
