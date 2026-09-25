package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.MissionRepository;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.Mission;
import com.dwurdy.straja.domain.model.MissionEvidence;
import com.dwurdy.straja.domain.model.MissionStatus;
import com.dwurdy.straja.domain.model.MissionStore;
import com.dwurdy.straja.domain.model.Settlement;

/** Typed V2 mission lifecycle layered beside the V1 mission compatibility model. */
public final class MissionV2Service {
    private final MissionRepository repository;
    private final AuthorizationService authorization;
    private final SettlementService settlements;
    private final Clock clock;
    private final IdGenerator ids;
    private CampaignService campaigns;
    private java.util.function.Consumer<Mission> publicationListener = ignored -> {};

    public MissionV2Service(MissionRepository repository, AuthorizationService authorization,
                            SettlementService settlements, Clock clock, IdGenerator ids) {
        this.repository = repository; this.authorization = authorization; this.settlements = settlements;
        this.clock = clock; this.ids = ids;
    }

    public void useCampaignService(CampaignService campaigns) {
        this.campaigns = campaigns;
    }

    public void onPublished(java.util.function.Consumer<Mission> listener) {
        this.publicationListener = listener == null ? ignored -> {} : listener;
    }

    public synchronized Mission publish(String creatorUuid, String beneficiaryUuid, String stationId,
                                        String jurisdiction, String missionType, long deadline, int maxAssignees) {
        AuthorizationContext context = AuthorizationContext.of(creatorUuid, "CREATE_MISSIONS");
        context.stationId = stationId; context.jurisdiction = jurisdiction;
        if (authorization != null && !authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (deadline <= clock.nowMillis() || maxAssignees <= 0) throw new IllegalArgumentException("invalid mission");
        MissionStore store = repository.read();
        Mission mission = new Mission(); mission.id = store.nextMissionId(); mission.creatorUuid = creatorUuid;
        mission.issuerUuid = creatorUuid; mission.beneficiaryUuid = beneficiaryUuid == null ? "" : beneficiaryUuid;
        mission.stationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        mission.jurisdiction = jurisdiction == null ? "" : jurisdiction; mission.missionType = missionType == null ? "" : missionType;
        mission.dueAt = deadline; mission.originalDeadline = deadline; mission.maxAssignees = maxAssignees;
        mission.auditCorrelationId = ids.token(); mission.v2Status = MissionStatus.AVAILABLE; mission.status = "ISSUED";
        mission.outboundIntents.add(com.dwurdy.straja.domain.model.OutboxIntent.of(
                "IMPORTANT_MISSION_CREATED", "mission:" + mission.id,
                mission.id, mission.beneficiaryUuid, clock.nowMillis()));
        store.missions.add(mission); repository.write(store); publicationListener.accept(mission); return mission;
    }

    /** Trusted generator path: still validates the worker's professional authority. */
    public synchronized Mission publishGenerated(String generatorId, String beneficiaryUuid, String stationId,
                                                 String jurisdiction, String objective, String profession,
                                                 long deadline, int maxAssignees, String offerKey) {
        if (authorization != null) {
            AuthorizationContext context = AuthorizationContext.of(beneficiaryUuid, "PROFESSIONAL_JOBS");
            context.subjectUuid = beneficiaryUuid; context.beneficiaryUuid = beneficiaryUuid;
            context.stationId = stationId; context.jurisdiction = jurisdiction;
            if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_PROFESSIONAL_AUTHORITY");
        }
        MissionStore store = repository.read();
        String requestFingerprint = RequestFingerprint.of(generatorId, beneficiaryUuid, stationId,
                jurisdiction, objective, profession, deadline, maxAssignees, offerKey);
        if (offerKey != null && !offerKey.isBlank()) {
            for (Mission existing : store.missions) {
                if (existing != null && offerKey.equals(existing.auditCorrelationId)) {
                    if (!requestFingerprint.equals(existing.requestFingerprint))
                        throw new IllegalStateException("IDEMPOTENCY_PAYLOAD_MISMATCH");
                    return existing;
                }
            }
        }
        if (deadline <= clock.nowMillis() || maxAssignees <= 0) throw new IllegalArgumentException("invalid generated mission");
        Mission mission = new Mission();
        mission.id = store.nextMissionId(); mission.creatorUuid = generatorId; mission.issuerUuid = generatorId;
        mission.beneficiaryUuid = beneficiaryUuid == null ? "" : beneficiaryUuid;
        mission.stationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        mission.jurisdiction = jurisdiction == null ? "" : jurisdiction;
        mission.missionType = "PROFESSIONAL_WORK"; mission.profession = profession == null ? "" : profession;
        mission.objective = objective == null ? "" : objective; mission.dueAt = deadline;
        mission.originalDeadline = deadline; mission.maxAssignees = maxAssignees;
        mission.auditCorrelationId = offerKey == null || offerKey.isBlank() ? ids.token() : offerKey;
        mission.requestFingerprint = requestFingerprint;
        mission.origin = "GENERATOR:" + mission.profession;
        mission.v2Status = MissionStatus.AVAILABLE; mission.status = "ISSUED";
        mission.outboundIntents.add(com.dwurdy.straja.domain.model.OutboxIntent.of(
                "IMPORTANT_MISSION_CREATED", "mission:" + mission.id,
                mission.id, mission.beneficiaryUuid, clock.nowMillis()));
        store.missions.add(mission); repository.write(store); publicationListener.accept(mission); return mission;
    }

    public synchronized Mission claim(String actorUuid, String missionId) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        requireParticipant(actorUuid, mission);
        MissionStatus status = normalized(mission);
        if (status == MissionStatus.CLAIMED || status == MissionStatus.IN_PROGRESS || status == MissionStatus.SUBMITTED) {
            if (actorUuid.equals(mission.actorUuid)) return mission;
            throw new IllegalStateException("MISSION_ALREADY_CLAIMED");
        }
        if (status != MissionStatus.AVAILABLE) throw new IllegalStateException("MISSION_NOT_AVAILABLE");
        if (mission.dueAt <= clock.nowMillis()) { mission.v2Status = MissionStatus.EXPIRED; repository.write(store); throw new IllegalStateException("MISSION_EXPIRED"); }
        mission.actorUuid = actorUuid; mission.claimReceiptId = ids.newId("CLAIM"); mission.assignmentRevision++;
        recordAssignment(mission, actorUuid, actorUuid, mission.claimReceiptId, "CLAIMED");
        mission.acceptedAt = clock.nowMillis(); mission.v2Status = MissionStatus.CLAIMED; mission.status = "ACCEPTED";
        repository.write(store); return mission;
    }

