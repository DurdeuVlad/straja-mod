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

    // deployment gates
    public boolean requireRealCoinProviderOutsideLocal = true;
    public boolean requireCommissionerUuidOutsideLocal = true;
    public boolean requireDebugDisabledOutsideLocal = true;

    // timers
    public int checkpointUnlockMinutes = 10;
    public int checkpointDeadlineMinutes = 30;
    public int salaryBlockMinutes = 10;
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
    public boolean ropeRequiresCuffs = true;
    public int ropeSlownessTicks = 40;
    public int ropeSlownessAmplifier = 1;
    public int headSackBlindnessTicks = 40;
    public boolean restraintActionLock = true;

    // downed
    public boolean downedEnabled = true;
    public int downedCooldownSeconds = 60;
    public boolean downedFreezeInPlace = true;
    public boolean downedActionLock = true;
    public int downedSlownessTicks = 40;
    public int downedSlownessAmplifier = 255;
    public double downedWakeHealthRatio = 0.5;

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

    /** The jailer is damageable; guard immunity only blocks on-duty guards. */
    public boolean jailerDamageAllowed(boolean attackerOnDutyGuard) {
        return !jailerGuardImmunity || !attackerOnDutyGuard;
    }

    // salary
    public Map<Integer, Integer> salaryPerBlock = new LinkedHashMap<>(Map.of(1, 20, 2, 30, 3, 40, 4, 50));
    public int salaryMaxBlocksPerDay = 24;
    public int salaryWindowMinutes = 24 * 60;
    public int salaryActivityGraceSeconds = 90;
    public double salaryActivityMoveThreshold = 0.15;

    // promotions
    public Map<Integer, Integer> promotionServiceBlocks = new LinkedHashMap<>(Map.of(2, 60, 3, 180));

    // free duty: senior ranks and the commissioner run shifts without the
    // patrol route; salary accrues per minecraftDayMinutes of duty and the
    // anti-AFK movement gate is intentionally not applied to them.
    public int freeDutyMinRank = 3;
    public Map<Integer, Integer> freeDutySalaryPerDay = new LinkedHashMap<>(Map.of(3, 80, 4, 100));
    public int nativeFactionMaxLength = 40;

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
                    "Ce face un Străjer Senior într-o plângere? (scrie: investigheaza)",
                    List.of("investigheaza", "investighează", "investigatie", "investigație"))));

    // trainer
    public String trainingManualItem = "straja:training_manual";

    // coins
    public Map<Integer, String> coinItemIds = new LinkedHashMap<>(Map.of(
            1, "adys_decorations:bronze_coin",
            10, "adys_decorations:brass_coin",
            100, "adys_decorations:silver_coin",
            1000, "adys_decorations:gold_coin"));

    // food/kits/equipment
    public String foodItem = "minecraft:bread";
    public int foodAmount = 8;
    public Map<Integer, List<ItemSpec>> kits = defaultKits();
    public Map<Integer, List<EquipmentEntry>> serviceEquipment = defaultServiceEquipment();
    public int serviceLeaseMinutes = 240;
    public Map<Integer, Integer> regearCost = new LinkedHashMap<>(Map.of(1, 100, 2, 250, 3, 500, 4, 1000));

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

    public int salaryPerBlock(int rank) {
        return salaryPerBlock.getOrDefault(rank, 0);
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
                ItemSpec.of("minecraft:shield", 1)));
        kits.put(3, List.of(
                ItemSpec.of("minecraft:iron_helmet", 1),
                ItemSpec.of("minecraft:iron_chestplate", 1),
                ItemSpec.of("minecraft:iron_leggings", 1),
                ItemSpec.of("minecraft:iron_boots", 1),
                ItemSpec.of("minecraft:diamond_sword", 1),
                ItemSpec.of("minecraft:shield", 1)));
        kits.put(4, List.of(
                ItemSpec.of("minecraft:diamond_helmet", 1),
                ItemSpec.of("minecraft:diamond_chestplate", 1),
                ItemSpec.of("minecraft:diamond_leggings", 1),
                ItemSpec.of("minecraft:diamond_boots", 1),
                ItemSpec.of("minecraft:diamond_sword", 1),
                ItemSpec.of("minecraft:shield", 1)));
        return kits;
    }

    private static Map<Integer, List<EquipmentEntry>> defaultServiceEquipment() {
        Map<Integer, List<EquipmentEntry>> equipment = new LinkedHashMap<>();
        equipment.put(1, List.of(
                new EquipmentEntry("sword", "minecraft:iron_sword", 1, 100, "sabie de serviciu")));
        equipment.put(2, List.of(
                new EquipmentEntry("sword", "minecraft:iron_sword", 1, 100, "sabie de serviciu"),
                new EquipmentEntry("baton", "straja:baton", 1, 100, "baston de serviciu"),
                new EquipmentEntry("cuffs", "straja:cuffs", 1, 100, "cătușe de serviciu")));
        equipment.put(3, List.of(
                new EquipmentEntry("sword", "minecraft:diamond_sword", 1, 500, "sabie de serviciu"),
                new EquipmentEntry("baton", "straja:baton", 1, 100, "baston de serviciu"),
                new EquipmentEntry("cuffs", "straja:cuffs", 1, 100, "cătușe de serviciu")));
        equipment.put(4, List.of(
                new EquipmentEntry("sword", "minecraft:diamond_sword", 1, 500, "sabie de serviciu"),
                new EquipmentEntry("baton", "straja:baton", 1, 100, "baston de serviciu"),
                new EquipmentEntry("cuffs", "straja:cuffs", 1, 100, "cătușe de serviciu")));
        return equipment;
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

    public record EquipmentEntry(String key, String itemId, int count, int replacementCost, String label) {}

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

    /** "rank=key|itemId|count|replacementCost|label" entries; malformed entries are skipped. */
    public static Map<Integer, List<EquipmentEntry>> parseEquipment(List<? extends String> entries) {
        Map<Integer, List<EquipmentEntry>> out = new LinkedHashMap<>();
        for (String entry : entries) {
            int sep = entry.indexOf('=');
            if (sep <= 0) continue;
            String[] parts = entry.substring(sep + 1).split("\\|", 5);
            if (parts.length < 4 || parts[0].isBlank() || parts[1].isBlank()) continue;
            try {
                int rank = Integer.parseInt(entry.substring(0, sep).trim());
                int count = Integer.parseInt(parts[2].trim());
                int cost = Integer.parseInt(parts[3].trim());
                String label = parts.length > 4 ? parts[4].trim() : parts[0].trim();
                out.computeIfAbsent(rank, r -> new ArrayList<>())
                        .add(new EquipmentEntry(parts[0].trim(), parts[1].trim(), count, cost, label));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static List<String> formatEquipment(Map<Integer, List<EquipmentEntry>> equipment) {
        List<String> out = new ArrayList<>();
        equipment.forEach((rank, entries) -> entries.forEach(e -> out.add(
                rank + "=" + e.key() + "|" + e.itemId() + "|" + e.count() + "|" + e.replacementCost() + "|" + e.label())));
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
