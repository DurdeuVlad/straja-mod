package com.dwurdy.straja.domain.model;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated registry of runtime-overridable policy keys. Each key maps a stable
 * {@code section.field} path to a {@link StrajaPolicies} field plus a value
 * kind. Overrides are strict: a malformed value fails instead of silently
 * falling back, so in-game edits can never corrupt the live rule set.
 *
 * <p>Pure domain: no YAML/file types — the store handles raw strings only.
 * Identity/security fields (commissioner identity, environment, deployment
 * gates, debug/test flags) are intentionally absent: they stay TOML-pinned.</p>
 */
public final class PolicyRegistry {
    private PolicyRegistry() {}

    public enum Kind { INT, BOOL, DOUBLE, STRING, INT_MAP, INT_STR_MAP, INT_LIST, STRING_LIST, QUIZ, KITS, ARMORY }

    public record Key(String path, String field, Kind kind) {}

    private static final Map<String, Key> KEYS = new LinkedHashMap<>();

    private static void k(String path, String field, Kind kind) {
        KEYS.put(path, new Key(path, field, kind));
    }

    static {
        // timers
        k("timers.checkpointUnlockMinutes", "checkpointUnlockMinutes", Kind.INT);
        k("timers.checkpointDeadlineMinutes", "checkpointDeadlineMinutes", Kind.INT);
        k("timers.serviceBlockMinutes", "serviceBlockMinutes", Kind.INT);
        k("timers.patrolMinCheckpoints", "patrolMinCheckpoints", Kind.INT);
        k("timers.patrolRounds", "patrolRounds", Kind.INT);
        k("timers.patrolMaxMinutes", "patrolMaxMinutes", Kind.INT);
        k("timers.patrolMaxCheckpoints", "patrolMaxCheckpoints", Kind.INT);
        k("timers.foodCooldownMinutes", "foodCooldownMinutes", Kind.INT);
        k("timers.quizCooldownMinutes", "quizCooldownMinutes", Kind.INT);
        k("timers.resignationCooldownDays", "resignationCooldownDays", Kind.INT);
        k("timers.resignationNoticeMinutes", "resignationNoticeMinutes", Kind.INT);
        // mission
        k("mission.minMinutes", "missionMinMinutes", Kind.INT);
        k("mission.maxMinutes", "missionMaxMinutes", Kind.INT);
        k("mission.maxScheduleDays", "missionMaxScheduleDays", Kind.INT);
        k("mission.maxReward", "missionMaxReward", Kind.INT);
        k("mission.maxCopiesPerDraft", "missionMaxCopiesPerDraft", Kind.INT);
        k("mission.maxRewardPool", "missionMaxRewardPool", Kind.INT);
        k("mission.maxRewardPerIssuerPerDay", "missionMaxRewardPerIssuerPerDay", Kind.INT);
        k("mission.maxActivePerPlayer", "missionMaxActivePerPlayer", Kind.INT);
        k("mission.defaultMinimumRank", "missionDefaultMinimumRank", Kind.INT);
        k("mission.rewardOverrideMargin", "missionRewardOverrideMargin", Kind.DOUBLE);
        k("mission.maxAssignees", "missionMaxAssignees", Kind.INT);
        k("mission.retentionLimit", "missionRetentionLimit", Kind.INT);
        k("mission.quickCreateEnabled", "missionQuickCreateEnabled", Kind.BOOL);
        // cuffs
        k("cuffs.requestTimeoutSeconds", "cuffRequestTimeoutSeconds", Kind.INT);
        k("cuffs.surrenderTimeoutSeconds", "surrenderTimeoutSeconds", Kind.INT);
        k("cuffs.slownessTicks", "cuffSlownessTicks", Kind.INT);
        k("cuffs.slownessAmplifier", "cuffSlownessAmplifier", Kind.INT);
        k("cuffs.actionLock", "cuffActionLock", Kind.BOOL);
        k("cuffs.hideHeldItem", "hideHeldItemWhenPossible", Kind.BOOL);
        k("cuffs.breakDistance", "cuffBreakDistance", Kind.DOUBLE);
        k("cuffs.breakGraceSeconds", "cuffBreakGraceSeconds", Kind.INT);
        k("cuffs.genericKeyTokens", "genericKeyTokens", Kind.STRING_LIST);
        // restraints
        k("restraints.ropeRequiresCuffs", "ropeRequiresCuffs", Kind.BOOL);
        k("restraints.ropeSlownessTicks", "ropeSlownessTicks", Kind.INT);
        k("restraints.ropeSlownessAmplifier", "ropeSlownessAmplifier", Kind.INT);
        k("restraints.headSackBlindnessTicks", "headSackBlindnessTicks", Kind.INT);
        k("restraints.actionLock", "restraintActionLock", Kind.BOOL);
        // downed
        k("downed.enabled", "downedEnabled", Kind.BOOL);
        k("downed.cooldownSeconds", "downedCooldownSeconds", Kind.INT);
        k("downed.freezeInPlace", "downedFreezeInPlace", Kind.BOOL);
        k("downed.actionLock", "downedActionLock", Kind.BOOL);
        k("downed.slownessTicks", "downedSlownessTicks", Kind.INT);
        k("downed.slownessAmplifier", "downedSlownessAmplifier", Kind.INT);
        k("downed.wakeHealthRatio", "downedWakeHealthRatio", Kind.DOUBLE);
        // arrestRewards
        k("arrestRewards.minimum", "arrestRewardMinimum", Kind.INT);
        k("arrestRewards.maximum", "arrestRewardMaximum", Kind.INT);
        k("arrestRewards.maxDailyPayout", "arrestMaxDailyPayout", Kind.INT);
        k("arrestRewards.minorDivider", "arrestMinorDivider", Kind.INT);
        k("arrestRewards.aliveMultiplier", "arrestAliveMultiplier", Kind.INT);
        k("arrestRewards.deathMultiplier", "arrestDeathMultiplier", Kind.INT);
        // jailer
        k("jailer.name", "jailerName", Kind.STRING);
        k("jailer.assaultWoundedAmount", "jailerAssaultWoundedAmount", Kind.INT);
        k("jailer.assaultKilledAmount", "jailerAssaultKilledAmount", Kind.INT);
        k("jailer.assaultSentenceDays", "jailerAssaultSentenceDays", Kind.INT);
        k("jailer.assaultMissionMaxAssignees", "jailerAssaultMissionMaxAssignees", Kind.INT);
        k("jailer.guardImmunity", "jailerGuardImmunity", Kind.BOOL);
        // salary — hourly wage (§10)
        k("salary.perHour", "salaryPerHour", Kind.INT_MAP);
        k("salary.commissionerPerHour", "salaryCommissionerPerHour", Kind.INT);
        k("salary.granularitySeconds", "salaryGranularitySeconds", Kind.INT);
        k("salary.maxPaidMinutesPerDay", "salaryMaxPaidMinutesPerDay", Kind.INT);
        k("salary.windowMinutes", "salaryWindowMinutes", Kind.INT);
        k("salary.activityGraceSeconds", "salaryActivityGraceSeconds", Kind.INT);
        k("salary.activityMoveThreshold", "salaryActivityMoveThreshold", Kind.DOUBLE);
        // promotion
        k("promotion.serviceBlocks", "promotionServiceBlocks", Kind.INT_MAP);
        k("promotion.bonusHours", "promotionBonusHours", Kind.INT);
        // merit — spendable requisition ledger (distinct from serviceBlocks)
        k("merit.requisitionPerBlock", "requisitionPointsPerBlock", Kind.INT);
        k("merit.demotionServiceBlockCost", "demotionServiceBlockCost", Kind.INT);
        k("merit.suspensionRequisitionCost", "suspensionRequisitionCost", Kind.INT);
        // rank display names (§2: Stagiar/Străjer/Sergent/Inspector)
        k("rank.names", "rankNames", Kind.INT_STR_MAP);
        // §11 activity reports
        k("reports.intervalDays", "reportIntervalDays", Kind.INT);
        k("reports.blockDutyWhenOverdue", "reportBlockDutyWhenOverdue", Kind.BOOL);
        k("audiences.notifyCooldownSeconds", "audienceNotifyCooldownSeconds", Kind.INT);
        // §4 rank prefix surfaces
        k("rank.prefix.chat", "rankPrefixChat", Kind.BOOL);
        k("rank.prefix.tab", "rankPrefixTab", Kind.BOOL);
        k("rank.prefix.nameplate", "rankPrefixNameplate", Kind.BOOL);
        k("rank.comisarTitle", "comisarTitle", Kind.STRING);
        // duty / faction
        k("duty.freeDutyMinRank", "freeDutyMinRank", Kind.INT);
        k("duty.nativeFactionMaxLength", "nativeFactionMaxLength", Kind.INT);
        k("duty.secretaryRadiusBlocks", "secretaryRadiusBlocks", Kind.INT);
        k("duty.captureDutyFaction", "captureDutyFaction", Kind.BOOL);
        k("duty.strajaTeamName", "strajaTeamName", Kind.STRING);
        // §25 emergency system
        k("emergency.urgencyTtlMinutes", "emergencyUrgencyTtlMinutes", Kind.INT);
        k("emergency.payMultiplier", "emergencyPayMultiplier", Kind.DOUBLE);
        k("emergency.maxPayMultiplier", "emergencyMaxPayMultiplier", Kind.DOUBLE);
        k("emergency.patrolRounds", "emergencyPatrolRounds", Kind.INT);
        k("emergency.maxPatrolRounds", "emergencyMaxPatrolRounds", Kind.INT);
        // quiz / trainer
        k("quiz.questions", "quiz", Kind.QUIZ);
        k("quiz.trainingQuestions", "trainingQuiz", Kind.QUIZ);
        k("trainer.manualItem", "trainingManualItem", Kind.STRING);
        // economy
        k("economy.coinItems", "coinItemIds", Kind.INT_STR_MAP);
        k("economy.foodItem", "foodItem", Kind.STRING);
        k("economy.foodAmount", "foodAmount", Kind.INT);
        // equipment
        k("equipment.kits", "kits", Kind.KITS);
        k("armory.stock", "armoryStock", Kind.ARMORY);
        k("armory.reserves", "armoryReserves", Kind.ARMORY);
        // envelope
        k("envelope.enabled", "envelopeEnabled", Kind.BOOL);
        k("envelope.fallbackToChat", "envelopeFallbackToChat", Kind.BOOL);
        k("envelope.maxBodyLength", "envelopeMaxBodyLength", Kind.INT);
        // archive
        k("archive.enabled", "archiveEnabled", Kind.BOOL);
        k("archive.commissionerAlways", "archiveCommissionerAlways", Kind.BOOL);
        k("archive.maxFoldersPerOwner", "archiveMaxFoldersPerOwner", Kind.INT);
        k("archive.maxSheetsPerFolder", "archiveMaxSheetsPerFolder", Kind.INT);
        k("archive.maxCatalogEntriesPerFolder", "archiveMaxCatalogEntriesPerFolder", Kind.INT);
        k("archive.maxTitleLength", "archiveMaxTitleLength", Kind.INT);
        k("archive.maxContentLength", "archiveMaxContentLength", Kind.INT);
        k("archive.maxRecipients", "archiveMaxRecipients", Kind.INT);
        k("archive.maxCopiesPerOperation", "archiveMaxCopiesPerOperation", Kind.INT);
        // rooms
        k("rooms.retentionLimit", "roomRetentionLimit", Kind.INT);
        k("rooms.minInteriorX", "roomMinInteriorX", Kind.INT);
        k("rooms.minInteriorY", "roomMinInteriorY", Kind.INT);
        k("rooms.minInteriorZ", "roomMinInteriorZ", Kind.INT);
        k("rooms.maxDimension", "roomMaxDimension", Kind.INT);
        k("rooms.maxBlocks", "roomMaxBlocks", Kind.INT);
        k("rooms.requireSingleDoor", "roomRequireSingleDoor", Kind.BOOL);
        k("rooms.waitlistRetentionDays", "roomWaitlistRetentionDays", Kind.INT);
        k("rooms.allowedAccessBlocks", "roomAllowedAccessBlocks", Kind.STRING_LIST);
        // fines
        k("fines.enabled", "finesEnabled", Kind.BOOL);
        k("fines.onlineDaysUntilEscalation", "fineOnlineDaysUntilEscalation", Kind.INT);
        k("fines.minecraftDayMinutes", "minecraftDayMinutes", Kind.INT);
        k("fines.allowedAmounts", "fineAllowedAmounts", Kind.INT_LIST);
        k("fines.maxLawLength", "fineMaxLawLength", Kind.INT);
        k("fines.maxDescriptionLength", "fineMaxDescriptionLength", Kind.INT);
        k("fines.paymentRequiresReception", "finePaymentRequiresReception", Kind.BOOL);
        k("fines.appealsEnabled", "appealsEnabled", Kind.BOOL);
        k("fines.appealDecisionTimeoutRealDays", "appealDecisionTimeoutRealDays", Kind.INT);
        k("fines.appealMaxReasonLength", "appealMaxReasonLength", Kind.INT);
        k("fines.appealMaxPerWindow", "appealMaxPerWindow", Kind.INT);
        k("fines.appealAbuseWindowRealDays", "appealAbuseWindowRealDays", Kind.INT);
        k("fines.appealAbuseBlockRealDays", "appealAbuseBlockRealDays", Kind.INT);
        k("fines.escalationMissionMinutes", "escalationMissionMinutes", Kind.INT);
        k("fines.sentenceDaysByAmount", "sentenceDaysByAmount", Kind.INT_MAP);
        // prison
        k("prison.enabled", "prisonEnabled", Kind.BOOL);
        k("prison.retentionLimit", "prisonRetentionLimit", Kind.INT);
        k("prison.maxCells", "prisonMaxCells", Kind.INT);
        k("prison.activeMinutesPerMinecraftDay", "prisonActiveMinutesPerMinecraftDay", Kind.INT);
        k("prison.defaultSentenceDays", "prisonDefaultSentenceDays", Kind.INT);
        k("prison.afkGraceSeconds", "prisonAfkGraceSeconds", Kind.INT);
        k("prison.maxSentenceDays", "prisonMaxSentenceDays", Kind.INT);
        k("prison.arrestRadius", "prisonArrestRadius", Kind.DOUBLE);
        // audit
        k("audit.enabled", "auditEnabled", Kind.BOOL);
        k("audit.retentionLimit", "auditRetentionLimit", Kind.INT);
        // complaints
        k("complaints.enabled", "complaintsEnabled", Kind.BOOL);
        k("complaints.maxDescriptionLength", "complaintMaxDescriptionLength", Kind.INT);
        k("complaints.maxEvidenceLength", "complaintMaxEvidenceLength", Kind.INT);
        k("complaints.maxActivePerComplainant", "complaintMaxActivePerComplainant", Kind.INT);
        k("complaints.maxParticipants", "complaintMaxParticipants", Kind.INT);
        k("complaints.defaultSeverity", "complaintDefaultSeverity", Kind.INT);
        k("complaints.rewardBySeverity", "complaintRewardBySeverity", Kind.INT_MAP);
        k("complaints.maxReward", "complaintMaxReward", Kind.INT);
        k("complaints.maxRewardPerReviewerPerDay", "complaintMaxRewardPerReviewerPerDay", Kind.INT);
    }

