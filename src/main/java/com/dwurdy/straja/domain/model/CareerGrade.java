package com.dwurdy.straja.domain.model;

/**
 * Career ladder: two part-time tracks feed a shared full-time officer
 * corps. Enlisted (auto-join by quiz) STAGIAR → STRAJER; trades
 * (petition-join) ZILIER → MESTER; officers (petition-promote, full-time)
 * LOCOTENENT → CAPITAN → COLONEL. All officer grades share the same broad
 * mechanical authority — specialties are RP flavor via professions and
 * rankName, not capability gates.
 *
 * <p>Persisted by enum name in Gson stores; {@link #of(String)} maps the
 * pre-remodel names so old saves read forward instead of failing closed
 * into corrupt_backup.
 */
public enum CareerGrade {
    MILITARY_STAGIAR(CareerTrack.MILITARY, false),
    MILITARY_STRAJER(CareerTrack.MILITARY, false),
    PROFESSIONAL_ZILIER(CareerTrack.PROFESSIONAL, false),
    PROFESSIONAL_MESTER(CareerTrack.PROFESSIONAL, false),
    OFFICER_LOCOTENENT(CareerTrack.OFFICER, true),
    OFFICER_CAPITAN(CareerTrack.OFFICER, true),
    OFFICER_COLONEL(CareerTrack.OFFICER, true);

    private final CareerTrack track;
    private final boolean fullTimeRequired;

    CareerGrade(CareerTrack track, boolean fullTimeRequired) {
        this.track = track;
        this.fullTimeRequired = fullTimeRequired;
    }

    public CareerTrack track() { return track; }
    public boolean fullTimeRequired() { return fullTimeRequired; }
    public boolean isProfessional() { return track == CareerTrack.PROFESSIONAL; }
    public boolean isOfficer() { return track == CareerTrack.OFFICER; }

    /** Resolves a persisted enum name, mapping pre-remodel grade names. */
    public static CareerGrade of(String name) {
        if (name == null) return null;
        try {
            return valueOf(name);
        } catch (IllegalArgumentException error) {
            return switch (name) {
                case "MILITARY_SERGENT" -> OFFICER_LOCOTENENT;
                case "PROFESSIONAL_STAGIAR_SPECIALIST" -> PROFESSIONAL_ZILIER;
                case "PROFESSIONAL_SPECIALIST", "PROFESSIONAL_MAISTRU" -> PROFESSIONAL_MESTER;
                case "INSPECTOR" -> OFFICER_CAPITAN;
                default -> null;
            };
        }
    }

    /**
     * Pre-remodel names this grade answers to on role-ban surfaces, so a
     * checkpoint configured against the old ladder keeps matching migrated
     * records. Officers also still answer the MILITARY track name — their
     * pre-remodel track — handled by the caller.
     */
    public java.util.Set<String> legacyRoleNames() {
        return switch (this) {
            case PROFESSIONAL_ZILIER -> java.util.Set.of("PROFESSIONAL_STAGIAR_SPECIALIST");
            case PROFESSIONAL_MESTER -> java.util.Set.of("PROFESSIONAL_SPECIALIST", "PROFESSIONAL_MAISTRU");
            case OFFICER_LOCOTENENT -> java.util.Set.of("MILITARY_SERGENT");
            case OFFICER_CAPITAN -> java.util.Set.of("INSPECTOR");
            default -> java.util.Set.of();
        };
    }
}
