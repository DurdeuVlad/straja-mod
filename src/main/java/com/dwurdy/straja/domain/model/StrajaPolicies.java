package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All tunable rules, resolved at bootstrap. Domain/application code reads this
 * object only — never ModConfigSpec — so tests construct it directly.
 * Defaults mirror kubejs/startup_scripts/00_straja_config.js.
 */
public class StrajaPolicies {
    // identity
    public String commissionerName = "dwurdy";
    public String commissionerUuid = "";
    public boolean requireUuid;
    public boolean allowNameFallback = true;
    public String commissionerTitle = "Comisaru'";
    public String environment = "local";

    // physical identity cards
    public boolean identityCardsEnabled = true;
    public int identityCardValidityDays = 30;

    // deployment gates
    public boolean requireRealCoinProviderOutsideLocal = true;
    public boolean requireCommissionerUuidOutsideLocal = true;
    public boolean requireDebugDisabledOutsideLocal = true;

    // timers
    public int checkpointUnlockMinutes = 5;
    public int checkpointDeadlineMinutes = 30;
    /** Minimum placed checkpoints required to start a NORMAL patrol. */
    public int patrolMinCheckpoints = 2;
    /** Laps of the route that complete a patrol (0 = endless loop). */
    public int patrolRounds = 1;
    /** Hard real-time ceiling for a NORMAL patrol; <=0 disables. */
    public int patrolMaxMinutes = 60;
    /** Upper bound on admin-defined checkpoint slots. */
    public int patrolMaxCheckpoints = 16;
    public int foodCooldownMinutes = 30;
    public int quizCooldownMinutes = 10;
    public int resignationCooldownDays = 7;
    public int resignationNoticeMinutes = 15;

    // missions
    public int missionMinMinutes = 1;
    public int missionMaxMinutes = 240;
    public int missionMaxScheduleDays = 7;
    public int missionMaxReward = 250;
    public int missionMaxCopiesPerDraft = 4;
    public int missionMaxRewardPool = 1000;
    public int missionMaxRewardPerIssuerPerDay = 1000;
    public int missionMaxActivePerPlayer = 3;
    public int missionDefaultMinimumRank = 1;
    public int missionMaxAssignees = 4;
    public int missionRetentionLimit = 200;
    public boolean missionQuickCreateEnabled = true;
    public boolean missionQuickCreateLocalOnly = true;
    // §13: rewards above calculated × (1 + margin) require an explicit,
    // audited reason.
    public double missionRewardOverrideMargin = 0.25;

    // operational incidents, whistle and dispatch
    public boolean incidentsEnabled = true;
    public int incidentCitizenReportCooldownSeconds = 60;
    public int incidentMaxActiveCitizenReports = 3;
    public int incidentDefaultExpirationSeconds = 900;
    public int incidentMaxSupportingGuards = 3;
    public int incidentMaxDescriptionLength = 240;
    public boolean whistleEnabled = true;
    public int whistleCooldownSeconds = 120;
    public double whistleSoundRadius = 32;

    // BOLO notices remain distinct from authoritative arrest tasks.
    public boolean bolosEnabled = true;
    public int boloMinimumIssuerRank = 3;
    public int boloCancellationMinimumRank = 4;
    public int boloDefaultExpirationSeconds = 3600;
    public int boloMaxReasonLength = 240;
    public int boloMaxActivePerSubject = 3;

    // search/confiscation/evidence
    public boolean evidenceEnabled = true;
    public double searchRangeBlocks = 4;
    public int evidenceMaxReasonLength = 200;

    // cuffs
    public int cuffRequestTimeoutSeconds = 60;
    public int surrenderTimeoutSeconds = 15;
    public int cuffSlownessTicks = 40;
    public int cuffSlownessAmplifier = 0;
    public boolean cuffActionLock = true;
    public boolean hideHeldItemWhenPossible = true;
    public double cuffBreakDistance = 32;
    public int cuffBreakGraceSeconds = 10;
    public List<String> genericKeyTokens = new ArrayList<>(List.of("key", "keycard", "lockpick"));

