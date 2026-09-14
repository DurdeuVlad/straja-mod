package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.MissionRoleplayUseCase;
import com.dwurdy.straja.application.port.out.DeliveryProvider;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.Mission;
import com.dwurdy.straja.domain.model.MissionBudget;
import com.dwurdy.straja.domain.model.MissionDraft;
import com.dwurdy.straja.domain.model.MissionStore;
import com.dwurdy.straja.domain.model.MissionTemplate;
import com.dwurdy.straja.domain.model.MissionTemplateStore;
import com.dwurdy.straja.domain.model.PermissionLevel;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.SetupData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mission lifecycle — a structural port of the reference mission subsystem:
 * draft → sign → package (Envelope sealed package) → issue to a subordinate →
 * invite/join → accept → report → complete → per-participant reward claims.
 * Every external effect is persisted before it runs so a crash or a failed
 * Envelope delivery never loses the mission or pays twice.
 */
public class MissionService implements MissionRoleplayUseCase {
    public static final String ORDER_BOOK = "straja:order_book";
    public static final String MISSION_CARNET = "straja:mission_carnet";
    private static final long MINUTE_MS = 60_000L;
    private static final long DAY_MS = 24 * 60 * MINUTE_MS;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public MissionService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    private MissionStore store() {
        return ctx.missions().read();
    }

    private static String playerKey(PlayerGateway player) {
        String uuid = player.uuid() == null ? "" : player.uuid().toString();
        return !uuid.isEmpty() ? uuid : PlayerService.canon(player.name());
    }

    private static boolean identityMatches(PlayerGateway player, Mission.Identity id) {
        return id != null && PlayerService.identityMatches(player, id.uuid, id.name);
    }

    private boolean missionAuthority(PlayerGateway player) {
        return players.isCommissioner(player)
                || players.hasCapability(player, Capability.CREATE_MISSIONS);
    }

    private boolean targetMatches(PlayerGateway player, Mission mission) {
        for (var entry : mission.assignees) {
            if (identityMatches(player, entry)) return true;
        }
        return false;
    }

    private boolean issuerMatches(PlayerGateway player, Mission mission) {
        return PlayerService.identityMatches(player, mission.issuerUuid, mission.issuer);
    }

    private boolean invitedMatches(PlayerGateway player, Mission mission) {
        for (var entry : mission.invited) {
            if (identityMatches(player, entry)) return true;
        }
        return false;
    }

    private boolean declinedMatches(PlayerGateway player, Mission mission) {
        for (var entry : mission.declined) {
            if (identityMatches(player, entry)) return true;
        }
        return false;
    }

    private boolean hasCarnet(PlayerGateway player) {
        var hand = player.mainHand();
        return hand != null && (ORDER_BOOK.equals(hand.id()) || MISSION_CARNET.equals(hand.id()));
    }

    private boolean atSecretary(PlayerGateway player) {
        SetupData.Location location = ctx.setup().read().location("secretary");
        if (location == null) return true; // unconfigured: local tests stay usable
        if (location.dimension != null && !location.dimension.equals(player.dimension())) return false;
        double dx = player.x() - location.x, dy = player.y() - location.y, dz = player.z() - location.z;
        return dx * dx + dy * dy + dz * dz <= 36;
    }

    private boolean timeValid(int minutes) {
        return minutes >= ctx.policies().missionMinMinutes && minutes <= ctx.policies().missionMaxMinutes;
    }

    private boolean rewardValid(int reward) {
        return reward >= 0 && reward <= ctx.policies().missionMaxReward;
    }

    /** Target must be an online active subordinate with a lower rank. */
    private String targetEligibility(PlayerGateway issuer, PlayerGateway target) {
        if (target == null || PlayerService.canon(target.name()).equals(PlayerService.canon(issuer.name()))) {
            return "self_or_offline";
        }
        GuardState targetState = players.state(target.uuid());
        if (targetState.rank < Rank.STAGIAR.level() || targetState.resigned || targetState.fired
                || targetState.suspended || targetState.resignationPending) return "inactive_target";
        if (!players.isCommissioner(issuer)) {
            GuardState issuerState = players.state(issuer.uuid());
            if (targetState.rank >= issuerState.rank) return "peer_or_superior";
        }
        return "ok";
    }

    private boolean targetAllowed(PlayerGateway issuer, PlayerGateway target) {
        String eligibility = targetEligibility(issuer, target);
        if ("ok".equals(eligibility)) return true;
        switch (eligibility) {
            case "self_or_offline" ->
                    issuer.tell("Misiunea trebuie predată unui subordonat online, nu emitentului.");
            case "inactive_target" ->
                    issuer.tell("Destinatarul trebuie să fie un străjer activ și nesuspendat.");
            default ->
                    issuer.tell("Misiunea poate fi predată doar unui rang inferior emitentului.");
        }
        return false;
    }

    private boolean scopeValid(PlayerGateway issuer, int minimumRank, int maxAssignees) {
        if (minimumRank < Rank.STAGIAR.level() || minimumRank > Rank.INSPECTOR.level()) {
            issuer.tell("Rangul minim trebuie să fie junior, străjer, senior sau locotenent.");
            return false;
        }
        int configuredMax = Math.max(1, ctx.policies().missionMaxAssignees);
        if (maxAssignees < 1 || maxAssignees > configuredMax) {
            issuer.tell("Numărul de participanți trebuie să fie între 1 și " + configuredMax + ".");
            return false;
        }
        if (!players.isCommissioner(issuer)
                && minimumRank >= players.state(issuer.uuid()).rank) {
            issuer.tell("Rangul minim trebuie să fie inferior rangului emitentului.");
            return false;
        }
        return true;
    }