    public synchronized Mission begin(String actorUuid, String missionId) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        requireParticipant(actorUuid, mission);
        if (!actorUuid.equals(mission.actorUuid)) throw new IllegalStateException("DENIED_SUBJECT");
        if (normalized(mission) == MissionStatus.IN_PROGRESS) return mission;
        MissionStatus status = normalized(mission);
        if (status != MissionStatus.CLAIMED && status != MissionStatus.REPORT_REJECTED)
            throw new IllegalStateException("INVALID_STATE");
        if (clock.nowMillis() > effectiveDeadline(mission)) {
            mission.v2Status = MissionStatus.OVERDUE;
            repository.write(store);
            throw new IllegalStateException("MISSION_OVERDUE");
        }
        mission.v2Status = MissionStatus.IN_PROGRESS; mission.status = "ACCEPTED"; repository.write(store); return mission;
    }

    public synchronized Mission submit(String actorUuid, String missionId, String evidenceReference) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        requireParticipant(actorUuid, mission);
        if (!actorUuid.equals(mission.actorUuid)) throw new IllegalStateException("DENIED_SUBJECT");
        if (normalized(mission) != MissionStatus.IN_PROGRESS) throw new IllegalStateException("INVALID_STATE");
        if (evidenceReference == null || evidenceReference.isBlank()) throw new IllegalArgumentException("evidence required");
        if (clock.nowMillis() > effectiveDeadline(mission)) {
            mission.v2Status = MissionStatus.OVERDUE;
            repository.write(store);
            throw new IllegalStateException("MISSION_OVERDUE");
        }
        if (!mission.evidenceRefs.contains(evidenceReference)) mission.evidenceRefs.add(evidenceReference);
        mission.v2Status = MissionStatus.SUBMITTED; mission.status = "REPORTED";
        mission.reportedAt = clock.nowMillis(); repository.write(store); return mission;
    }

    public synchronized MissionEvidence addEvidence(String actorUuid, String missionId, String type,
                                                     String reference) {
        if (type == null || type.isBlank() || reference == null || reference.isBlank())
            throw new IllegalArgumentException("evidence type and reference required");
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        requireParticipant(actorUuid, mission);
        if (!actorUuid.equals(mission.actorUuid) && !canVerify(actorUuid, mission))
            throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (mission.evidence == null) mission.evidence = new java.util.ArrayList<>();
        for (MissionEvidence existing : mission.evidence) {
            if (existing != null && actorUuid.equals(existing.submittedBy)
                    && type.equals(existing.type) && reference.equals(existing.reference)) return existing;
        }
        MissionEvidence evidence = new MissionEvidence(); evidence.evidenceId = ids.newId("EVD");
        evidence.missionId = missionId; evidence.submittedBy = actorUuid;
        evidence.type = type; evidence.reference = reference; evidence.submittedAt = clock.nowMillis();
        evidence.version = 1; mission.evidence.add(evidence);
        if (!mission.evidenceRefs.contains(reference)) mission.evidenceRefs.add(reference);
        repository.write(store); return evidence;
    }

    public synchronized Mission verify(String verifierUuid, String missionId) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        if (normalized(mission) == MissionStatus.VERIFIED) return mission;
        if (normalized(mission) != MissionStatus.SUBMITTED) throw new IllegalStateException("INVALID_STATE");
        if (!canVerify(verifierUuid, mission)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        mission.v2Status = MissionStatus.VERIFIED; mission.signedBy = verifierUuid; mission.signedAt = clock.nowMillis();
        repository.write(store); return mission;
    }

    public synchronized Mission complete(String actorUuid, String missionId, long reward) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        requireParticipant(actorUuid, mission);
        if (normalized(mission) == MissionStatus.COMPLETED) return mission;
        if (normalized(mission) != MissionStatus.VERIFIED) throw new IllegalStateException("INVALID_STATE");
        if (!actorUuid.equals(mission.actorUuid) && !canVerify(actorUuid, mission))
            throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (campaigns != null && mission.quotaReservationId != null && !mission.quotaReservationId.isBlank()) {
            campaigns.fulfill(mission.quotaReservationId, 1, "MISSION_COMPLETE:" + mission.id);
        }
        mission.v2Status = MissionStatus.COMPLETED; mission.status = "COMPLETED"; mission.completedAt = clock.nowMillis();
        if (settlements != null && reward > 0) {
            Settlement.SettlementCategory category = mission.profession == null || mission.profession.isBlank()
                    ? Settlement.SettlementCategory.MISSION_REWARD : Settlement.SettlementCategory.JOB;
            Settlement settlement = settlements.create(mission.actorUuid, category,
                    reward, "MISSION:" + mission.id + ":" + mission.actorUuid, mission.id);
            mission.paymentSettlementIds.add(settlement.settlementId);
        }
        repository.write(store); return mission;
    }

    /** Binds a previously reserved campaign quota to its mission exactly once. */
    public synchronized Mission attachQuotaReservation(String actorUuid, String missionId,
                                                        String reservationId) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        if (!canVerify(actorUuid, mission) && !actorUuid.equals(mission.creatorUuid))
            throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (reservationId == null || reservationId.isBlank() || campaigns == null)
            throw new IllegalArgumentException("campaign reservation required");
        var reservation = campaigns.findReservation(reservationId);
        if (reservation == null || !mission.id.equals(reservation.missionId))
            throw new IllegalArgumentException("reservation mission mismatch");
        if (mission.quotaReservationId != null && !mission.quotaReservationId.isBlank()
                && !mission.quotaReservationId.equals(reservationId))
            throw new IllegalStateException("mission already has a quota reservation");
        mission.quotaReservationId = reservationId;
        mission.campaignId = reservation.campaignId;
        repository.write(store);
        return mission;
    }

    /** Reassigns a mission without rewriting its original assignment history. */
    public synchronized Mission reassign(String actorUuid, String missionId, String replacementUuid, String reason) {
        if (replacementUuid == null || replacementUuid.isBlank()) throw new IllegalArgumentException("replacement required");
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        if (!canVerify(actorUuid, mission)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (replacementUuid.equals(mission.actorUuid)) throw new IllegalArgumentException("replacement already assigned");
        requireParticipant(replacementUuid, mission);
        MissionStatus status = normalized(mission);
        if (status != MissionStatus.CLAIMED && status != MissionStatus.IN_PROGRESS
                && status != MissionStatus.SUBMITTED && status != MissionStatus.OVERDUE
                && status != MissionStatus.REPORT_REJECTED)
            throw new IllegalStateException("INVALID_STATE");
        String prior = mission.actorUuid;
        mission.actorUuid = replacementUuid;
        mission.claimReceiptId = ids.newId("CLAIM");
        mission.assignmentRevision++;
        mission.acceptedAt = clock.nowMillis();
        mission.v2Status = MissionStatus.CLAIMED;
        mission.status = "ACCEPTED";
        recordAssignment(mission, replacementUuid, actorUuid, mission.claimReceiptId,
                reason == null ? "REASSIGNED_FROM:" + prior : reason);
        repository.write(store); return mission;
    }

    public synchronized Mission cancel(String actorUuid, String missionId, String reason) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        if (!canVerify(actorUuid, mission) && !actorUuid.equals(mission.creatorUuid))
            throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (normalized(mission) == MissionStatus.COMPLETED || normalized(mission) == MissionStatus.CANCELLED)
            return mission;
        if (campaigns != null && mission.quotaReservationId != null && !mission.quotaReservationId.isBlank())
            campaigns.release(mission.quotaReservationId);
        mission.v2Status = MissionStatus.CANCELLED; mission.status = "CANCELLED_ROLE_CHANGE";
        mission.cancellationReason = reason == null ? "" : reason;
        mission.cancelledAt = clock.nowMillis(); repository.write(store); return mission;
    }

    public synchronized Mission rejectReport(String verifierUuid, String missionId, String reason) {
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        if (normalized(mission) != MissionStatus.SUBMITTED) throw new IllegalStateException("INVALID_STATE");
        if (!canVerify(verifierUuid, mission)) throw new IllegalStateException("DENIED_AUTHORIZATION");
        mission.v2Status = MissionStatus.REPORT_REJECTED; mission.failureReason = reason == null ? "" : reason;
        repository.write(store); return mission;
    }

    public synchronized Mission extendDeadline(String actorUuid, String missionId, long extensionMillis) {
        if (extensionMillis <= 0) throw new IllegalArgumentException("extension must be positive");
        MissionStore store = repository.read(); Mission mission = require(store, missionId);
        requireParticipant(actorUuid, mission);
        if (!actorUuid.equals(mission.creatorUuid) && !canVerify(actorUuid, mission))
            throw new IllegalStateException("DENIED_AUTHORIZATION");
        if (mission.deadlineExtensions == null) mission.deadlineExtensions = new java.util.ArrayList<>();
        long next = effectiveDeadline(mission) + extensionMillis;
        mission.deadlineExtensions.add(next);
        mission.dueAt = next;
        if (normalized(mission) == MissionStatus.OVERDUE) mission.v2Status = MissionStatus.IN_PROGRESS;
        repository.write(store); return mission;
    }

    public synchronized int expireDue() {
        MissionStore store = repository.read(); int changed = 0; long now = clock.nowMillis();
        for (Mission mission : store.missions) {
            if (mission == null || mission.v2Status == null || mission.v2Status == MissionStatus.COMPLETED) continue;
            if ((mission.v2Status == MissionStatus.AVAILABLE || mission.v2Status == MissionStatus.CLAIMED
                    || mission.v2Status == MissionStatus.IN_PROGRESS || mission.v2Status == MissionStatus.SUBMITTED)
                    && effectiveDeadline(mission) < now) { mission.v2Status = MissionStatus.OVERDUE; changed++; }
        }
        if (changed > 0) repository.write(store); return changed;
    }

    private boolean canVerify(String verifierUuid, Mission mission) {
        if (authorization == null) return false;
        AuthorizationContext context = AuthorizationContext.of(verifierUuid, "VERIFY_REPORTS");
        context.subjectUuid = mission.actorUuid; context.beneficiaryUuid = mission.actorUuid;
        context.requiresIndependentApproval = true; context.stationId = mission.stationId;
        context.jurisdiction = mission.jurisdiction;
        return authorization.allowed(context);
    }

    private void requireParticipant(String actorUuid, Mission mission) {
        if (authorization == null) return;
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "SELF_SERVICE_FORMS");
        context.subjectUuid = actorUuid;
        context.beneficiaryUuid = actorUuid;
        context.stationId = mission.stationId;
        context.jurisdiction = mission.jurisdiction;
        if (!authorization.allowed(context)) throw new IllegalStateException("DENIED_AUTHORIZATION");
    }

    private static long effectiveDeadline(Mission mission) {
        if (mission.deadlineExtensions != null && !mission.deadlineExtensions.isEmpty())
            return mission.deadlineExtensions.get(mission.deadlineExtensions.size() - 1);
        return mission.originalDeadline == null ? mission.dueAt : mission.originalDeadline;
    }

    private static MissionStatus normalized(Mission mission) {
        if (mission.v2Status != null) return mission.v2Status;
        return switch (mission.status) {
            case "ISSUED" -> MissionStatus.AVAILABLE;
            case "ACCEPTED" -> MissionStatus.IN_PROGRESS;
            case "REPORTED" -> MissionStatus.SUBMITTED;
            case "COMPLETED" -> MissionStatus.COMPLETED;
            case "DECLINED", "FAILED" -> MissionStatus.REJECTED;
            case "EXPIRED" -> MissionStatus.EXPIRED;
            case "CANCELLED_ROLE_CHANGE" -> MissionStatus.CANCELLED;
            default -> MissionStatus.LEGACY_FAILED;
        };
    }
    private static Mission require(MissionStore store, String id) { Mission mission = store.find(id); if (mission == null) throw new IllegalArgumentException("unknown mission: " + id); return mission; }

    private void recordAssignment(Mission mission, String actorUuid, String assignedBy,
                                  String receiptId, String reason) {
        if (mission.assignmentHistory == null) mission.assignmentHistory = new java.util.ArrayList<>();
        Mission.Assignment assignment = new Mission.Assignment();
        assignment.actorUuid = actorUuid == null ? "" : actorUuid;
        assignment.assignedBy = assignedBy == null ? "" : assignedBy;
        assignment.receiptId = receiptId == null ? "" : receiptId;
        assignment.assignedAt = clock.nowMillis();
        assignment.reason = reason == null ? "" : reason;
        mission.assignmentHistory.add(assignment);
    }
}