    // restraints
    public int ropeSlownessTicks = 40;
    public int ropeSlownessAmplifier = 1;
    public int headSackBlindnessTicks = 40;
    public boolean restraintActionLock = true;

    // downed
    public boolean downedEnabled = true;
    /** Canonical downed deadline; downedCooldownSeconds remains a legacy alias. */
    public int downedDurationSeconds = 60;
    public int downedCooldownSeconds = 60;
    public boolean downedFreezeInPlace = true;
    public boolean downedActionLock = true;
    public int downedSlownessTicks = 40;
    public int downedSlownessAmplifier = 255;
    public double downedWakeHealthRatio = 0.5;

    // downed/custody domain contract — consumed by CustodyTransitionEngine.
    public int carryTransportDeadlineSeconds = 120;
    public int resuscitationTimeoutSeconds = 30;
    public int resuscitationProgressPercent = 100;
    public int unconsciousCustodyDurationSeconds = 120;
    public int jailDeliveryDeadlineSeconds = 300;
    public boolean jailAutomaticRevivalEnabled = true;
    public int jailAutomaticRevivalDelaySeconds = 30;
    public String secondWeaponHitBehavior = "PRESERVE_DOWNED";
    public String ordinaryDamageBehavior = "ALLOW";
    public String nonWeaponDamageBehavior = "ALLOW";
    public String exceptionalDamageBehavior = "KILL";
    public boolean criminalRopeEnabled = true;
    public boolean criminalCutterEnabled = true;
    public boolean policeCuffsEnabled = true;
    public boolean universalKeyEnabled = true;
    public boolean blackSackApplicationEnabled = true;
    public boolean blackSackRemovalEnabled = true;
    public boolean blackSackSelfRemoval = true;
    public String logoutRecoveryBehavior = "RETAIN";
    public String restartRecoveryBehavior = "RETAIN";
    public String deathRecoveryBehavior = "CLEAR_ALL";
    public String dimensionChangeRecoveryBehavior = "RELEASE_TRANSPORT";
    public String missingDestinationRecoveryBehavior = "RELEASE_TRANSPORT";

    /** Typed accessors keep adapters from interpreting raw config strings. */
    public DamageBehavior damageBehavior(DamageCategory category) {
        if (category == null) return DamageBehavior.CANCEL;
        return switch (category) {
            case SECOND_WEAPON_HIT -> parseDamage(secondWeaponHitBehavior, DamageBehavior.PRESERVE_DOWNED);
            case ORDINARY -> parseDamage(ordinaryDamageBehavior, DamageBehavior.ALLOW);
            case NON_WEAPON -> parseDamage(nonWeaponDamageBehavior, DamageBehavior.ALLOW);
            case EXCEPTIONAL -> parseDamage(exceptionalDamageBehavior, DamageBehavior.KILL);
        };
    }

    public RecoveryBehavior recoveryBehavior(RecoveryEvent event) {
        if (event == null) return RecoveryBehavior.CLEAR_ALL;
        return switch (event) {
            case LOGOUT -> parseRecovery(logoutRecoveryBehavior, RecoveryBehavior.RETAIN);
            case RESTART -> parseRecovery(restartRecoveryBehavior, RecoveryBehavior.RETAIN);
            case DEATH -> parseRecovery(deathRecoveryBehavior, RecoveryBehavior.CLEAR_ALL);
            case DIMENSION_CHANGE -> parseRecovery(dimensionChangeRecoveryBehavior,
                    RecoveryBehavior.RELEASE_TRANSPORT);
            case MISSING_DESTINATION -> parseRecovery(missingDestinationRecoveryBehavior,
                    RecoveryBehavior.RELEASE_TRANSPORT);
        };
    }

