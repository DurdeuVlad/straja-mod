package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** A short-lived operational event linked to the formal record graph. */
public class Incident {
    public String id = "";
    public IncidentType type = IncidentType.MANUAL;
    public IncidentPriority priority = IncidentPriority.ROUTINE;
    public IncidentStatus status = IncidentStatus.OPEN;
    public String title = "";
    public String description = "";
    public long createdAt;
    public String createdByUuid = "";
    public String createdByName = "";
    public String source = "";
    public String dimension = "";
    public double x;
    public double y;
    public double z;
    public String placeLabel = "";
    public String subjectUuid = "";
    public String subjectName = "";
    public String linkedFineId = "";
    public String linkedComplaintId = "";
    public String linkedArrestTaskId = "";
    public String linkedArrestId = "";
    public String leadGuardUuid = "";
    public String leadGuardName = "";
    public List<String> supportingGuardUuids = new ArrayList<>();
    public List<String> supportingGuardNames = new ArrayList<>();
    public Long acceptedAt;
    public Long resolvedAt;
    public IncidentResolution resolution;
    public String resolutionNotes = "";
    public long expiresAt;
    public String provenance = "";
}
