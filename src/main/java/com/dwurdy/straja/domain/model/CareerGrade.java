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
    MILITARY_STAGIAR(CareerTrack.MILITARY, false, "Stagiar"),
    MILITARY_STRAJER(CareerTrack.MILITARY, false, "Străjer"),
    PROFESSIONAL_ZILIER(CareerTrack.PROFESSIONAL, false, "Zilier"),
    PROFESSIONAL_MESTER(CareerTrack.PROFESSIONAL, false, "Meșter"),
    OFFICER_LOCOTENENT(CareerTrack.OFFICER, true, "Locotenent"),
    OFFICER_CAPITAN(CareerTrack.OFFICER, true, "Căpitan"),
    OFFICER_COLONEL(CareerTrack.OFFICER, true, "Colonel");

    private final CareerTrack track;
    private final boolean fullTimeRequired;
    private final String displayName;

    CareerGrade(CareerTrack track, boolean fullTimeRequired, String displayName) {
        this.track = track;
        this.fullTimeRequired = fullTimeRequired;
        this.displayName = displayName;
    }

    public CareerTrack track() { return track; }
    public boolean fullTimeRequired() { return fullTimeRequired; }
    public boolean isProfessional() { return track == CareerTrack.PROFESSIONAL; }
    public boolean isOfficer() { return track == CareerTrack.OFFICER; }

    /** Static fallback name — prefer {@code StrajaPolicies.rankName(grade)} for display. */
    public String displayName() { return displayName; }

    /**
     * The one bridge from the legacy {@link Rank} ladder into V2 grades.
     * Returns null for CIVIL and unknown levels so callers decide their own
     * default (projection tolerates CIVIL; promotion refuses it).
     */
    public static CareerGrade fromLegacyRank(Rank rank) {
        if (rank == null) return null;
        return switch (rank) {
            case STAGIAR -> MILITARY_STAGIAR;
            case GUARD -> MILITARY_STRAJER;
            case LOCOTENENT -> OFFICER_LOCOTENENT;
            case CAPITAN -> OFFICER_CAPITAN;
            case COLONEL -> OFFICER_COLONEL;
            case CIVIL -> null;
        };
    }

    /**
     * The inverse bridge: this grade's level on the legacy {@link Rank}
     * ladder. Trades grades read as CIVIL — the V1 ladder has no trades rung.
     */
    public int legacyRankLevel() {
        return switch (this) {
            case MILITARY_STAGIAR -> Rank.STAGIAR.level();
            case MILITARY_STRAJER -> Rank.GUARD.level();
            case OFFICER_LOCOTENENT -> Rank.LOCOTENENT.level();
            case OFFICER_CAPITAN -> Rank.CAPITAN.level();
            case OFFICER_COLONEL -> Rank.COLONEL.level();
            case PROFESSIONAL_ZILIER, PROFESSIONAL_MESTER -> Rank.CIVIL.level();
        };
    }

    /**
     * Next petitionable grade, or null at the top of the ladder. The two
     * part-time tracks both feed OFFICER_LOCOTENENT; officers climb
     * LT → CPT → COL. Single source of truth for promotion transitions.
     */
    public CareerGrade nextGrade() {
        return switch (this) {
            case MILITARY_STAGIAR -> MILITARY_STRAJER;
            case MILITARY_STRAJER -> OFFICER_LOCOTENENT;
            case PROFESSIONAL_ZILIER -> PROFESSIONAL_MESTER;
            case PROFESSIONAL_MESTER -> OFFICER_LOCOTENENT;
            case OFFICER_LOCOTENENT -> OFFICER_CAPITAN;
            case OFFICER_CAPITAN -> OFFICER_COLONEL;
            case OFFICER_COLONEL -> null;
        };
    }

    /**
     * The grade's rung on its career ladder, shared across tracks: both
     * entry grades are step 1, both second grades are step 2, officers
     * climb from 3. Promotion XP thresholds key off this so the two
     * part-time tracks share one policy table.
     */
    public int careerStep() {
        return switch (this) {
            case MILITARY_STAGIAR, PROFESSIONAL_ZILIER -> 1;
            case MILITARY_STRAJER, PROFESSIONAL_MESTER -> 2;
            case OFFICER_LOCOTENENT -> 3;
            case OFFICER_CAPITAN -> 4;
            case OFFICER_COLONEL -> 5;
        };
    }

    /**
     * Parses a user-typed grade token — enum names plus the friendly chat
     * aliases — after diacritic/case normalization. The single vocabulary for
     * grade names accepted from free text.
     */
    public static CareerGrade parse(String token) {
        if (token == null) return null;
        String normalized = java.text.Normalizer
                .normalize(token.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(java.util.Locale.ROOT)
                .replace(' ', '_').replace('-', '_');
        CareerGrade parsed = of(normalized);
        if (parsed != null) return parsed;
        return switch (normalized) {
            case "STAGIAR" -> MILITARY_STAGIAR;
            case "STRAJER", "GENDARME", "JANDAR" -> MILITARY_STRAJER;
            case "ZILIER", "ZILER", "DAYMAN" -> PROFESSIONAL_ZILIER;
            case "MESTER", "MESERIAS", "TRADESMAN", "MAISTRU", "MASTER" -> PROFESSIONAL_MESTER;
            case "LOCOTENENT", "LIEUTENANT", "SERGENT", "SERGEANT" -> OFFICER_LOCOTENENT;
            case "CAPITAN", "CAPTAIN", "INSPECTOR" -> OFFICER_CAPITAN;
            case "COLONEL" -> OFFICER_COLONEL;
            default -> null;
        };
    }

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
