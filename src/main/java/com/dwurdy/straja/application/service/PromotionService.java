package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.PersonnelRepository;
import com.dwurdy.straja.application.port.out.PromotionRepository;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import com.dwurdy.straja.domain.model.PersonnelStatus;
import com.dwurdy.straja.domain.model.PersonnelStore;
import com.dwurdy.straja.domain.model.PromotionApplication;
import com.dwurdy.straja.domain.model.PromotionStatus;
import com.dwurdy.straja.domain.model.PromotionStore;
import com.dwurdy.straja.domain.model.QualificationEvidence;
import com.dwurdy.straja.domain.model.Text;

public final class PromotionService implements com.dwurdy.straja.application.port.in.PromotionV2UseCase {
    private final PromotionRepository promotions;
    private final PersonnelRepository personnel;
    private final AuthorizationService authorization;
    private final Clock clock;
    private final IdGenerator ids;
    private java.util.function.Consumer<PromotionApplication> approvalListener = ignored -> {};

    public PromotionService(PromotionRepository promotions, PersonnelRepository personnel,
                             AuthorizationService authorization, Clock clock, IdGenerator ids) {
        this.promotions = promotions;
        this.personnel = personnel;
        this.authorization = authorization;
        this.clock = clock;
        this.ids = ids;
    }

    private AuditService audit;

    public void onApproved(java.util.function.Consumer<PromotionApplication> listener) {
        this.approvalListener = listener == null ? ignored -> {} : listener;
    }

    /** Installed by the composition root; absent in unit compatibility contexts. */
    public void useAuditService(AuditService audit) {
        this.audit = audit;
    }

    public synchronized PromotionApplication submit(String subjectUuid, CareerGrade targetGrade) {
        PersonnelRecord person = requirePerson(subjectUuid);
        if (!person.active()) throw new IllegalStateException("personnel is not active");
        if (targetGrade == null || !validTransition(person.careerGrade, targetGrade))
            throw new IllegalArgumentException("invalid career transition");
        PromotionStore store = promotions.read();
        if (store.applications == null) store.applications = new java.util.LinkedHashMap<>();
        for (PromotionApplication application : store.applications.values()) {
            if (application != null && subjectUuid.equals(application.subjectUuid)
                    && targetGrade == application.targetGrade && isOpen(application.status)) {
                // Re-pin on resubmission: the member re-asserts the request
                // against their current record, so a routine record change
                // (profession assignment, status cycle) since the first filing
                // must not wedge the application as permanently stale.
                if (application.expectedPersonnelVersion != person.version) {
                    application.expectedPersonnelVersion = person.version;
                    application.version++;
                    store.storeRevision++;
                    promotions.write(store);
                }
                return application;
            }
        }
        PromotionApplication application = new PromotionApplication();
        application.applicationId = ids.newId("PROM");
        application.subjectUuid = subjectUuid;
        application.careerOrigin = person.careerOrigin;
        application.fromGrade = person.careerGrade;
        application.targetGrade = targetGrade;
        application.submittedAt = clock.nowMillis();
        application.expectedPersonnelVersion = person.version;
        store.applications.put(application.applicationId, application);
        store.storeRevision++;
        promotions.write(store);
        return application;
    }

    /**
     * Petition promotions: a member's request lands directly in
     * READY_FOR_APPROVAL — the Comisar reads and stamps it, no evidence or
     * readiness pipeline stands between the request and the decision.
     * Submitting twice returns the still-open petition instead of duplicating.
     */
    public synchronized PromotionApplication submitPetition(String subjectUuid, CareerGrade targetGrade,
                                                             String reason) {
        PromotionApplication application = submit(subjectUuid, targetGrade);
        String normalized = Text.bounded(reason, Text.REASON_LIMIT);
        if (!isOpen(application.status)) return application;
        if (application.status == PromotionStatus.READY_FOR_APPROVAL
                && normalized.equals(application.petitionReason)) {
            return application; // identical open petition — idempotent
        }
        // A still-open request flips to ready (and refreshes its reason) —
        // petitions skip the evidence/readiness pipeline entirely.
        PromotionStore store = promotions.read();
        PromotionApplication persisted = requireApplication(store, application.applicationId);
        persisted.status = PromotionStatus.READY_FOR_APPROVAL;
        persisted.petitionReason = normalized;
        persisted.readinessAt = clock.nowMillis();
        persisted.version++;
        store.storeRevision++;
        promotions.write(store);
        return persisted;
    }