    private int openCountFor(PlayerGateway target, MissionStore store) {
        int count = 0;
        for (Mission m : store.missions) {
            if (m.isOpen() && targetMatches(target, m)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------ issuer budget

    private long budgetWindow(long timestamp) {
        return timestamp / DAY_MS;
    }

    private int issuerRewardLimit() {
        return Math.max(0, ctx.policies().missionMaxRewardPerIssuerPerDay);
    }

    private record Budget(boolean ok, String key, int used, int limit) {}

    private Budget reserveIssuerBudget(MissionStore store, PlayerGateway issuer, int reward) {
        int limit = issuerRewardLimit();
        if (reward <= 0) return new Budget(true, "", 0, limit);
        String key = budgetWindow(now()) + ":" + playerKey(issuer);
        Integer raw = store.rewardBudgets.get(key);
        int used = raw == null ? 0 : Math.max(0, raw); // negative/corrupt counters fail closed
        if (reward > limit || used + reward > limit) {
            return new Budget(false, key, used, limit);
        }
        store.rewardBudgets.put(key, used + reward);
        long currentWindow = budgetWindow(now());
        store.rewardBudgets.keySet().removeIf(k -> {
            try {
                return Long.parseLong(k.split(":", 2)[0]) < currentWindow - 7;
            } catch (NumberFormatException e) {
                return false;
            }
        });
        return new Budget(true, key, used + reward, limit);
    }

    private void releaseIssuerBudget(MissionStore store, Mission mission) {
        if (mission == null || !"RESERVED".equals(mission.issuerBudgetStatus)
                || mission.issuerBudgetAmount <= 0) return;
        Integer used = store.rewardBudgets.get(mission.issuerBudgetKey);
        if (used != null) {
            store.rewardBudgets.put(mission.issuerBudgetKey, Math.max(0, used - mission.issuerBudgetAmount));
        }
        mission.issuerBudgetStatus = "RELEASED";
        mission.issuerBudgetReleasedAt = now();
    }

    // ------------------------------------------------------------ draft (carnet)

    public void giveCarnet(PlayerGateway player) {
        if (!missionAuthority(player)) {
            player.tell("Carnetul de Misiuni este disponibil doar Inspectorului și Comisarului.");
            return;
        }
        player.give(ItemSpec.of(ORDER_BOOK, 1).named("Carnetul de Ordine"));
        player.tell("Ai primit Carnetul de Ordine. Itemul este reutilizabil și nu se consumă.");
    }

    private MissionDraft draft(PlayerGateway player, MissionStore store) {
        return store.drafts.get(playerKey(player));
    }

    public void draftStatus(PlayerGateway player) {
        var draft = draft(player, store());
        if (draft == null) {
            player.tell("Nu ai un ordin în lucru.");
            return;
        }
        player.tell("Ordin: " + draft.minutes + " min, reward " + draft.reward + " monede, începe "
                + draft.startLabel + " — " + draft.objective
                + " | rang minim: " + ctx.policies().rankName(draft.minimumRank)
                + " | max participanți: " + draft.maxAssignees
                + " | semnat: " + (draft.signedBy.isEmpty() ? "nu" : draft.signedBy)
                + " | împachetat: " + (draft.packagedAt != null ? "da" : "nu")
                + " | copii: " + draft.issuedCount + "/" + draft.maxCopies
                + " | fond rămas: " + draft.remainingRewardPool + " monede.");
    }

    /** Parses "acum" or YYYY-MM-DDTHH:MM against the schedule window. */
    public record StartParse(boolean ok, long at, String label, String error) {}

    public StartParse parseStart(String raw) {
        String value = raw == null ? "" : raw.trim();
        long referenceNow = now();
        if ("acum".equalsIgnoreCase(value)) return new StartParse(true, referenceNow, "acum", null);
        var match = java.util.regex.Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2})$").matcher(value);
        if (!match.matches()) {
            return new StartParse(false, 0, "", "Folosește „acum” sau data în format YYYY-MM-DDTHH:MM.");
        }
        java.time.LocalDateTime date;
        try {
            date = java.time.LocalDateTime.of(
                    Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)), Integer.parseInt(match.group(4)),
                    Integer.parseInt(match.group(5)));
        } catch (java.time.DateTimeException e) {
            return new StartParse(false, 0, "", "Data de început nu este validă.");
        }
        long at = date.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        if (at < referenceNow - MINUTE_MS) {
            return new StartParse(false, 0, "", "Data de început trebuie să fie acum sau în viitor.");
        }
        long maxDays = Math.max(1, ctx.policies().missionMaxScheduleDays);
        if (at > referenceNow + maxDays * DAY_MS) {
            return new StartParse(false, 0, "",
                    "Data de început poate fi programată la maximum " + maxDays + " zile în viitor.");
        }
        return new StartParse(true, at, value, null);
    }

    public void draftWrite(PlayerGateway player, int minutes, String startRaw, int reward, String objective) {
        if (!draftGate(player)) return;
        if (!timeValid(minutes)) {
            player.tell("Timpul estimativ trebuie să fie un număr întreg între "
                    + ctx.policies().missionMinMinutes + " și " + ctx.policies().missionMaxMinutes + " minute.");
            return;
        }
        var start = parseStart(startRaw);
        if (!start.ok()) {
            player.tell(start.error());
            return;
        }
        if (!rewardValid(reward)) {
            player.tell("Recompensa trebuie să fie un număr întreg între 0 și "
                    + ctx.policies().missionMaxReward + " monede.");
            return;
        }
        if (objective == null || objective.isBlank()) {
            player.tell("Scrie obiectivul misiunii după timpul de început.");
            return;
        }
        var store = store();
        var draft = new MissionDraft();
        draft.issuer = player.name();
        draft.issuerUuid = player.uuid() == null ? "" : player.uuid().toString();
        draft.objective = objective.substring(0, Math.min(objective.length(), ctx.policies().envelopeMaxBodyLength));
        draft.minutes = minutes;
        draft.reward = reward;
        draft.maxCopies = Math.max(1, ctx.policies().missionMaxCopiesPerDraft);
        draft.rewardPool = Math.min(ctx.policies().missionMaxRewardPool, reward * draft.maxCopies);
        draft.minimumRank = ctx.policies().missionDefaultMinimumRank;
        draft.maxAssignees = 1;
        draft.rewardCommitted = 0;
        draft.remainingRewardPool = draft.rewardPool;
        draft.startAt = start.at();
        draft.startLabel = start.label();
        draft.issuedCount = 0;
        store.drafts.put(playerKey(player), draft);
        ctx.missions().write(store);
        player.tell("Ordin scris. Verifică detaliile la secretară, apoi semnează și sigilează-l în Carnetul de Ordine.");
    }

    public void draftScope(PlayerGateway player, String rankName, int maxAssignees) {
        if (!draftGate(player)) return;
        var store = store();
        var draft = draft(player, store);
        if (draft == null) {
            player.tell("Scrie ordinul înainte să configurezi participanții.");
            return;
        }
        int minimumRank = rankValue(rankName);
        if (!scopeValid(player, minimumRank, maxAssignees)) return;
        draft.minimumRank = minimumRank;
        draft.maxAssignees = maxAssignees;
        // Scope changes invalidate the signature and seal.
        draft.signedBy = "";
        draft.signedAt = null;
        draft.packagedAt = null;
        ctx.missions().write(store);
        player.tell("Sfera ordinului: minim " + ctx.policies().rankName(minimumRank) + ", maximum "
                + maxAssignees + " participanți. Semnează și sigilează din nou.");
    }

    private int rankValue(String name) {
        if (name == null) return -1;
        return switch (PlayerService.canon(name)) {
            case "junior", "civil" -> Rank.STAGIAR.level();
            case "guard", "strajer", "străjer" -> Rank.GUARD.level();
            case "senior" -> Rank.SERGENT.level();
            case "lieutenant", "locotenent" -> Rank.INSPECTOR.level();
            default -> {
                try {
                    yield Integer.parseInt(name.trim());
                } catch (NumberFormatException e) {
                    yield -1;
                }
            }
        };
    }

    public void draftSign(PlayerGateway player) {
        if (!draftGate(player)) return;
        var store = store();
        var draft = draft(player, store);
        if (draft == null) {
            player.tell("Nu există un ordin în lucru.");
            return;
        }
        draft.signedBy = player.name();
        draft.signedAt = now();
        draft.packagedAt = null;
        ctx.missions().write(store);
        player.tell("Ordin semnat de " + player.name() + ". Sigilează-l în Carnetul de Ordine.");
    }

    public void draftPackage(PlayerGateway player) {
        if (!draftGate(player)) return;
        var store = store();
        var draft = draft(player, store);
        if (draft == null || draft.signedBy.isEmpty()) {
            player.tell("Semnează ordinul înainte să-l împachetezi.");
            return;
        }
        draft.packagedAt = now();
        ctx.missions().write(store);
        player.tell("Ordin împachetat și gata de predare. Dă click dreapta pe subordonat cu carnetul de ordine.");
    }

    private boolean draftGate(PlayerGateway player) {
        if (!missionAuthority(player)) {
            player.tell("Doar Inspectorul sau Comisaru' pot folosi Carnetul de Misiuni.");
            return false;
        }
        if (!hasCarnet(player)) {
            player.tell("Ține Carnetul de Misiuni în mâna principală.");
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------ §13 templates + calculated budgets

    private MissionTemplateStore templateStore() {
        var store = ctx.missionTemplates().read();
        if (!store.seeded) {
            store.seeded = true;
            seed(store, "Patrulare suplimentară", 2, 2.0, 1.0, 2, 120,
                    "Parcurge ruta de patrulare alocată și raportează incidențele.", true);
            seed(store, "Escortă oficială", 2, 1.5, 1.5, 2, 90,
                    "Însoțește oficialul sau încărcătura pe traseul indicat până la destinație.", false);
            seed(store, "Pază de cartier", 1, 2.0, 1.0, 1, 120,
                    "Menține prezența la punctul indicat și notează mișcările suspecte.", true);
            ctx.missionTemplates().write(store);
        }
        return store;
    }

    private void seed(MissionTemplateStore store, String name, int minRank, double hours,
                      double risk, int maxPaid, int deadlineMinutes, String objective,
                      boolean supersedesPatrol) {
        var t = new MissionTemplate();
        t.id = "T" + store.nextId++;
        t.name = name;
        t.minRank = minRank;
        t.estimatedHours = hours;
        t.risk = risk;
        t.maxPaidParticipants = maxPaid;
        t.deadlineMinutes = deadlineMinutes;
        t.objective = objective;
        t.supersedesPatrol = supersedesPatrol;
        t.createdAt = t.updatedAt = now();
        store.templates.put(t.id, t);
    }

    /** Per-participant reward in Bronze, re-derived from the current wage table. */
    private int calculatedReward(MissionTemplate t) {
        return MissionBudget.perParticipant(
                ctx.policies().salaryPerHour(t.minRank), t.estimatedHours, t.risk);
    }

    private static String num(double v) {
        return v == Math.floor(v) && !Double.isInfinite(v)
                ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /** Issuer preview: enabled templates with live-calculated budgets. */
    public void templateList(PlayerGateway player) {
        if (!missionAuthority(player)) {
            player.tell("Doar Inspectorul sau Comisaru' pot consulta șabloanele de misiune.");
            return;
        }
        var templates = templateStore().enabled();
        if (templates.isEmpty()) {
            player.tell("Nu există șabloane de misiune active.");
            return;
        }
        player.tell("Șabloane de misiune (recompense derivate din salariul orar curent):");
        for (var t : templates) {
            int per = calculatedReward(t);
            player.tell(t.id + " „" + t.name + "” — " + ctx.policies().rankName(t.minRank)
                    + " " + ctx.policies().salaryPerHour(t.minRank) + " B/h × " + num(t.estimatedHours)
                    + " h × risc " + num(t.risk) + " = " + per + " B/participant | max "
                    + t.maxPaidParticipants + " plătiți | buget " + MissionBudget.teamBudget(per, t.maxPaidParticipants)
                    + " B | termen " + t.deadlineMinutes + " min"
                    + (t.supersedesPatrol ? " | înlocuiește patrula" : ""));
        }
    }

    /** Creates a work draft from a template: reward is calculated, never hand-typed. */
    public void draftFromTemplate(PlayerGateway player, String templateId) {
        if (!draftGate(player)) return;
        var t = templateStore().get(templateId);
        if (t == null || !t.enabled) {
            player.tell("Șablonul nu există sau este dezactivat.");
            return;
        }
        int reward = calculatedReward(t);
        var store = store();
        var draft = new MissionDraft();
        draft.issuer = player.name();
        draft.issuerUuid = player.uuid() == null ? "" : player.uuid().toString();
        draft.objective = t.objective.substring(0, Math.min(t.objective.length(), ctx.policies().envelopeMaxBodyLength));
        draft.minutes = t.deadlineMinutes;
        draft.reward = reward;
        draft.minimumRank = t.minRank;
        draft.maxAssignees = t.maxPaidParticipants;
        draft.maxCopies = Math.max(1, Math.min(t.maxPaidParticipants, ctx.policies().missionMaxCopiesPerDraft));
        draft.rewardPool = Math.min(ctx.policies().missionMaxRewardPool, reward * draft.maxCopies);
        draft.rewardCommitted = 0;
        draft.remainingRewardPool = draft.rewardPool;
        draft.startAt = now();
        draft.startLabel = "acum";
        draft.issuedCount = 0;
        draft.templateId = t.id;
        draft.estimatedHours = t.estimatedHours;
        draft.risk = t.risk;
        draft.supersedesPatrol = t.supersedesPatrol;
        store.drafts.put(playerKey(player), draft);
        ctx.missions().write(store);
        player.tell("Ordin din șablonul " + t.id + " „" + t.name + "”: " + reward + " B/participant"
                + " (rang " + ctx.policies().rankName(t.minRank) + " × " + num(t.estimatedHours)
                + " h × risc " + num(t.risk) + "), buget " + draft.rewardPool + " B. "
                + "Ajustează bugetul la secretară dacă e nevoie, apoi semnează și sigilează.");
    }

    /**
     * Adjusts a template draft's budget fields. An explicit reward above
     * calculated × (1 + margin) requires a reason and is audited.
     */
    public void draftAdjust(PlayerGateway player, String hoursRaw, String riskRaw,
                            String rewardRaw, String reason) {
        if (!draftGate(player)) return;
        var store = store();
        var draft = draft(player, store);
        if (draft == null || draft.templateId.isEmpty()) {
            player.tell("Ajustarea bugetului se aplică doar ordinelor create dintr-un șablon.");
            return;
        }
        if (draft.issuedCount > 0) {
            player.tell("Ordinul are deja copii emise — bugetul nu se mai poate schimba.");
            return;
        }
        double hours = parsePositive(hoursRaw, -1);
        double risk = parsePositive(riskRaw, -1);
        if (hours <= 0 || hours > 24 || risk <= 0 || risk > 10) {
            player.tell("Orele estimate trebuie să fie între 0 și 24, iar riscul între 0 și 10.");
            return;
        }
        int calculated = MissionBudget.perParticipant(
                ctx.policies().salaryPerHour(draft.minimumRank), hours, risk);
        int reward = calculated;
        String trimmedReason = reason == null ? "" : reason.trim();
        int explicit = (int) parsePositive(rewardRaw, -1);
        if (explicit >= 0) {
            if (!rewardValid(explicit)) {
                player.tell("Recompensa trebuie să fie un număr întreg între 0 și "
                        + ctx.policies().missionMaxReward + " monede.");
                return;
            }
            int limit = (int) Math.ceil(calculated * (1.0 + ctx.policies().missionRewardOverrideMargin));
            if (explicit > limit) {
                if (trimmedReason.isEmpty()) {
                    player.tell("Recompensa depășește limita calculată (" + limit
                            + " B). Precizează un motiv pentru depășire.");
                    return;
                }
                audit.record("mission_reward_override", player.name(),
                        player.uuid() == null ? "" : player.uuid().toString(),
                        draft.templateId, "", "OVERRIDDEN",
                        "calculated=" + calculated + " requested=" + explicit
                                + " reason=" + trimmedReason);
            }
            reward = explicit;
        }
        draft.estimatedHours = hours;
        draft.risk = risk;
        draft.reward = reward;
        draft.rewardPool = Math.min(ctx.policies().missionMaxRewardPool, reward * draft.maxCopies);
        draft.remainingRewardPool = Math.max(0, draft.rewardPool - draft.rewardCommitted);
        draft.overrideReason = explicit > calculated ? trimmedReason : "";
        draft.signedBy = "";
        draft.signedAt = null;
        draft.packagedAt = null;
        ctx.missions().write(store);
        player.tell("Buget ajustat: " + reward + " B/participant (calculat " + calculated
                + " B), fond " + draft.rewardPool + " B. Semnează și sigilează din nou.");
    }

    private static double parsePositive(String raw, double fallback) {
        if (raw == null || raw.isBlank() || "-".equals(raw.trim())) return fallback;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ §13 template administration (commissioner)

    private boolean templateAuthority(PlayerGateway player) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' administrează șabloanele de misiune.");
            return false;
        }
        return true;
    }

    public void templateListAll(PlayerGateway player) {
        if (!templateAuthority(player)) return;
        var all = new ArrayList<>(templateStore().templates.values());
        all.sort((a, b) -> a.id.compareTo(b.id));
        if (all.isEmpty()) {
            player.tell("Nu există șabloane de misiune.");
            return;
        }
        for (var t : all) {
            player.tell(t.id + " „" + t.name + "” — " + ctx.policies().rankName(t.minRank)
                    + " × " + num(t.estimatedHours) + " h × risc " + num(t.risk)
                    + " | max " + t.maxPaidParticipants + " | termen " + t.deadlineMinutes + " min"
                    + " | " + (t.enabled ? "activ" : "dezactivat")
                    + (t.supersedesPatrol ? " | înlocuiește patrula" : ""));
        }
    }

    public void templateCreate(PlayerGateway player, String name, int minRank, double hours,
                               double risk, int maxPaid, int deadlineMinutes, String objective,
                               boolean supersedesPatrol) {
        if (!templateAuthority(player)) return;
        String problem = templateProblem(name, minRank, hours, risk, maxPaid, deadlineMinutes, objective);
        if (problem != null) {
            player.tell(problem);
            return;
        }
        var store = templateStore();
        var t = new MissionTemplate();
        t.id = "T" + store.nextId++;
        t.name = name.trim();
        t.minRank = minRank;
        t.estimatedHours = hours;
        t.risk = risk;
        t.maxPaidParticipants = maxPaid;
        t.deadlineMinutes = deadlineMinutes;
        t.objective = objective.trim();
        t.supersedesPatrol = supersedesPatrol;
        t.createdAt = t.updatedAt = now();
        store.templates.put(t.id, t);
        ctx.missionTemplates().write(store);
        audit.record("mission_template_create", player.name(),
                player.uuid() == null ? "" : player.uuid().toString(), t.id, "", "CREATED", t.name);
        player.tell("Șablon " + t.id + " „" + t.name + "” creat — " + calculatedReward(t)
                + " B/participant la salariul curent.");
    }

    public void templateSet(PlayerGateway player, String id, String field, String value) {
        if (!templateAuthority(player)) return;
        var store = templateStore();
        var t = store.get(id);
        if (t == null) {
            player.tell("Șablonul " + id + " nu există.");
            return;
        }
        String f = PlayerService.canon(field);
        String v = value == null ? "" : value.trim();
        switch (f) {
            case "name" -> {
                if (v.isEmpty() || v.length() > 60) { player.tell("Numele trebuie să aibă 1-60 caractere."); return; }
                t.name = v;
            }
            case "minrank" -> {
                int r;
                try { r = Integer.parseInt(v); } catch (NumberFormatException e) { player.tell("Rangul minim trebuie să fie 1-4."); return; }
                if (r < 1 || r > 4) { player.tell("Rangul minim trebuie să fie 1-4."); return; }
                t.minRank = r;
            }
            case "hours" -> {
                double h = parsePositive(v, -1);
                if (h <= 0 || h > 24) { player.tell("Orele estimate trebuie să fie între 0 și 24."); return; }
                t.estimatedHours = h;
            }
            case "risk" -> {
                double r = parsePositive(v, -1);
                if (r <= 0 || r > 10) { player.tell("Riscul trebuie să fie între 0 și 10."); return; }
                t.risk = r;
            }
            case "participants" -> {
                int p;
                try { p = Integer.parseInt(v); } catch (NumberFormatException e) { player.tell("Număr de participanți invalid."); return; }
                if (p < 1 || p > ctx.policies().missionMaxAssignees) {
                    player.tell("Participanții plătiți trebuie să fie între 1 și " + ctx.policies().missionMaxAssignees + ".");
                    return;
                }
                t.maxPaidParticipants = p;
            }
            case "deadline" -> {
                int m;
                try { m = Integer.parseInt(v); } catch (NumberFormatException e) { player.tell("Termen invalid."); return; }
                if (!timeValid(m)) {
                    player.tell("Termenul trebuie să fie între " + ctx.policies().missionMinMinutes
                            + " și " + ctx.policies().missionMaxMinutes + " minute.");
                    return;
                }
                t.deadlineMinutes = m;
            }
            case "objective" -> {
                if (v.isEmpty() || v.length() > ctx.policies().envelopeMaxBodyLength) {
                    player.tell("Obiectivul trebuie să aibă 1-" + ctx.policies().envelopeMaxBodyLength + " caractere.");
                    return;
                }
                t.objective = v;
            }
            case "supersedespatrol" -> t.supersedesPatrol = "da".equals(v) || "true".equals(v) || "yes".equals(v);
            default -> {
                player.tell("Câmp necunoscut. Folosește: name|minrank|hours|risk|participants|deadline|objective|supersedesPatrol.");
                return;
            }
        }
        t.updatedAt = now();
        ctx.missionTemplates().write(store);
        audit.record("mission_template_set", player.name(),
                player.uuid() == null ? "" : player.uuid().toString(), t.id, "", "UPDATED",
                f + "=" + v);
        player.tell(t.id + " actualizat (" + f + "). Recompensa curentă: " + calculatedReward(t) + " B/participant.");
    }

    public void templateDuplicate(PlayerGateway player, String id) {
        if (!templateAuthority(player)) return;
        var store = templateStore();
        var src = store.get(id);
        if (src == null) {
            player.tell("Șablonul " + id + " nu există.");
            return;
        }
        var t = new MissionTemplate();
        t.id = "T" + store.nextId++;
        t.name = src.name + " (copie)";
        t.minRank = src.minRank;
        t.estimatedHours = src.estimatedHours;
        t.risk = src.risk;
        t.maxPaidParticipants = src.maxPaidParticipants;
        t.deadlineMinutes = src.deadlineMinutes;
        t.objective = src.objective;
        t.supersedesPatrol = src.supersedesPatrol;
        t.enabled = false;
        t.createdAt = t.updatedAt = now();
        store.templates.put(t.id, t);
        ctx.missionTemplates().write(store);
        audit.record("mission_template_duplicate", player.name(),
                player.uuid() == null ? "" : player.uuid().toString(), t.id, "", "DUPLICATED",
                "from=" + src.id);
        player.tell("Copiat în " + t.id + " (dezactivat — activează-l după ajustări).");
    }

    public void templateSetEnabled(PlayerGateway player, String id, boolean enabled) {
        if (!templateAuthority(player)) return;
        var store = templateStore();
        var t = store.get(id);
        if (t == null) {
            player.tell("Șablonul " + id + " nu există.");
            return;
        }
        t.enabled = enabled;
        t.updatedAt = now();
        ctx.missionTemplates().write(store);
        audit.record("mission_template_enable", player.name(),
                player.uuid() == null ? "" : player.uuid().toString(), t.id, "",
                enabled ? "ENABLED" : "DISABLED", t.name);
        player.tell(t.id + " „" + t.name + "” " + (enabled ? "activat" : "dezactivat") + ".");
    }

    private String templateProblem(String name, int minRank, double hours, double risk,
                                   int maxPaid, int deadlineMinutes, String objective) {
        if (name == null || name.isBlank() || name.length() > 60) return "Numele trebuie să aibă 1-60 caractere.";
        if (minRank < 1 || minRank > 4) return "Rangul minim trebuie să fie 1-4.";
        if (hours <= 0 || hours > 24) return "Orele estimate trebuie să fie între 0 și 24.";
        if (risk <= 0 || risk > 10) return "Riscul trebuie să fie între 0 și 10.";
        if (maxPaid < 1 || maxPaid > ctx.policies().missionMaxAssignees)
            return "Participanții plătiți trebuie să fie între 1 și " + ctx.policies().missionMaxAssignees + ".";
        if (!timeValid(deadlineMinutes))
            return "Termenul trebuie să fie între " + ctx.policies().missionMinMinutes
                    + " și " + ctx.policies().missionMaxMinutes + " minute.";
        if (objective == null || objective.isBlank() || objective.length() > ctx.policies().envelopeMaxBodyLength)
            return "Obiectivul trebuie să aibă 1-" + ctx.policies().envelopeMaxBodyLength + " caractere.";
        return null;
    }

    // ------------------------------------------------------------ issue

    /** Quick/secretary create: declares and delivers a mission in one step. */
    public void createMission(PlayerGateway issuer, PlayerGateway target, int minutes,
                              String objective, int reward) {
        if (!ctx.policies().missionQuickCreateEnabled
                || (ctx.policies().missionQuickCreateLocalOnly && !ctx.policies().isLocalEnvironment())) {
            issuer.tell("Crearea rapidă de misiuni este dezactivată de configurația serverului.");
            return;
        }
        if (!missionAuthority(issuer)) {
            issuer.tell("Doar Inspectorul sau Comisaru' pot declara misiuni plătite.");
            return;
        }
        if (target == null) {
            issuer.tell("Jucătorul țintă trebuie să fie online.");
            return;
        }
        if (!atSecretary(issuer)) {
            issuer.tell("Misiunile oficiale se declară la secretară.");
            return;
        }
        int minimumRank = ctx.policies().missionDefaultMinimumRank;
        int maxAssignees = 1;
        if (!scopeValid(issuer, minimumRank, maxAssignees)) return;
        if (!targetAllowed(issuer, target)) return;
        if (players.state(target.uuid()).rank < minimumRank) {
            issuer.tell("Destinatarul nu atinge rangul minim al misiunii ("
                    + ctx.policies().rankName(minimumRank) + ").");
            return;
        }
        if (!timeValid(minutes) || objective == null || objective.isBlank()) {
            issuer.tell("Folosește timpul între " + ctx.policies().missionMinMinutes + " și "
                    + ctx.policies().missionMaxMinutes + " minute și descrie obiectivul.");
            return;
        }
        if (!rewardValid(reward)) {
            issuer.tell("Recompensa trebuie să fie un număr întreg între 0 și "
                    + ctx.policies().missionMaxReward + " monede.");
            return;
        }
        var store = store();
        if (openCountFor(target, store) >= ctx.policies().missionMaxActivePerPlayer) {
            issuer.tell("Ținta are deja prea multe misiuni active.");
            return;
        }
        var budget = reserveIssuerBudget(store, issuer, reward);
        if (!budget.ok()) {
            issuer.tell("Bugetul zilnic de recompense al emitentului este epuizat ("
                    + budget.used() + "/" + budget.limit() + " monede).");
            ctx.missions().write(store);
            return;
        }
        long createdAt = now();
        var mission = new Mission();
        mission.id = store.nextMissionId();
        mission.issuer = issuer.name();
        mission.issuerUuid = issuer.uuid() == null ? "" : issuer.uuid().toString();
        mission.issuerRank = players.state(issuer.uuid()).rank;
        mission.target = target.name();
        mission.targetUuid = target.uuid() == null ? "" : target.uuid().toString();
        var identity = new Mission.Identity(mission.targetUuid, mission.target, createdAt);
        mission.assignees.add(identity);
        mission.invited.add(identity);
        mission.minimumRank = minimumRank;
        mission.maxAssignees = maxAssignees;
        mission.objective = objective.substring(0, Math.min(objective.length(), ctx.policies().envelopeMaxBodyLength));
        mission.minutes = minutes;
        mission.reward = reward;
        mission.rewardStatus = reward > 0 ? "PENDING" : "NONE";
        mission.rewardPayoutId = "mission:" + mission.id + ":reward";
        mission.issuerBudgetKey = budget.key();
        mission.issuerBudgetAmount = reward;
        mission.issuerBudgetStatus = reward > 0 ? "RESERVED" : "NONE";
        mission.startAt = createdAt;
        mission.startLabel = "acum";
        mission.createdAt = createdAt;
        mission.dueAt = createdAt + (long) minutes * MINUTE_MS;
        mission.status = "ISSUED";
        mission.origin = "SECRETARY";
        // Persist before the external effect: a failed or crashed delivery never loses the mission.
        store.missions.add(mission);
        ctx.missions().write(store);

        var delivery = deliver(issuer, target, mission);
        mission.delivery = delivery.mode().name();
        ctx.missions().write(store);
        audit.record("mission_create_quick", issuer.name(), mission.issuerUuid,
                target.name(), mission.targetUuid,
                delivery.succeeded() ? "SUCCESS" : "FAILED", delivery.mode().name() + " missionId=" + mission.id);

        if (!delivery.succeeded()) {
            releaseIssuerBudget(store, mission);
            ctx.missions().write(store);
            target.tell("Misiunea #" + mission.id + " nu a putut fi livrată. Anunță Comisaru'.");
        } else {
            target.tell("Ai primit misiunea #" + mission.id + " de la " + mission.issuer
                    + ". Deschide ordinul primit și alege Acceptă sau Refuză.");
            if (delivery.mode() == DeliveryProvider.Mode.DELIVERED
                    || delivery.mode() == DeliveryProvider.Mode.PENDING_MAILBOX) {
                target.tell("Scrisoarea misiunii a fost trimisă prin Envelope.");
            }
        }
        issuer.tell("Misiune emisă: " + summary(mission) + ". Livrare: " + delivery.mode() + ".");
    }

    /** Carnet flow: hand the signed+sealed draft to a subordinate. */
    public boolean give(PlayerGateway issuer, PlayerGateway target) {
        if (!missionAuthority(issuer)) {
            issuer.tell("Doar Inspectorul sau Comisaru' pot declara misiuni plătite.");
            return false;
        }
        if (!hasCarnet(issuer)) {
            issuer.tell("Ține Carnetul de Misiuni în mâna principală.");
            return false;
        }
        if (!atSecretary(issuer)) {
            issuer.tell("Predarea oficială se face la secretară.");
            return false;
        }
        var store = store();
        var draft = draft(issuer, store);
        if (draft == null || draft.objective.isEmpty() || !timeValid(draft.minutes)
                || !rewardValid(draft.reward) || draft.startAt <= 0 || draft.packagedAt == null
                || draft.signedBy.isEmpty()) {
            issuer.tell("Nu ai un ordin complet. Scrie misiunea, semneaz-o și împacheteaz-o mai întâi.");
            return false;
        }
        if (draft.startAt + (long) draft.minutes * MINUTE_MS <= now()) {
            issuer.tell("Ordinul a expirat înainte de predare. Scrie un draft nou.");
            return false;
        }
        if (target == null || PlayerService.canon(target.name()).equals(PlayerService.canon(issuer.name()))) {
            issuer.tell("Predarea trebuie făcută unui subordonat online, nu emitentului.");
            return false;
        }
        if (!targetAllowed(issuer, target)) return false;
        if (!scopeValid(issuer, draft.minimumRank, draft.maxAssignees)) return false;
        if (players.state(target.uuid()).rank < draft.minimumRank) {
            issuer.tell("Destinatarul nu atinge rangul minim al misiunii ("
                    + ctx.policies().rankName(draft.minimumRank) + ").");
            return false;
        }
        if (openCountFor(target, store) >= ctx.policies().missionMaxActivePerPlayer) {
            issuer.tell("Ținta are deja prea multe misiuni active.");
            return false;
        }
        if (draft.issuedCount >= draft.maxCopies) {
            issuer.tell("Acest ordin a atins limita de " + draft.maxCopies
                    + " copii. Scrie un ordin nou pentru alte predări.");
            return false;
        }
        if (draft.reward > 0 && draft.remainingRewardPool < draft.reward) {
            issuer.tell("Fondul ordinului este epuizat: mai sunt " + draft.remainingRewardPool
                    + " monede disponibile.");
            return false;
        }

        var budget = reserveIssuerBudget(store, issuer, draft.reward);
        if (!budget.ok()) {
            issuer.tell("Bugetul zilnic de recompense al emitentului este epuizat ("
                    + budget.used() + "/" + budget.limit() + " monede).");
            ctx.missions().write(store);
            return false;
        }

        long createdAt = now();
        var mission = new Mission();
        mission.id = store.nextMissionId();
        mission.issuer = issuer.name();
        mission.issuerUuid = issuer.uuid() == null ? "" : issuer.uuid().toString();
        mission.issuerRank = players.state(issuer.uuid()).rank;
        mission.target = target.name();
        mission.targetUuid = target.uuid() == null ? "" : target.uuid().toString();
        var identity = new Mission.Identity(mission.targetUuid, mission.target, createdAt);
        mission.assignees.add(identity);
        mission.invited.add(identity);
        mission.minimumRank = draft.minimumRank;
        mission.maxAssignees = draft.maxAssignees;
        mission.objective = draft.objective;
        mission.minutes = draft.minutes;
        mission.reward = draft.reward;
        mission.rewardStatus = draft.reward > 0 ? "PENDING" : "NONE";
        mission.rewardPayoutId = "mission:" + mission.id + ":reward";
        mission.rewardPool = draft.rewardPool;
        mission.copyIndex = draft.issuedCount + 1;
        mission.startAt = draft.startAt;
        mission.startLabel = draft.startLabel;
        mission.createdAt = createdAt;
        mission.dueAt = draft.startAt + (long) draft.minutes * MINUTE_MS;
        mission.status = "ISSUED";
        mission.signedBy = draft.signedBy;
        mission.signedAt = draft.signedAt;
        mission.packagedAt = draft.packagedAt;
        mission.delivery = "PENDING_PACKAGE";
        mission.origin = "CARNET";
        mission.templateId = draft.templateId;
        mission.supersedesPatrol = draft.supersedesPatrol;
        mission.draftKey = playerKey(issuer);
        mission.issuerBudgetKey = budget.key();
        mission.issuerBudgetAmount = draft.reward;
        mission.issuerBudgetStatus = draft.reward > 0 ? "RESERVED" : "NONE";
        int previousIssued = draft.issuedCount;
        int previousCommitted = draft.rewardCommitted;
        int previousRemaining = draft.remainingRewardPool;
        draft.issuedCount += 1;
        draft.rewardCommitted += draft.reward;
        draft.remainingRewardPool = Math.max(0, draft.remainingRewardPool - draft.reward);
        // Persist before external effects: a failed or crashed delivery never
        // loses the mission or leaks the reserved reward budget.
        store.missions.add(mission);
        ctx.missions().write(store);

        // Physical hand-over: the sealed order book page goes to the target's inventory.
        var orderItem = new ItemSpec(ORDER_BOOK, 1, Map.of(
                "StrajaMissionId", mission.id,
                "StrajaMissionSignedBy", mission.signedBy), null)
                .named("Ordin sigilat #" + mission.id);

        // Preflight the hand-over, then deliver the physical order first: the
        // inventory is the only step we can verify locally, so it must happen
        // before the Envelope package (an external, non-atomic side effect).
        var targetInventory = target.inventory();
        if (targetInventory == null || !targetInventory.canReceive(List.of(orderItem))) {
            failGive(store, mission, draft, previousIssued, previousCommitted, previousRemaining,
                    "inventory_delivery_failed", issuer, target);
            return false;
        }
        if (!target.giveVerified(orderItem)) {
            failGive(store, mission, draft, previousIssued, previousCommitted, previousRemaining,
                    "inventory_delivery_failed", issuer, target);
            return false;
        }
        var contents = List.of(new ItemSpec("envelope:letter", 1, Map.of(), null));
        var packageResult = ctx.delivery().sendPackage(issuer, mission.target,
                contents, envelopeBody(mission));
        if (!packageResult.succeeded()) {
            // The sealed order is already in the target's hands and the mission
            // is live; only the Envelope copy is missing. Keep a durable
            // pending state so the issuer can retry with `mission resend`.
            mission.delivery = "PACKAGE_PENDING";
            mission.deliveryError = "envelope_package_failed";
            ctx.missions().write(store);
            audit.record("mission_give", issuer.name(), mission.issuerUuid,
                    target.name(), mission.targetUuid, "PARTIAL",
                    "order_delivered_package_pending missionId=" + mission.id);
            target.tell("Ai primit un ordin sigilat de la " + mission.issuer
                    + ". Deschide ordinul și alege Acceptă pentru misiunea #" + mission.id + ".");
            issuer.tell("Ordinul #" + mission.id + " a fost predat fizic, dar pachetul Envelope nu a putut fi trimis. "
                    + "Anunță Comisaru' pentru retrimiterea pachetului.");
            return true;
        }
        mission.delivery = "ENVELOPE_PACKAGE";
        draft.lastMissionId = mission.id;
        ctx.missions().write(store);
        audit.record("mission_give", issuer.name(), mission.issuerUuid,
                target.name(), mission.targetUuid, "SUCCESS", "envelope_package_delivered" + " missionId=" + mission.id);
        target.tell("Ai primit un ordin sigilat prin Envelope, de la " + mission.issuer
                + ". Deschide pachetul și alege Acceptă pentru misiunea #" + mission.id + ".");
        issuer.tell("Ordinul #" + mission.id + " a fost semnat, sigilat și predat lui "
                + mission.target + ". Carnetul rămâne disponibil pentru următoarea predare.");
        return true;
    }

    /** Rolls a persisted carnet mission back to a durable FAILED state. */
    private void failGive(MissionStore store, Mission mission, MissionDraft draft,
                          int issued, int committed, int remaining, String error,
                          PlayerGateway issuer, PlayerGateway target) {
        releaseIssuerBudget(store, mission);
        draft.issuedCount = issued;
        draft.rewardCommitted = committed;
        draft.remainingRewardPool = remaining;
        mission.status = "DELIVERY_FAILED";
        mission.failedAt = now();
        mission.delivery = "FAILED";
        mission.deliveryError = error;
        ctx.missions().write(store);
        audit.record("mission_give", issuer.name(), mission.issuerUuid,
                target.name(), mission.targetUuid, "FAILED", error + " missionId=" + mission.id);
        issuer.tell("Pachetul nu a putut fi predat; ordinul a rămas în carnet și misiunea este păstrată ca FAILED.");
    }

    /**
     * Retries the Envelope package for a mission whose physical order was
     * delivered but whose package send failed (delivery = PACKAGE_PENDING).
     * Issuer or commissioner only; safe to repeat — succeeds at most once.
     */
    public boolean resendPackage(PlayerGateway actor, String id) {
        var store = store();
        var mission = store.find(id);
        if (mission == null || !"PACKAGE_PENDING".equals(mission.delivery)) {
            actor.tell("Misiunea nu are un pachet Envelope în așteptare.");
            return false;
        }
        if (!issuerMatches(actor, mission) && !players.isCommissioner(actor)) {
            actor.tell("Doar emitentul sau Comisaru' poate retrimite pachetul.");
            return false;
        }
        var contents = List.of(new ItemSpec("envelope:letter", 1, Map.of(), null));
        var packageResult = ctx.delivery().sendPackage(actor, mission.target,
                contents, envelopeBody(mission));
        if (!packageResult.succeeded()) {
            audit.record("mission_resend_package", actor.name(), actor.uuid() == null ? "" : actor.uuid().toString(),
                    mission.target, mission.targetUuid, "FAILED",
                    "envelope_package_retry_failed missionId=" + mission.id);
            actor.tell("Pachetul Envelope încă nu poate fi trimis pentru misiunea #" + mission.id + ".");
            return false;
        }
        mission.delivery = "ENVELOPE_PACKAGE";
        mission.deliveryError = "";
        ctx.missions().write(store);
        audit.record("mission_resend_package", actor.name(), actor.uuid() == null ? "" : actor.uuid().toString(),
                mission.target, mission.targetUuid, "SUCCESS", "envelope_package_delivered missionId=" + mission.id);
        notify(mission.target, "Scrisoarea misiunii #" + mission.id + " a fost trimisă prin Envelope.");
        actor.tell("Pachetul Envelope pentru misiunea #" + mission.id + " a fost trimis.");
        return true;
    }

    private DeliveryProvider.Outcome deliver(PlayerGateway issuer, PlayerGateway target, Mission mission) {
        if (!ctx.policies().envelopeEnabled || !ctx.delivery().available()) {
            if (!ctx.policies().envelopeFallbackToChat) {
                return DeliveryProvider.Outcome.failed("envelope_disabled");
            }
            return new DeliveryProvider.Outcome(DeliveryProvider.Mode.CHAT_FALLBACK, "");
        }
        String body = "Misiune #" + mission.id + "\n\n" + mission.objective
                + "\n\nTimp alocat: " + mission.minutes + " minute."
                + "\nEmisă de: " + mission.issuer + ".";
        return ctx.delivery().sendLetter(issuer, mission.target, "Ordin de misiune #" + mission.id, body);
    }

    private String envelopeBody(Mission mission) {
        String prefix = "ORDIN DE MISIUNE #" + mission.id
                + "\n\nDestinatar: " + mission.target + "\nObiectiv: ";
        String suffix = "\nTimp estimativ: " + mission.minutes + " minute"
                + "\nRecompensă: " + mission.reward + " monede"
                + "\nRang minim: " + ctx.policies().rankName(mission.minimumRank)
                + "\nParticipanți: maximum " + mission.maxAssignees
                + "\nÎncepere: " + mission.startLabel
                + "\nSemnat de: " + mission.signedBy;
        int room = Math.max(0, ctx.policies().envelopeMaxBodyLength - prefix.length() - suffix.length());
        return prefix + mission.objective.substring(0, Math.min(mission.objective.length(), room)) + suffix;
    }

    // ------------------------------------------------------------ participant flow

    /**
     * Read-only projection for native surfaces. Derives only what the freshly
     * loaded store already contains — malformed legacy lists degrade to empty
     * and claim data is never materialized here.
     */
    @Override
    public List<AvailableAction> availableActions(PlayerGateway player) {
        var store = store();
        long now = now();
        List<AvailableAction> actions = new ArrayList<>();
        if (missionAuthority(player)) {
            actions.add(new AvailableAction(Action.GET_CARNET, ""));
            if (hasCarnet(player)) {
                actions.add(new AvailableAction(Action.DRAFT_WRITE, ""));
                actions.add(new AvailableAction(Action.TEMPLATE_LIST, ""));
                for (var t : templateStore().enabled()) {
                    actions.add(new AvailableAction(Action.ISSUE_TEMPLATE, t.id));
                }
            }
            var draft = draft(player, store);
            if (draft != null) {
                actions.add(new AvailableAction(Action.DRAFT_STATUS, ""));
                actions.add(new AvailableAction(Action.DRAFT_SCOPE, ""));
                if (draft.issuedCount == 0 && !draft.templateId.isEmpty()) {
                    actions.add(new AvailableAction(Action.ADJUST_BUDGET, ""));
                }
                if (draft.objective != null && !draft.objective.isEmpty() && timeValid(draft.minutes)
                        && rewardValid(draft.reward) && draft.startAt > 0) {
                    actions.add(new AvailableAction(Action.DRAFT_SIGN, ""));
                }
                if (draft.signedBy != null && !draft.signedBy.isEmpty()) {
                    actions.add(new AvailableAction(Action.DRAFT_PACKAGE, ""));
                }
            }
        }
        boolean commissioner = players.isCommissioner(player);
        for (Mission mission : store.missions) {
            if (mission == null) continue;
            String status = mission.status == null ? "" : mission.status;
            var assignees = mission.assignees == null ? List.<Mission.Identity>of() : mission.assignees;
            var invited = mission.invited == null ? List.<Mission.Identity>of() : mission.invited;
            var declined = mission.declined == null ? List.<Mission.Identity>of() : mission.declined;
            boolean assigned = assignees.stream().anyMatch(e -> identityMatches(player, e));
            boolean invitedOnly = !assigned
                    && invited.stream().anyMatch(e -> identityMatches(player, e));
            boolean declinedBefore = declined.stream().anyMatch(e -> identityMatches(player, e));
            boolean beforeDue = mission.dueAt > now;
            String id = mission.id == null ? "" : mission.id;
            if (invitedOnly && !declinedBefore
                    && ("ISSUED".equals(status) || "ACCEPTED".equals(status))
                    && beforeDue && assignees.size() < mission.maxAssignees) {
                actions.add(new AvailableAction(Action.JOIN, id));
            }
            if ((assigned || invitedOnly) && "ISSUED".equals(status) && beforeDue) {
                actions.add(new AvailableAction(Action.DECLINE, id));
            }
            if (assigned && "ISSUED".equals(status) && mission.startAt <= now && beforeDue) {
                actions.add(new AvailableAction(Action.ACCEPT, id));
            }
            if (assigned && "ACCEPTED".equals(status) && beforeDue) {
                actions.add(new AvailableAction(Action.REPORT, id));
                actions.add(new AvailableAction(Action.FAIL, id));
            }
            if ("REPORTED".equals(status) && beforeDue
                    && (commissioner || (missionAuthority(player) && issuerMatches(player, mission)))) {
                actions.add(new AvailableAction(Action.COMPLETE, id));
            }
            if (assigned && "COMPLETED".equals(status) && claimableReward(player, mission)) {
                actions.add(new AvailableAction(Action.CLAIM_REWARD, id));
            }
            if (commissioner && "COMPLETED".equals(status) && recoverableClaims(mission)) {
                actions.add(new AvailableAction(Action.RECOVER_REWARD, id));
            }
        }
        return List.copyOf(actions);
    }

    private boolean claimableReward(PlayerGateway player, Mission mission) {
        if (mission.reward <= 0) return false;
        var claims = mission.rewardClaims == null
                ? Map.<String, Mission.RewardClaim>of() : mission.rewardClaims;
        var claim = claims.get(playerKey(player));
        if (claim != null) {
            return claim.amount > 0
                    && ("PENDING".equals(claim.status) || "PAYMENT_FAILED".equals(claim.status));
        }
        // Claims materialize lazily at claim time; fall back to the persisted
        // mission-level status instead of mutating state for a read.
        return "PENDING".equals(mission.rewardStatus)
                || "PAYMENT_FAILED".equals(mission.rewardStatus);
    }

    private static boolean recoverableClaims(Mission mission) {
        var claims = mission.rewardClaims == null
                ? Map.<String, Mission.RewardClaim>of() : mission.rewardClaims;
        for (var claim : claims.values()) {
            if (claim != null && ("PENDING".equals(claim.status)
                    || "PAYMENT_FAILED".equals(claim.status))) return true;
        }
        return claims.isEmpty() && ("PENDING".equals(mission.rewardStatus)
                || "PAYMENT_FAILED".equals(mission.rewardStatus));
    }

    @Override
    public boolean issueDraft(PlayerGateway issuer, PlayerGateway target) {
        return give(issuer, target);
    }

    public void list(PlayerGateway player) {
        var all = store().missions;
        boolean commissioner = players.isCommissioner(player);
        List<Mission> visible = new ArrayList<>();
        for (Mission m : all) {
            if (commissioner || targetMatches(player, m) || issuerMatches(player, m)
                    || invitedMatches(player, m)) visible.add(m);
        }
        if (visible.isEmpty()) {
            player.tell("Nu există misiuni vizibile.");
            return;
        }
        visible.stream().skip(Math.max(0, visible.size() - 20)).forEach(m ->
                player.tell(summary(m) + (m.isOpen() ? " | termen: " + pretty(m.dueAt) : "")));
    }

    public boolean invite(PlayerGateway issuer, String id, PlayerGateway target) {
        var store = store();
        var mission = store.find(id);
        if (mission == null || !issuerMatches(issuer, mission)) {
            issuer.tell("Doar proprietarul misiunii poate invita participanți.");
            return false;
        }
        if (!"ISSUED".equals(mission.status) && !"ACCEPTED".equals(mission.status)) {
            issuer.tell("Misiunea nu mai acceptă participanți: " + mission.status + ".");
            return false;
        }
        if (mission.assignees.size() >= mission.maxAssignees) {
            issuer.tell("Misiunea a atins limita de " + mission.maxAssignees + " participanți.");
            return false;
        }
        if (target == null || PlayerService.canon(target.name()).equals(PlayerService.canon(issuer.name()))) {
            issuer.tell("Participantul trebuie să fie online și diferit de emitent.");
            return false;
        }
        if (!targetAllowed(issuer, target)) return false;
        if (players.state(target.uuid()).rank < mission.minimumRank) {
            issuer.tell("Participantul trebuie să aibă cel puțin rangul "
                    + ctx.policies().rankName(mission.minimumRank) + ".");
            return false;
        }
        if (targetMatches(target, mission) || invitedMatches(target, mission)) {
            issuer.tell("Jucătorul este deja invitat sau participant.");
            return false;
        }
        if (declinedMatches(target, mission)) {
            issuer.tell("Jucătorul a refuzat deja acest ordin și nu poate fi reinvitat.");
            return false;
        }
        if (openCountFor(target, store) >= ctx.policies().missionMaxActivePerPlayer) {
            issuer.tell("Participantul are deja prea multe misiuni active.");
            return false;
        }
        mission.invited.add(new Mission.Identity(
                target.uuid() == null ? "" : target.uuid().toString(), target.name(), now()));
        ctx.missions().write(store);
        audit.record("mission_invite", issuer.name(), issuer.uuid().toString(),
                target.name(), mission.targetUuid, "SUCCESS", "invited" + " missionId=" + mission.id);
        target.tell("Ai fost invitat în misiunea #" + mission.id + " de " + mission.issuer
                + ". Prezintă-te la secretară și confirmă participarea la ordinul primit.");
        issuer.tell(target.name() + " a fost invitat în misiunea #" + mission.id + ".");
        return true;
    }

    public boolean join(PlayerGateway player, String id) {
        if (!guardActive(player, "intra într-o misiune")) return false;
        var store = store();
        var mission = store.find(id);
        if (mission == null || (!invitedMatches(player, mission) && !targetMatches(player, mission))) {
            player.tell("Misiunea nu există sau nu ai fost invitat.");
            return false;
        }
        if (declinedMatches(player, mission)) {
            player.tell("Ai refuzat deja acest ordin și nu mai poți reintra.");
            return false;
        }
        if (targetMatches(player, mission)) {
            player.tell("Ești deja participant în misiunea #" + mission.id + ".");
            return false;
        }
        if (!"ISSUED".equals(mission.status) && !"ACCEPTED".equals(mission.status)) {
            player.tell("Misiunea nu mai acceptă participanți: " + mission.status + ".");
            return false;
        }
        if (mission.assignees.size() >= mission.maxAssignees) {
            player.tell("Misiunea a atins limita de participanți.");
            return false;
        }
        var state = players.state(player.uuid());
        if (state.rank < mission.minimumRank || state.resigned || state.fired
                || state.suspended || state.resignationPending) {
            player.tell("Nu îndeplinești rangul sau statutul necesar pentru această misiune.");
            return false;
        }
        mission.assignees.add(new Mission.Identity(
                player.uuid() == null ? "" : player.uuid().toString(), player.name(), now()));
        ctx.missions().write(store);
        audit.record("mission_join", player.name(), player.uuid().toString(),
                mission.issuer, mission.issuerUuid, "SUCCESS", "joined" + " missionId=" + mission.id);
        player.tell("Ai intrat în misiunea #" + mission.id
                + ". Așteaptă acceptarea și lucrează împreună cu ceilalți participanți.");
        notify(mission.issuer, "[Straja] " + player.name() + " a intrat în misiunea #" + mission.id + ".");
        return true;
    }

    public void accept(PlayerGateway player, String id) {
        if (!guardActive(player, "accepta misiuni")) return;
        var store = store();
        var mission = store.find(id);
        if (mission == null || !targetMatches(player, mission)) {
            player.tell("Misiunea nu există sau nu îți aparține.");
            return;
        }
        if (!"ISSUED".equals(mission.status)) {
            player.tell("Misiunea nu mai poate fi acceptată: " + mission.status + ".");
            return;
        }
        if (mission.startAt > now()) {
            player.tell("Misiunea poate fi acceptată de la: " + mission.startLabel + ".");
            return;
        }
        if (mission.dueAt <= now()) {
            expire(store, mission);
            player.tell("Misiunea a expirat.");
            return;
        }
        mission.status = "ACCEPTED";
        mission.acceptedAt = now();
        ctx.missions().write(store);
        audit.record("mission_accept", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "accepted" + " missionId=" + mission.id);
        notify(mission.issuer, "[Straja] Misiunea #" + mission.id + " a fost acceptată de " + player.name() + ".");
        player.tell("Misiune acceptată: #" + mission.id + ".");
    }

    public boolean decline(PlayerGateway player, String id) {
        if (!guardActive(player, "refuza misiuni")) return false;
        var store = store();
        var mission = store.find(id);
        if (mission == null || !targetMatches(player, mission) && !invitedMatches(player, mission)) {
            player.tell("Misiunea nu există sau nu îți aparține.");
            return false;
        }
        if (!"ISSUED".equals(mission.status)) {
            player.tell("Misiunea nu mai poate fi refuzată: " + mission.status + ".");
            return false;
        }
        if (mission.dueAt <= now()) {
            expire(store, mission);
            player.tell("Misiunea a expirat și nu mai poate fi refuzată.");
            return false;
        }
        if (!declinedMatches(player, mission)) {
            mission.declined.add(new Mission.Identity(
                    player.uuid() == null ? "" : player.uuid().toString(), player.name(), now()));
        }
        if (mission.assignees.size() > 1 || mission.invited.size() > 1) {
            mission.assignees.removeIf(e -> identityMatches(player, e));
            mission.invited.removeIf(e -> identityMatches(player, e));
            ctx.missions().write(store);
            audit.record("mission_decline", player.name(), player.uuid().toString(),
                    player.name(), player.uuid().toString(), "SUCCESS", "left_team_mission" + " missionId=" + mission.id);
            notify(mission.issuer, "[Straja] " + player.name() + " a refuzat participarea la misiunea #"
                    + mission.id + ". Misiunea rămâne deschisă pentru ceilalți participanți.");
            player.tell("Ai refuzat participarea la misiunea #" + mission.id + ".");
            return true;
        }
        mission.status = "DECLINED";
        ctx.missions().write(store);
        audit.record("mission_decline", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "declined" + " missionId=" + mission.id);
        notify(mission.issuer, "[Straja] Misiunea #" + mission.id + " a fost refuzată de " + player.name() + ".");
        player.tell("Misiunea #" + mission.id + " a fost refuzată.");
        return true;
    }

    public boolean report(PlayerGateway player, String id, String report) {
        if (!guardActive(player, "raporta o misiune")) return false;
        var store = store();
        var mission = store.find(id);
        if (mission == null || !targetMatches(player, mission)) {
            player.tell("Misiunea nu există sau nu îți aparține.");
            return false;
        }
        if (!"ACCEPTED".equals(mission.status)) {
            player.tell("Raportul nu mai poate fi trimis pentru această misiune: " + mission.status + ".");
            return false;
        }
        if (mission.dueAt <= now()) {
            expire(store, mission);
            player.tell("Misiunea a depășit timpul și a fost marcată ca eșuată.");
            return false;
        }
        if (report == null || report.isBlank()) {
            player.tell("Scrie raportul misiunii.");
            return false;
        }
        mission.status = "REPORTED";
        mission.report = report.substring(0, Math.min(report.length(), ctx.policies().envelopeMaxBodyLength));
        mission.reportedAt = now();
        ctx.missions().write(store);
        audit.record("mission_report", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", "reported" + " missionId=" + mission.id);
        notify(mission.issuer, "[Straja] Raport primit pentru misiunea #" + mission.id + ": " + mission.report);
        player.tell("Raportul pentru misiunea #" + mission.id + " a fost trimis.");
        return true;
    }

    public boolean fail(PlayerGateway player, String id, String reason) {
        if (!guardActive(player, "eșua o misiune")) return false;
        var store = store();
        var mission = store.find(id);
        if (mission == null || !targetMatches(player, mission)) {
            player.tell("Misiunea nu există sau nu îți aparține.");
            return false;
        }
        if (!"ISSUED".equals(mission.status) && !"ACCEPTED".equals(mission.status)) {
            player.tell("Misiunea nu mai poate fi eșuată: " + mission.status + ".");
            return false;
        }
        boolean deadlinePassed = mission.dueAt <= now();
        mission.status = "FAILED";
        mission.failedAt = now();
        mission.failureReason = deadlinePassed ? "deadline"
                : (reason == null || reason.isBlank() ? "renunțare" : reason.substring(0, Math.min(reason.length(), 240)));
        releaseIssuerBudget(store, mission);
        ctx.missions().write(store);
        audit.record("mission_fail", player.name(), player.uuid().toString(),
                player.name(), player.uuid().toString(), "SUCCESS", mission.failureReason + " missionId=" + mission.id);
        notify(mission.issuer, "[Straja] Misiunea #" + mission.id + " a eșuat pentru "
                + player.name() + ": " + mission.failureReason + ".");
        player.tell("Misiunea #" + mission.id + " a fost marcată ca eșuată. Recompensa nu se plătește.");
        return true;
    }

    public boolean complete(PlayerGateway player, String id) {
        var store = store();
        var mission = store.find(id);
        if (mission == null || (!players.isCommissioner(player)
                && (!missionAuthority(player) || !issuerMatches(player, mission)))) {
            player.tell("Nu ai autoritate pentru această misiune.");
            return false;
        }
        if (!"REPORTED".equals(mission.status)) {
            player.tell("Misiunea trebuie să aibă raportul primit înainte de închidere.");
            return false;
        }
        if (mission.dueAt <= now()) {
            mission.status = "FAILED";
            mission.failedAt = now();
            mission.failureReason = "deadline_before_completion";
            releaseIssuerBudget(store, mission);
            ctx.missions().write(store);
            notify(mission.target, "[Straja] Misiunea #" + mission.id
                    + " a eșuat: termen depășit înainte de închidere.");
            player.tell("Misiunea a eșuat: termenul a fost depășit înainte de închidere.");
            return false;
        }
        mission.status = "COMPLETED";
        mission.completedAt = now();
        ctx.missions().write(store);
        audit.record("mission_complete", player.name(), player.uuid().toString(),
                mission.target, mission.targetUuid, "SUCCESS", "completed" + " missionId=" + mission.id);
        // Auto-claim for online participants; offline claims stay pending.
        int claimed = 0;
        for (var entry : mission.assignees) {
            var participant = ctx.server().findPlayer(entry.name);
            if (participant != null && claimReward(participant, mission.id)) claimed++;
        }
        participantNames(mission).forEach(name ->
                notify(name, "[Straja] Misiunea #" + mission.id + " a fost închisă de " + player.name() + "."));
        player.tell("Misiunea #" + mission.id + " a fost închisă.");
        if (mission.reward > 0 && claimed < mission.assignees.size()) {
            player.tell("Recompensa participanților offline rămâne în așteptare până la reconectare.");
        }
        return true;
    }

    // ------------------------------------------------------------ rewards

    private Map<String, Mission.RewardClaim> claims(Mission mission) {
        var participants = mission.assignees;
        int total = Math.max(0, mission.reward);
        int base = participants.isEmpty() ? 0 : total / participants.size();
        int remainder = participants.isEmpty() ? 0 : total % participants.size();
        for (int i = 0; i < participants.size(); i++) {
            var entry = participants.get(i);
            String key = !entry.uuid.isEmpty() ? entry.uuid : PlayerService.canon(entry.name);
            if (key.isEmpty() || mission.rewardClaims.containsKey(key)) continue;
            var claim = new Mission.RewardClaim();
            claim.name = entry.name;
            claim.uuid = entry.uuid;
            claim.amount = base + (i < remainder ? 1 : 0);
            claim.status = claim.amount > 0 ? "PENDING" : "NONE";
            claim.payoutId = participants.size() == 1
                    ? mission.rewardPayoutId
                    : "mission:" + mission.id + ":" + key + ":reward";
            mission.rewardClaims.put(key, claim);
        }
        return mission.rewardClaims;
    }

    private String refreshRewardStatus(Mission mission) {
        var claims = claims(mission);
        var statuses = claims.values().stream().map(c -> c.status).toList();
        if (statuses.isEmpty() || statuses.stream().allMatch("NONE"::equals)) mission.rewardStatus = "NONE";
        else if (statuses.stream().allMatch(s -> "PAID".equals(s) || "NONE".equals(s))) mission.rewardStatus = "PAID";
        else if (statuses.stream().anyMatch("PAYMENT_REVIEW"::equals)) mission.rewardStatus = "PAYMENT_REVIEW";
        else if (statuses.stream().anyMatch("PAID"::equals)) mission.rewardStatus = "PARTIAL";
        else if (statuses.stream().anyMatch("PAYMENT_IN_PROGRESS"::equals)) mission.rewardStatus = "PAYMENT_IN_PROGRESS";
        else if (statuses.stream().anyMatch("PAYMENT_FAILED"::equals)) mission.rewardStatus = "PAYMENT_FAILED";
        else mission.rewardStatus = "PENDING";
        return mission.rewardStatus;
    }

    public boolean claimReward(PlayerGateway player, String id) {
        var store = store();
        var mission = store.find(id);
        if (mission == null || (!targetMatches(player, mission) && !players.isCommissioner(player))) {
            player.tell("Recompensa nu există sau nu îți aparține.");
            return false;
        }
        if (!"COMPLETED".equals(mission.status)) {
            player.tell("Recompensa se poate ridica numai după completarea misiunii.");
            return false;
        }
        if (!rewardValid(mission.reward)) {
            player.tell("Recompensa misiunii este invalidă; anunță Comisaru'.");
            return false;
        }
        var claims = claims(mission);
        if (mission.reward <= 0) {
            mission.rewardStatus = "NONE";
            ctx.missions().write(store);
            player.tell("Misiunea nu are recompensă monetară.");
            return true;
        }
        PlayerGateway recipient = targetMatches(player, mission) ? player : null;
        if (recipient == null && players.isCommissioner(player)) {
            for (var entry : mission.assignees) {
                // UUID-bound assignees resolve by UUID only; the name lookup is
                // reserved for name-only legacy records.
                var candidate = ctx.server().findPlayer(
                        entry.uuid != null && !entry.uuid.isEmpty() ? entry.uuid : entry.name);
                if (candidate == null) continue;
                var claim = claims.get(playerKey(candidate));
                if (claim != null && Set.of("PENDING", "PAYMENT_FAILED").contains(claim.status)) {
                    recipient = candidate;
                    break;
                }
            }
        }
        if (recipient == null) {
            player.tell("Destinatarul recompensei trebuie să fie online pentru ridicare.");
            return false;
        }
        var claim = claims.get(playerKey(recipient));
        if (claim == null) claim = claims.get(PlayerService.canon(recipient.name())); // name-keyed legacy records
        if (claim == null) {
            player.tell("Nu există o cotă de recompensă pentru acest participant.");
            return false;
        }
        if ("PAID".equals(claim.status) || "NONE".equals(claim.status)) {
            player.tell("Cota ta din recompensa misiunii a fost deja plătită.");
            return false;
        }
        if ("PAYMENT_REVIEW".equals(claim.status) || "PAYMENT_IN_PROGRESS".equals(claim.status)) {
            player.tell("Plata cotei tale este în verificare; Comisaru' trebuie să verifice tranzacția "
                    + "pentru misiunea #" + mission.id + ".");
            return false;
        }
        claim.status = "PAYMENT_IN_PROGRESS";
        refreshRewardStatus(mission);
        ctx.missions().write(store); // persist before the external coin delivery

        var payout = ctx.currency().deposit(recipient, claim.amount, claim.payoutId);
        if (!payout.ok()) {
            claim.status = payout.delivered() > 0 ? "PAYMENT_REVIEW" : "PAYMENT_FAILED";
            mission.rewardError = String.valueOf(payout.error()).substring(0,
                    Math.min(240, String.valueOf(payout.error()).length()));
            mission.rewardDeliveredDenominations = payout.delivered();
            refreshRewardStatus(mission);
            ctx.missions().write(store);
            audit.record("mission_reward", player.name(), player.uuid().toString(),
                    recipient.name(), claim.uuid, "FAILED", claim.status + " missionId=" + mission.id);
            player.tell(payout.delivered() > 0
                    ? "Cota ta a fost livrată parțial; plata este blocată pentru verificarea Comisarului."
                    : "Cota ta nu a putut fi livrată; soldul rămâne în așteptare.");
            return false;
        }
        claim.status = "PAID";
        claim.paidAt = now();
        refreshRewardStatus(mission);
        mission.rewardPaidAt = now();
        ctx.missions().write(store);
        audit.record("mission_reward", player.name(), player.uuid().toString(),
                recipient.name(), claim.uuid, "SUCCESS", "paid" + " missionId=" + mission.id);
        recipient.tell("Ai primit cota ta din recompensa misiunii #" + mission.id + ": "
                + claim.amount + " monede.");
        if (recipient != player) player.tell("Recompensa a fost livrată lui " + recipient.name() + ".");
        return true;
    }

    public boolean recoverReward(PlayerGateway actor, String id) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate recupera o plată.");
            return false;
        }
        var store = store();
        var mission = store.find(id);
        if (mission == null || !"COMPLETED".equals(mission.status)) {
            actor.tell("Misiunea nu există sau nu este închisă.");
            return false;
        }
        var claims = claims(mission);
        boolean recovered = false;
        for (var entry : mission.assignees) {
            var target = ctx.server().findPlayer(entry.name);
            if (target == null) continue;
            var claim = claims.get(playerKey(target));
            if (claim == null || claim.payoutId.isEmpty()) continue;
            if (!ctx.currency().hasReceipt(target, claim.payoutId)) continue;
            claim.status = "PAID";
            claim.recoveredAt = now();
            recovered = true;
            audit.record("mission_reward_recover", actor.name(), actor.uuid().toString(),
                    entry.name, entry.uuid, "SUCCESS", "receipt_found" + " missionId=" + mission.id);
        }
        refreshRewardStatus(mission);
        mission.rewardRecoveryRequestedAt = now();
        ctx.missions().write(store);
        boolean attempted = recovered;
        for (var entry : mission.assignees) {
            var target = ctx.server().findPlayer(entry.name);
            if (target == null) continue;
            var claim = claims.get(playerKey(target));
            if (claim == null || !Set.of("PENDING", "PAYMENT_FAILED").contains(claim.status)) continue;
            attempted = claimReward(target, id) || attempted;
        }
        if (!attempted) {
            actor.tell("Nu există încă un participant online cu o plată recuperabilă; soldul rămâne pending.");
        }
        return attempted;
    }

    /**
     * Login recovery: reconciles the player's reward claims on completed
     * missions against payout receipts, then retries pending or interrupted
     * payments while the recipient is online. A claim under PAYMENT_REVIEW is
     * left for the commissioner — a partially delivered payment cannot be
     * resolved automatically.
     */
    @Override
    public void deliverPendingRewards(PlayerGateway player) {
        if (player == null) return;
        String uuid = player.uuid() == null ? "" : player.uuid().toString();
        var store = store();
        List<String> retry = new ArrayList<>();
        boolean changed = false;
        for (Mission mission : store.missions) {
            if (mission == null || !"COMPLETED".equals(mission.status)) continue;
            if (mission.rewardClaims == null) mission.rewardClaims = new java.util.LinkedHashMap<>();
            // claims() materializes the durable per-participant shares on first
            // access — an offline completer may not have an entry yet; persist
            // the materialization so the records survive the next restart.
            int before = mission.rewardClaims.size();
            var claims = claims(mission);
            if (mission.rewardClaims.size() != before) changed = true;
            var claim = claims.get(playerKey(player));
            if (claim == null) {
                claim = claims.get(PlayerService.canon(player.name()));
            }
            if (claim == null || claim.status == null) continue;
            boolean receipt = !claim.payoutId.isEmpty()
                    && ctx.currency().hasReceipt(player, claim.payoutId);
            switch (claim.status) {
                case "PAYMENT_IN_PROGRESS" -> {
                    if (receipt) {
                        claim.status = "PAID";
                        claim.recoveredAt = now();
                        refreshRewardStatus(mission);
                        changed = true;
                        audit.record("mission_reward_recover", player.name(), uuid,
                                player.name(), uuid, "SUCCESS",
                                "receipt_found missionId=" + mission.id);
                    } else {
                        claim.status = "PAYMENT_FAILED";
                        refreshRewardStatus(mission);
                        changed = true;
                        audit.record("mission_reward_recover", player.name(), uuid,
                                player.name(), uuid, "FAILED",
                                "interrupted_payment_retryable missionId=" + mission.id);
                        retry.add(mission.id);
                    }
                }
                case "PENDING", "PAYMENT_FAILED" -> {
                    if (receipt) {
                        claim.status = "PAID";
                        claim.recoveredAt = now();
                        refreshRewardStatus(mission);
                        changed = true;
                        audit.record("mission_reward_recover", player.name(), uuid,
                                player.name(), uuid, "SUCCESS",
                                "receipt_found missionId=" + mission.id);
                    } else {
                        retry.add(mission.id);
                    }
                }
                default -> {}
            }
        }
        if (changed) ctx.missions().write(store);
        for (String id : retry) claimReward(player, id);
    }

    // ------------------------------------------------------------ lifecycle

    private void expire(MissionStore store, Mission mission) {
        if (mission == null || !mission.isOpen()) return;
        mission.status = "EXPIRED";
        mission.expiredAt = now();
        releaseIssuerBudget(store, mission);
        audit.record("mission_expire", "", "", "", "", "SUCCESS",
                "deadline missionId=" + mission.id);
        notify(mission.target, "[Straja] Misiunea #" + mission.id + " a expirat.");
        notify(mission.issuer, "[Straja] Misiunea #" + mission.id + " a expirat pentru " + mission.target + ".");
    }

    /** Tick: open missions past their deadline fail (accepted) or expire (issued). */
    @Override
    public void tick() {
        var store = store();
        boolean changed = false;
        for (Mission mission : store.missions) {
            if (!mission.isOpen() || mission.dueAt > now()) continue;
            boolean wasAccepted = "ACCEPTED".equals(mission.status) || "REPORTED".equals(mission.status);
            mission.status = wasAccepted ? "FAILED" : "EXPIRED";
            if (wasAccepted) {
                mission.failedAt = now();
                mission.failureReason = "deadline";
            } else {
                mission.expiredAt = now();
            }
            releaseIssuerBudget(store, mission);
            changed = true;
            String resultText = wasAccepted ? "a eșuat: termen depășit" : "a expirat";
            notify(mission.target, "[Straja] Misiunea #" + mission.id + " " + resultText + ".");
            notify(mission.issuer, "[Straja] Misiunea #" + mission.id + " " + resultText
                    + " pentru " + mission.target + ".");
        }
        changed |= pruneClosedMissions(store);
        if (changed) ctx.missions().write(store);
    }

    /**
     * Bounds the mission store to {@code missionRetentionLimit} closed missions.
     * Open missions are never pruned; closed missions with unsettled reward
     * work (pending payment or recovery) are kept until they resolve.
     */
    private boolean pruneClosedMissions(MissionStore store) {
        int limit = ctx.policies().missionRetentionLimit;
        if (limit <= 0) return false;
        List<Mission> prunable = new ArrayList<>();
        int kept = 0;
        for (Mission mission : store.missions) {
            if (mission.isOpen() || hasUnsettledReward(mission)) {
                kept++;
                continue;
            }
            prunable.add(mission);
        }
        if (kept + prunable.size() <= limit) return false;
        prunable.sort(Comparator.comparingLong(MissionService::closedAt).reversed());
        int surplus = Math.max(0, kept + prunable.size() - limit);
        List<Mission> doomed = prunable.subList(Math.max(0, prunable.size() - surplus), prunable.size());
        boolean removed = store.missions.removeAll(doomed);
        if (removed) {
            audit.record("mission_pruned", "system", "", "", "", "SUCCESS",
                    "removed=" + doomed.size() + " limit=" + limit);
        }
        return removed;
    }

    private static boolean hasUnsettledReward(Mission mission) {
        if (!Set.of("NONE", "PAID").contains(mission.rewardStatus)) return true;
        for (Mission.RewardClaim claim : mission.rewardClaims.values()) {
            if (!"PAID".equals(claim.status) && !"NONE".equals(claim.status)) return true;
        }
        return false;
    }

    private static long closedAt(Mission mission) {
        long at = mission.createdAt;
        if (mission.completedAt != null) at = Math.max(at, mission.completedAt);
        if (mission.failedAt != null) at = Math.max(at, mission.failedAt);
        if (mission.expiredAt != null) at = Math.max(at, mission.expiredAt);
        if (mission.cancelledAt != null) at = Math.max(at, mission.cancelledAt);
        return at;
    }

    /** Cancels open missions when a player's role/status changes (suspend/fire/etc.). */
    public int cancelOpenFor(PlayerGateway player, String reason) {
        var store = store();
        int changed = 0;
        String cancellationReason = reason == null || reason.isBlank() ? "schimbare de statut" : reason;
        for (Mission mission : store.missions) {
            if (!mission.isOpen()) continue;
            if (!targetMatches(player, mission) && !issuerMatches(player, mission)) continue;
            mission.status = "CANCELLED_ROLE_CHANGE";
            mission.cancelledAt = now();
            mission.cancellationReason = cancellationReason;
            releaseIssuerBudget(store, mission);
            changed++;
            String other = targetMatches(player, mission) ? mission.issuer : mission.target;
            notify(other, "[Straja] Misiunea #" + mission.id + " a fost anulată: "
                    + mission.cancellationReason + ".");
        }
        if (changed > 0) {
            ctx.missions().write(store);
            audit.record("mission_cancel_role_change", player.name(), player.uuid().toString(),
                    "", "", "SUCCESS", cancellationReason + " count=" + changed);
        }
        return changed;
    }

    // ------------------------------------------------------------ helpers

    private boolean guardActive(PlayerGateway player, String action) {
        var state = players.state(player.uuid());
        if (!players.permissionLevel(player, state).atLeast(PermissionLevel.GUARD)) {
            player.tell("Doar un străjer activ poate " + action + ".");
            return false;
        }
        return true;
    }

    private void notify(String name, String text) {
        if (name == null || name.isEmpty()) return;
        var target = ctx.server().findPlayer(name);
        if (target != null) target.tell(text);
    }

    private List<String> participantNames(Mission mission) {
        List<String> names = new ArrayList<>();
        for (var entry : mission.assignees) if (entry.name != null) names.add(entry.name);
        return names;
    }

    public String summary(Mission mission) {
        var participants = participantNames(mission);
        String participantLabel = participants.size() > 1 ? String.join(", ", participants)
                : (mission.target.isEmpty() ? (participants.isEmpty() ? "—" : participants.get(0)) : mission.target);
        String base = "#" + mission.id + " [" + mission.status + "] pentru " + participantLabel
                + " — " + mission.minutes + " min — reward " + mission.reward
                + " — minim " + ctx.policies().rankName(mission.minimumRank)
                + " — " + participants.size() + "/" + mission.maxAssignees + " participanți"
                + " — începe " + mission.startLabel + " — " + mission.objective;
        if ("REPORTED".equals(mission.status) && mission.report != null && !mission.report.isEmpty()) {
            base += " — raport: " + mission.report.substring(0, Math.min(mission.report.length(), 160));
        }
        return base;
    }

    private String pretty(long timestamp) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()));
    }
}
