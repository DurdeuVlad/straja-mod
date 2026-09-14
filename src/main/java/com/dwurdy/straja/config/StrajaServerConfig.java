package com.dwurdy.straja.config;

import com.dwurdy.straja.domain.model.StrajaPolicies;
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
    public static final ModConfigSpec.ConfigValue<String> COMMISSIONER_TITLE;
    public static final ModConfigSpec.IntValue NATIVE_FACTION_MAX_LENGTH;
    public static final ModConfigSpec.BooleanValue REQUIRE_UUID;
    public static final ModConfigSpec.BooleanValue ALLOW_NAME_FALLBACK;
    public static final ModConfigSpec.ConfigValue<String> ENVIRONMENT;

    public static final ModConfigSpec.IntValue CHECKPOINT_UNLOCK_MINUTES;
    public static final ModConfigSpec.IntValue CHECKPOINT_DEADLINE_MINUTES;
    public static final ModConfigSpec.IntValue SERVICE_BLOCK_MINUTES;
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
    public static final ModConfigSpec.DoubleValue MISSION_REWARD_OVERRIDE_MARGIN;

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
    public static final ModConfigSpec.BooleanValue RESTRAINT_ACTION_LOCK;

    public static final ModConfigSpec.BooleanValue DOWNED_ENABLED;
    public static final ModConfigSpec.IntValue DOWNED_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue DOWNED_SLOWNESS_TICKS;
    public static final ModConfigSpec.IntValue DOWNED_SLOWNESS_AMPLIFIER;
    public static final ModConfigSpec.DoubleValue DOWNED_WAKE_HEALTH_RATIO;
    public static final ModConfigSpec.BooleanValue DOWNED_FREEZE_IN_PLACE;
    public static final ModConfigSpec.BooleanValue DOWNED_ACTION_LOCK;

    public static final ModConfigSpec.IntValue CARRY_TRANSPORT_DEADLINE_SECONDS;
    public static final ModConfigSpec.IntValue RESUSCITATION_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue RESUSCITATION_PROGRESS_PERCENT;
    public static final ModConfigSpec.IntValue UNCONSCIOUS_CUSTODY_DURATION_SECONDS;
    public static final ModConfigSpec.IntValue JAIL_DELIVERY_DEADLINE_SECONDS;
    public static final ModConfigSpec.BooleanValue JAIL_AUTOMATIC_REVIVAL_ENABLED;
    public static final ModConfigSpec.IntValue JAIL_AUTOMATIC_REVIVAL_DELAY_SECONDS;
    public static final ModConfigSpec.ConfigValue<String> SECOND_WEAPON_HIT_BEHAVIOR;
    public static final ModConfigSpec.ConfigValue<String> ORDINARY_DAMAGE_BEHAVIOR;
    public static final ModConfigSpec.ConfigValue<String> NON_WEAPON_DAMAGE_BEHAVIOR;
    public static final ModConfigSpec.ConfigValue<String> EXCEPTIONAL_DAMAGE_BEHAVIOR;
    public static final ModConfigSpec.BooleanValue CRIMINAL_ROPE_ENABLED;
    public static final ModConfigSpec.BooleanValue CRIMINAL_CUTTER_ENABLED;
    public static final ModConfigSpec.BooleanValue POLICE_CUFFS_ENABLED;
    public static final ModConfigSpec.BooleanValue UNIVERSAL_KEY_ENABLED;
    public static final ModConfigSpec.BooleanValue BLACK_SACK_APPLICATION_ENABLED;
    public static final ModConfigSpec.BooleanValue BLACK_SACK_REMOVAL_ENABLED;
    public static final ModConfigSpec.BooleanValue BLACK_SACK_SELF_REMOVAL;
    public static final ModConfigSpec.ConfigValue<String> LOGOUT_RECOVERY_BEHAVIOR;
    public static final ModConfigSpec.ConfigValue<String> RESTART_RECOVERY_BEHAVIOR;
    public static final ModConfigSpec.ConfigValue<String> DEATH_RECOVERY_BEHAVIOR;
    public static final ModConfigSpec.ConfigValue<String> DIMENSION_CHANGE_RECOVERY_BEHAVIOR;
    public static final ModConfigSpec.ConfigValue<String> MISSING_DESTINATION_RECOVERY_BEHAVIOR;

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

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SALARY_PER_HOUR;
    public static final ModConfigSpec.IntValue SALARY_COMMISSIONER_PER_HOUR;
    public static final ModConfigSpec.IntValue SALARY_GRANULARITY_SECONDS;
    public static final ModConfigSpec.IntValue SALARY_MAX_PAID_MINUTES_PER_DAY;
    public static final ModConfigSpec.IntValue SALARY_WINDOW_MINUTES;
    public static final ModConfigSpec.IntValue SALARY_ACTIVITY_GRACE_SECONDS;
    public static final ModConfigSpec.DoubleValue SALARY_ACTIVITY_MOVE_THRESHOLD;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> RANK_NAMES;
    public static final ModConfigSpec.BooleanValue RANK_PREFIX_CHAT;
    public static final ModConfigSpec.BooleanValue RANK_PREFIX_TAB;
    public static final ModConfigSpec.BooleanValue RANK_PREFIX_NAMEPLATE;
    public static final ModConfigSpec.ConfigValue<String> COMISAR_TITLE;
    public static final ModConfigSpec.IntValue REPORT_INTERVAL_DAYS;
    public static final ModConfigSpec.BooleanValue REPORT_BLOCK_DUTY_WHEN_OVERDUE;
    public static final ModConfigSpec.IntValue AUDIENCE_NOTIFY_COOLDOWN;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PROMOTION_SERVICE_BLOCKS;

    public static final ModConfigSpec.IntValue FREE_DUTY_MIN_RANK;
    public static final ModConfigSpec.IntValue SECRETARY_RADIUS_BLOCKS;
    public static final ModConfigSpec.BooleanValue CAPTURE_DUTY_FACTION;
    public static final ModConfigSpec.ConfigValue<String> STRAJA_TEAM_NAME;
    public static final ModConfigSpec.IntValue EMERGENCY_URGENCY_TTL;
    public static final ModConfigSpec.DoubleValue EMERGENCY_PAY_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue EMERGENCY_MAX_PAY_MULTIPLIER;
    public static final ModConfigSpec.IntValue EMERGENCY_PATROL_ROUNDS;
    public static final ModConfigSpec.IntValue EMERGENCY_MAX_PATROL_ROUNDS;


    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> QUIZ_QUESTIONS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> TRAINING_QUIZ_QUESTIONS;

    public static final ModConfigSpec.ConfigValue<String> JAILER_NAME;
    public static final ModConfigSpec.BooleanValue JAILER_GUARD_IMMUNITY;
    public static final ModConfigSpec.IntValue JAILER_ASSAULT_WOUNDED;
    public static final ModConfigSpec.IntValue JAILER_ASSAULT_KILLED;
    public static final ModConfigSpec.IntValue JAILER_ASSAULT_SENTENCE_DAYS;
    public static final ModConfigSpec.IntValue JAILER_ASSAULT_MISSION_MAX_ASSIGNEES;

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> KITS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SERVICE_EQUIPMENT;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> REGEAR_COST;
    public static final ModConfigSpec.ConfigValue<String> FOOD_ITEM;
    public static final ModConfigSpec.IntValue FOOD_AMOUNT;
    public static final ModConfigSpec.ConfigValue<String> TRAINING_MANUAL_ITEM;

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
    public static final ModConfigSpec.IntValue ROOM_MIN_INTERIOR_X;
    public static final ModConfigSpec.IntValue ROOM_MIN_INTERIOR_Y;
    public static final ModConfigSpec.IntValue ROOM_MIN_INTERIOR_Z;
    public static final ModConfigSpec.BooleanValue ROOM_REQUIRE_SINGLE_DOOR;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> ROOM_ALLOWED_ACCESS_BLOCKS;

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
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends Integer>> FINE_ALLOWED_AMOUNTS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SENTENCE_DAYS_BY_AMOUNT;

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
    public static final ModConfigSpec.IntValue COMPLAINT_DEFAULT_SEVERITY;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_REWARD;
    public static final ModConfigSpec.IntValue COMPLAINT_MAX_REWARD_PER_REVIEWER_PER_DAY;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> COMPLAINT_REWARD_BY_SEVERITY;

    public static final ModConfigSpec.BooleanValue DEBUG_ENABLED;
    public static final ModConfigSpec.BooleanValue DEBUG_LOCAL_ONLY;
    public static final ModConfigSpec.BooleanValue DEBUG_REVEAL_QUIZ_ANSWERS;
    public static final ModConfigSpec.BooleanValue DEBUG_ALLOW_GRANT_RANK;
    public static final ModConfigSpec.BooleanValue DEBUG_ALLOW_COMMISSIONER_OVERRIDE;
    public static final ModConfigSpec.BooleanValue TEST_COMMANDS_ENABLED;

    public static final ModConfigSpec SPEC;

    static {
        var defaults = new StrajaPolicies();

        B.push("identity");
        COMMISSIONER_NAME = B.comment("Configured commissioner account name (local fallback only).")
                .define("commissionerName", "dwurdy");
        COMMISSIONER_UUID = B.comment("Canonical commissioner UUID. Required outside local environments.")
                .define("commissionerUuid", "");
        COMMISSIONER_TITLE = B.comment("Display title for the commissioner in rules/status text.")
                .define("commissionerTitle", defaults.commissionerTitle);
        NATIVE_FACTION_MAX_LENGTH = B.comment(
                        "Maximum length of a player's self-declared native faction name.")
                .defineInRange("nativeFactionMaxLength", defaults.nativeFactionMaxLength, 4, 200);
        REQUIRE_UUID = B.define("requireUuid", false);
        ALLOW_NAME_FALLBACK = B.define("allowNameFallback", true);
        ENVIRONMENT = B.comment("local | staging | production").define("environment", "local");
        B.pop();

        B.push("timers");
        CHECKPOINT_UNLOCK_MINUTES = B.defineInRange("checkpointUnlockMinutes", 10, 1, 1440);
        CHECKPOINT_DEADLINE_MINUTES = B.defineInRange("checkpointDeadlineMinutes", 30, 1, 1440);
        SERVICE_BLOCK_MINUTES = B.comment(
                        "Duty-time interval that earns one service point",
                        "(promotion credit). Salary accrues separately — see [salary].")
                .defineInRange("serviceBlockMinutes", 10, 1, 60);
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
        MISSION_REWARD_OVERRIDE_MARGIN = B.comment(
                        "Fraction above the §13 calculated reward that triggers",
                        "the mandatory-reason override gate (0.25 = +25%).")
                .defineInRange("rewardOverrideMargin", defaults.missionRewardOverrideMargin, 0.0, 10.0);
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

        B.push("custody");
        CARRY_TRANSPORT_DEADLINE_SECONDS = B.comment(
                        "Maximum time a carried player may remain in transport without delivery.")
                .defineInRange("carryTransportDeadlineSeconds", defaults.carryTransportDeadlineSeconds, 1, 86400);
        RESUSCITATION_TIMEOUT_SECONDS = B.defineInRange(
                "resuscitationTimeoutSeconds", defaults.resuscitationTimeoutSeconds, 1, 3600);
        RESUSCITATION_PROGRESS_PERCENT = B.defineInRange(
                "resuscitationProgressPercent", defaults.resuscitationProgressPercent, 1, 100);
        UNCONSCIOUS_CUSTODY_DURATION_SECONDS = B.defineInRange(
                "unconsciousCustodyDurationSeconds", defaults.unconsciousCustodyDurationSeconds, 1, 86400);
        JAIL_DELIVERY_DEADLINE_SECONDS = B.defineInRange(
                "jailDeliveryDeadlineSeconds", defaults.jailDeliveryDeadlineSeconds, 1, 86400);
        JAIL_AUTOMATIC_REVIVAL_ENABLED = B.define(
                "jailAutomaticRevivalEnabled", defaults.jailAutomaticRevivalEnabled);
        JAIL_AUTOMATIC_REVIVAL_DELAY_SECONDS = B.defineInRange(
                "jailAutomaticRevivalDelaySeconds", defaults.jailAutomaticRevivalDelaySeconds, 1, 86400);
        SECOND_WEAPON_HIT_BEHAVIOR = B.define("secondWeaponHitBehavior", defaults.secondWeaponHitBehavior,
                StrajaServerConfig::isDamageBehavior);
        ORDINARY_DAMAGE_BEHAVIOR = B.define("ordinaryDamageBehavior", defaults.ordinaryDamageBehavior,
                StrajaServerConfig::isDamageBehavior);
        NON_WEAPON_DAMAGE_BEHAVIOR = B.define("nonWeaponDamageBehavior", defaults.nonWeaponDamageBehavior,
                StrajaServerConfig::isDamageBehavior);
        EXCEPTIONAL_DAMAGE_BEHAVIOR = B.define("exceptionalDamageBehavior", defaults.exceptionalDamageBehavior,
                StrajaServerConfig::isDamageBehavior);
        CRIMINAL_ROPE_ENABLED = B.define("criminalRopeEnabled", defaults.criminalRopeEnabled);
        CRIMINAL_CUTTER_ENABLED = B.define("criminalCutterEnabled", defaults.criminalCutterEnabled);
        POLICE_CUFFS_ENABLED = B.define("policeCuffsEnabled", defaults.policeCuffsEnabled);
        UNIVERSAL_KEY_ENABLED = B.define("universalKeyEnabled", defaults.universalKeyEnabled);
        BLACK_SACK_APPLICATION_ENABLED = B.define(
                "blackSackApplicationEnabled", defaults.blackSackApplicationEnabled);
        BLACK_SACK_REMOVAL_ENABLED = B.define(
                "blackSackRemovalEnabled", defaults.blackSackRemovalEnabled);
        BLACK_SACK_SELF_REMOVAL = B.define("blackSackSelfRemoval", defaults.blackSackSelfRemoval);
        B.pop();

        B.push("recovery");
        LOGOUT_RECOVERY_BEHAVIOR = B.define("logout", defaults.logoutRecoveryBehavior,
                StrajaServerConfig::isRecoveryBehavior);
        RESTART_RECOVERY_BEHAVIOR = B.define("restart", defaults.restartRecoveryBehavior,
                StrajaServerConfig::isRecoveryBehavior);
        DEATH_RECOVERY_BEHAVIOR = B.define("death", defaults.deathRecoveryBehavior,
                StrajaServerConfig::isRecoveryBehavior);
        DIMENSION_CHANGE_RECOVERY_BEHAVIOR = B.define(
                "dimensionChange", defaults.dimensionChangeRecoveryBehavior,
                StrajaServerConfig::isRecoveryBehavior);
        MISSING_DESTINATION_RECOVERY_BEHAVIOR = B.define(
                "missingDestination", defaults.missingDestinationRecoveryBehavior,
                StrajaServerConfig::isRecoveryBehavior);
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
                        "Item IDs for the four coin denominations (values 1, 64, 4096, 262144).",
                        "Any mod's items work — the coin provider is unavailable until all",
                        "four IDs resolve in the item registry. Defaults use Ady's Decorations.")
                .define("bronzeCoin", "adys_decorations:bronze_coin", StrajaServerConfig::isItemId);
        COIN_BRASS_ITEM = B.define("brassCoin", "adys_decorations:brass_coin", StrajaServerConfig::isItemId);
        COIN_SILVER_ITEM = B.define("silverCoin", "adys_decorations:silver_coin", StrajaServerConfig::isItemId);
        COIN_GOLD_ITEM = B.define("goldCoin", "adys_decorations:gold_coin", StrajaServerConfig::isItemId);
        B.pop();

        B.push("salary");
        SALARY_PER_HOUR = B.comment(
                        "Hourly wage in Bronze coins, per rank (§10):",
                        "Stagiar 16, Străjer 24, Sergent 36, Inspector 64 by default.",
                        "Accrues continuously in granularitySeconds chunks.",
                        "Entries: \"rank=coinsPerHour\".")
                .defineListAllowEmpty(List.of("perHour"),
                        StrajaPolicies.formatIntMap(defaults.salaryPerHour),
                        () -> "1=16", StrajaServerConfig::isIntMapEntry);
        SALARY_COMMISSIONER_PER_HOUR = B.comment(
                        "Comisar hourly wage in Bronze (default 128 = 2× Inspector).")
                .defineInRange("commissionerPerHour", 128, 0, 100000);
        SALARY_GRANULARITY_SECONDS = B.comment(
                        "Accrual chunk size in seconds; sub-chunk duty time and",
                        "sub-coin fractions carry forward — nothing is truncated.")
                .defineInRange("granularitySeconds", 60, 1, 3600);
        SALARY_MAX_PAID_MINUTES_PER_DAY = B.defineInRange("maxPaidMinutesPerDay", 240, 0, 100000);
        SALARY_WINDOW_MINUTES = B.defineInRange("windowMinutes", 1440, 1, 100000);
        SALARY_ACTIVITY_GRACE_SECONDS = B.defineInRange("activityGraceSeconds", 90, 5, 3600);
        SALARY_ACTIVITY_MOVE_THRESHOLD = B.defineInRange("activityMoveThreshold", 0.15, 0.0, 10.0);
        B.pop();

        B.push("ranks");
        RANK_NAMES = B.comment(
                        "Display names per rank level (1=Stagiar ... 4=Inspector).",
                        "Entries: \"rank=nume\". The Comisar title is a separate",
                        "personnel flag, not a ladder step.")
                .defineListAllowEmpty(List.of("names"),
                        StrajaPolicies.formatIntStringMap(defaults.rankNames),
                        () -> "1=Stagiar", StrajaServerConfig::isIntStringMapEntry);
        RANK_PREFIX_CHAT = B.comment(
                        "Show the [Rank] prefix on chat messages.")
                .define("prefixChat", defaults.rankPrefixChat);
        RANK_PREFIX_TAB = B.comment(
                        "Show the [Rank] prefix in the TAB player list.")
                .define("prefixTab", defaults.rankPrefixTab);
        RANK_PREFIX_NAMEPLATE = B.comment(
                        "Show the [Rank] prefix on the above-head nameplate.",
                        "Overrides team styling on the plate while enabled.")
                .define("prefixNameplate", defaults.rankPrefixNameplate);
        COMISAR_TITLE = B.comment(
                        "Display title for the commissioner prefix.")
                .define("comisarTitle", defaults.comisarTitle);
        B.pop();

        B.push("reports");
        REPORT_INTERVAL_DAYS = B.comment(
                        "Days between a member's activity reports (§11).")
                .defineInRange("intervalDays", defaults.reportIntervalDays, 1, 365);
        REPORT_BLOCK_DUTY_WHEN_OVERDUE = B.comment(
                        "Refuse duty start at the Secretary while the member's",
                        "activity report is overdue.")
                .define("blockDutyWhenOverdue", defaults.reportBlockDutyWhenOverdue);
        B.pop();

        B.push("audiences");
        AUDIENCE_NOTIFY_COOLDOWN = B.comment(
                        "Minimum seconds between Comisar notifications for new",
                        "audience requests (§12) — notifications are coalesced.")
                .defineInRange("notifyCooldownSeconds", defaults.audienceNotifyCooldownSeconds, 0, 3600);
        B.pop();

        B.push("promotion");
        PROMOTION_SERVICE_BLOCKS = B.comment(
                        "Service blocks required for automatic promotion.",
                        "Entries: \"targetRank=blocks\".")
                .defineListAllowEmpty(List.of("serviceBlocks"),
                        StrajaPolicies.formatIntMap(defaults.promotionServiceBlocks),
                        () -> "2=60", StrajaServerConfig::isIntMapEntry);
        B.pop();

        B.push("duty");
        FREE_DUTY_MIN_RANK = B.comment(
                        "Guards at or above this rank (and the commissioner) start and",
                        "end shifts at will — no patrol route or checkpoints required.")
                .defineInRange("freeDutyMinRank", defaults.freeDutyMinRank, 1, 4);
        SECRETARY_RADIUS_BLOCKS = B.comment(
                        "Normal (patrol) duty may only be started or stopped within this",
                        "many blocks of the configured secretary location.")
                .defineInRange("secretaryRadiusBlocks", defaults.secretaryRadiusBlocks, 0, 128);
        CAPTURE_DUTY_FACTION = B.comment(
                        "Capture the guard's scoreboard team at duty start, move them to",
                        "the operational Straja team, and restore the captured team at",
                        "shift end.")
                .define("captureDutyFaction", defaults.captureDutyFaction);
        STRAJA_TEAM_NAME = B.comment(
                        "Scoreboard team applied to on-duty guards.")
                .define("strajaTeamName", defaults.strajaTeamName);
        B.pop();

        B.push("emergency");
        EMERGENCY_URGENCY_TTL = B.comment(
                        "Minutes an urgency call stays live; members logging in while",
                        "it is live still receive it (§25).")
                .defineInRange("urgencyTtlMinutes", defaults.emergencyUrgencyTtlMinutes, 1, 1440);
        EMERGENCY_PAY_MULTIPLIER = B.comment(
                        "Default hazard-pay multiplier applied to hourly wages while",
                        "the emergency mode is active.")
                .defineInRange("payMultiplier", defaults.emergencyPayMultiplier, 1.0, 100.0);
        EMERGENCY_MAX_PAY_MULTIPLIER = B.comment(
                        "Upper clamp for the hazard-pay multiplier a Comisar may set.")
                .defineInRange("maxPayMultiplier", defaults.emergencyMaxPayMultiplier, 1.0, 100.0);
        EMERGENCY_PATROL_ROUNDS = B.comment(
                        "Default full route laps required per patrol shift started",
                        "while the emergency mode is active.")
                .defineInRange("patrolRounds", defaults.emergencyPatrolRounds, 1, 100);
        EMERGENCY_MAX_PATROL_ROUNDS = B.comment(
                        "Upper clamp for required patrol rounds a Comisar may set.")
                .defineInRange("maxPatrolRounds", defaults.emergencyMaxPatrolRounds, 1, 100);
        B.pop();

        B.push("quiz");
        QUIZ_QUESTIONS = B.comment(
                        "Recruitment quiz questions asked by the trainer.",
                        "Entries: \"id|minRank|question|answer1;answer2\" — any listed answer accepts.")
                .defineListAllowEmpty(List.of("questions"),
                        StrajaPolicies.formatQuiz(defaults.quiz),
                        () -> "id|0|question|answer", StrajaServerConfig::isQuizEntry);
        TRAINING_QUIZ_QUESTIONS = B.comment(
                        "Training questions asked on promotion/kit progression.",
                        "Same entry format as questions.")
                .defineListAllowEmpty(List.of("trainingQuestions"),
                        StrajaPolicies.formatQuiz(defaults.trainingQuiz),
                        () -> "id|1|question|answer", StrajaServerConfig::isQuizEntry);
        B.pop();

        B.push("jailer");
        JAILER_NAME = B.define("name", defaults.jailerName);
        JAILER_GUARD_IMMUNITY = B.comment(
                        "When true, on-duty guards cannot damage the jailer.")
                .define("guardImmunity", defaults.jailerGuardImmunity);
        JAILER_ASSAULT_WOUNDED = B.defineInRange("assaultWoundedReward",
                defaults.jailerAssaultWoundedAmount, 0, 1000000);
        JAILER_ASSAULT_KILLED = B.defineInRange("assaultKilledReward",
                defaults.jailerAssaultKilledAmount, 0, 1000000);
        JAILER_ASSAULT_SENTENCE_DAYS = B.defineInRange("assaultSentenceDays",
                defaults.jailerAssaultSentenceDays, 1, 365);
        JAILER_ASSAULT_MISSION_MAX_ASSIGNEES = B.defineInRange("assaultMissionMaxAssignees",
                defaults.jailerAssaultMissionMaxAssignees, 1, 64);
        B.pop();

        B.push("equipment");
        KITS = B.comment(
                        "Duty kits granted per rank.",
                        "Entries: \"rank=itemId,count\" — one entry per item.",
                        "An empty or fully malformed list falls back to the built-in defaults.")
                .defineListAllowEmpty(List.of("kits"),
                        StrajaPolicies.formatKits(defaults.kits),
                        () -> "1=minecraft:iron_sword,1", StrajaServerConfig::isKitEntry);
        SERVICE_EQUIPMENT = B.comment(
                        "Leased service equipment per rank (reclaimed at duty end).",
                        "Entries: \"rank=key|itemId|count|replacementCost|label\".")
                .defineListAllowEmpty(List.of("serviceEquipment"),
                        StrajaPolicies.formatEquipment(defaults.serviceEquipment),
                        () -> "2=cuffs|straja:cuffs|1|100|cătușe de serviciu",
                        StrajaServerConfig::isEquipmentEntry);
        REGEAR_COST = B.comment(
                        "Regear approval cost per rank.",
                        "Entries: \"rank=coins\".")
                .defineListAllowEmpty(List.of("regearCost"),
                        StrajaPolicies.formatIntMap(defaults.regearCost),
                        () -> "1=100", StrajaServerConfig::isIntMapEntry);
        FOOD_ITEM = B.define("foodItem", defaults.foodItem, StrajaServerConfig::isItemId);
        FOOD_AMOUNT = B.defineInRange("foodAmount", defaults.foodAmount, 1, 64);
        B.pop();

        B.push("trainer");
        TRAINING_MANUAL_ITEM = B.comment(
                        "Physical theory manual the trainer hands to recruits and guards.")
                .define("manualItem", defaults.trainingManualItem, StrajaServerConfig::isItemId);
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
        ROOM_MIN_INTERIOR_X = B.defineInRange("minInteriorX", defaults.roomMinInteriorX, 1, 32);
        ROOM_MIN_INTERIOR_Y = B.defineInRange("minInteriorY", defaults.roomMinInteriorY, 1, 32);
        ROOM_MIN_INTERIOR_Z = B.defineInRange("minInteriorZ", defaults.roomMinInteriorZ, 1, 32);
        ROOM_REQUIRE_SINGLE_DOOR = B.comment(
                        "Rooms and cells must have exactly one standard two-block door.")
                .define("requireSingleDoor", defaults.roomRequireSingleDoor);
        ROOM_ALLOWED_ACCESS_BLOCKS = B.comment(
                        "Extra block IDs treated as allowed openings inside room walls",
                        "(e.g. barred windows). Empty = solid walls only.")
                .defineListAllowEmpty(List.of("allowedAccessBlocks"),
                        defaults.roomAllowedAccessBlocks, () -> "", o -> o instanceof String);
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
        FINE_ALLOWED_AMOUNTS = B.comment(
                        "Fine amounts a draft may select, in coins.")
                .defineListAllowEmpty(List.of("allowedAmounts"),
                        defaults.fineAllowedAmounts, () -> 10,
                        o -> o instanceof Integer i && i > 0);
        SENTENCE_DAYS_BY_AMOUNT = B.comment(
                        "Prison sentence days imposed when an unpaid fine escalates,",
                        "keyed by fine amount. Entries: \"amount=days\".")
                .defineListAllowEmpty(List.of("sentenceDaysByAmount"),
                        StrajaPolicies.formatIntMap(defaults.sentenceDaysByAmount),
                        () -> "10=1", StrajaServerConfig::isIntMapEntry);
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
        COMPLAINT_DEFAULT_SEVERITY = B.defineInRange("defaultSeverity",
                defaults.complaintDefaultSeverity, 1, 4);
        COMPLAINT_MAX_REWARD = B.defineInRange("maxReward", 250, 0, 1000000);
        COMPLAINT_MAX_REWARD_PER_REVIEWER_PER_DAY = B.defineInRange("maxRewardPerReviewerPerDay", 1000, 0, 1000000);
        COMPLAINT_REWARD_BY_SEVERITY = B.comment(
                        "Investigator reward per complaint severity.",
                        "Entries: \"severity=coins\".")
                .defineListAllowEmpty(List.of("rewardBySeverity"),
                        StrajaPolicies.formatIntMap(defaults.complaintRewardBySeverity),
                        () -> "1=25", StrajaServerConfig::isIntMapEntry);
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
        p.commissionerTitle = COMMISSIONER_TITLE.get();
        p.nativeFactionMaxLength = NATIVE_FACTION_MAX_LENGTH.get();
        p.requireUuid = REQUIRE_UUID.get();
        p.allowNameFallback = ALLOW_NAME_FALLBACK.get();
        p.environment = ENVIRONMENT.get();

        p.checkpointUnlockMinutes = CHECKPOINT_UNLOCK_MINUTES.get();
        p.checkpointDeadlineMinutes = CHECKPOINT_DEADLINE_MINUTES.get();
        p.serviceBlockMinutes = SERVICE_BLOCK_MINUTES.get();
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
        p.missionRewardOverrideMargin = MISSION_REWARD_OVERRIDE_MARGIN.get();

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
        p.restraintActionLock = RESTRAINT_ACTION_LOCK.get();

        p.downedEnabled = DOWNED_ENABLED.get();
        p.downedCooldownSeconds = DOWNED_COOLDOWN_SECONDS.get();
        p.downedSlownessTicks = DOWNED_SLOWNESS_TICKS.get();
        p.downedSlownessAmplifier = DOWNED_SLOWNESS_AMPLIFIER.get();
        p.downedWakeHealthRatio = DOWNED_WAKE_HEALTH_RATIO.get();
        p.downedFreezeInPlace = DOWNED_FREEZE_IN_PLACE.get();
        p.downedActionLock = DOWNED_ACTION_LOCK.get();
        p.downedDurationSeconds = DOWNED_COOLDOWN_SECONDS.get();
        p.downedCooldownSeconds = DOWNED_COOLDOWN_SECONDS.get();

        p.carryTransportDeadlineSeconds = CARRY_TRANSPORT_DEADLINE_SECONDS.get();
        p.resuscitationTimeoutSeconds = RESUSCITATION_TIMEOUT_SECONDS.get();
        p.resuscitationProgressPercent = RESUSCITATION_PROGRESS_PERCENT.get();
        p.unconsciousCustodyDurationSeconds = UNCONSCIOUS_CUSTODY_DURATION_SECONDS.get();
        p.jailDeliveryDeadlineSeconds = JAIL_DELIVERY_DEADLINE_SECONDS.get();
        p.jailAutomaticRevivalEnabled = JAIL_AUTOMATIC_REVIVAL_ENABLED.get();
        p.jailAutomaticRevivalDelaySeconds = JAIL_AUTOMATIC_REVIVAL_DELAY_SECONDS.get();
        p.secondWeaponHitBehavior = SECOND_WEAPON_HIT_BEHAVIOR.get();
        p.ordinaryDamageBehavior = ORDINARY_DAMAGE_BEHAVIOR.get();
        p.nonWeaponDamageBehavior = NON_WEAPON_DAMAGE_BEHAVIOR.get();
        p.exceptionalDamageBehavior = EXCEPTIONAL_DAMAGE_BEHAVIOR.get();
        p.criminalRopeEnabled = CRIMINAL_ROPE_ENABLED.get();
        p.criminalCutterEnabled = CRIMINAL_CUTTER_ENABLED.get();
        p.policeCuffsEnabled = POLICE_CUFFS_ENABLED.get();
        p.universalKeyEnabled = UNIVERSAL_KEY_ENABLED.get();
        p.blackSackApplicationEnabled = BLACK_SACK_APPLICATION_ENABLED.get();
        p.blackSackRemovalEnabled = BLACK_SACK_REMOVAL_ENABLED.get();
        p.blackSackSelfRemoval = BLACK_SACK_SELF_REMOVAL.get();
        p.logoutRecoveryBehavior = LOGOUT_RECOVERY_BEHAVIOR.get();
        p.restartRecoveryBehavior = RESTART_RECOVERY_BEHAVIOR.get();
        p.deathRecoveryBehavior = DEATH_RECOVERY_BEHAVIOR.get();
        p.dimensionChangeRecoveryBehavior = DIMENSION_CHANGE_RECOVERY_BEHAVIOR.get();
        p.missingDestinationRecoveryBehavior = MISSING_DESTINATION_RECOVERY_BEHAVIOR.get();

        p.arrestRewardMinimum = ARREST_REWARD_MIN.get();
        p.arrestRewardMaximum = ARREST_REWARD_MAX.get();
        p.arrestMaxDailyPayout = ARREST_REWARD_MAX_DAILY.get();
        p.arrestMinorDivider = ARREST_MINOR_DIVIDER.get();
        p.arrestAliveMultiplier = ARREST_ALIVE_MULTIPLIER.get();
        p.arrestDeathMultiplier = ARREST_DEATH_MULTIPLIER.get();

        p.coinItemIds = new java.util.LinkedHashMap<>();
        p.coinItemIds.put(1, COIN_BRONZE_ITEM.get());
        p.coinItemIds.put(64, COIN_BRASS_ITEM.get());
        p.coinItemIds.put(4096, COIN_SILVER_ITEM.get());
        p.coinItemIds.put(262144, COIN_GOLD_ITEM.get());

        p.salaryPerHour = mapOrDefault(StrajaPolicies.parseIntMap(SALARY_PER_HOUR.get()),
                defaults().salaryPerHour);
        p.salaryCommissionerPerHour = SALARY_COMMISSIONER_PER_HOUR.get();
        p.salaryGranularitySeconds = SALARY_GRANULARITY_SECONDS.get();
        p.salaryMaxPaidMinutesPerDay = SALARY_MAX_PAID_MINUTES_PER_DAY.get();
        p.salaryWindowMinutes = SALARY_WINDOW_MINUTES.get();
        p.salaryActivityGraceSeconds = SALARY_ACTIVITY_GRACE_SECONDS.get();
        p.salaryActivityMoveThreshold = SALARY_ACTIVITY_MOVE_THRESHOLD.get();
        p.rankNames = mapOrDefault(StrajaPolicies.parseIntStringMap(RANK_NAMES.get()),
                defaults().rankNames);
        p.rankPrefixChat = RANK_PREFIX_CHAT.get();
        p.rankPrefixTab = RANK_PREFIX_TAB.get();
        p.rankPrefixNameplate = RANK_PREFIX_NAMEPLATE.get();
        p.comisarTitle = COMISAR_TITLE.get();
        p.reportIntervalDays = REPORT_INTERVAL_DAYS.get();
        p.reportBlockDutyWhenOverdue = REPORT_BLOCK_DUTY_WHEN_OVERDUE.get();
        p.audienceNotifyCooldownSeconds = AUDIENCE_NOTIFY_COOLDOWN.get();
        p.promotionServiceBlocks = mapOrDefault(StrajaPolicies.parseIntMap(PROMOTION_SERVICE_BLOCKS.get()),
                defaults().promotionServiceBlocks);
        p.freeDutyMinRank = FREE_DUTY_MIN_RANK.get();
        p.secretaryRadiusBlocks = SECRETARY_RADIUS_BLOCKS.get();
        p.captureDutyFaction = CAPTURE_DUTY_FACTION.get();
        p.strajaTeamName = STRAJA_TEAM_NAME.get();
        p.emergencyUrgencyTtlMinutes = EMERGENCY_URGENCY_TTL.get();
        p.emergencyPayMultiplier = EMERGENCY_PAY_MULTIPLIER.get();
        p.emergencyMaxPayMultiplier = EMERGENCY_MAX_PAY_MULTIPLIER.get();
        p.emergencyPatrolRounds = EMERGENCY_PATROL_ROUNDS.get();
        p.emergencyMaxPatrolRounds = EMERGENCY_MAX_PATROL_ROUNDS.get();
        p.quiz = listOrDefault(StrajaPolicies.parseQuiz(QUIZ_QUESTIONS.get()), defaults().quiz);
        p.trainingQuiz = listOrDefault(StrajaPolicies.parseQuiz(TRAINING_QUIZ_QUESTIONS.get()),
                defaults().trainingQuiz);
        p.jailerName = JAILER_NAME.get();
        p.jailerGuardImmunity = JAILER_GUARD_IMMUNITY.get();
        p.jailerAssaultWoundedAmount = JAILER_ASSAULT_WOUNDED.get();
        p.jailerAssaultKilledAmount = JAILER_ASSAULT_KILLED.get();
        p.jailerAssaultSentenceDays = JAILER_ASSAULT_SENTENCE_DAYS.get();
        p.jailerAssaultMissionMaxAssignees = JAILER_ASSAULT_MISSION_MAX_ASSIGNEES.get();
        p.kits = mapOrDefault(StrajaPolicies.parseKits(KITS.get()), defaults().kits);
        p.serviceEquipment = mapOrDefault(StrajaPolicies.parseEquipment(SERVICE_EQUIPMENT.get()),
                defaults().serviceEquipment);
        p.regearCost = mapOrDefault(StrajaPolicies.parseIntMap(REGEAR_COST.get()), defaults().regearCost);
        p.foodItem = FOOD_ITEM.get();
        p.foodAmount = FOOD_AMOUNT.get();
        p.trainingManualItem = TRAINING_MANUAL_ITEM.get();
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
        p.roomMinInteriorX = ROOM_MIN_INTERIOR_X.get();
        p.roomMinInteriorY = ROOM_MIN_INTERIOR_Y.get();
        p.roomMinInteriorZ = ROOM_MIN_INTERIOR_Z.get();
        p.roomRequireSingleDoor = ROOM_REQUIRE_SINGLE_DOOR.get();
        p.roomAllowedAccessBlocks = new java.util.ArrayList<>(ROOM_ALLOWED_ACCESS_BLOCKS.get());

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
        p.fineAllowedAmounts = new java.util.ArrayList<>(FINE_ALLOWED_AMOUNTS.get());
        p.sentenceDaysByAmount = mapOrDefault(StrajaPolicies.parseIntMap(SENTENCE_DAYS_BY_AMOUNT.get()),
                defaults().sentenceDaysByAmount);

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
        p.complaintDefaultSeverity = COMPLAINT_DEFAULT_SEVERITY.get();
        p.complaintMaxReward = COMPLAINT_MAX_REWARD.get();
        p.complaintMaxRewardPerReviewerPerDay = COMPLAINT_MAX_REWARD_PER_REVIEWER_PER_DAY.get();
        p.complaintRewardBySeverity = mapOrDefault(
                StrajaPolicies.parseIntMap(COMPLAINT_REWARD_BY_SEVERITY.get()),
                defaults().complaintRewardBySeverity);

        p.debugEnabled = DEBUG_ENABLED.get();
        p.debugLocalOnly = DEBUG_LOCAL_ONLY.get();
        p.debugRevealQuizAnswers = DEBUG_REVEAL_QUIZ_ANSWERS.get();
        p.debugAllowGrantRank = DEBUG_ALLOW_GRANT_RANK.get();
        p.debugAllowCommissionerOverride = DEBUG_ALLOW_COMMISSIONER_OVERRIDE.get();
        p.testCommandsEnabled = TEST_COMMANDS_ENABLED.get();
        return p;
    }

    /** Built-in defaults used as fallback when a configured list parses to nothing. */
    private static StrajaPolicies defaults() {
        return new StrajaPolicies();
    }

    /** Keeps the parsed map unless it is empty (cleared or fully malformed input). */
    private static <K, V> java.util.Map<K, V> mapOrDefault(
            java.util.Map<K, V> parsed, java.util.Map<K, V> fallback) {
        return parsed.isEmpty() ? fallback : parsed;
    }

    private static <T> java.util.List<T> listOrDefault(java.util.List<T> parsed, java.util.List<T> fallback) {
        return parsed.isEmpty() ? fallback : parsed;
    }

    private static boolean isItemId(Object o) {
        return o instanceof String s && ResourceLocation.tryParse(s) != null;
    }

    private static boolean isDamageBehavior(Object o) {
        return o instanceof String s && java.util.Arrays.stream(
                com.dwurdy.straja.domain.model.DamageBehavior.values())
                .anyMatch(value -> value.name().equalsIgnoreCase(s.trim()));
    }

    private static boolean isRecoveryBehavior(Object o) {
        return o instanceof String s && java.util.Arrays.stream(
                com.dwurdy.straja.domain.model.RecoveryBehavior.values())
                .anyMatch(value -> value.name().equalsIgnoreCase(s.trim()));
    }

    private static boolean isIntMapEntry(Object o) {
        if (!(o instanceof String s)) return false;
        int sep = s.indexOf('=');
        if (sep <= 0) return false;
        try {
            Integer.parseInt(s.substring(0, sep).trim());
            Integer.parseInt(s.substring(sep + 1).trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isIntStringMapEntry(Object o) {
        if (!(o instanceof String s)) return false;
        int sep = s.indexOf('=');
        if (sep <= 0 || sep == s.length() - 1) return false;
        try {
            Integer.parseInt(s.substring(0, sep).trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isKitEntry(Object o) {
        if (!(o instanceof String s)) return false;
        int sep = s.indexOf('=');
        return sep > 0 && ResourceLocation.tryParse(
                s.substring(sep + 1).split(",")[0].trim()) != null;
    }

    private static boolean isEquipmentEntry(Object o) {
        if (!(o instanceof String s)) return false;
        int sep = s.indexOf('=');
        if (sep <= 0) return false;
        String[] parts = s.substring(sep + 1).split("\\|", 5);
        return parts.length >= 4 && ResourceLocation.tryParse(parts[1].trim()) != null;
    }

    private static boolean isQuizEntry(Object o) {
        return o instanceof String s && s.split("\\|", 4).length >= 4;
    }
}
