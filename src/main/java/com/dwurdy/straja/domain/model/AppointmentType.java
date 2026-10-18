package com.dwurdy.straja.domain.model;

public enum AppointmentType {
    COMMISSIONER,
    INSTRUCTOR,
    RECRUITER,
    PATROL_LEAD,
    /** Organizational post per the officer-corps announcement: section
     *  leadership (police section, garrison, prison/mine, industry) or a
     *  specialist desk (investigations, logistics, construction…). Carries
     *  a free title the Comandament sets; capabilities stay grade-uniform
     *  by design — the post is identity and accountability, not a
     *  permission key. */
    POSTING
}