    public static Map<String, Key> keys() {
        return KEYS;
    }

    /** Applies {@code raw} to {@code policies}; strict — malformed input fails. */
    public static Result apply(StrajaPolicies policies, String path, String raw) {
        Key key = KEYS.get(path);
        if (key == null) return Result.fail("unknown_key");
        if (raw == null || raw.isBlank()) return Result.fail("empty_value");
        try {
            Field field = StrajaPolicies.class.getField(key.field());
            Object parsed = parse(key.kind(), raw.trim());
            // Collections mutate in place so adapters that captured the
            // reference at bootstrap (e.g. the coin provider) see the change.
            Object current = field.get(policies);
            if (current instanceof Map<?, ?> map && parsed instanceof Map<?, ?> next) {
                @SuppressWarnings("unchecked") Map<Object, Object> m = (Map<Object, Object>) map;
                m.clear(); m.putAll(next);
            } else if (current instanceof List<?> list && parsed instanceof List<?> next) {
                @SuppressWarnings("unchecked") List<Object> l = (List<Object>) list;
                l.clear(); l.addAll(next);
            } else {
                field.set(policies, parsed);
            }
            return Result.pass();
        } catch (IllegalArgumentException | ReflectiveOperationException e) {
            return Result.fail("invalid_value");
        }
    }