    private static DamageBehavior parseDamage(String raw, DamageBehavior fallback) {
        try {
            return DamageBehavior.valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static RecoveryBehavior parseRecovery(String raw, RecoveryBehavior fallback) {
        try {
            return RecoveryBehavior.valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    // arrest rewards
    public int arrestRewardMinimum = 25;
    public int arrestRewardMaximum = 1000;
    public int arrestMaxDailyPayout = 1000;
    public int arrestMinorDivider = 5;
    public int arrestAliveMultiplier = 4;
    public int arrestDeathMultiplier = 1;

    // jailer
    public String jailerName = "Temnicerul Străjii";
    public int jailerAssaultWoundedAmount = 250;
    public int jailerAssaultKilledAmount = 500;
    public int jailerAssaultSentenceDays = 1;
    public int jailerAssaultMissionMaxAssignees = 4;
    public boolean jailerGuardImmunity = true;

    // persistent reputation, deliberately independent from rank and merit
    public boolean reputationEnabled = true;
    public int reputationMinScore = -1000;
    public int reputationMaxScore = 1000;
    public int reputationOrdinaryKillDelta = -120;
    /** Killing a restrained, downed or custody-bound player is a distinct, harsher event. */
    public int reputationRestrainedKillDelta = -250;
    public int reputationKillOnDutyGuardDelta = -200;
    public int reputationJailerKillDelta = -180;
    public int reputationJailerAssaultDelta = -60;
    public int reputationGuardExecutionDelta = -250;
    public int reputationPrisonEscapeDelta = -100;
    public int reputationCustodyEscapeDelta = -40;
    public int reputationFineRefusalDelta = -25;
    public int reputationSentenceCompletionDelta = 60;
    public int reputationPrisonTaskDelta = 5;
    public int reputationPrisonTaskCap = 25;
    public int reputationFinePaymentDelta = 10;
    public int recruitmentMinReputation = -100;
    public int lawfulHostilityWindowSeconds = 30;

    /** The jailer is damageable; guard immunity only blocks on-duty guards. */
    public boolean jailerDamageAllowed(boolean attackerOnDutyGuard) {
        return !jailerGuardImmunity || !attackerOnDutyGuard;
    }

    // salary — hourly wage per docs/gameplay-decisions.md §10:
    // Stagiar 16, Străjer 24, Sergent 36, Inspector 64, Comisar 128 Bronze/h,
    // accrued continuously at salaryGranularitySeconds with sub-coin carry.
    public Map<Integer, Integer> salaryPerHour = new LinkedHashMap<>(Map.of(1, 16, 2, 24, 3, 36, 4, 64));
    public int salaryCommissionerPerHour = 128;
    public int salaryGranularitySeconds = 60;
    public int serviceBlockMinutes = 10;
    public int salaryMaxPaidMinutesPerDay = 240;
    public int salaryWindowMinutes = 24 * 60;
    public int salaryActivityGraceSeconds = 90;
    public double salaryActivityMoveThreshold = 0.15;

    // rank display names (§2 ladder: Stagiar → Străjer → Sergent → Inspector;
    // Comisar is a personnel flag, not a ladder step)
    public Map<Integer, String> rankNames = new LinkedHashMap<>(Map.of(
            1, "Stagiar", 2, "Străjer", 3, "Sergent", 4, "Inspector"));

    // promotions
    public Map<Integer, Integer> promotionServiceBlocks = new LinkedHashMap<>(Map.of(2, 60, 3, 180));
    /** Rank-up bonus in hours of the new rank's hourly wage, credited to
     * unpaidSalary on every effective promotion. */
    public int promotionBonusHours = 5;

    // requisition — spendable merit earned per service block, spent on armory
    // reserves and docked by punishments. Distinct from serviceBlocks, which
    // remain promotion-only.
    public int requisitionPointsPerBlock = 1;
    public int demotionServiceBlockCost = 30;
    public int suspensionRequisitionCost = 20;

    // free duty: senior ranks and the commissioner run shifts without the
    // patrol route; they earn the same hourly wage and the anti-AFK movement
    // gate is intentionally not applied to them (trusted ranks, §10).
    public int freeDutyMinRank = 3;
    public int nativeFactionMaxLength = 40;

    // §11: activity reports — a reporting interval runs per member; overdue
    // reports optionally block duty start at the Secretary.
    public int reportIntervalDays = 7;
    public boolean reportBlockDutyWhenOverdue = false;
    // §12: Comisar notifications for new audience requests are coalesced —
    // at most one tell per cooldown window regardless of request count.
    public int audienceNotifyCooldownSeconds = 60;

    // §4: bracketed rank prefix on chat / TAB / nameplate — per-surface flags;
    // the composition uses the configurable rank display names.
    public boolean rankPrefixChat = true;
    public boolean rankPrefixTab = true;
    public boolean rankPrefixNameplate = false;
    public String comisarTitle = "Comisar";

    // §7: normal shifts start and end at the Secretary — the player's
    // scoreboard team is captured on duty start, the operational Straja team
    // is applied, and the captured team is restored on duty end.
    public int secretaryRadiusBlocks = 8;
    public boolean captureDutyFaction = true;
    public String strajaTeamName = "Straja";

    // §25 emergency system: a TTL-bound urgency call and a sustained
    // emergency mode that multiplies hourly wages and requires extra
    // patrol rounds per shift.
    public int emergencyUrgencyTtlMinutes = 60;
    public double emergencyPayMultiplier = 2.0;
    public double emergencyMaxPayMultiplier = 5.0;
    public int emergencyPatrolRounds = 2;
    public int emergencyMaxPatrolRounds = 10;

    // quiz
    public List<QuizQuestion> quiz = new ArrayList<>(List.of(
            new QuizQuestion("juramant", 0,
                    "Care este regula de bază a Străjii? (scrie: disciplina)",
                    List.of("disciplina")),
            new QuizQuestion("checkpoint", 0,
                    "Câte minute ai pentru a ajunge la următorul checkpoint? (scrie: 30)",
                    List.of("30", "30 minute", "30 de minute")),
            new QuizQuestion("raport", 0,
                    "Cui trimiți raportul final? (scrie: comisaru)",
                    List.of("comisaru", "dwurdy"))));
    public List<QuizQuestion> trainingQuiz = new ArrayList<>(List.of(
            new QuizQuestion("junior_fines_standard", 1,
                    "Cum alegi cuantumul unei amenzi? (scrie: tarif standard)",
                    List.of("tarif standard", "standard", "suma standard")),
            new QuizQuestion("junior_fines_reception", 1,
                    "Unde se plătește amenda? (scrie: recepționistă)",
                    List.of("receptionista", "recepționistă", "receptie", "recepție")),
            new QuizQuestion("guard_cuffs_consent", 2,
                    "Ce precede încătușarea normală? (scrie: acordul)",
                    List.of("acordul", "consimțământul", "acceptarea")),
            new QuizQuestion("guard_arrest_process", 2,
                    "Ce condiție trebuie înaintea arestării? (scrie: misiune)",
                    List.of("misiune", "mandat", "misiune sau mandat")),
            new QuizQuestion("senior_orders_lower", 3,
                    "Ce face un Sergent într-o plângere? (scrie: investigheaza)",
                    List.of("investigheaza", "investighează", "investigatie", "investigație"))));

    // trainer
    public String trainingManualItem = "straja:training_manual";

    // coins — 64:1 ladder per docs/gameplay-decisions.md §10:
    // Bronze → Brass → Silver → Gold. Values are Bronze-equivalents.
    public Map<Integer, String> coinItemIds = new LinkedHashMap<>(Map.of(
            1, "adys_decorations:bronze_coin",
            64, "adys_decorations:brass_coin",
            4096, "adys_decorations:silver_coin",
            262144, "adys_decorations:gold_coin"));

    // food/kits/equipment — kits are permanent owned gear granted at rank-up;
    // the former per-shift lease was removed (see docs/guard-gear-*).
    public String foodItem = "minecraft:bread";
    public int foodAmount = 8;
    public Map<Integer, List<ItemSpec>> kits = defaultKits();

    // armory — the Armorer NPC shopkeeper. `armoryStock` sells rank-gated
    // gear for physical coins; `armoryReserves` prices replacement gear in
    // requisition points. Entries: {key, itemId, count, cost, minRank}.
    public List<ArmoryItem> armoryStock = new ArrayList<>(List.of(
            new ArmoryItem("baton", "straja:baton", 1, 200, 2),
            new ArmoryItem("whip", "straja:whip", 1, 200, 2),
            new ArmoryItem("cuffs", "straja:cuffs", 1, 200, 2),
            new ArmoryItem("sword_diamond", "minecraft:diamond_sword", 1, 500, 3),
            new ArmoryItem("armor_diamond", "minecraft:diamond_chestplate", 1, 800, 4)));
    public List<ArmoryItem> armoryReserves = new ArrayList<>(List.of(
            new ArmoryItem("sword", "minecraft:iron_sword", 1, 3, 1),
            new ArmoryItem("shield", "minecraft:shield", 1, 3, 1),
            new ArmoryItem("baton", "straja:baton", 1, 5, 2),
            new ArmoryItem("whip", "straja:whip", 1, 5, 2),
            new ArmoryItem("cuffs", "straja:cuffs", 1, 5, 2),
            new ArmoryItem("sword_diamond", "minecraft:diamond_sword", 1, 10, 3)));

    // envelope
    public boolean envelopeEnabled = true;
    public boolean envelopeFallbackToChat = true;
    public int envelopeMaxBodyLength = 900;

    // archive
    public boolean archiveEnabled = true;
    public boolean archiveCommissionerAlways = true;
    public int archiveMaxFoldersPerOwner = 10;
    public int archiveMaxSheetsPerFolder = 100;
    public int archiveMaxCatalogEntriesPerFolder = 200;
    public int archiveMaxTitleLength = 80;
    public int archiveMaxContentLength = 4000;
    public int archiveMaxRecipients = 8;
    public int archiveMaxCopiesPerOperation = 16;

    // rooms
    public int roomRetentionLimit = 32;
    public int roomMinInteriorX = 2;
    public int roomMinInteriorY = 3;
    public int roomMinInteriorZ = 3;
    public int roomMaxDimension = 8;
    public int roomMaxBlocks = 512;
    public boolean roomRequireSingleDoor = true;
    public int roomWaitlistRetentionDays = 30;
    public List<String> roomAllowedAccessBlocks = new ArrayList<>();

    // fines
    public boolean finesEnabled = true;
    public int fineOnlineDaysUntilEscalation = 14;
    public int minecraftDayMinutes = 20;
    public List<Integer> fineAllowedAmounts = new ArrayList<>(List.of(10, 25, 50, 100, 250, 500));
    public int fineMaxLawLength = 80;
    public int fineMaxDescriptionLength = 240;
    public boolean finePaymentRequiresReception = true;
    public boolean appealsEnabled = true;
    public int appealDecisionTimeoutRealDays = 5;
    public int appealMaxReasonLength = 240;
    public int appealMaxPerWindow = 5;
    public int appealAbuseWindowRealDays = 30;
    public int appealAbuseBlockRealDays = 7;
    public int escalationMissionMinutes = 30;
    public Map<Integer, Integer> sentenceDaysByAmount = new LinkedHashMap<>(Map.of(
            10, 1, 25, 1, 50, 1, 100, 2, 250, 2, 500, 3));

    // prison
    public boolean prisonEnabled = true;
    public int prisonRetentionLimit = 32;
    public int prisonMaxCells = 32;
    public int prisonActiveMinutesPerMinecraftDay = 20;
    public int prisonDefaultSentenceDays = 1;
    public int prisonAfkGraceSeconds = 60;
    public int prisonMaxSentenceDays = 7;
    public double prisonArrestRadius = 6;

    // audit
    public boolean auditEnabled = true;
    public int auditRetentionLimit = 500;

    // complaints
    public boolean complaintsEnabled = true;
    public int complaintMaxDescriptionLength = 400;
    public int complaintMaxEvidenceLength = 400;
    public int complaintMaxActivePerComplainant = 3;
    public int complaintMaxParticipants = 4;
    public int complaintDefaultSeverity = 1;
    public Map<Integer, Integer> complaintRewardBySeverity = new LinkedHashMap<>(Map.of(1, 25, 2, 50, 3, 100, 4, 200));
    public int complaintMaxReward = 250;
    public int complaintMaxRewardPerReviewerPerDay = 1000;

    // debug / test
    public boolean debugEnabled = true;
    public boolean debugLocalOnly = true;
    public boolean debugRevealQuizAnswers = true;
    public boolean debugAllowGrantRank = true;
    public boolean debugAllowCommissionerOverride = true;
    public boolean testCommandsEnabled;

    public boolean isLocalEnvironment() {
        return "local".equalsIgnoreCase(environment);
    }

    public int salaryPerHour(int rank) {
        return salaryPerHour.getOrDefault(rank, 0);
    }

    /** Configurable display name for a rank level; falls back to the enum name. */
    public String rankName(int rank) {
        String name = rankNames.get(rank);
        return name != null && !name.isBlank() ? name : Rank.of(rank).displayName();
    }

    private static Map<Integer, List<ItemSpec>> defaultKits() {
        Map<Integer, List<ItemSpec>> kits = new LinkedHashMap<>();
        kits.put(1, List.of(
                ItemSpec.of("minecraft:chainmail_helmet", 1),
                ItemSpec.of("minecraft:chainmail_chestplate", 1),
                ItemSpec.of("minecraft:chainmail_leggings", 1),
                ItemSpec.of("minecraft:chainmail_boots", 1),
                ItemSpec.of("minecraft:iron_sword", 1),
                ItemSpec.of("minecraft:shield", 1)));
        kits.put(2, List.of(
                ItemSpec.of("minecraft:iron_helmet", 1),
                ItemSpec.of("minecraft:iron_chestplate", 1),
                ItemSpec.of("minecraft:iron_leggings", 1),
                ItemSpec.of("minecraft:iron_boots", 1),
                ItemSpec.of("minecraft:iron_sword", 1),
                ItemSpec.of("minecraft:shield", 1),
                ItemSpec.of("straja:baton", 1),
                ItemSpec.of("straja:cuffs", 1)));
        kits.put(3, List.of(
                ItemSpec.of("minecraft:iron_helmet", 1),
                ItemSpec.of("minecraft:iron_chestplate", 1),
                ItemSpec.of("minecraft:iron_leggings", 1),
                ItemSpec.of("minecraft:iron_boots", 1),
                ItemSpec.of("minecraft:diamond_sword", 1),
                ItemSpec.of("minecraft:shield", 1),
                ItemSpec.of("straja:baton", 1),
                ItemSpec.of("straja:cuffs", 1)));
        kits.put(4, List.of(
                ItemSpec.of("minecraft:diamond_helmet", 1),
                ItemSpec.of("minecraft:diamond_chestplate", 1),
                ItemSpec.of("minecraft:diamond_leggings", 1),
                ItemSpec.of("minecraft:diamond_boots", 1),
                ItemSpec.of("minecraft:diamond_sword", 1),
                ItemSpec.of("minecraft:shield", 1),
                ItemSpec.of("straja:baton", 1),
                ItemSpec.of("straja:cuffs", 1)));
        return kits;
    }

    public record QuizQuestion(String id, int minRank, String question, List<String> answers) {
        public boolean accepts(String answer) {
            String normalized = answer == null ? "" : answer.trim().toLowerCase();
            for (String expected : answers) {
                if (expected.toLowerCase().equals(normalized)) return true;
            }
            return false;
        }
    }

    /** Armory offer: cost is Bronze coins in armoryStock, requisition points in armoryReserves. */
    public record ArmoryItem(String key, String itemId, int count, int cost, int minRank) {}

    // ------------------------------------------------------------------
    // config encoding: StrajaServerConfig serializes the same defaults through
    // these helpers and parses user entries back with them, so the file format
    // and the compiled defaults can never drift apart.
    // ------------------------------------------------------------------

    /** "key=value" integer entries; malformed entries are skipped. */
    public static Map<Integer, Integer> parseIntMap(List<? extends String> entries) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        for (String entry : entries) {
            int sep = entry.indexOf('=');
            if (sep <= 0) continue;
            try {
                out.put(Integer.parseInt(entry.substring(0, sep).trim()),
                        Integer.parseInt(entry.substring(sep + 1).trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<String> formatIntMap(Map<Integer, Integer> map) {
        List<String> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(k + "=" + v));
        return out;
    }

    /** "key=value" entries with integer keys and string values (coin items); malformed entries are skipped. */
    public static Map<Integer, String> parseIntStringMap(List<? extends String> entries) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (String entry : entries) {
            int sep = entry.indexOf('=');
            if (sep <= 0 || entry.substring(sep + 1).isBlank()) continue;
            try {
                out.put(Integer.parseInt(entry.substring(0, sep).trim()),
                        entry.substring(sep + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<String> formatIntStringMap(Map<Integer, String> map) {
        List<String> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(k + "=" + v));
        return out;
    }

    /** "rank=itemId,count" kit entries; malformed entries are skipped. */
    public static Map<Integer, List<ItemSpec>> parseKits(List<? extends String> entries) {
        Map<Integer, List<ItemSpec>> out = new LinkedHashMap<>();
        for (String entry : entries) {
            int sep = entry.indexOf('=');
            if (sep <= 0) continue;
            String[] parts = entry.substring(sep + 1).split(",");
            if (parts.length < 1 || parts[0].isBlank()) continue;
            try {
                int rank = Integer.parseInt(entry.substring(0, sep).trim());
                int count = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                if (count < 1) continue;
                out.computeIfAbsent(rank, r -> new ArrayList<>()).add(ItemSpec.of(parts[0].trim(), count));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<String> formatKits(Map<Integer, List<ItemSpec>> kits) {
        List<String> out = new ArrayList<>();
        kits.forEach((rank, items) -> items.forEach(item -> out.add(rank + "=" + item.id() + "," + item.count())));
        return out;
    }

    /** "key|itemId|count|cost|minRank" entries; malformed entries are skipped. */
    public static List<ArmoryItem> parseArmoryItems(List<? extends String> entries) {
        List<ArmoryItem> out = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.split("\\|", 5);
            if (parts.length < 5 || parts[0].isBlank() || parts[1].isBlank()) continue;
            try {
                int count = Integer.parseInt(parts[2].trim());
                int cost = Integer.parseInt(parts[3].trim());
                int minRank = Integer.parseInt(parts[4].trim());
                if (count < 1 || cost < 0) continue;
                out.add(new ArmoryItem(parts[0].trim(), parts[1].trim(), count, cost, minRank));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<String> formatArmoryItems(List<ArmoryItem> items) {
        List<String> out = new ArrayList<>();
        for (ArmoryItem item : items) {
            out.add(item.key() + "|" + item.itemId() + "|" + item.count() + "|" + item.cost() + "|" + item.minRank());
        }
        return out;
    }

    /** "id|minRank|question|answer1;answer2" entries; malformed entries are skipped. */
    public static List<QuizQuestion> parseQuiz(List<? extends String> entries) {
        List<QuizQuestion> out = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.split("\\|", 4);
            if (parts.length < 4 || parts[0].isBlank() || parts[2].isBlank()) continue;
            List<String> answers = new ArrayList<>();
            for (String a : parts[3].split(";")) if (!a.isBlank()) answers.add(a.trim());
            if (answers.isEmpty()) continue;
            try {
                out.add(new QuizQuestion(parts[0].trim(), Integer.parseInt(parts[1].trim()),
                        parts[2].trim(), answers));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<String> formatQuiz(List<QuizQuestion> questions) {
        List<String> out = new ArrayList<>();
        for (var q : questions) {
            out.add(q.id() + "|" + q.minRank() + "|" + q.question() + "|" + String.join(";", q.answers()));
        }
        return out;
    }
}
