package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** Authoritative V2 personnel state. GuardState remains a compatibility projection. */
public class PersonnelRecord {
    public String playerUuid = "";
    public String serviceNumber = "";
    public PersonnelStatus membershipStatus = PersonnelStatus.CIVIL;
    public CareerTrack careerTrack = CareerTrack.MILITARY;
    public CareerGrade careerGrade = CareerGrade.MILITARY_STAGIAR;
    public CareerTrack careerOrigin = CareerTrack.MILITARY;
    public EmploymentMode employmentMode = EmploymentMode.PART_TIME;
    public long authorizedAt;
    public String authorizationSource = "";
    public String authorizationActorUuid = "";
    public String authorizationOperationKey = "";
    public String homeStationId = "hq";
    public List<AppointmentRecord> appointments = new ArrayList<>();
    public List<String> specializations = new ArrayList<>();
    public List<String> professions = new ArrayList<>();
    public List<AffiliationRecord> affiliations = new ArrayList<>();
    public Long suspendedAt;
    public String suspensionReason = "";
    public Long resignationAt;
    public String resignationReason = "";
    public long version;
    public long createdAt;
    public long updatedAt;

    public boolean active() { return membershipStatus == PersonnelStatus.AUTHORIZED_ACTIVE; }
    public boolean fullTimeRequired() { return careerGrade != null && careerGrade.fullTimeRequired(); }
    public boolean hasAppointment(AppointmentType type, long now) {
        return appointments.stream().anyMatch(a -> a != null && a.type == type && a.activeAt(now));
    }
    public boolean hasIncompatibleAffiliation(long now) {
        return affiliations.stream().anyMatch(a -> a != null
                && a.conflictClass == AffiliationRecord.ConflictClass.INCOMPATIBLE
                && (a.endsAt == null || now < a.endsAt));
    }
}