    /** Formats the live value for display/persistence (empty string on error). */
    public static String read(StrajaPolicies policies, String path) {
        Key key = KEYS.get(path);
        if (key == null) return "";
        try {
            return format(key.kind(), StrajaPolicies.class.getField(key.field()).get(policies));
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    // ------------------------------------------------------------ encoding

    /** Splits a {@code ;}-separated entry list (structured values use ';' so ',' stays free inside entries). */
    private static List<String> entries(String raw) {
        List<String> out = new ArrayList<>();
        for (String part : raw.split(";")) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    private static Object parse(Kind kind, String raw) {
        switch (kind) {
            case INT -> { return Integer.parseInt(raw); }
            case DOUBLE -> { return Double.parseDouble(raw); }
            case BOOL -> {
                if ("true".equalsIgnoreCase(raw)) return true;
                if ("false".equalsIgnoreCase(raw)) return false;
                throw new IllegalArgumentException("not a boolean");
            }
            case STRING -> { return raw; }
            case INT_LIST -> {
                List<Integer> out = new ArrayList<>();
                for (String e : entries(raw)) out.add(Integer.parseInt(e));
                return out;
            }
            case STRING_LIST -> { return entries(raw); }
            case INT_MAP -> {
                var list = entries(raw);
                var parsed = StrajaPolicies.parseIntMap(list);
                if (parsed.size() != list.size()) throw new IllegalArgumentException("malformed entries");
                return parsed;
            }
            case INT_STR_MAP -> {
                var list = entries(raw);
                var parsed = StrajaPolicies.parseIntStringMap(list);
                if (parsed.size() != list.size()) throw new IllegalArgumentException("malformed entries");
                return parsed;
            }
            case QUIZ -> {
                // Entries are exactly 4 pipe-separated fields and answers use
                // ';' internally — so split on '|' in groups of 4 rather than
                // on the entry separator other kinds use.
                String[] fields = raw.split("\\|", -1);
                if (fields.length % 4 != 0) throw new IllegalArgumentException("malformed quiz entries");
                List<String> list = new ArrayList<>();
                for (int i = 0; i + 3 < fields.length; i += 4) {
                    list.add(fields[i] + "|" + fields[i + 1] + "|" + fields[i + 2] + "|" + fields[i + 3]);
                }
                var parsed = StrajaPolicies.parseQuiz(list);
                if (parsed.size() != list.size()) throw new IllegalArgumentException("malformed entries");
                return parsed;
            }
            case KITS -> {
                var list = entries(raw);
                var parsed = StrajaPolicies.parseKits(list);
                if (countItems(parsed) != list.size()) throw new IllegalArgumentException("malformed entries");
                return parsed;
            }
            case ARMORY -> {
                var list = entries(raw);
                var parsed = StrajaPolicies.parseArmoryItems(list);
                if (parsed.size() != list.size()) throw new IllegalArgumentException("malformed entries");
                return parsed;
            }
            default -> throw new IllegalArgumentException("unknown kind");
        }
    }

    private static <T> int countItems(Map<Integer, List<T>> map) {
        int total = 0;
        for (var list : map.values()) total += list.size();
        return total;
    }

    private static String format(Kind kind, Object value) {
        switch (kind) {
            case INT, DOUBLE, BOOL, STRING -> { return String.valueOf(value); }
            case INT_LIST, STRING_LIST -> { return ((List<?>) value).stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(";")); }
            case INT_MAP -> { return String.join(";", StrajaPolicies.formatIntMap(castMap(value))); }
            case INT_STR_MAP -> { return String.join(";", StrajaPolicies.formatIntStringMap(castMap(value))); }
            case QUIZ -> { return String.join(";", StrajaPolicies.formatQuiz(castList(value))); }
            case KITS -> { return String.join(";", StrajaPolicies.formatKits(castMap(value))); }
            case ARMORY -> { return String.join(";", StrajaPolicies.formatArmoryItems(castList(value))); }
            default -> { return ""; }
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> castMap(Object value) { return (Map<K, V>) value; }

    @SuppressWarnings("unchecked")
    private static <T> List<T> castList(Object value) { return (List<T>) value; }
}