    /**
     * Delivers the subject's latest undelivered petition decision. Called on
     * login and right after a live review so the member learns the outcome —
     * and on denial, the reason — without watching Discord.
     */
    public synchronized void deliverOutcome(
            com.dwurdy.straja.application.port.out.PlayerGateway player) {
        if (player == null) return;
        PromotionStore store = promotions.read();
        if (store.applications == null) return;
        PromotionApplication decided = null;
        for (PromotionApplication application : store.applications.values()) {
            if (application == null || application.outcomeDelivered
                    || !player.uuid().toString().equals(application.subjectUuid)) continue;
            if (application.status == PromotionStatus.APPROVED
                    || application.status == PromotionStatus.REJECTED) {
                if (decided == null || (application.decisionAt != null
                        && (decided.decisionAt == null || application.decisionAt > decided.decisionAt))) {
                    decided = application;
                }
            }
        }
        if (decided == null) return;
        if (decided.status == PromotionStatus.APPROVED) {
            player.tell("[Straja] Cererea " + decided.applicationId + " a fost aprobată — noul tău grad: "
                    + display(decided.targetGrade) + ".");
        } else {
            player.tell("[Straja] Cererea " + decided.applicationId + " a fost respinsă"
                    + (decided.decisionReason.isBlank() ? "." : ": " + decided.decisionReason));
        }
        decided.outcomeDelivered = true;
        store.storeRevision++;
        promotions.write(store);
    }

    private static String display(CareerGrade grade) {
        String name = grade == null ? "?" : grade.name();
        int underscore = name.indexOf('_');
        return underscore < 0 ? name : name.substring(underscore + 1);
    }

    /** Open petitions awaiting the Comisar's decision, oldest first. */
    public synchronized java.util.List<PromotionApplication> pendingPetitions() {
        PromotionStore store = promotions.read();
        if (store.applications == null) return java.util.List.of();
        return store.applications.values().stream()
                .filter(application -> application != null
                        && application.status == PromotionStatus.READY_FOR_APPROVAL)
                .sorted(java.util.Comparator.comparingLong(a -> a.submittedAt))
                .toList();
    }

    /** Legacy caller-asserted evidence is deliberately rejected. */
    public synchronized QualificationEvidence addEvidence(String applicationId, String actorUuid, String kind,
                                                           String source, String result, Double score,
                                                           String payloadFingerprint) {
        throw new IllegalStateException("TRUSTED_ASSESSMENT_REQUIRED");
    }

    /** Records a result produced by the server-owned training ledger. */
    public synchronized QualificationEvidence recordServerTrainingEvidence(
            String applicationId, String actorUuid, String kind, boolean passed,
            Double score, String assessmentFingerprint) {
        if (assessmentFingerprint == null || assessmentFingerprint.isBlank())
            throw new IllegalArgumentException("trusted assessment fingerprint required");
        return addEvidenceInternal(applicationId, actorUuid, kind, "SERVER_TRAINING_LEDGER",
                passed ? "PASS" : "FAIL", score, assessmentFingerprint);
    }

