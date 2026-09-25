package com.dwurdy.straja.domain.model;

public class AppointmentRecord {
    public String appointmentId = "";
    public AppointmentType type = AppointmentType.PATROL_LEAD;
    public String subjectUuid = "";
    public String stationId = "";
    public String jurisdiction = "";
    public String appointedBy = "";
    public long startsAt;
    public Long expiresAt;
    public Long revokedAt;

    public boolean activeAt(long now) {
        return revokedAt == null && startsAt <= now && (expiresAt == null || now < expiresAt);
    }
}
