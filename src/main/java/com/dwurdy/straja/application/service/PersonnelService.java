package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.PersonnelRepository;
import com.dwurdy.straja.domain.model.AffiliationRecord;
import com.dwurdy.straja.domain.model.AppointmentRecord;
import com.dwurdy.straja.domain.model.AppointmentType;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.CareerTrack;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.PersonnelRecord;
import com.dwurdy.straja.domain.model.PersonnelStatus;
import com.dwurdy.straja.domain.model.PersonnelStore;
import java.util.UUID;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.AuthorizationContext;

/** Personnel mutations. No physical item, scoreboard, or NPC is authoritative here. */
public final class PersonnelService implements com.dwurdy.straja.application.port.in.PersonnelV2UseCase {
    private final PersonnelRepository repository;
    private final Clock clock;
    private final IdGenerator ids;
    private AuthorizationService authorization;
    private DocumentService documents;
    private StationService stations;
    private java.util.function.Consumer<PersonnelRecord> authorizationListener = ignored -> {};

    public PersonnelService(PersonnelRepository repository, Clock clock, IdGenerator ids) {
        this.repository = repository;
        this.clock = clock;
        this.ids = ids;
    }

    /** Installs the runtime policy after the repositories have been composed. */
    public void useAuthorization(AuthorizationService authorization) {
        this.authorization = authorization;
    }

    public void useDocumentService(DocumentService documents) {
        this.documents = documents;
    }

    public void useStationService(StationService stations) {
        this.stations = stations;
    }

    public void onAuthorized(java.util.function.Consumer<PersonnelRecord> listener) {
        this.authorizationListener = listener == null ? ignored -> {} : listener;
    }

    public synchronized PersonnelRecord find(String playerUuid) {
        PersonnelStore store = repository.read();
        return store.records == null ? null : store.records.get(playerUuid);
    }

    public synchronized boolean findByGrade(CareerGrade grade, String jurisdiction, long now) {
        PersonnelStore store = repository.read();
        if (store.records == null) return false;
        for (PersonnelRecord record : store.records.values()) {
            if (record != null && record.active() && record.careerGrade == grade
                    && servesJurisdiction(record, jurisdiction)) return true;
        }
        return false;
    }

    public synchronized String findFirstByGrade(CareerGrade grade, String jurisdiction, long now) {
        PersonnelStore store = repository.read();
        if (store.records == null) return null;
        for (PersonnelRecord record : store.records.values()) {
            if (record != null && record.active() && record.careerGrade == grade
                    && servesJurisdiction(record, jurisdiction))
                return record.playerUuid;
        }
        return null;
    }

    private boolean servesJurisdiction(PersonnelRecord record, String jurisdiction) {
        if (jurisdiction == null || jurisdiction.isBlank()) return true;
        if (stations == null) return false;
        var station = stations.get(record.homeStationId);
        return station != null && station.serves(jurisdiction);
    }

    public synchronized java.util.List<PersonnelRecord> all() {
        PersonnelStore store = repository.read();
        return store.records == null ? java.util.List.of() : java.util.List.copyOf(store.records.values());
    }

    public synchronized PersonnelRecord authorize(String actorUuid, String subjectUuid, CareerGrade grade,
                                                  EmploymentMode employment, String source, String stationId,
                                                  String idempotencyKey) {
        if (subjectUuid == null || subjectUuid.isBlank() || grade == null) throw new IllegalArgumentException("subject and grade required");
        requireAuthorization(actorUuid, subjectUuid, source);
        PersonnelStore store = repository.read();
        if (store.records == null) store.records = new java.util.LinkedHashMap<>();
        PersonnelRecord existing = store.records.get(subjectUuid);
        if (existing != null && existing.active()) {
            if (idempotencyKey != null && idempotencyKey.equals(existing.authorizationOperationKey)) return existing;
            throw new IllegalStateException("personnel already authorized");
        }
        long now = clock.nowMillis();
        EmploymentMode mode = employment == null ? EmploymentMode.PART_TIME : employment;
        if (grade.fullTimeRequired() && mode != EmploymentMode.FULL_TIME)
            throw new IllegalArgumentException("grade requires full-time employment");
        if (existing != null && existing.hasIncompatibleAffiliation(now) && mode == EmploymentMode.FULL_TIME)
            throw new IllegalStateException("incompatible affiliation");
        PersonnelRecord record = existing == null ? new PersonnelRecord() : existing;
        record.playerUuid = subjectUuid;
        record.serviceNumber = record.serviceNumber == null || record.serviceNumber.isBlank()
                ? ids.newId("SRV") : record.serviceNumber;
        record.membershipStatus = PersonnelStatus.AUTHORIZED_ACTIVE;
        record.careerTrack = grade.track();
        record.careerGrade = grade;
        record.careerOrigin = grade == CareerGrade.INSPECTOR && record.careerOrigin != null
                ? record.careerOrigin : grade.track();
        record.employmentMode = mode;
        record.authorizedAt = record.authorizedAt == 0 ? now : record.authorizedAt;
        record.authorizationSource = source == null ? "" : source;
        record.authorizationActorUuid = actorUuid == null ? "" : actorUuid;
        record.authorizationOperationKey = idempotencyKey == null ? "" : idempotencyKey;
        record.homeStationId = stationId == null || stationId.isBlank() ? "hq" : stationId;
        if (record.createdAt == 0) record.createdAt = now;
        record.updatedAt = now;
        record.version++;
        store.records.put(subjectUuid, record);
        store.storeRevision++;
        repository.write(store);
        if (documents != null) {
            var type = grade.isProfessional()
                    ? com.dwurdy.straja.domain.model.DocumentType.RECRUITMENT_ORDER
                    : com.dwurdy.straja.domain.model.DocumentType.APPOINTMENT_ORDER;
            documents.issueInternalSystem(subjectUuid, type, grade.name(), record.homeStationId, "",
                    "", "personnel:" + record.serviceNumber,
                    "personnel-authorization:" + subjectUuid + ":" + record.authorizationOperationKey
                            + ":v" + record.version);
        }
        authorizationListener.accept(record);
        return record;
    }

