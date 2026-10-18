package com.dwurdy.straja.domain.model;

/**
 * Career ladder: two part-time tracks feed a shared full-time officer
 * corps, matching the announced distribution — enlisted (auto-join by
 * quiz) STAGIAR → STRAJER → SERGEANT; trades (petition-join) ZILIER →
 * MESERIAS → MESTER ("Maistru"); officers (petition-promote, full-time)
 * LOCOTENENT → CAPITAN → MAIOR → COLONEL. All officer grades share the
 * same broad mechanical authority — specialties are RP flavor via
 * professions and rankName, not capability gates.
 *
 * <p>Persisted by enum name in Gson stores; {@link #of(String)} maps the
 * pre-remodel names so old saves read forward instead of failing closed
 * into corrupt_backup. The third enlisted rung is named SERGEANT rather
 * than SERGENT specifically so the persisted name MILITARY_SERGENT keeps
 * resolving to the pre-remodel officer tier (LOCOTENENT).
 */
public enum CareerGrade {
    MILITARY_STAGIAR(CareerTrack.MILITARY, false, "Stagiar"),
    MILITARY_STRAJER(CareerTrack.MILITARY, false, "Străjer"),
    MILITARY_SERGEANT(CareerTrack.MILITARY, false, "Sergent"),
    PROFESSIONAL_ZILIER(CareerTrack.PROFESSIONAL, false, "Zilier"),
    PROFESSIONAL_MESERIAS(CareerTrack.PROFESSIONAL, false, "Meseriaș"),
    PROFESSIONAL_MESTER(CareerTrack.PROFESSIONAL, false, "Maistru"),
    OFFICER_LOCOTENENT(CareerTrack.OFFICER, true, "Locotenent"),
    OFFICER_CAPITAN(CareerTrack.OFFICER, true, "Căpitan"),
    OFFICER_MAIOR(CareerTrack.OFFICER, true, "Maior"),
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
     * ladder. Trades grades read as CIVIL — the V1 ladder has no trades
     * rung — and the added rungs project conservatively onto the nearest
     * lower rung the V1 ladder can express (SERGEANT → GUARD, MAIOR →
     * CAPITAN); grade-aware surfaces distinguish what the projection cannot.
     */
    public int legacyRankLevel() {
        return switch (this) {
            case MILITARY_STAGIAR -> Rank.STAGIAR.level();
            case MILITARY_STRAJER, MILITARY_SERGEANT -> Rank.GUARD.level();
            case OFFICER_LOCOTENENT -> Rank.LOCOTENENT.level();
            case OFFICER_CAPITAN, OFFICER_MAIOR -> Rank.CAPITAN.level();
            case OFFICER_COLONEL -> Rank.COLONEL.level();
            case PROFESSIONAL_ZILIER, PROFESSIONAL_MESERIAS,
                    PROFESSIONAL_MESTER -> Rank.CIVIL.level();
        };
    }

    /**
     * Next petitionable grade, or null at the top of the ladder. The two
     * part-time tracks both feed OFFICER_LOCOTENENT; officers climb
     * LT → CPT → MAIOR → COL. Single source of truth for promotion
     * transitions.
     */
    public CareerGrade nextGrade() {
        return switch (this) {
            case MILITARY_STAGIAR -> MILITARY_STRAJER;
            case MILITARY_STRAJER -> MILITARY_SERGEANT;
            case MILITARY_SERGEANT -> OFFICER_LOCOTENENT;
            case PROFESSIONAL_ZILIER -> PROFESSIONAL_MESERIAS;
            case PROFESSIONAL_MESERIAS -> PROFESSIONAL_MESTER;
            case PROFESSIONAL_MESTER -> OFFICER_LOCOTENENT;
            case OFFICER_LOCOTENENT -> OFFICER_CAPITAN;
            case OFFICER_CAPITAN -> OFFICER_MAIOR;
            case OFFICER_MAIOR -> OFFICER_COLONEL;
            case OFFICER_COLONEL -> null;
        };
    }

    /**
     * Previous grade on the same ladder, or null at an entry rung. Returns
     * null for OFFICER_LOCOTENENT: its demotion target depends on the
     * member's entry-track origin and is resolved by PersonnelService.
     */
    public CareerGrade previousGrade() {
        return switch (this) {
            case MILITARY_STRAJER -> MILITARY_STAGIAR;
            case MILITARY_SERGEANT -> MILITARY_STRAJER;
            case PROFESSIONAL_MESERIAS -> PROFESSIONAL_ZILIER;
            case PROFESSIONAL_MESTER -> PROFESSIONAL_MESERIAS;
            case OFFICER_CAPITAN -> OFFICER_LOCOTENENT;
            case OFFICER_MAIOR -> OFFICER_CAPITAN;
            case OFFICER_COLONEL -> OFFICER_MAIOR;
            case MILITARY_STAGIAR, PROFESSIONAL_ZILIER, OFFICER_LOCOTENENT -> null;
        };
    }

    /**
     * The grade's rung on its career ladder, shared across tracks: entry
     * grades are step 1, second grades step 2, third grades step 3, and
     * the officer corps climbs steps 4–7. Promotion XP thresholds and the
     * exam bank key off this so the two part-time tracks share one policy
     * table and each soldier step draws a deeper pool than the last.
     */
    public int careerStep() {
        return switch (this) {
            case MILITARY_STAGIAR, PROFESSIONAL_ZILIER -> 1;
            case MILITARY_STRAJER, PROFESSIONAL_MESERIAS -> 2;
            case MILITARY_SERGEANT, PROFESSIONAL_MESTER -> 3;
            case OFFICER_LOCOTENENT -> 4;
            case OFFICER_CAPITAN -> 5;
            case OFFICER_MAIOR -> 6;
            case OFFICER_COLONEL -> 7;
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
            case "SERGENT", "SERGEANT" -> MILITARY_SERGEANT;
            case "ZILIER", "ZILER", "DAYMAN" -> PROFESSIONAL_ZILIER;
            case "MESERIAS" -> PROFESSIONAL_MESERIAS;
            case "MESTER", "TRADESMAN", "MAISTRU", "MASTER" -> PROFESSIONAL_MESTER;
            case "LOCOTENENT", "LIEUTENANT" -> OFFICER_LOCOTENENT;
            case "CAPITAN", "CAPTAIN", "INSPECTOR" -> OFFICER_CAPITAN;
            case "MAIOR", "MAJOR" -> OFFICER_MAIOR;
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