    /** Records a commissioner decision after checking the V2 authority gate. */
    public synchronized QualificationEvidence recordCommissionerEvidence(
            String applicationId, String actorUuid, String kind, String result,
            Double score, String assessmentFingerprint) {
        PromotionStore store = promotions.read();
        PromotionApplication application = requireApplication(store, applicationId);
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "APPROVE_PROMOTION");
        context.subjectUuid = application.subjectUuid;
        context.beneficiaryUuid = application.subjectUuid;
        context.requiresIndependentApproval = true;
        if (authorization == null || !authorization.allowed(context))
            throw new IllegalStateException("DENIED_ASSESSMENT_AUTHORITY");
        if (assessmentFingerprint == null || assessmentFingerprint.isBlank())
            throw new IllegalArgumentException("trusted assessment fingerprint required");
        String normalized = Text.normalize(result);
        if (!"PASS".equals(normalized) && !"FAIL".equals(normalized))
            throw new IllegalArgumentException("assessment result must be PASS or FAIL");
        return addEvidenceInternal(applicationId, actorUuid, kind, "COMMISSIONER_ASSESSMENT",
                normalized, score, assessmentFingerprint);
    }

    private QualificationEvidence addEvidenceInternal(String applicationId, String actorUuid, String kind,
                                                       String source, String result, Double score,
                                                       String payloadFingerprint) {
        PromotionStore store = promotions.read();
        PromotionApplication application = requireApplication(store, applicationId);
        if (!isOpen(application.status)) throw new IllegalStateException("application is closed");
        String fingerprint = payloadFingerprint == null ? "" : payloadFingerprint.trim();
        if (fingerprint.isBlank()) throw new IllegalArgumentException("assessment fingerprint required");
        for (QualificationEvidence existing : store.evidence.values()) {
            if (existing != null && applicationId.equals(existing.applicationId)
                    && fingerprint.equals(existing.payloadFingerprint) && !fingerprint.isBlank()) return existing;
        }
        QualificationEvidence evidence = new QualificationEvidence();
        evidence.evidenceId = ids.newId("EVD");
        evidence.applicationId = applicationId;
        evidence.kind = kind == null ? "" : kind;
        evidence.subjectUuid = application.subjectUuid;
        evidence.createdBy = actorUuid == null ? "" : actorUuid;
        evidence.source = source == null ? "" : source;
        evidence.result = result == null ? "" : result;
        evidence.score = score;
        evidence.payloadFingerprint = fingerprint;
        evidence.createdAt = clock.nowMillis();
        store.evidence.put(evidence.evidenceId, evidence);
        application.evidenceIds.add(evidence.evidenceId);
        if (application.status == PromotionStatus.SUBMITTED) application.status = PromotionStatus.EVIDENCE_IN_PROGRESS;
        application.version++;
        store.storeRevision++;
        promotions.write(store);
        return evidence;
    }

    public synchronized PromotionApplication markReady(String applicationId) {
        PromotionStore store = promotions.read();
        PromotionApplication application = requireApplication(store, applicationId);
        if (application.evidenceIds.isEmpty()) throw new IllegalStateException("qualification evidence required");
        if (!isOpen(application.status)) throw new IllegalStateException("application is closed");
        boolean hasPassingEvidence = application.evidenceIds.stream()
                .map(store.evidence::get)
                .anyMatch(evidence -> evidence != null && "PASS".equalsIgnoreCase(evidence.result));
        if (!hasPassingEvidence) throw new IllegalStateException("passing qualification evidence required");
        application.status = PromotionStatus.READY_FOR_APPROVAL;
        application.readinessAt = clock.nowMillis();
        application.version++;
        store.storeRevision++;
        promotions.write(store);
        return application;
    }

    public synchronized PromotionApplication approve(String actorUuid, String applicationId,
                                                       long expectedApplicationVersion) {
        PromotionStore store = promotions.read();
        PromotionApplication application = requireApplication(store, applicationId);
        if (application.status == PromotionStatus.APPROVED) {
            reconcileApproval(application, store);
            return requireApplication(promotions.read(), applicationId);
        }
        if (application.version != expectedApplicationVersion)
            throw new IllegalStateException("STALE_STATE");
        if (application.status != PromotionStatus.READY_FOR_APPROVAL)
            throw new IllegalStateException("application is not ready");
        PersonnelStore people = personnel.read();
        PersonnelRecord person = people.records.get(application.subjectUuid);
        if (person == null || person.version != application.expectedPersonnelVersion)
            throw new IllegalStateException("STALE_STATE");
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "APPROVE_PROMOTION");
        context.subjectUuid = application.subjectUuid;
        context.beneficiaryUuid = application.subjectUuid;
        context.requiresIndependentApproval = true;
        if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_APPROVAL");
        if (application.targetGrade.fullTimeRequired()
                && person.hasIncompatibleAffiliation(clock.nowMillis())) {
            // Approving a full-time petition confers the full-time posting;
            // an incompatible active affiliation is the only blocker.
            throw new IllegalStateException("DENIED_AFFILIATION_CONFLICT");
        }
        application.status = PromotionStatus.APPROVED;
        application.reviewedBy = actorUuid;
        application.decisionAt = clock.nowMillis();
        application.personnelSyncState = "PENDING";
        application.personnelAppliedAt = null;
        application.version++;
        if (application.outboundIntents == null) application.outboundIntents = new java.util.ArrayList<>();
        application.outboundIntents.add(com.dwurdy.straja.domain.model.OutboxIntent.of(
                "PROMOTION_COMPLETED", "promotion:" + application.applicationId + ":v" + application.version,
                application.applicationId, application.subjectUuid, clock.nowMillis()));
        store.storeRevision++;
        // The promotion decision is the recovery intent. If the process dies
        // after this write, reconcileApprovals() can finish the personnel
        // projection on the next server start.
        promotions.write(store);
        if (audit != null) {
            audit.record("promotion_petition_review", actorUuid, actorUuid,
                    application.applicationId, application.subjectUuid, "APPROVED",
                    "from=" + application.fromGrade + " to=" + application.targetGrade);
        }
        reconcileApproval(application, store);
        PromotionApplication persisted = requireApplication(promotions.read(), applicationId);
        approvalListener.accept(persisted);
        return persisted;
    }

    /**
     * Completes approved applications left between the promotion and
     * personnel repository writes. Safe to call on every server start.
     */
    public synchronized int reconcileApprovals() {
        PromotionStore store = promotions.read();
        int repaired = 0;
        if (store.applications == null) return 0;
        for (PromotionApplication application : store.applications.values()) {
            if (application == null || application.status != PromotionStatus.APPROVED
                    || "COMPLETE".equals(application.personnelSyncState)) continue;
            try {
                reconcileApproval(application, store);
                approvalListener.accept(application);
                repaired++;
            } catch (IllegalStateException conflict) {
                // Keep the server alive and leave a durable CONFLICT marker
                // for an administrator instead of silently changing a newer
                // personnel record.
            }
        }
        return repaired;
    }

    private void reconcileApproval(PromotionApplication application, PromotionStore promotionStore) {
        PersonnelStore people = personnel.read();
        if (people.records == null) people.records = new java.util.LinkedHashMap<>();
        PersonnelRecord person = people.records.get(application.subjectUuid);
        if (person == null) {
            application.personnelSyncState = "CONFLICT";
            promotionStore.storeRevision++;
            promotions.write(promotionStore);
            throw new IllegalStateException("PROMOTION_PERSONNEL_MISSING");
        }
        boolean alreadyApplied = person.careerGrade == application.targetGrade
                && person.careerTrack == application.targetGrade.track()
                && person.membershipStatus == PersonnelStatus.AUTHORIZED_ACTIVE;
        if (!alreadyApplied) {
            if (person.version != application.expectedPersonnelVersion) {
                application.personnelSyncState = "CONFLICT";
                promotionStore.storeRevision++;
                promotions.write(promotionStore);
                throw new IllegalStateException("PROMOTION_PERSONNEL_STALE");
            }
            person.careerGrade = application.targetGrade;
            person.careerTrack = application.targetGrade.track();
            person.careerOrigin = application.careerOrigin;
            // Any full-time petition (trades or officer corps) upgrades the
            // member to full-time employment on approval.
            if (application.targetGrade.fullTimeRequired())
                person.employmentMode = EmploymentMode.FULL_TIME;
            person.membershipStatus = PersonnelStatus.AUTHORIZED_ACTIVE;
            person.version++;
            person.updatedAt = clock.nowMillis();
            people.storeRevision++;
            personnel.write(people);
        }
        application.personnelSyncState = "COMPLETE";
        application.personnelAppliedAt = clock.nowMillis();
        promotionStore.storeRevision++;
        promotions.write(promotionStore);
    }

    public synchronized PromotionApplication findOpen(String subjectUuid, CareerGrade targetGrade) {
        PromotionStore store = promotions.read();
        if (store.applications == null) return null;
        for (PromotionApplication application : store.applications.values()) {
            if (application != null && subjectUuid.equals(application.subjectUuid)
                    && targetGrade == application.targetGrade && isOpen(application.status)) return application;
        }
        return null;
    }

    public synchronized java.util.List<PromotionApplication> all() {
        PromotionStore store = promotions.read();
        return store.applications == null ? java.util.List.of() : java.util.List.copyOf(store.applications.values());
    }

    public synchronized PromotionApplication find(String applicationId) {
        PromotionStore store = promotions.read();
        return store.applications == null ? null : store.applications.get(applicationId);
    }

    /** Compatibility command helper: it still requires a real application and current version. */
    public synchronized PromotionApplication approveOpen(String actorUuid, String subjectUuid, CareerGrade targetGrade) {
        PromotionApplication application = findOpen(subjectUuid, targetGrade);
        if (application == null) throw new IllegalStateException("NO_OPEN_PROMOTION_APPLICATION");
        return approve(actorUuid, application.applicationId, application.version);
    }

    public synchronized PromotionApplication reject(String actorUuid, String applicationId, String reason) {
        PromotionStore store = promotions.read();
        PromotionApplication application = requireApplication(store, applicationId);
        // A decided application must not be silently flipped back — the
        // personnel projection may already have applied an approval.
        if (!isOpen(application.status)) throw new IllegalStateException("application is closed");
        if (authorization != null) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "APPROVE_PROMOTION");
            context.subjectUuid = application.subjectUuid;
            context.beneficiaryUuid = application.subjectUuid;
            context.requiresIndependentApproval = true;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_APPROVAL");
        }
        application.status = PromotionStatus.REJECTED;
        application.reviewedBy = actorUuid == null ? "" : actorUuid;
        application.decisionReason = Text.bounded(reason, Text.REASON_LIMIT);
        application.decisionAt = clock.nowMillis();
        application.version++;
        store.storeRevision++;
        promotions.write(store);
        if (audit != null) {
            audit.record("promotion_petition_review", actorUuid == null ? "" : actorUuid, actorUuid,
                    application.applicationId, application.subjectUuid, "REJECTED",
                    application.decisionReason);
        }
        return application;
    }

    public synchronized PromotionApplication withdraw(String actorUuid, String applicationId) {
        PromotionStore store = promotions.read();
        PromotionApplication application = requireApplication(store, applicationId);
        if (actorUuid == null || !actorUuid.equals(application.subjectUuid))
            throw new IllegalStateException("DENIED_SUBJECT");
        if (!isOpen(application.status)) return application;
        application.status = PromotionStatus.WITHDRAWN;
        application.decisionReason = "WITHDRAWN_BY_SUBJECT";
        application.decisionAt = clock.nowMillis();
        application.version++;
        store.storeRevision++;
        promotions.write(store);
        return application;
    }

    private PersonnelRecord requirePerson(String uuid) {
        PersonnelStore store = personnel.read();
        PersonnelRecord person = store.records == null ? null : store.records.get(uuid);
        if (person == null) throw new IllegalArgumentException("unknown personnel: " + uuid);
        return person;
    }
    private static PromotionApplication requireApplication(PromotionStore store, String id) {
        PromotionApplication application = store.applications == null ? null : store.applications.get(id);
        if (application == null) throw new IllegalArgumentException("unknown promotion: " + id);
        return application;
    }
    private static boolean isOpen(PromotionStatus status) {
        return status == PromotionStatus.SUBMITTED || status == PromotionStatus.EVIDENCE_IN_PROGRESS
                || status == PromotionStatus.READY_FOR_APPROVAL;
    }
    /** Two part-time ladders feed the shared officer corps; officers climb
     *  LT → CPT → COL. No skipped rungs, no cross-track enlisted hops. */
    private static boolean validTransition(CareerGrade from, CareerGrade target) {
        return from != null && target == from.nextGrade();
    }
}