    public synchronized PersonnelRecord suspend(String actorUuid, String subjectUuid, String reason) {
        requireAuthorization(actorUuid, subjectUuid, "COMMAND");
        return changeStatus(subjectUuid, PersonnelStatus.SUSPENDED, reason);
    }

    public synchronized PersonnelRecord reinstate(String actorUuid, String subjectUuid) {
        requireAuthorization(actorUuid, subjectUuid, "COMMAND");
        return changeStatus(subjectUuid, PersonnelStatus.AUTHORIZED_ACTIVE, "");
    }

    public synchronized PersonnelRecord terminate(String actorUuid, String subjectUuid, String reason) {
        requireAuthorization(actorUuid, subjectUuid, "COMMAND");
        return changeStatus(subjectUuid, PersonnelStatus.TERMINATED, reason);
    }

    public synchronized PersonnelRecord resign(String subjectUuid, String reason) {
        return changeStatus(subjectUuid, PersonnelStatus.RESIGNED, reason);
    }

    public synchronized AppointmentRecord appoint(String actorUuid, String subjectUuid, AppointmentType type,
                                                  String stationId, String jurisdiction, Long expiresAt) {
        return appoint(actorUuid, subjectUuid, type, stationId, jurisdiction, expiresAt, false);
    }

    /** Explicit composition-root path for the configured commissioner bootstrap. */
    public synchronized AppointmentRecord appointInternal(String subjectUuid, AppointmentType type,
                                                          String stationId, String jurisdiction, Long expiresAt) {
        return appoint("bootstrap", subjectUuid, type, stationId, jurisdiction, expiresAt, true);
    }

