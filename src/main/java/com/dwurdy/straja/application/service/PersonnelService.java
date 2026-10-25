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
        for (PersonnelRecord record : store.ensureRecords().values()) {
            if (record != null && record.active() && record.careerGrade == grade
                    && servesJurisdiction(record, jurisdiction)) return true;
        }
        return false;
    }

    public synchronized String findFirstByGrade(CareerGrade grade, String jurisdiction, long now) {
        PersonnelStore store = repository.read();
        for (PersonnelRecord record : store.ensureRecords().values()) {
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

    /**
     * The sitting Comisar: the first active record holding a live COMMISSIONER
     * appointment. This is the in-world selection made by
     * {@code /straja setup commissioner} — authoritative alongside (and
     * preferred over) the config pins, so an appointee survives a restart
     * without any TOML edit.
     */
    public synchronized PersonnelRecord commissioner() {
        PersonnelStore store = repository.read();
        for (PersonnelRecord record : store.ensureRecords().values()) {
            if (record != null && record.active()
                    && record.hasAppointment(AppointmentType.COMMISSIONER, clock.nowMillis()))
                return record;
        }
        return null;
    }

    /**
     * Announced minimum-activity signal: a login or an on-duty tick stamps
     * {@code lastActiveAt} on the member's record. Throttled to one write
     * per minute — the weekly contract never needs finer resolution and a
     * per-tick write would churn the store for nothing.
     */
    public synchronized void noteActivity(String playerUuid, long now) {
        if (playerUuid == null) return;
        PersonnelStore store = repository.read();
        PersonnelRecord record = store.records == null ? null : store.records.get(playerUuid);
        if (record == null || !record.active()) return;
        if (record.lastActiveAt != null && now - record.lastActiveAt < 60_000L) return;
        record.lastActiveAt = now;
        // Real presence supersedes a still-open absence notice — the member
        // came back early; the file should say so, not keep them "away".
        if (record.absentUntil != null && now < record.absentUntil) {
            record.absentUntil = null;
            record.absenceReason = "";
            record.version++;
        }
        store.storeRevision++;
        repository.write(store);
    }

    /**
     * Announced absence (the announcement's "anunțați Comandamentul"): the
     * member records that they will be away until {@code untilMs}; the
     * notice covers the weekly minimum for that window. Declaring again
     * replaces the notice — the Comandament sees one current absence.
     */
    public synchronized PersonnelRecord declareAbsence(String playerUuid, long untilMs, String reason) {
        if (untilMs <= clock.nowMillis()) throw new IllegalArgumentException("absence must end in the future");
        PersonnelStore store = repository.read();
        PersonnelRecord record = store.records == null ? null : store.records.get(playerUuid);
        if (record == null || !record.active()) throw new IllegalStateException("personnel is not active");
        record.absentUntil = untilMs;
        record.absenceReason = com.dwurdy.straja.domain.model.Text.bounded(
                reason, com.dwurdy.straja.domain.model.Text.REASON_LIMIT);
        record.updatedAt = clock.nowMillis();
        record.version++;
        store.storeRevision++;
        repository.write(store);
        return record;
    }

    /** Weekly-minimum evaluation: what the member's record says right now. */
    public enum ActivityState { ACTIVE, NOTICED_ABSENT, INACTIVE, UNOBSERVED }

    /**
     * {@code INACTIVE} only after the whole window passes silently — a
     * member who never logged activity (pre-remodel record) is UNOBSERVED,
     * not delinquent; a notice inside its window is NOTICED_ABSENT.
     */
    public ActivityState activityState(PersonnelRecord record, long now, long windowMs) {
        if (record == null || !record.active()) return ActivityState.UNOBSERVED;
        if (record.absentUntil != null && now < record.absentUntil) return ActivityState.NOTICED_ABSENT;
        if (record.lastActiveAt == null) return ActivityState.UNOBSERVED;
        if (windowMs > 0 && now - record.lastActiveAt > windowMs) return ActivityState.INACTIVE;
        return ActivityState.ACTIVE;
    }

    public synchronized PersonnelRecord authorize(String actorUuid, String subjectUuid, CareerGrade grade,
                                                  EmploymentMode employment, String source, String stationId,
                                                  String idempotencyKey) {
        if (subjectUuid == null || subjectUuid.isBlank() || grade == null) throw new IllegalArgumentException("subject and grade required");
        requireAuthorization(actorUuid, subjectUuid);
        return authorizeUnchecked(actorUuid, subjectUuid, grade, employment, source, stationId, idempotencyKey);
    }

    /** Composition-root-only projection path; never expose this as a command. */
    synchronized PersonnelRecord authorizeInternal(String actorUuid, String subjectUuid, CareerGrade grade,
                                                   EmploymentMode employment, String source, String stationId,
                                                   String idempotencyKey) {
        if (subjectUuid == null || subjectUuid.isBlank() || grade == null)
            throw new IllegalArgumentException("subject and grade required");
        return authorizeUnchecked(actorUuid, subjectUuid, grade, employment, source, stationId, idempotencyKey);
    }

    private PersonnelRecord authorizeUnchecked(String actorUuid, String subjectUuid, CareerGrade grade,
                                               EmploymentMode employment, String source, String stationId,
                                               String idempotencyKey) {
        PersonnelStore store = repository.read();
        store.ensureRecords();
        PersonnelRecord existing = store.records.get(subjectUuid);
        if (existing != null && existing.active()) {
            EmploymentMode requestedMode = employment == null ? EmploymentMode.PART_TIME : employment;
            String requestedStation = stationId == null || stationId.isBlank() ? "hq" : stationId;
            if (idempotencyKey != null && idempotencyKey.equals(existing.authorizationOperationKey)) {
                boolean sameRequest = existing.careerGrade == grade
                        && existing.employmentMode == requestedMode
                        && java.util.Objects.equals(existing.authorizationSource, source == null ? "" : source)
                        && java.util.Objects.equals(existing.homeStationId, requestedStation);
                if (!sameRequest) throw new IllegalStateException("IDEMPOTENCY_PAYLOAD_MISMATCH");
                return existing;
            }
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
        // Officer commissions keep the member's entry-track origin (mil/prof
        // flavor follows them into the officer corps).
        record.careerOrigin = grade.isOfficer() && record.careerOrigin != null
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
        if (record.outboundIntents == null) record.outboundIntents = new java.util.ArrayList<>();
        record.outboundIntents.add(com.dwurdy.straja.domain.model.OutboxIntent.of(
                "PERSONNEL_AUTHORIZED", "personnel:" + subjectUuid + ":v" + record.version,
                record.serviceNumber, subjectUuid, now));
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

    /**
     * Repairs a legacy projection before the configured commissioner is
     * appointed. Existing lower-grade or part-time records are normalized to
     * the configured commissioner's full-time inspector identity.
     */
    public synchronized PersonnelRecord ensureCommissioner(String subjectUuid) {
        if (subjectUuid == null || subjectUuid.isBlank())
            throw new IllegalArgumentException("commissioner subject required");
        PersonnelStore store = repository.read();
        store.ensureRecords();
        PersonnelRecord record = store.records.get(subjectUuid);
        if (record == null) {
            return authorizeInternal("bootstrap", subjectUuid, CareerGrade.OFFICER_COLONEL,
                    EmploymentMode.FULL_TIME, "COMMISSIONER_BOOTSTRAP", "hq",
                    "commissioner:" + subjectUuid);
        }
        long now = clock.nowMillis();
        boolean changed = record.membershipStatus != PersonnelStatus.AUTHORIZED_ACTIVE
                || record.careerGrade != CareerGrade.OFFICER_COLONEL
                || record.careerTrack != CareerTrack.OFFICER
                || record.careerOrigin != CareerTrack.MILITARY
                || record.employmentMode != EmploymentMode.FULL_TIME
                || !"hq".equals(record.homeStationId);
        if (changed) {
            record.membershipStatus = PersonnelStatus.AUTHORIZED_ACTIVE;
            record.careerTrack = CareerTrack.OFFICER;
            record.careerGrade = CareerGrade.OFFICER_COLONEL;
            record.careerOrigin = CareerTrack.MILITARY;
            record.employmentMode = EmploymentMode.FULL_TIME;
            record.homeStationId = "hq";
            record.authorizationSource = "COMMISSIONER_BOOTSTRAP";
            record.authorizationActorUuid = "bootstrap";
            record.authorizationOperationKey = "commissioner:" + subjectUuid;
            if (record.authorizedAt == 0) record.authorizedAt = now;
            record.updatedAt = now;
            record.version++;
            store.storeRevision++;
            repository.write(store);
        }
        return record;
    }

    public synchronized PersonnelRecord suspend(String actorUuid, String subjectUuid, String reason) {
        requireAuthorization(actorUuid, subjectUuid);
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (!record.active()) throw new IllegalStateException("personnel is not active");
        return changeStatus(subjectUuid, PersonnelStatus.SUSPENDED, reason);
    }

    public synchronized PersonnelRecord reinstate(String actorUuid, String subjectUuid) {
        requireAuthorization(actorUuid, subjectUuid);
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (record.membershipStatus != PersonnelStatus.SUSPENDED)
            throw new IllegalStateException("personnel is not suspended");
        return changeStatus(subjectUuid, PersonnelStatus.AUTHORIZED_ACTIVE, "");
    }

    public synchronized PersonnelRecord terminate(String actorUuid, String subjectUuid, String reason) {
        requireAuthorization(actorUuid, subjectUuid);
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (!record.active() && record.membershipStatus != PersonnelStatus.SUSPENDED)
            throw new IllegalStateException("personnel is not active or suspended");
        return changeStatus(subjectUuid, PersonnelStatus.TERMINATED, reason);
    }

    /**
     * Lowers the subject one rung on their career ladder. Demoting a
     * LOCOTENENT returns them to their entry track's top enlisted rung
     * (kept via careerOrigin); demoting an entry-grade member removes them
     * from the corps entirely (TERMINATED — the V2 equivalent of
     * demoted-to-civilian).
     */
    public synchronized PersonnelRecord demote(String actorUuid, String subjectUuid, String reason) {
        requireAuthorization(actorUuid, subjectUuid);
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (!record.active()) throw new IllegalStateException("personnel is not active");
        if (record.careerGrade == null) throw new IllegalStateException("personnel has no grade");
        CareerGrade previous = record.careerGrade == CareerGrade.OFFICER_LOCOTENENT
                ? (record.careerOrigin == CareerTrack.PROFESSIONAL
                        ? CareerGrade.PROFESSIONAL_MESTER : CareerGrade.MILITARY_SERGEANT)
                : record.careerGrade.previousGrade();
        if (previous == null) {
            record.membershipStatus = PersonnelStatus.TERMINATED;
        } else {
            record.careerGrade = previous;
        }
        record.demotedAt = clock.nowMillis();
        record.demotionReason = reason == null ? "" : reason;
        record.updatedAt = clock.nowMillis();
        record.version++;
        store.storeRevision++;
        repository.write(store);
        return record;
    }

    public synchronized PersonnelRecord resign(String subjectUuid, String reason) {
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (!record.active() && record.membershipStatus != PersonnelStatus.SUSPENDED)
            throw new IllegalStateException("personnel is not active or suspended");
        return changeStatus(subjectUuid, PersonnelStatus.RESIGNED, reason);
    }

    public synchronized AppointmentRecord appoint(String actorUuid, String subjectUuid, AppointmentType type,
                                                  String stationId, String jurisdiction, Long expiresAt) {
        return appoint(actorUuid, subjectUuid, type, stationId, jurisdiction, expiresAt, false, "");
    }

    /** Explicit composition-root path for the configured commissioner bootstrap. */
    public synchronized AppointmentRecord appointInternal(String subjectUuid, AppointmentType type,
                                                          String stationId, String jurisdiction, Long expiresAt) {
        return appoint("bootstrap", subjectUuid, type, stationId, jurisdiction, expiresAt, true, "");
    }

    /**
     * Trusted-setup path used by {@code /straja setup commissioner}: the
     * command's gate ({@code isCommissioner || isOp}) has already authorized
     * the actor — a cold-start op holds no personnel record, so the service
     * AUTHORIZE_PERSONNEL check would refuse the very bootstrap it exists
     * to perform. The appointing uuid is still recorded for audit.
     */
    public synchronized AppointmentRecord appointInternal(String actorUuid, String subjectUuid,
                                                          AppointmentType type, String stationId,
                                                          String jurisdiction, Long expiresAt) {
        return appoint(actorUuid, subjectUuid, type, stationId, jurisdiction, expiresAt, true, "");
    }

    /**
     * Officer-corps posting (announced structures): assigns an officer to a
     * section or specialist desk — police section, garrison, prison/mine,
     * industry, investigations, logistics, construction, smithing — with
     * the free title the Comandament sets. Postings are organizational:
     * they carry identity, station, jurisdiction and audit, never extra
     * capabilities. Officer grade required; part-time tracks hold
     * professions and shifts instead.
     */
    public synchronized AppointmentRecord post(String actorUuid, String subjectUuid, String stationId,
                                               String jurisdiction, String title, Long expiresAt) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("posting title required");
        return appoint(actorUuid, subjectUuid, AppointmentType.POSTING,
                stationId, jurisdiction, expiresAt, false, title);
    }

    private AppointmentRecord appoint(String actorUuid, String subjectUuid, AppointmentType type,
                                     String stationId, String jurisdiction, Long expiresAt,
                                     boolean internal, String postTitle) {
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
        if (type == AppointmentType.POSTING
                && (record.careerGrade == null || !record.careerGrade.isOfficer()))
            throw new IllegalStateException("postings require an officer grade");
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
        appointment.postTitle = type == AppointmentType.POSTING
                ? com.dwurdy.straja.domain.model.Text.bounded(postTitle, com.dwurdy.straja.domain.model.Text.REASON_LIMIT)
                : "";
        record.appointments.add(appointment);
        record.updatedAt = now;
        record.version++;
        store.storeRevision++;
        repository.write(store);
        return appointment;
    }

    /** Revokes an active appointment/posting (reassignment, removal from a
     *  section). Same AUTHORIZE_PERSONNEL gate as the grant; audited via the
     *  record version and the caller's command context. */
    public synchronized AppointmentRecord revokeAppointment(String actorUuid, String subjectUuid,
                                                            String appointmentId) {
        return revokeAppointment(actorUuid, subjectUuid, appointmentId, false);
    }

    /**
     * Trusted-setup revocation, paired with {@link #appointInternal}: the
     * setup command needs to clear a superseded Comisar's appointment while
     * the operator holds no personnel record of their own.
     */
    public synchronized AppointmentRecord revokeAppointmentInternal(String actorUuid,
                                                                    String subjectUuid,
                                                                    String appointmentId) {
        return revokeAppointment(actorUuid, subjectUuid, appointmentId, true);
    }

    private AppointmentRecord revokeAppointment(String actorUuid, String subjectUuid,
                                                String appointmentId, boolean internal) {
        PersonnelStore store = repository.read();
        PersonnelRecord record = require(store, subjectUuid);
        if (authorization != null && !internal) {
            AuthorizationContext context = AuthorizationContext.of(actorUuid, "AUTHORIZE_PERSONNEL");
            context.subjectUuid = subjectUuid;
            context.beneficiaryUuid = subjectUuid;
            context.requiresIndependentApproval = true;
            if (!authorization.allowed(context)) throw new IllegalStateException("appointment revocation denied");
        }
        AppointmentRecord appointment = record.appointments.stream()
                .filter(a -> appointmentId != null && appointmentId.equals(a.appointmentId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("appointment not found"));
        if (appointment.revokedAt != null) throw new IllegalStateException("appointment already revoked");
        appointment.revokedAt = clock.nowMillis();
        record.updatedAt = appointment.revokedAt;
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
        if (authorization != null) {
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
        if (authorization != null) {
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

    /**
     * Self-service entry into the trades (Meseriași) path: a civilian signs on
     * as a part-time Ziler — no exam, no approval chain. Sworn members and
     * fired/suspended records are refused; resigned members may rejoin on the
     * trades track. Idempotent per player; re-enrolling an active trades
     * member only adds the newly chosen profession.
     */
    public synchronized PersonnelRecord enrollProfessional(String subjectUuid, String profession) {
        return enrollProfessional(subjectUuid, subjectUuid, profession,
                "TRADES_ENROLLMENT", "trades-enroll:" + subjectUuid);
    }

    /**
     * Approval-attributed enrollment used by the join-petition review path:
     * the reviewer is recorded as the actor and the join request id becomes
     * the idempotency key, so a retried approval converges instead of
     * double-writing.
     */
    public synchronized PersonnelRecord enrollProfessional(String actorUuid, String subjectUuid,
                                                           String profession, String source,
                                                           String idempotencyKey) {
        if (subjectUuid == null || subjectUuid.isBlank()) throw new IllegalArgumentException("subject required");
        if (profession == null || profession.isBlank()) throw new IllegalArgumentException("profession required");
        PersonnelStore store = repository.read();
        PersonnelRecord existing = store.records == null ? null : store.records.get(subjectUuid);
        if (existing != null && existing.active()) {
            if (existing.careerGrade != null && existing.careerGrade.isProfessional()) {
                return assignProfessionUnchecked(store, existing, profession);
            }
            throw new IllegalStateException("TRADES_ENROLL_DENIED_SWORN_MEMBER");
        }
        if (existing != null && (existing.membershipStatus == PersonnelStatus.SUSPENDED
                || existing.membershipStatus == PersonnelStatus.TERMINATED)) {
            throw new IllegalStateException("TRADES_ENROLL_DENIED_" + existing.membershipStatus);
        }
        authorizeUnchecked(actorUuid == null || actorUuid.isBlank() ? subjectUuid : actorUuid, subjectUuid,
                CareerGrade.PROFESSIONAL_ZILIER, EmploymentMode.PART_TIME,
                source, "hq", idempotencyKey == null ? "trades-enroll:" + subjectUuid : idempotencyKey);
        // authorizeUnchecked wrote its own store snapshot; repositories may
        // round-trip through serialization, so re-read and mutate the persisted
        // instance rather than the copy authorizeUnchecked returned.
        PersonnelStore fresh = repository.read();
        PersonnelRecord persisted = fresh.records == null ? null : fresh.records.get(subjectUuid);
        if (persisted == null) throw new IllegalStateException("TRADES_ENROLL_PERSIST_FAILED");
        return assignProfessionUnchecked(fresh, persisted, profession);
    }

    private PersonnelRecord assignProfessionUnchecked(PersonnelStore store, PersonnelRecord record,
                                                      String profession) {
        String normalized = profession.trim().toUpperCase(java.util.Locale.ROOT);
        if (!record.professions.contains(normalized)) record.professions.add(normalized);
        record.updatedAt = clock.nowMillis();
        record.version++;
        store.storeRevision++;
        repository.write(store);
        return record;
    }

    /** Idempotent shadow projection from legacy GuardState. */
    public synchronized PersonnelRecord projectLegacy(UUID playerUuid, GuardState legacy, String actorUuid) {
        if (playerUuid == null || legacy == null) throw new IllegalArgumentException("player and legacy state required");
        // Once a V2 record exists, login projection must never resurrect a
        // suspended/terminated record from stale GuardState.
        PersonnelRecord existing = find(playerUuid.toString());
        if (existing != null) return existing;
        CareerGrade grade = CareerGrade.fromLegacyRank(Rank.of(legacy.rank));
        if (grade == null || !"AUTHORIZED".equals(legacy.applicationState)) return find(playerUuid.toString());
        PersonnelRecord created = authorizeInternal(actorUuid, playerUuid.toString(), grade,
                grade.fullTimeRequired() ? EmploymentMode.FULL_TIME : EmploymentMode.PART_TIME,
                "LEGACY_PROJECTION", "hq", "legacy:" + playerUuid);
        // First projection must carry the legacy discipline state — otherwise a
        // suspended or dismissed member wakes up with an active V2 record and
        // the projection immediately clears the suspension back in GuardState.
        if (created != null && (legacy.fired || legacy.resigned || legacy.suspended)) {
            changeStatus(playerUuid.toString(),
                    legacy.fired ? PersonnelStatus.TERMINATED
                            : legacy.resigned ? PersonnelStatus.RESIGNED : PersonnelStatus.SUSPENDED,
                    "LEGACY_PROJECTION");
            return find(playerUuid.toString());
        }
        return created;
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

    private void requireAuthorization(String actorUuid, String subjectUuid) {
        if (authorization == null) return;
        AuthorizationContext context = AuthorizationContext.of(actorUuid, "AUTHORIZE_PERSONNEL");
        context.subjectUuid = subjectUuid == null ? "" : subjectUuid;
        context.beneficiaryUuid = context.subjectUuid;
        context.requiresIndependentApproval = true;
        var decision = authorization.authorize(context);
        if (!decision.allowed) throw new IllegalStateException("personnel authorization denied: " + decision.reasonCode);
    }

    private static PersonnelRecord require(PersonnelStore store, String uuid) {
        PersonnelRecord record = store.records == null ? null : store.records.get(uuid);
        if (record == null) throw new IllegalArgumentException("unknown personnel: " + uuid);
        return record;
    }
}
