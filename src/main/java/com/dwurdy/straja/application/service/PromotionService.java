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

    public void onApproved(java.util.function.Consumer<PromotionApplication> listener) {
        this.approvalListener = listener == null ? ignored -> {} : listener;
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
                    && targetGrade == application.targetGrade && isOpen(application.status)) return application;
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
        String normalized = result == null ? "" : result.trim().toUpperCase(java.util.Locale.ROOT);
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
        if (application.targetGrade.fullTimeRequired()) {
            if (person.employmentMode != EmploymentMode.FULL_TIME || person.hasIncompatibleAffiliation(clock.nowMillis()))
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
        if (authorization != null) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "APPROVE_PROMOTION");
            context.subjectUuid = application.subjectUuid;
            context.beneficiaryUuid = application.subjectUuid;
            context.requiresIndependentApproval = true;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_APPROVAL");
        }
        application.status = PromotionStatus.REJECTED;
        application.reviewedBy = actorUuid == null ? "" : actorUuid;
        application.decisionReason = reason == null ? "" : reason;
        application.decisionAt = clock.nowMillis();
        application.version++;
        store.storeRevision++;
        promotions.write(store);
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
    private static boolean validTransition(CareerGrade from, CareerGrade target) {
        return switch (from) {
            case MILITARY_STAGIAR -> target == CareerGrade.MILITARY_STRAJER;
            case MILITARY_STRAJER -> target == CareerGrade.MILITARY_SERGENT;
            case MILITARY_SERGENT -> target == CareerGrade.INSPECTOR;
            case PROFESSIONAL_STAGIAR_SPECIALIST -> target == CareerGrade.PROFESSIONAL_SPECIALIST;
            case PROFESSIONAL_SPECIALIST -> target == CareerGrade.INSPECTOR;
            default -> false;
        };
    }
}