    private AppointmentRecord appoint(String actorUuid, String subjectUuid, AppointmentType type,
                                     String stationId, String jurisdiction, Long expiresAt,
                                     boolean internal) {
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (authorization != null && !internal) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "AUTHORIZE_PERSONNEL");
            context.subjectUuid = subjectUuid;
            context.beneficiaryUuid = subjectUuid;
            context.requiresIndependentApproval = true;
            context.stationId = stationId;
            context.jurisdiction = jurisdiction;
            if (!authorization.allowed(context)) throw new IllegalStateException("personnel appointment denied");
        }
        if (type == AppointmentType.COMMISSIONER && record.employmentMode != EmploymentMode.FULL_TIME)
            throw new IllegalStateException("commissioner appointment requires full-time employment");
        if (!record.active()) throw new IllegalStateException("personnel is not active");
        long now = clock.nowMillis();
        AppointmentRecord appointment = new AppointmentRecord();
        appointment.appointmentId = ids.newId("APT");
        appointment.type = type == null ? AppointmentType.PATROL_LEAD : type;
        appointment.subjectUuid = subjectUuid;
        appointment.stationId = stationId == null ? "" : stationId;
        appointment.jurisdiction = jurisdiction == null ? "" : jurisdiction;
        appointment.appointedBy = actorUuid == null ? "" : actorUuid;
        appointment.startsAt = now;
        appointment.expiresAt = expiresAt;
        record.appointments.add(appointment);
        record.updatedAt = now;
        record.version++;
        store.storeRevision++;
        repository.write(store);
        return appointment;
    }

    public synchronized AffiliationRecord addAffiliation(String subjectUuid, String factionId, String name,
                                                          AffiliationRecord.ConflictClass conflictClass,
                                                          long startsAt, Long endsAt) {
        return addAffiliation(subjectUuid, subjectUuid, factionId, name, conflictClass, startsAt, endsAt);
    }

    public synchronized AffiliationRecord addAffiliation(String actorUuid, String subjectUuid, String factionId,
                                                          String name, AffiliationRecord.ConflictClass conflictClass,
                                                          long startsAt, Long endsAt) {
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (authorization != null && !isInternalProjection(actorUuid)) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "AUTHORIZE_PERSONNEL");
            context.subjectUuid = subjectUuid; context.beneficiaryUuid = subjectUuid;
            context.requiresIndependentApproval = true;
            if (!authorization.allowed(context)) throw new IllegalStateException("affiliation change denied");
        }
        AffiliationRecord affiliation = new AffiliationRecord();
        affiliation.affiliationId = ids.newId("AFF");
        affiliation.playerUuid = subjectUuid;
        affiliation.externalFactionId = factionId == null ? "" : factionId;
        affiliation.externalFactionName = name == null ? "" : name;
        affiliation.conflictClass = conflictClass == null ? AffiliationRecord.ConflictClass.NONE : conflictClass;
        affiliation.startsAt = startsAt;
        affiliation.endsAt = endsAt;
        record.affiliations.add(affiliation);
        record.updatedAt = clock.nowMillis();
        record.version++;
        store.storeRevision++;
        repository.write(store);
        return affiliation;
    }

    public synchronized PersonnelRecord assignProfession(String actorUuid, String subjectUuid, String profession) {
        if (profession == null || profession.isBlank()) throw new IllegalArgumentException("profession required");
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (record.careerGrade == null || !record.careerGrade.isProfessional())
            throw new IllegalStateException("profession requires professional career");
        if (authorization != null && !isInternalProjection(actorUuid)) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "AUTHORIZE_PERSONNEL");
            context.subjectUuid = subjectUuid; context.beneficiaryUuid = subjectUuid;
            context.requiresIndependentApproval = true;
            if (!authorization.allowed(context)) throw new IllegalStateException("profession assignment denied");
        }
        String normalized = profession.trim().toUpperCase(java.util.Locale.ROOT);
        if (!record.professions.contains(normalized)) record.professions.add(normalized);
        record.updatedAt = clock.nowMillis(); record.version++; store.storeRevision++; repository.write(store);
        return record;
    }

    /** Idempotent shadow projection from legacy GuardState. */
    public synchronized PersonnelRecord projectLegacy(UUID playerUuid, GuardState legacy, String actorUuid) {
        if (playerUuid == null || legacy == null) throw new IllegalArgumentException("player and legacy state required");
        // Once a V2 record exists, login projection must never resurrect a
        // suspended/terminated record from stale GuardState.
        PersonnelRecord existing = find(playerUuid.toString());
        if (existing != null) return existing;
        CareerGrade grade = switch (Rank.of(legacy.rank)) {
            case STAGIAR -> CareerGrade.MILITARY_STAGIAR;
            case GUARD -> CareerGrade.MILITARY_STRAJER;
            case SERGENT -> CareerGrade.MILITARY_SERGENT;
            case INSPECTOR -> CareerGrade.INSPECTOR;
            default -> null;
        };
        if (grade == null || !"AUTHORIZED".equals(legacy.applicationState)) return find(playerUuid.toString());
        return authorize(actorUuid, playerUuid.toString(), grade,
                grade.fullTimeRequired() ? EmploymentMode.FULL_TIME : EmploymentMode.PART_TIME,
                "LEGACY_PROJECTION", "hq", "legacy:" + playerUuid);
    }

    private PersonnelRecord changeStatus(String subjectUuid, PersonnelStatus status, String reason) {
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        record.membershipStatus = status;
        if (status == PersonnelStatus.SUSPENDED) {
            record.suspendedAt = clock.nowMillis();
            record.suspensionReason = reason == null ? "" : reason;
        }
        if (status == PersonnelStatus.RESIGNED) {
            record.resignationAt = clock.nowMillis();
            record.resignationReason = reason == null ? "" : reason;
        }
        record.updatedAt = clock.nowMillis();
        record.version++;
        store.storeRevision++;
        repository.write(store);
        return record;
    }

    private void requireAuthorization(String actorUuid, String subjectUuid, String source) {
        if (authorization == null || isInternalProjection(source)) return;
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "AUTHORIZE_PERSONNEL");
        context.subjectUuid = subjectUuid == null ? "" : subjectUuid;
        context.beneficiaryUuid = context.subjectUuid;
        context.requiresIndependentApproval = true;
        var decision = authorization.authorize(context);
        if (!decision.allowed) throw new IllegalStateException("personnel authorization denied: " + decision.reasonCode);
    }

    private static boolean isInternalProjection(String source) {
        return "LEGACY_PROJECTION".equals(source)
                || "COMMISSIONER_BOOTSTRAP".equals(source)
                || "BOOTSTRAP".equals(source);
    }

    private static PersonnelRecord require(PersonnelStore store, String uuid) {
        PersonnelRecord record = store.records == null ? null : store.records.get(uuid);
        if (record == null) throw new IllegalArgumentException("unknown personnel: " + uuid);
        return record;
    }
}
