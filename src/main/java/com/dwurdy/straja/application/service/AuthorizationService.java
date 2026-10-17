package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.MobilizationRepository;
import com.dwurdy.straja.application.port.out.PersonnelRepository;
import com.dwurdy.straja.application.port.out.StationRepository;
import com.dwurdy.straja.domain.model.AuthorizationContext;
import com.dwurdy.straja.domain.model.AuthorizationDecision;
import com.dwurdy.straja.domain.model.AppointmentType;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.CareerTrack;
import com.dwurdy.straja.domain.model.MobilizationOrder;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import com.dwurdy.straja.domain.model.Station;
import com.dwurdy.straja.domain.model.StationStore;

/** Single server-side policy predicate used by V2-sensitive operations. */
public final class AuthorizationService {
    private final PersonnelRepository personnelRepository;
    private final StationRepository stationRepository;
    private final MobilizationRepository mobilizationRepository;
    private final Clock clock;

    public AuthorizationService(PersonnelRepository personnelRepository, Clock clock) {
        this(personnelRepository, null, null, clock);
    }

    public AuthorizationService(PersonnelRepository personnelRepository, StationRepository stationRepository,
                                MobilizationRepository mobilizationRepository, Clock clock) {
        this.personnelRepository = personnelRepository;
        this.stationRepository = stationRepository;
        this.mobilizationRepository = mobilizationRepository;
        this.clock = clock;
    }

