package com.dwurdy.straja.domain.model;

public enum CareerGrade {
    MILITARY_STAGIAR(CareerTrack.MILITARY, false),
    MILITARY_STRAJER(CareerTrack.MILITARY, false),
    MILITARY_SERGENT(CareerTrack.MILITARY, true),
    PROFESSIONAL_STAGIAR_SPECIALIST(CareerTrack.PROFESSIONAL, false),
    PROFESSIONAL_SPECIALIST(CareerTrack.PROFESSIONAL, false),
    INSPECTOR(CareerTrack.MILITARY, true);

    private final CareerTrack track;
    private final boolean fullTimeRequired;

    CareerGrade(CareerTrack track, boolean fullTimeRequired) {
        this.track = track;
        this.fullTimeRequired = fullTimeRequired;
    }

    public CareerTrack track() { return track; }
    public boolean fullTimeRequired() { return fullTimeRequired; }
    public boolean isProfessional() { return track == CareerTrack.PROFESSIONAL; }
}
