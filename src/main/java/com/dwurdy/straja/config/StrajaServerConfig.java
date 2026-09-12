package com.dwurdy.straja.config;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;
import java.util.List;

/**
 * Server-side configuration for Straja. Mirrors the authoritative values from
 * the reference KubeJS 00_straja_config.js. World-dependent values
 * (checkpoints, locations) live in SavedData, not here.
 */
public final class StrajaServerConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> COMMISSIONER_NAME;
    public static final ModConfigSpec.ConfigValue<String> COMMISSIONER_UUID;
    public static final ModConfigSpec.BooleanValue REQUIRE_UUID;
    public static final ModConfigSpec.BooleanValue ALLOW_NAME_FALLBACK;
    public static final ModConfigSpec.ConfigValue<String> ENVIRONMENT;

    public static final ModConfigSpec.IntValue CHECKPOINT_UNLOCK_MINUTES;
    public static final ModConfigSpec.IntValue CHECKPOINT_DEADLINE_MINUTES;
    public static final ModConfigSpec.IntValue SALARY_BLOCK_MINUTES;
    public static final ModConfigSpec.IntValue FOOD_COOLDOWN_MINUTES;
    public static final ModConfigSpec.IntValue QUIZ_COOLDOWN_MINUTES;
    public static final ModConfigSpec.IntValue RESIGNATION_COOLDOWN_DAYS;
    public static final ModConfigSpec.IntValue RESIGNATION_NOTICE_MINUTES;

    public static final ModConfigSpec.IntValue MISSION_MIN_MINUTES;
    public static final ModConfigSpec.IntValue MISSION_MAX_MINUTES;
    public static final ModConfigSpec.IntValue MISSION_MAX_SCHEDULE_DAYS;
    public static final ModConfigSpec.IntValue MISSION_MAX_REWARD;
    public static final ModConfigSpec.IntValue MISSION_MAX_COPIES_PER_DRAFT;
    public static final ModConfigSpec.IntValue MISSION_MAX_REWARD_POOL;
    public static final ModConfigSpec.IntValue MISSION_MAX_REWARD_PER_ISSUER_PER_DAY;
    public static final ModConfigSpec.IntValue MISSION_MAX_ACTIVE_PER_PLAYER;
    public static final ModConfigSpec.IntValue MISSION_DEFAULT_MIN_RANK;
    public static final ModConfigSpec.IntValue MISSION_MAX_ASSIGNEES;
    public static final ModConfigSpec.IntValue MISSION_RETENTION_LIMIT;
    public static final ModConfigSpec.BooleanValue MISSION_QUICK_CREATE_ENABLED;
    public static final ModConfigSpec.BooleanValue MISSION_QUICK_CREATE_LOCAL_ONLY;

    public static final ModConfigSpec.IntValue CUFF_REQUEST_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue SURRENDER_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue CUFF_SLOWNESS_TICKS;
    public static final ModConfigSpec.IntValue CUFF_SLOWNESS_AMPLIFIER;
    public static final ModConfigSpec.IntValue CUFF_BREAK_DISTANCE;
    public static final ModConfigSpec.IntValue CUFF_BREAK_GRACE_SECONDS;
    public static final ModConfigSpec.BooleanValue CUFF_ACTION_LOCK;
    public static final ModConfigSpec.BooleanValue HIDE_HELD_ITEM_WHEN_POSSIBLE;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> GENERIC_KEY_TOKENS;

    public static final ModConfigSpec.IntValue ROPE_SLOWNESS_TICKS;
    public static final ModConfigSpec.IntValue ROPE_SLOWNESS_AMPLIFIER;
    public static final ModConfigSpec.IntValue HEAD_SACK_BLINDNESS_TICKS;
    public static final ModConfigSpec.BooleanValue ROPE_REQUIRES_CUFFS;
    public static final ModConfigSpec.BooleanValue RESTRAINT_ACTION_LOCK;

    public static final ModConfigSpec.BooleanValue DOWNED_ENABLED;
    public static final ModConfigSpec.IntValue DOWNED_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue DOWNED_SLOWNESS_TICKS;
    public static final ModConfigSpec.IntValue DOWNED_SLOWNESS_AMPLIFIER;
    public static final ModConfigSpec.DoubleValue DOWNED_WAKE_HEALTH_RATIO;
    public static final ModConfigSpec.BooleanValue DOWNED_FREEZE_IN_PLACE;
    public static final ModConfigSpec.BooleanValue DOWNED_ACTION_LOCK;

    public static final ModConfigSpec.IntValue ARREST_REWARD_MIN;
    public static final ModConfigSpec.IntValue ARREST_REWARD_MAX;
    public static final ModConfigSpec.IntValue ARREST_REWARD_MAX_DAILY;
    public static final ModConfigSpec.IntValue ARREST_MINOR_DIVIDER;
    public static final ModConfigSpec.IntValue ARREST_ALIVE_MULTIPLIER;
    public static final ModConfigSpec.IntValue ARREST_DEATH_MULTIPLIER;

    public static final ModConfigSpec.ConfigValue<String> COIN_BRONZE_ITEM;
    public static final ModConfigSpec.ConfigValue<String> COIN_BRASS_ITEM;
    public static final ModConfigSpec.ConfigValue<String> COIN_SILVER_ITEM;
    public static final ModConfigSpec.ConfigValue<String> COIN_GOLD_ITEM;

    public static final ModConfigSpec.IntValue SALARY_MAX_BLOCKS_PER_DAY;
    public static final ModConfigSpec.IntValue SALARY_WINDOW_MINUTES;
    public static final ModConfigSpec.IntValue SALARY_ACTIVITY_GRACE_SECONDS;
    public static final ModConfigSpec.DoubleValue SALARY_ACTIVITY_MOVE_THRESHOLD;

    public static final ModConfigSpec.IntValue SERVICE_LEASE_MINUTES;
    public static final ModConfigSpec.BooleanValue REQUIRE_REAL_COIN_PROVIDER_OUTSIDE_LOCAL;
    public static final ModConfigSpec.BooleanValue REQUIRE_COMMISSIONER_UUID_OUTSIDE_LOCAL;
    public static final ModConfigSpec.BooleanValue REQUIRE_DEBUG_DISABLED_OUTSIDE_LOCAL;

    public static final ModConfigSpec.BooleanValue ENVELOPE_ENABLED;
    public static final ModConfigSpec.BooleanValue ENVELOPE_FALLBACK_TO_CHAT;
    public static final ModConfigSpec.IntValue ENVELOPE_MAX_BODY_LENGTH;

    public static final ModConfigSpec.BooleanValue ARCHIVE_ENABLED;
    public static final ModConfigSpec.BooleanValue ARCHIVE_COMMISSIONER_ALWAYS;
    public static final ModConfigSpec.IntValue ARCHIVE_MAX_FOLDERS_PER_OWNER;
    public static final ModConfigSpec.IntValue ARCHIVE_MAX_SHEETS_PER_FOLDER;
    public static final ModConfigSpec.IntValue ARCHIVE_MAX_CATALOG_PER_FOLDER;
    public static final ModConfigSpec.IntValue ARCHIVE_MAX_TITLE_LENGTH;
    public static final ModConfigSpec.IntValue ARCHIVE_MAX_CONTENT_LENGTH;
    public static final ModConfigSpec.IntValue ARCHIVE_MAX_RECIPIENTS;
    public static final ModConfigSpec.IntValue ARCHIVE_MAX_COPIES_PER_OPERATION;

    public static final ModConfigSpec.IntValue ROOM_RETENTION_LIMIT;
    public static final ModConfigSpec.IntValue ROOM_MAX_DIMENSION;
    public static final ModConfigSpec.IntValue ROOM_MAX_BLOCKS;
    public static final ModConfigSpec.IntValue ROOM_WAITLIST_RETENTION_DAYS;

    public static final ModConfigSpec.BooleanValue FINES_ENABLED;
    public static final ModConfigSpec.BooleanValue APPEALS_ENABLED;
    public static final ModConfigSpec.IntValue FINE_ONLINE_DAYS_UNTIL_ESCALATION;
    public static final ModConfigSpec.IntValue FINE_MINECRAFT_DAY_MINUTES;
    public static final ModConfigSpec.IntValue FINE_MAX_LAW_LENGTH;
    public static final ModConfigSpec.IntValue FINE_MAX_DESCRIPTION_LENGTH;
    public static final ModConfigSpec.BooleanValue FINE_PAYMENT_REQUIRES_RECEPTION;
    public static final ModConfigSpec.IntValue APPEAL_DECISION_TIMEOUT_REAL_DAYS;
    public static final ModConfigSpec.IntValue APPEAL_MAX_REASON_LENGTH;
    public static final ModConfigSpec.IntValue APPEAL_MAX_PER_WINDOW;
    public static final ModConfigSpec.IntValue APPEAL_ABUSE_WINDOW_DAYS;
    public static final ModConfigSpec.IntValue APPEAL_ABUSE_BLOCK_DAYS;
    public static final ModConfigSpec.IntValue FINE_ESCALATION_MISSION_MINUTES;

    public static final ModConfigSpec.BooleanValue PRISON_ENABLED;
    public static final ModConfigSpec.IntValue PRISON_MAX_CELLS;
    public static final ModConfigSpec.IntValue PRISON_ACTIVE_MINUTES_PER_DAY;
    public static final ModConfigSpec.IntValue PRISON_DEFAULT_SENTENCE_DAYS;
    public static final ModConfigSpec.IntValue PRISON_AFK_GRACE_SECONDS;
    public static final ModConfigSpec.IntValue PRISON_MAX_SENTENCE_DAYS;
    public static final ModConfigSpec.IntValue PRISON_ARREST_RADIUS;
    public static final ModConfigSpec.IntValue PRISON_RETENTION_LIMIT;

    public static final ModConfigSpec.BooleanValue AUDIT_ENABLED;
    public static final ModConfigSpec.IntValue AUDIT_RETENTION_LIMIT;

    public static final ModConfigSpec.BooleanValue COMPLAINTS_ENABLED;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_DESCRIPTION_LENGTH;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_EVIDENCE_LENGTH;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_ACTIVE_PER_COMPLAINANT;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_PARTICIPANTS;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_REWARD;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_REWARD_PER_REVIEWER_PER_DAY;

    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue DEBUG_LOCAL_ONLY;
    public static final ModConfigSpec.BooleanValue DEBUG_REVEAL_QUIZ_ANSWERS;
    public static final ModConfigSpec.BooleanValue DEBUG_ALLOW_GRANT_RANK;
    public static final ModConfigSpec.BooleanValue DEBUG_ALLOW_COMMISSIONER_OVERRIDE;
    public static final ModConfigSpec.BooleanValue TEST_COMMANDS_ENABLED;

    public static final ModConfigSpec SPEC;

    static {
        B.push("identity");
        COMMISSIONER_NAME = B.comment("Configured commissioner account name (local fallback only).")
                .define("commissionerName", "dwurdy");
        COMMISSIONER_UUID = B.comment("Canonical commissioner UUID. Required outside local environments.")
                .define("commissionerUuid", "");
        REQUIRE_UUID = B.define("requireUuid", false);
        ALLOW_NAME_FALLBACK = B.define("allowNameFallback", true);
        ENVIRONMENT = B.comment("local | staging | production").define("environment", "local");
        B.pop();

        B.push("timers");
        CHECKPOINT_UNLOCK_MINUTES = B.defineInRange("checkpointUnlockMinutes", 10, 1, 1440);
        CHECKPOINT_DEADLINE_MINUTES = B.defineInRange("checkpointDeadlineMinutes", 30, 1, 1440);
        SALARY_BLOCK_MINUTES = B.defineInRange("salaryBlockMinutes", 10, 1, 60);
        FOOD_COOLDOWN_MINUTES = B.defineInRange("foodCooldownMinutes", 30, 1, 1440);
        QUIZ_COOLDOWN_MINUTES = B.defineInRange("quizCooldownMinutes", 10, 0, 1440);
        RESIGNATION_COOLDOWN_DAYS = B.defineInRange("resignationCooldownDays", 7, 0, 365);
        RESIGNATION_NOTICE_MINUTES = B.defineInRange("resignationNoticeMinutes", 15, 0, 1440);
        B.pop();

        B.push("mission");
        MISSION_MIN_MINUTES = B.defineInRange("minMinutes", 1, 1, 10080);
        MISSION_MAX_MINUTES = B.defineInRange("maxMinutes", 240, 1, 10080);
        MISSION_MAX_SCHEDULE_DAYS = B.defineInRange("maxScheduleDays", 7, 0, 365);
        MISSION_MAX_REWARD = B.defineInRange("maxReward", 250, 0, 1000000);
        MISSION_MAX_COPIES_PER_DRAFT = B.defineInRange("maxCopiesPerDraft", 4, 1, 64);
        MISSION_MAX_REWARD_POOL = B.defineInRange("maxRewardPool", 1000, 0, 1000000);
        MISSION_MAX_REWARD_PER_ISSUER_PER_DAY = B.defineInRange("maxRewardPerIssuerPerDay", 1000, 0, 1000000);
        MISSION_MAX_ACTIVE_PER_PLAYER = B.defineInRange("maxActivePerPlayer", 3, 1, 64);
        MISSION_DEFAULT_MIN_RANK = B.defineInRange("defaultMinimumRank", 1, 0, 4);
        MISSION_MAX_ASSIGNEES = B.defineInRange("maxAssignees", 4, 1, 64);
        MISSION_RETENTION_LIMIT = B.defineInRange("retentionLimit", 200, 1, 10000);
        MISSION_QUICK_CREATE_ENABLED = B.comment("Enables /straja mission create (one-step secretary issue).")
                .define("quickCreateEnabled", true);
        MISSION_QUICK_CREATE_LOCAL_ONLY = B.comment("Restricts quick create to the local environment.")
                .define("quickCreateLocalOnly", true);
        B.pop();

        B.push("cuffs");
        CUFF_REQUEST_TIMEOUT_SECONDS = B.defineInRange("requestTimeoutSeconds", 60, 5, 600);
        SURRENDER_TIMEOUT_SECONDS = B.defineInRange("surrenderTimeoutSeconds", 15, 1, 300);
        CUFF_SLOWNESS_TICKS = B.defineInRange("slownessTicks", 40, 1, 1200);
        CUFF_SLOWNESS_AMPLIFIER = B.defineInRange("slownessAmplifier", 0, 0, 255);
        CUFF_BREAK_DISTANCE = B.defineInRange("breakDistance", 32, 4, 256);
        CUFF_BREAK_GRACE_SECONDS = B.defineInRange("breakGraceSeconds", 10, 0, 300);
        CUFF_ACTION_LOCK = B.define("actionLock", true);
        HIDE_HELD_ITEM_WHEN_POSSIBLE = B.define("hideHeldItemWhenPossible", true);
        GENERIC_KEY_TOKENS = B.defineListAllowEmpty(List.of("genericKeyTokens"),
                List.of("key", "keycard", "lockpick"), () -> "", o -> o instanceof String);
        B.pop();

        B.push("restraints");
        ROPE_SLOWNESS_TICKS = B.defineInRange("ropeSlownessTicks", 40, 1, 1200);
        ROPE_SLOWNESS_AMPLIFIER = B.defineInRange("ropeSlownessAmplifier", 1, 0, 255);
        HEAD_SACK_BLINDNESS_TICKS = B.defineInRange("headSackBlindnessTicks", 40, 1, 1200);
        ROPE_REQUIRES_CUFFS = B.define("ropeRequiresCuffs", true);
        RESTRAINT_ACTION_LOCK = B.define("actionLock", true);
        B.pop();

        B.push("downed");
        DOWNED_ENABLED = B.define("enabled", true);
        DOWNED_COOLDOWN_SECONDS = B.defineInRange("cooldownSeconds", 60, 1, 3600);
        DOWNED_SLOWNESS_TICKS = B.defineInRange("slownessTicks", 40, 1, 1200);
        DOWNED_SLOWNESS_AMPLIFIER = B.defineInRange("slownessAmplifier", 255, 0, 255);
        DOWNED_WAKE_HEALTH_RATIO = B.defineInRange("wakeHealthRatio", 0.5, 0.0, 1.0);
        DOWNED_FREEZE_IN_PLACE = B.define("freezeInPlace", true);
        DOWNED_ACTION_LOCK = B.define("actionLock", true);
        B.pop();

        B.push("arrestRewards");
        ARREST_REWARD_MIN = B.defineInRange("minimum", 25, 0, 1000000);
        ARREST_REWARD_MAX = B.defineInRange("maximum", 1000, 0, 1000000);
        ARREST_REWARD_MAX_DAILY = B.defineInRange("maxDailyPayout", 1000, 0, 1000000);
        ARREST_MINOR_DIVIDER = B.defineInRange("minorDivider", 5, 1, 100);
        ARREST_ALIVE_MULTIPLIER = B.defineInRange("aliveMultiplier", 4, 1, 100);
        ARREST_DEATH_MULTIPLIER = B.defineInRange("deathMultiplier", 1, 0, 100);
        B.pop();

        B.push("economy");
        COIN_BRONZE_ITEM = B.comment(
                        "Item IDs for the four coin denominations (values 1, 10, 100, 1000).",
                        "Any mod's items work — the coin provider is unavailable until all",
                        "four IDs resolve in the item registry. Defaults use Ady's Decorations.")
                .define("bronzeCoin", "adys_decorations:bronze_coin", StrajaServerConfig::isItemId);
        COIN_BRASS_ITEM = B.define("brassCoin", "adys_decorations:brass_coin", StrajaServerConfig::isItemId);
        COIN_SILVER_ITEM = B.define("silverCoin", "adys_decorations:silver_coin", StrajaServerConfig::isItemId);
        COIN_GOLD_ITEM = B.define("goldCoin", "adys_decorations:gold_coin", StrajaServerConfig::isItemId);
        B.pop();

        B.push("salary");
        SALARY_MAX_BLOCKS_PER_DAY = B.defineInRange("maxBlocksPerDay", 24, 0, 10000);
        SALARY_WINDOW_MINUTES = B.defineInRange("windowMinutes", 1440, 1, 100000);
        SALARY_ACTIVITY_GRACE_SECONDS = B.defineInRange("activityGraceSeconds", 90, 5, 3600);
        SALARY_ACTIVITY_MOVE_THRESHOLD = B.defineInRange("activityMoveThreshold", 0.15, 0.0, 10.0);
        B.pop();

        B.push("security");
        SERVICE_LEASE_MINUTES = B.defineInRange("serviceLeaseMinutes", 240, 1, 10080);
        REQUIRE_REAL_COIN_PROVIDER_OUTSIDE_LOCAL = B.comment(
                        "Outside local, an unavailable coin provider is logged as a failed",
                        "deployment gate at startup; coin operations fail closed regardless.")
                .define("requireRealCoinProviderOutsideLocal", true);
        REQUIRE_COMMISSIONER_UUID_OUTSIDE_LOCAL = B.comment(
                        "Outside local, the commissioner is matched by the configured UUID only —",
                        "never by name; without a pinned UUID no commissioner exists.")
                .define("requireCommissionerUuidOutsideLocal", true);
        REQUIRE_DEBUG_DISABLED_OUTSIDE_LOCAL = B.comment(
                        "Outside local, debug commands stay disabled while this gate is on,",
                        "regardless of debug.enabled/debug.localOnly.")
                .define("requireDebugDisabledOutsideLocal", true);
        B.pop();

        B.push("envelope");
        ENVELOPE_ENABLED = B.define("enabled", true);
        ENVELOPE_FALLBACK_TO_CHAT = B.define("fallbackToChat", true);
        ENVELOPE_MAX_BODY_LENGTH = B.defineInRange("maxBodyLength", 900, 1, 32000);
        B.pop();

        B.push("archive");
        ARCHIVE_ENABLED = B.define("enabled", true);
        ARCHIVE_COMMISSIONER_ALWAYS = B.define("commissionerAlways", true);
        ARCHIVE_MAX_FOLDERS_PER_OWNER = B.defineInRange("maxFoldersPerOwner", 10, 1, 1000);
        ARCHIVE_MAX_SHEETS_PER_FOLDER = B.defineInRange("maxSheetsPerFolder", 100, 1, 10000);
        ARCHIVE_MAX_CATALOG_PER_FOLDER = B.defineInRange("maxCatalogEntriesPerFolder", 200, 1, 10000);
        ARCHIVE_MAX_TITLE_LENGTH = B.defineInRange("maxTitleLength", 80, 1, 1000);
        ARCHIVE_MAX_CONTENT_LENGTH = B.defineInRange("maxContentLength", 4000, 1, 100000);
        ARCHIVE_MAX_RECIPIENTS = B.defineInRange("maxRecipients", 8, 1, 100);
        ARCHIVE_MAX_COPIES_PER_OPERATION = B.defineInRange("maxCopiesPerOperation", 16, 1, 64);
        B.pop();

        B.push("rooms");
        ROOM_RETENTION_LIMIT = B.defineInRange("retentionLimit", 32, 1, 1024);
        ROOM_MAX_DIMENSION = B.defineInRange("maxDimension", 8, 1, 64);
        ROOM_MAX_BLOCKS = B.defineInRange("maxBlocks", 512, 1, 65536);
        ROOM_WAITLIST_RETENTION_DAYS = B.defineInRange("waitlistRetentionDays", 30, 1, 365);
        B.pop();

        B.push("fines");
        FINES_ENABLED = B.define("enabled", true);
        APPEALS_ENABLED = B.define("appealsEnabled", true);
        FINE_ONLINE_DAYS_UNTIL_ESCALATION = B.defineInRange("onlineDaysUntilEscalation", 14, 1, 365);
        FINE_MINECRAFT_DAY_MINUTES = B.defineInRange("minecraftDayMinutes", 20, 1, 1440);
        FINE_MAX_LAW_LENGTH = B.defineInRange("maxLawLength", 80, 1, 1000);
        FINE_MAX_DESCRIPTION_LENGTH = B.defineInRange("maxDescriptionLength", 240, 1, 4000);
        FINE_PAYMENT_REQUIRES_RECEPTION = B.define("paymentRequiresReception", true);
        APPEAL_DECISION_TIMEOUT_REAL_DAYS = B.defineInRange("appealDecisionTimeoutRealDays", 5, 1, 365);
        APPEAL_MAX_REASON_LENGTH = B.defineInRange("appealMaxReasonLength", 240, 1, 4000);
        APPEAL_MAX_PER_WINDOW = B.defineInRange("appealMaxPerWindow", 5, 1, 100);
        APPEAL_ABUSE_WINDOW_DAYS = B.defineInRange("appealAbuseWindowRealDays", 30, 1, 365);
        APPEAL_ABUSE_BLOCK_DAYS = B.defineInRange("appealAbuseBlockRealDays", 7, 0, 365);
        FINE_ESCALATION_MISSION_MINUTES = B.defineInRange("escalationMissionMinutes", 30, 1, 10080);
        B.pop();

        B.push("prison");
        PRISON_ENABLED = B.define("enabled", true);
        PRISON_MAX_CELLS = B.defineInRange("maxCells", 32, 1, 1024);
        PRISON_ACTIVE_MINUTES_PER_DAY = B.defineInRange("activeMinutesPerMinecraftDay", 20, 1, 1440);
        PRISON_DEFAULT_SENTENCE_DAYS = B.defineInRange("defaultSentenceDays", 1, 1, 365);
        PRISON_AFK_GRACE_SECONDS = B.defineInRange("afkGraceSeconds", 60, 5, 3600);
        PRISON_MAX_SENTENCE_DAYS = B.defineInRange("maxSentenceDays", 7, 1, 365);
        PRISON_ARREST_RADIUS = B.defineInRange("arrestRadius", 6, 1, 64);
        PRISON_RETENTION_LIMIT = B.defineInRange("retentionLimit", 32, 1, 10000);
        B.pop();

        B.push("audit");
        AUDIT_ENABLED = B.define("enabled", true);
        AUDIT_RETENTION_LIMIT = B.defineInRange("retentionLimit", 500, 10, 100000);
        B.pop();

        B.push("complaints");
        COMPLAINTS_ENABLED = B.define("enabled", true);
        COMPLAINT_MAX_DESCRIPTION_LENGTH = B.defineInRange("maxDescriptionLength", 400, 1, 4000);
        COMPLAINT_MAX_EVIDENCE_LENGTH = B.defineInRange("maxEvidenceLength", 400, 1, 4000);
        COMPLAINT_MAX_ACTIVE_PER_COMPLAINANT = B.defineInRange("maxActivePerComplainant", 3, 1, 100);
        COMPLAINT_MAX_PARTICIPANTS = B.defineInRange("maxParticipants", 4, 1, 100);
        COMPLAINT_MAX_REWARD = B.defineInRange("maxReward", 250, 0, 1000000);
        COMPLAINT_MAX_REWARD_PER_REVIEWER_PER_DAY = B.defineInRange("maxRewardPerReviewerPerDay", 1000, 0, 1000000);
        B.pop();

        B.push("debug");
        DEBUG_ENABLED = B.comment("Master switch for /straja debug *. Set false in production.")
                .define("enabled", true);
        DEBUG_LOCAL_ONLY = B.comment("Restricts /straja debug * to the local environment.")
                .define("localOnly", true);
        DEBUG_REVEAL_QUIZ_ANSWERS = B.define("revealQuizAnswers", true);
        DEBUG_ALLOW_GRANT_RANK = B.define("allowGrantRank", true);
        DEBUG_ALLOW_COMMISSIONER_OVERRIDE = B.define("allowCommissionerOverride", true);
        B.pop();

        B.push("testing");
        TEST_COMMANDS_ENABLED = B.comment(
                        "Enables /straja test * (virtual players). Local environments only —",
                        "the flag has no effect when environment is not \"local\".")
                .define("enableTestCommands", false);
        B.pop();

        SPEC = B.build();
    }

    private StrajaServerConfig() {}

    /** Maps the loaded ModConfigSpec values into the domain policy object. */
    public static com.dwurdy.straja.domain.model.StrajaPolicies toPolicies() {
        var p = new com.dwurdy.straja.domain.model.StrajaPolicies();
        p.commissionerName = COMMISSIONER_NAME.get();
        p.commissionerUuid = COMMISSIONER_UUID.get();
        p.requireUuid = REQUIRE_UUID.get();
        p.allowNameFallback = ALLOW_NAME_FALLBACK.get();
        p.environment = ENVIRONMENT.get();

        p.checkpointUnlockMinutes = CHECKPOINT_UNLOCK_MINUTES.get();
        p.checkpointDeadlineMinutes = CHECKPOINT_DEADLINE_MINUTES.get();
        p.salaryBlockMinutes = SALARY_BLOCK_MINUTES.get();
        p.foodCooldownMinutes = FOOD_COOLDOWN_MINUTES.get();
        p.quizCooldownMinutes = QUIZ_COOLDOWN_MINUTES.get();
        p.resignationCooldownDays = RESIGNATION_COOLDOWN_DAYS.get();
        p.resignationNoticeMinutes = RESIGNATION_NOTICE_MINUTES.get();

        p.missionMinMinutes = MISSION_MIN_MINUTES.get();
        p.missionMaxMinutes = MISSION_MAX_MINUTES.get();
        p.missionMaxScheduleDays = MISSION_MAX_SCHEDULE_DAYS.get();
        p.missionMaxReward = MISSION_MAX_REWARD.get();
        p.missionMaxCopiesPerDraft = MISSION_MAX_COPIES_PER_DRAFT.get();
        p.missionMaxRewardPool = MISSION_MAX_REWARD_POOL.get();
        p.missionMaxRewardPerIssuerPerDay = MISSION_MAX_REWARD_PER_ISSUER_PER_DAY.get();
        p.missionMaxActivePerPlayer = MISSION_MAX_ACTIVE_PER_PLAYER.get();
        p.missionDefaultMinimumRank = MISSION_DEFAULT_MIN_RANK.get();
        p.missionMaxAssignees = MISSION_MAX_ASSIGNEES.get();
        p.missionRetentionLimit = MISSION_RETENTION_LIMIT.get();
        p.missionQuickCreateEnabled = MISSION_QUICK_CREATE_ENABLED.get();
        p.missionQuickCreateLocalOnly = MISSION_QUICK_CREATE_LOCAL_ONLY.get();

        p.cuffRequestTimeoutSeconds = CUFF_REQUEST_TIMEOUT_SECONDS.get();
        p.surrenderTimeoutSeconds = SURRENDER_TIMEOUT_SECONDS.get();
        p.cuffSlownessTicks = CUFF_SLOWNESS_TICKS.get();
        p.cuffSlownessAmplifier = CUFF_SLOWNESS_AMPLIFIER.get();
        p.cuffBreakDistance = CUFF_BREAK_DISTANCE.get();
        p.cuffBreakGraceSeconds = CUFF_BREAK_GRACE_SECONDS.get();
        p.cuffActionLock = CUFF_ACTION_LOCK.get();
        p.hideHeldItemWhenPossible = HIDE_HELD_ITEM_WHEN_POSSIBLE.get();
        p.genericKeyTokens = new java.util.ArrayList<>(GENERIC_KEY_TOKENS.get());

        p.ropeSlownessTicks = ROPE_SLOWNESS_TICKS.get();
        p.ropeSlownessAmplifier = ROPE_SLOWNESS_AMPLIFIER.get();
        p.headSackBlindnessTicks = HEAD_SACK_BLINDNESS_TICKS.get();
        p.ropeRequiresCuffs = ROPE_REQUIRES_CUFFS.get();
        p.restraintActionLock = RESTRAINT_ACTION_LOCK.get();

        p.downedEnabled = DOWNED_ENABLED.get();
        p.downedCooldownSeconds = DOWNED_COOLDOWN_SECONDS.get();
        p.downedSlownessTicks = DOWNED_SLOWNESS_TICKS.get();
        p.downedSlownessAmplifier = DOWNED_SLOWNESS_AMPLIFIER.get();
        p.downedWakeHealthRatio = DOWNED_WAKE_HEALTH_RATIO.get();
        p.downedFreezeInPlace = DOWNED_FREEZE_IN_PLACE.get();
        p.downedActionLock = DOWNED_ACTION_LOCK.get();

        p.arrestRewardMinimum = ARREST_REWARD_MIN.get();
        p.arrestRewardMaximum = ARREST_REWARD_MAX.get();
        p.arrestMaxDailyPayout = ARREST_REWARD_MAX_DAILY.get();
        p.arrestMinorDivider = ARREST_MINOR_DIVIDER.get();
        p.arrestAliveMultiplier = ARREST_ALIVE_MULTIPLIER.get();
        p.arrestDeathMultiplier = ARREST_DEATH_MULTIPLIER.get();

        p.coinItemIds = new java.util.LinkedHashMap<>();
        p.coinItemIds.put(1, COIN_BRONZE_ITEM.get());
        p.coinItemIds.put(10, COIN_BRASS_ITEM.get());
        p.coinItemIds.put(100, COIN_SILVER_ITEM.get());
        p.coinItemIds.put(1000, COIN_GOLD_ITEM.get());

        p.salaryMaxBlocksPerDay = SALARY_MAX_BLOCKS_PER_DAY.get();
        p.salaryWindowMinutes = SALARY_WINDOW_MINUTES.get();
        p.salaryActivityGraceSeconds = SALARY_ACTIVITY_GRACE_SECONDS.get();
        p.salaryActivityMoveThreshold = SALARY_ACTIVITY_MOVE_THRESHOLD.get();
        p.serviceLeaseMinutes = SERVICE_LEASE_MINUTES.get();
        p.requireRealCoinProviderOutsideLocal = REQUIRE_REAL_COIN_PROVIDER_OUTSIDE_LOCAL.get();
        p.requireCommissionerUuidOutsideLocal = REQUIRE_COMMISSIONER_UUID_OUTSIDE_LOCAL.get();
        p.requireDebugDisabledOutsideLocal = REQUIRE_DEBUG_DISABLED_OUTSIDE_LOCAL.get();

        p.envelopeEnabled = ENVELOPE_ENABLED.get();
        p.envelopeFallbackToChat = ENVELOPE_FALLBACK_TO_CHAT.get();
        p.envelopeMaxBodyLength = ENVELOPE_MAX_BODY_LENGTH.get();

        p.archiveEnabled = ARCHIVE_ENABLED.get();
        p.archiveCommissionerAlways = ARCHIVE_COMMISSIONER_ALWAYS.get();
        p.archiveMaxFoldersPerOwner = ARCHIVE_MAX_FOLDERS_PER_OWNER.get();
        p.archiveMaxSheetsPerFolder = ARCHIVE_MAX_SHEETS_PER_FOLDER.get();
        p.archiveMaxCatalogEntriesPerFolder = ARCHIVE_MAX_CATALOG_PER_FOLDER.get();
        p.archiveMaxTitleLength = ARCHIVE_MAX_TITLE_LENGTH.get();
        p.archiveMaxContentLength = ARCHIVE_MAX_CONTENT_LENGTH.get();
        p.archiveMaxRecipients = ARCHIVE_MAX_RECIPIENTS.get();
        p.archiveMaxCopiesPerOperation = ARCHIVE_MAX_COPIES_PER_OPERATION.get();

        p.roomRetentionLimit = ROOM_RETENTION_LIMIT.get();
        p.roomMaxDimension = ROOM_MAX_DIMENSION.get();
        p.roomMaxBlocks = ROOM_MAX_BLOCKS.get();
        p.roomWaitlistRetentionDays = ROOM_WAITLIST_RETENTION_DAYS.get();

        p.finesEnabled = FINES_ENABLED.get();
        p.appealsEnabled = APPEALS_ENABLED.get();
        p.fineOnlineDaysUntilEscalation = FINE_ONLINE_DAYS_UNTIL_ESCALATION.get();
        p.minecraftDayMinutes = FINE_MINECRAFT_DAY_MINUTES.get();
        p.fineMaxLawLength = FINE_MAX_LAW_LENGTH.get();
        p.fineMaxDescriptionLength = FINE_MAX_DESCRIPTION_LENGTH.get();
        p.finePaymentRequiresReception = FINE_PAYMENT_REQUIRES_RECEPTION.get();
        p.appealDecisionTimeoutRealDays = APPEAL_DECISION_TIMEOUT_REAL_DAYS.get();
        p.appealMaxReasonLength = APPEAL_MAX_REASON_LENGTH.get();
        p.appealMaxPerWindow = APPEAL_MAX_PER_WINDOW.get();
        p.appealAbuseWindowRealDays = APPEAL_ABUSE_WINDOW_DAYS.get();
        p.appealAbuseBlockRealDays = APPEAL_ABUSE_BLOCK_DAYS.get();
        p.escalationMissionMinutes = FINE_ESCALATION_MISSION_MINUTES.get();

        p.prisonEnabled = PRISON_ENABLED.get();
        p.prisonMaxCells = PRISON_MAX_CELLS.get();
        p.prisonActiveMinutesPerMinecraftDay = PRISON_ACTIVE_MINUTES_PER_DAY.get();
        p.prisonDefaultSentenceDays = PRISON_DEFAULT_SENTENCE_DAYS.get();
        p.prisonAfkGraceSeconds = PRISON_AFK_GRACE_SECONDS.get();
        p.prisonMaxSentenceDays = PRISON_MAX_SENTENCE_DAYS.get();
        p.prisonArrestRadius = PRISON_ARREST_RADIUS.get();
        p.prisonRetentionLimit = PRISON_RETENTION_LIMIT.get();

        p.auditEnabled = AUDIT_ENABLED.get();
        p.auditRetentionLimit = AUDIT_RETENTION_LIMIT.get();

        p.complaintsEnabled = COMPLAINTS_ENABLED.get();
        p.complaintMaxDescriptionLength = COMPLAINT_MAX_DESCRIPTION_LENGTH.get();
        p.complaintMaxEvidenceLength = COMPLAINT_MAX_EVIDENCE_LENGTH.get();
        p.complaintMaxActivePerComplainant = COMPLAINT_MAX_ACTIVE_PER_COMPLAINANT.get();
        p.complaintMaxParticipants = COMPLAINT_MAX_PARTICIPANTS.get();
        p.complaintMaxReward = COMPLAINT_MAX_REWARD.get();
        p.complaintMaxRewardPerReviewerPerDay = COMPLAINT_MAX_REWARD_PER_REVIEWER_PER_DAY.get();

        p.debugEnabled = DEBUG_ENABLED.get();
        p.debugLocalOnly = DEBUG_LOCAL_ONLY.get();
        p.debugRevealQuizAnswers = DEBUG_REVEAL_QUIZ_ANSWERS.get();
        p.debugAllowGrantRank = DEBUG_ALLOW_GRANT_RANK.get();
        p.debugAllowCommissionerOverride = DEBUG_ALLOW_COMMISSIONER_OVERRIDE.get();
        p.testCommandsEnabled = TEST_COMMANDS_ENABLED.get();
        return p;
    }

    private static boolean isItemId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }
}