    public AuthorizationDecision authorize(AuthorizationContext context) {
        if (context == null || context.actorUuid == null || context.actorUuid.isBlank())
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_NOT_AUTHORIZED);
        PersonnelRecord actor = personnelRepository.read().get(context.actorUuid);
        if (actor == null) return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_NOT_AUTHORIZED);
        if (actor.membershipStatus == com.dwurdy.straja.domain.model.PersonnelStatus.SUSPENDED)
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_SUSPENDED);
        if (actor.membershipStatus == com.dwurdy.straja.domain.model.PersonnelStatus.TERMINATED
                || actor.membershipStatus == com.dwurdy.straja.domain.model.PersonnelStatus.RESIGNED)
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_TERMINATED);
        if (actor.membershipStatus != com.dwurdy.straja.domain.model.PersonnelStatus.AUTHORIZED_ACTIVE)
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_NOT_AUTHORIZED);
        long now = clock.nowMillis();
        if (context.expectedSubjectVersion != null && context.actualSubjectVersion != null
                && !context.expectedSubjectVersion.equals(context.actualSubjectVersion))
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_STALE_STATE);
        if (context.requiresIndependentApproval && context.actorUuid.equals(context.beneficiaryUuid))
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_SELF_APPROVAL);
        if (!context.approvalChain.isEmpty() && context.approvalChain.contains(context.actorUuid))
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_CONFLICT_OF_INTEREST);
        if (context.stationId != null && !context.stationId.isBlank() && stationRepository != null) {
            Station station = resolveStation(context.stationId, context.jurisdiction);
            if (station == null)
                return AuthorizationDecision.deny(stationChainExists(context.stationId)
                        ? AuthorizationDecision.ReasonCode.DENIED_JURISDICTION
                        : AuthorizationDecision.ReasonCode.DENIED_STATION);
        }
        String capability = context.capability == null ? "" : context.capability.toUpperCase();
        if (!capabilityAllowed(actor, capability, context, now))
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_GRADE);
        if ("AUTHORIZE_PERSONNEL".equals(capability) || "APPROVE_PROMOTION".equals(capability)) {
            if (!actor.hasAppointment(AppointmentType.COMMISSIONER, now))
                return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_APPOINTMENT);
        }
        // Part-time steps are routine advancement — any officer may stamp
        // them; full-time/officer-track decisions still need the Comisar.
        if ("APPROVE_PROMOTION_PART_TIME".equals(capability)
                && !actor.hasAppointment(AppointmentType.COMMISSIONER, now)
                && !(actor.careerGrade != null && actor.careerGrade.isOfficer()))
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_APPOINTMENT);
        if ("ROUTINE_PATROL".equals(capability)
                && actor.careerGrade != null
                && (actor.careerGrade.isProfessional()
                    || (actor.careerGrade.isOfficer()
                    && actor.careerOrigin == com.dwurdy.straja.domain.model.CareerTrack.PROFESSIONAL))) {
            if (!hasActiveMobilization(actor.playerUuid, context.stationId, context.jurisdiction, now))
                return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_MOBILIZATION_REQUIRED);
        }
        if (context.subjectUuid != null && !context.subjectUuid.isBlank()
                && context.requiresIndependentApproval && context.actorUuid.equals(context.subjectUuid))
            return AuthorizationDecision.deny(AuthorizationDecision.ReasonCode.DENIED_SELF_APPROVAL);
        return AuthorizationDecision.allow(actor.careerGrade == null ? "APPOINTMENT" : actor.careerGrade.name());
    }

    /**
     * The V2 matrix is deliberately explicit. A new capability must be added
     * here before any V2-backed caller can use it; unknown strings fail closed.
     */
    private boolean capabilityAllowed(PersonnelRecord actor, String capability,
                                      AuthorizationContext context, long now) {
        Capability requested;
        try {
            requested = Capability.valueOf(capability);
        } catch (IllegalArgumentException error) {
            return switch (capability) {
                case "AUTHORIZE_PERSONNEL", "APPROVE_PROMOTION",
                        "APPROVE_PROMOTION_PART_TIME" -> true;
                default -> false;
            };
        }
        CareerGrade grade = actor.careerGrade;
        boolean commissioner = actor.hasAppointment(AppointmentType.COMMISSIONER, now);
        if (commissioner) {
            // Commissioner authority is broad, but still requires an active
            // appointment and never bypasses status/station/conflict checks.
            return true;
        }
        if (grade == null) return false;
        // Every officer grade (LT/CPT/COL) holds the full non-comisar set:
        // specialties are RP flavor, not capability gates.
        return switch (requested) {
            case SELF_SERVICE_FORMS -> true;
            // Professional careers may use the same capability only while
            // an active mobilization scope is present; the predicate below
            // enforces that contextual requirement.
            case ROUTINE_PATROL -> !grade.isProfessional();
            case ISSUE_FINES -> !grade.isProfessional();
            case USE_CUFFS, USE_BATON, EXECUTE_ARRESTS, ASSIST_COMPLAINTS ->
                    grade == CareerGrade.MILITARY_STRAJER || grade.isOfficer()
                            || scopedSpecialist(actor, context, now);
            case INVESTIGATE_COMPLAINTS, MOBILIZE_PLAYERS ->
                    grade.isOfficer() || (requested == Capability.INVESTIGATE_COMPLAINTS
                            && scopedSpecialist(actor, context, now));
            case VERIFY_REPORTS, MOBILIZE_SPECIALISTS, ISSUE_DOCUMENTS -> grade.isOfficer();
            case CREATE_MISSIONS, APPROVE_REWARDS, REVIEW_APPEALS,
                    WAIVE_EQUIPMENT_DEBT, MANAGE_CAMPAIGNS -> grade.isOfficer();
            case PROFESSIONAL_JOBS -> grade.isProfessional() || grade.isOfficer();
            case AUTHORIZE_PERSONNEL, APPROVE_PROMOTION, DELEGATE -> false;
        };
    }

    public boolean allowed(AuthorizationContext context) { return authorize(context).allowed; }

    private boolean hasActiveMobilization(String uuid, String stationId, String jurisdiction, long now) {
        if (mobilizationRepository == null) return false;
        for (MobilizationOrder order : mobilizationRepository.read().orders.values()) {
            if (order != null && uuid.equals(order.specialistUuid) && order.activeAt(now)
                    && (stationId == null || stationId.isBlank() || stationId.equals(order.stationId))
                    && (jurisdiction == null || jurisdiction.isBlank() || jurisdiction.equals(order.jurisdiction))) return true;
        }
        return false;
    }

    private Station resolveStation(String requestedId, String jurisdiction) {
        var stations = stationRepository.read().stations;
        java.util.Set<String> visited = new java.util.HashSet<>();
        String current = requestedId;
        while (current != null && !current.isBlank() && visited.add(current)) {
            Station station = stations.get(current);
            if (station == null) return null;
            if (station.enabled && station.serves(jurisdiction)) return station;
            current = station.fallbackStationId;
        }
        return null;
    }

    private boolean stationChainExists(String requestedId) {
        var stations = stationRepository.read().stations;
        java.util.Set<String> visited = new java.util.HashSet<>();
        String current = requestedId;
        while (current != null && !current.isBlank() && visited.add(current)) {
            Station station = stations.get(current);
            if (station == null) return false;
            if (station.enabled) return true;
            current = station.fallbackStationId;
        }
        return false;
    }

    private boolean scopedSpecialist(PersonnelRecord actor, AuthorizationContext context, long now) {
        return actor.careerGrade != null && actor.careerGrade.isProfessional()
                && hasActiveMobilization(actor.playerUuid, context.stationId, context.jurisdiction, now);
    }
}
