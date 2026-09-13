package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.FineRoleplayUseCase;
import com.dwurdy.straja.application.port.in.PrisonRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.FineStore;
import com.dwurdy.straja.domain.model.FineTask;
import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.List;

/**
 * Fines, appeals, escalation, recovery tasks, hearing warrants and jailer
 * assault missions. Faithful port of the KubeJS FineService semantics.
 */
public class FineService implements FineRoleplayUseCase {
    private static final long TICK_MS = 1000;
    private static final long PERSIST_MS = 5000;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int FORM_PROTOCOL_LIMIT = 2000;
    private static final int REVIEW_REASON_LIMIT = 240;
    private static final int WARRANT_REASON_LIMIT = 240;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final PrisonRoleplayUseCase prison;
    private long lastTickAt;
    private long lastPersistAt;

    public FineService(StrajaContext ctx, PlayerService players, AuditService audit,
                       PrisonRoleplayUseCase prison) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.prison = prison;
    }

    private StrajaPolicies p() {
        return ctx.policies();
    }

    private com.dwurdy.straja.domain.model.Sentence findSentence(String id) {
        if (id == null || id.isEmpty()) return null;
        for (var s : ctx.prison().read().sentences) if (s != null && id.equals(s.id)) return s;
        return null;
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    /** On-duty guard (rank ≥ Junior) — used for fine issuance, notifications
     *  and the jailer-assault exemption. Distinct from custody enforcement,
     *  which is capability-based and duty-independent. */
    private boolean onDutyGuard(PlayerGateway player) {
        var state = players.state(player);
        return state.duty && state.rank >= 1;
    }

    private SetupData.Location location(String key) {
        return ctx.setup().read().location(key);
    }

    private static boolean near(PlayerGateway player, SetupData.Location at, double radius) {
        if (at == null) return false;
        if (at.dimension != null && !at.dimension.equals(player.dimension())) return false;
        double dx = player.x() - at.x, dy = player.y() - at.y, dz = player.z() - at.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static boolean near(PlayerGateway player, PlayerGateway other, double radius) {
        String dimension = player.dimension();
        if (dimension == null ? other.dimension() != null : !dimension.equals(other.dimension())) {
            return false;
        }
        double dx = player.x() - other.x(), dy = player.y() - other.y(), dz = player.z() - other.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static String itemId(String name) {
        return "straja:" + name;
    }

    /** Read-only projection of the fine actions currently available to the player. */
    @Override
    public List<AvailableAction> availableActions(PlayerGateway player) {
        FineStore data = ctx.fines().read();
        String key = player.uuid() == null ? "" : player.uuid().toString();
        boolean atReception = near(player, location("receptionist"), 6);
        boolean issueFines = players.hasCapability(player, Capability.ISSUE_FINES);
        boolean reviewer = players.isCommissioner(player)
                || players.hasCapability(player, Capability.REVIEW_APPEALS);
        boolean onDuty = onDutyGuard(player);
        boolean commissioner = players.isCommissioner(player);
        boolean executeArrests = players.hasCapability(player, Capability.EXECUTE_ARRESTS);
        List<AvailableAction> citizen = new java.util.ArrayList<>();
        List<AvailableAction> refuse = new java.util.ArrayList<>();
        List<AvailableAction> review = new java.util.ArrayList<>();
        List<AvailableAction> tasks = new java.util.ArrayList<>();
        for (Fine fine : data.fines) {
            if (fine == null) continue;
            String id = fine.id == null ? "" : fine.id;
            boolean own = PlayerService.identityMatches(player, fine.targetUuid, fine.target);
            if (own && atReception && p().finesEnabled && List.of("ISSUED", "ESCALATED",
                    "ARREST_PENDING", "IN_SENTENCE", "GRACE_AFTER_SENTENCE").contains(fine.status)) {
                citizen.add(new AvailableAction(Action.PAY, id));
            }
            boolean appealable = List.of("ISSUED", "GRACE_AFTER_SENTENCE").contains(fine.status);
            boolean activeAppeal = fine.appeal != null && List.of("PENDING", "UPHELD", "REDUCED",
                    "VOID", "AUTO_WAIVED").contains(fine.appeal.status);
            if (own && atReception && p().appealsEnabled && appealable && !activeAppeal) {
                citizen.add(new AvailableAction(Action.APPEAL, id));
            }
            if (reviewer && atReception && "APPEAL_PENDING".equals(fine.status)
                    && fine.appeal != null && "PENDING".equals(fine.appeal.status)
                    && !key.equals(fine.issuerUuid)) {
                review.add(new AvailableAction(Action.REVIEW_APPEAL, id));
            }
        }
        for (FineTask task : data.tasks) {
            if (task == null) continue;
            String id = task.id == null ? "" : task.id;
            var assignees = task.assignees == null ? List.<String>of() : task.assignees;
            boolean assigned = assignees.contains(key);
            Fine fine = task.fineId != null ? data.find(task.fineId) : null;
            if (atReception && "PRESENTED".equals(task.status) && fine != null
                    && "ARREST_PENDING".equals(fine.status)
                    && PlayerService.identityMatches(player, task.targetUuid, task.target)) {
                refuse.add(new AvailableAction(Action.REFUSE, id));
            }
            if (onDuty && "OPEN".equals(task.status) && !assigned
                    && assignees.size() < Math.max(1, task.maxAssignees)
                    && (!"JAILER_ASSAULT".equals(task.kind) || executeArrests)) {
                tasks.add(new AvailableAction(Action.ACCEPT_TASK, id));
            }
            if ("OPEN".equals(task.status) && ((assigned && onDuty) || commissioner)) {
                tasks.add(new AvailableAction(Action.COMPLETE_TASK, id));
            }
            if ("REFUSED".equals(task.status) && !"HEARING_WARRANT".equals(task.kind)
                    && executeArrests && (assigned || commissioner)) {
                tasks.add(new AvailableAction(Action.ARREST_TASK, id));
            }
            boolean recorded = task.arrestedByUuid != null && task.arrestedByUuid.equals(key);
            if (List.of("ARRESTED", "SUSPECT_KILLED").contains(task.status)
                    && (assigned || recorded)
                    && !ctx.currency().hasReceipt(player, "arrest:" + task.id + ":" + key)) {
                tasks.add(new AvailableAction(Action.CLAIM_TASK_REWARD, id));
            }
        }
        List<AvailableAction> actions = new java.util.ArrayList<>();
        if (issueFines && player.inventory().contains(itemId("fine_book"))) {
            actions.add(new AvailableAction(Action.DRAFT_WRITE, ""));
        }
        if (issueFines && data.drafts.get(key) != null) {
            actions.add(new AvailableAction(Action.DRAFT_STATUS, ""));
        }
        if (onDuty || commissioner) {
            actions.add(new AvailableAction(Action.LIST_TASKS, ""));
        }
        for (AvailableAction action : citizen) {
            if (action.action() == Action.PAY) actions.add(action);
        }
        actions.addAll(refuse);
        for (AvailableAction action : citizen) {
            if (action.action() == Action.APPEAL) actions.add(action);
        }
        if (reviewer && atReception) {
            actions.add(new AvailableAction(Action.LIST_APPEALS, ""));
        }
        actions.addAll(review);
        actions.addAll(tasks);
        if (commissioner || (onDuty && players.state(player).rank >= 4)) {
            actions.add(new AvailableAction(Action.HEARING_WARRANT, ""));
        }
        return List.copyOf(actions);
    }

    @Override
    public Limits limits() {
        return new Limits(
                Math.min(p().fineMaxLawLength, FORM_PROTOCOL_LIMIT),
                Math.min(p().fineMaxDescriptionLength, FORM_PROTOCOL_LIMIT),
                Math.min(p().appealMaxReasonLength, FORM_PROTOCOL_LIMIT),
                REVIEW_REASON_LIMIT,
                WARRANT_REASON_LIMIT);
    }

    private static String joinInts(List<Integer> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- drafts

    @Override
    public boolean writeDraft(PlayerGateway issuer, String targetName, int amount, String law, String description) {
        return writeDraft(issuer, ctx.server().findPlayer(targetName), amount, law, description);
    }

    public boolean writeDraft(PlayerGateway issuer, PlayerGateway target, int amount, String law, String description) {
        if (!p().finesEnabled) {
            issuer.tell("Sistemul de amenzi este dezactivat.");
            return false;
        }
        if (!players.hasCapability(issuer, Capability.ISSUE_FINES) || !issuer.inventory().contains(itemId("fine_book"))) {
            issuer.tell("Ține Registrul de Amenzi în inventar și fii în serviciu.");
            return false;
        }
        if (target == null) {
            issuer.tell("Cetățeanul trebuie să fie online pentru întocmirea amenzii.");
            return false;
        }
        if (players.isCommissioner(target)
                || (!players.isCommissioner(issuer)
                        && players.state(target).rank >= players.state(issuer).rank)) {
            issuer.tell("Nu poți amenda Comisaru' sau un gardian de același rang/superior.");
            return false;
        }
        List<Integer> allowed = p().fineAllowedAmounts.stream().sorted().toList();
        if (!allowed.contains(amount)) {
            issuer.tell("Suma trebuie să fie una standard: " + joinInts(allowed) + " monede.");
            return false;
        }
        if (law == null || law.isBlank() || law.length() > p().fineMaxLawLength) {
            issuer.tell("Legea trebuie completată și să aibă maximum " + p().fineMaxLawLength + " caractere.");
            return false;
        }
        if (description == null || description.isBlank() || description.length() > p().fineMaxDescriptionLength) {
            issuer.tell("Descrierea trebuie completată și să aibă maximum " + p().fineMaxDescriptionLength + " caractere.");
            return false;
        }
        FineStore data = ctx.fines().read();
        FineStore.FineDraft draft = new FineStore.FineDraft();
        draft.target = target.name();
        draft.targetUuid = target.uuid().toString();
        draft.amount = amount;
        draft.law = law.trim();
        draft.description = description.trim();
        draft.writtenAt = now();
        data.drafts.put(issuer.uuid().toString(), draft);
        ctx.fines().write(data);
        issuer.tell("Formularul amenzii este completat. Fă click dreapta pe " + target.name() + " cu Registrul de Amenzi pentru emitere.");
        return true;
    }

    public String draftText(PlayerGateway issuer) {
        FineStore.FineDraft draft = ctx.fines().read().drafts.get(issuer.uuid().toString());
        return draft == null ? "Nu ai formular de amendă."
                : "Formular: " + draft.target + " | " + draft.amount + " | " + draft.law + " | " + draft.description;
    }

    public boolean issueFromDraft(PlayerGateway issuer, PlayerGateway target) {
        if (!p().finesEnabled) {
            issuer.tell("Sistemul de amenzi este dezactivat.");
            return false;
        }
        FineStore data = ctx.fines().read();
        FineStore.FineDraft draft = data.drafts.get(issuer.uuid().toString());
        if (draft == null) {
            issuer.tell("Nu ai un formular de amendă scris. Completează Registrul de Amenzi înainte să-l prezinți țintei.");
            return false;
        }
        if (target == null || !target.uuid().toString().equals(draft.targetUuid)) {
            issuer.tell("Aceasta nu este ținta înscrisă în formular.");
            return false;
        }
        if (!players.hasCapability(issuer, Capability.ISSUE_FINES)
                || players.isCommissioner(target)
                || (!players.isCommissioner(issuer)
                        && players.state(target).rank >= players.state(issuer).rank)) {
            issuer.tell("Amenda este refuzată de matricea de autoritate.");
            return false;
        }
        for (Fine existing : data.fines) {
            if (!existing.issuerUuid.equals(issuer.uuid().toString())
                    || !existing.targetUuid.equals(draft.targetUuid)
                    || existing.issuedAt < draft.writtenAt
                    || existing.amount != draft.amount
                    || !existing.law.equalsIgnoreCase(draft.law)
                    || !existing.description.equalsIgnoreCase(draft.description)
                    || !("ISSUED".equals(existing.status) || "DELIVERY_FAILED".equals(existing.status))) continue;
            if ("DELIVERY_FAILED".equals(existing.status)) {
                if (!target.giveVerified(noticeStack(existing))) {
                    issuer.tell("Înștiințarea existentă încă nu poate fi predată.");
                    return false;
                }
                existing.status = "ISSUED";
            }
            data.drafts.remove(issuer.uuid().toString());
            ctx.fines().write(data);
            issuer.tell("Amenda " + existing.id + " există deja; nu a fost creat un duplicat.");
            return true;
        }
        long issuedAt = now();
        Fine fine = new Fine();
        fine.id = data.nextFineId();
        fine.target = target.name();
        fine.targetUuid = target.uuid().toString();
        fine.issuer = issuer.name();
        fine.issuerUuid = issuer.uuid().toString();
        fine.law = draft.law;
        fine.description = draft.description;
        fine.amount = draft.amount;
        fine.status = "ISSUED";
        fine.issuedAt = issuedAt;
        fine.onlineDays = onlineDays();
        fine.onlineGraceMs = onlineGraceMs();
        fine.onlineElapsedMs = 0;
        fine.onlineLastTickAt = issuedAt;
        fine.recoveryCycle = 1;
        data.fines.add(fine);
        if (!target.giveVerified(noticeStack(fine))) {
            fine.status = "DELIVERY_FAILED";
            ctx.fines().write(data);
            audit.record("fine_issue", issuer.name(), issuer.uuid().toString(), target.name(), target.uuid().toString(), "FAILED", "inventory_delivery_failed fineId=" + fine.id);
            issuer.tell("Înștiințarea nu a putut fi predată; amenda a fost blocată pentru verificare.");
            return false;
        }
        data.drafts.remove(issuer.uuid().toString());
        ctx.fines().write(data);
        audit.record("fine_issue", issuer.name(), issuer.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", "issued fineId=" + fine.id + " amount=" + fine.amount);
        target.tell("Ai primit amenda " + fine.id + ": " + fine.amount + " monede pentru " + fine.law + ". Plata se face la recepționistă.");
        issuer.tell("Amenda " + fine.id + " a fost emisă și predată. Registrul rămâne reutilizabil.");
        return true;
    }

    private ItemSpec noticeStack(Fine fine) {
        return ItemSpec.of(itemId("fine_notice"), 1)
                .withData("StrajaFineId", fine.id)
                .withData("StrajaFineDetails", fine.amount + "|" + fine.law + "|" + fine.description
                        + "|days=" + (fine.onlineDays > 0 ? fine.onlineDays : onlineDays())
                        + "|remainingMin=" + (long) Math.ceil(onlineRemainingMs(fine) / 60000.0))
                .named("Înștiințare de Amendă " + fine.id);
    }

    // ---------------------------------------------------------------- payment

    public boolean pay(PlayerGateway player, String id) {
        if (!p().finesEnabled) {
            player.tell("Sistemul de amenzi este dezactivat.");
            return false;
        }
        FineStore data = ctx.fines().read();
        Fine fine = data.find(id);
        if (fine == null || !List.of("ISSUED", "ESCALATED", "ARREST_PENDING", "IN_SENTENCE", "GRACE_AFTER_SENTENCE").contains(fine.status)) {
            player.tell("Amenda nu există sau este deja închisă.");
            return false;
        }
        if (!PlayerService.identityMatches(player, fine.targetUuid, fine.target)) {
            player.tell("Această amendă nu îți aparține.");
            return false;
        }
        if (p().finePaymentRequiresReception && !near(player, location("receptionist"), 6)) {
            player.tell("Plata se face la recepționistă. Mergi la locația configurată de Comisaru'.");
            return false;
        }
        if (ctx.currency().balanceOf(player) < fine.amount) {
            player.tell("Nu ai combinația exactă de monede pentru această amendă.");
            return false;
        }
        String previousStatus = fine.status;
        fine.status = "PAYMENT_REVIEW";
        Fine.PaymentAttempt attempt = new Fine.PaymentAttempt();
        attempt.amount = fine.amount;
        attempt.player = player.name();
        attempt.playerUuid = player.uuid().toString();
        attempt.startedAt = now();
        attempt.previousStatus = previousStatus;
        fine.paymentAttempt = attempt;
        ctx.fines().write(data);
        audit.record("fine_payment_review", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "STARTED", "payment_boundary fineId=" + fine.id + " amount=" + fine.amount);
        var withdrawal = ctx.currency().withdraw(player, fine.amount);
        attempt.removedValue = withdrawal.removed();
        attempt.error = withdrawal.error() == null ? "" : withdrawal.error();
        attempt.sideEffectUnknown = !withdrawal.ok() && withdrawal.removed() > 0;
        if (!withdrawal.ok()) {
            if (!attempt.sideEffectUnknown) {
                fine.status = previousStatus;
                fine.paymentAttempt = null;
            }
            ctx.fines().write(data);
            audit.record("fine_pay", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "FAILED",
                    (fine.paymentAttempt != null ? "payment_review" : "no_debit") + " fineId=" + fine.id + " removedValue=" + withdrawal.removed());
            player.tell(fine.paymentAttempt != null
                    ? "Plata a eșuat după o modificare posibilă a inventarului. Amenda este blocată pentru verificarea Comisarului; nu încerca din nou."
                    : "Plata nu a putut fi efectuată; monedele nu au fost debitate.");
            return false;
        }
        fine.status = "PAID";
        fine.paidAt = now();
        fine.paymentAttempt = null;
        for (FineTask task : data.tasks) {
            if (fine.id.equals(task.fineId) && List.of("OPEN", "PRESENTED", "REFUSED", "ARREST_PENDING", "ARRESTED").contains(task.status)) {
                task.status = "COMPLETED";
                task.completedAt = now();
            }
        }
        ctx.fines().write(data);
        audit.record("fine_pay", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "paid_at_reception fineId=" + fine.id + " amount=" + fine.amount);
        player.tell("Amenda " + fine.id + " a fost plătită la recepționistă.");
        return true;
    }

    public boolean recoverPayment(PlayerGateway player, String id, String decision) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate recupera o plată aflată în verificare.");
            return false;
        }
        FineStore data = ctx.fines().read();
        Fine fine = data.find(id);
        if (fine == null || !"PAYMENT_REVIEW".equals(fine.status) || fine.paymentAttempt == null) {
            player.tell("Nu există o plată de amendă în verificare pentru acest ID.");
            return false;
        }
        Fine.PaymentAttempt attempt = fine.paymentAttempt;
        String action = decision == null ? "" : decision.toLowerCase();
        if ("retry".equals(action) || "reincearca".equals(action)) {
            if (attempt.removedValue > 0 || attempt.sideEffectUnknown) {
                player.tell("Retry este blocat: inventarul poate fi modificat parțial. Verifică tranzacția și folosește recover paid dacă plata a fost încasată.");
                return false;
            }
            fine.status = attempt.previousStatus;
            fine.paymentAttempt = null;
            fine.paymentReviewResolvedAt = now();
            ctx.fines().write(data);
            audit.record("fine_payment_review", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "retry_authorized fineId=" + fine.id);
            player.tell("Verificarea a fost închisă fără debitare; plata poate fi încercată din nou.");
            return true;
        }
        if ("paid".equals(action) || "platita".equals(action)) {
            fine.status = "PAID";
            fine.paidAt = now();
            fine.paymentReviewResolvedAt = now();
            for (FineTask task : data.tasks) {
                if (fine.id.equals(task.fineId) && List.of("OPEN", "PRESENTED", "REFUSED", "ARREST_PENDING", "ARRESTED").contains(task.status)) {
                    task.status = "COMPLETED";
                    task.completedAt = now();
                }
            }
            fine.paymentAttempt = null;
            ctx.fines().write(data);
            audit.record("fine_payment_review", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "marked_paid_by_commissioner fineId=" + fine.id);
            player.tell("Amenda " + fine.id + " a fost marcată ca plătită după verificarea tranzacției.");
            return true;
        }
        player.tell("Verificare amendă: " + fine.id + " | status=" + fine.status + " | debitare estimată=" + attempt.amount
                + " | debitare observată=" + attempt.removedValue + " | efect necunoscut=" + (attempt.sideEffectUnknown ? "DA" : "NU")
                + ". Folosește /straja fine recover " + fine.id + " paid sau retry.");
        return true;
    }

    // ---------------------------------------------------------------- appeals

    public boolean appeal(PlayerGateway player, String id, String reason) {
        if (!p().appealsEnabled) {
            player.tell("Contestațiile sunt dezactivate.");
            return false;
        }
        if (!near(player, location("receptionist"), 6)) {
            player.tell("Contestația se depune la recepționistă.");
            return false;
        }
        FineStore data = ctx.fines().read();
        String key = player.uuid().toString();
        FineStore.AppealAbuse abuse = data.appealAbuse.get(key);
        if (abuse != null && abuse.blockedUntil > now()) {
            player.tell("Contestațiile tale sunt blocate temporar pentru depuneri repetate.");
            return false;
        }
        Fine fine = data.find(id);
        if (fine == null || !List.of("ISSUED", "GRACE_AFTER_SENTENCE").contains(fine.status)) {
            player.tell("Amenda nu mai poate fi contestată: trebuie să fie neachitată și neescaladată.");
            return false;
        }
        if (!PlayerService.identityMatches(player, fine.targetUuid, fine.target)) {
            player.tell("Doar cetățeanul amendat poate depune contestația.");
            return false;
        }
        if (fine.appeal != null && List.of("PENDING", "UPHELD", "REDUCED", "VOID", "AUTO_WAIVED").contains(fine.appeal.status)) {
            player.tell("Amenda are deja o contestație înregistrată.");
            return false;
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty() || text.length() > p().appealMaxReasonLength) {
            player.tell("Scrie motivul contestației, maximum " + p().appealMaxReasonLength + " caractere.");
            return false;
        }
        long windowMs = Math.max(1, p().appealAbuseWindowRealDays) * DAY_MS;
        if (abuse == null) {
            abuse = new FineStore.AppealAbuse();
            data.appealAbuse.put(key, abuse);
        }
        abuse.attempts.removeIf(t -> t < now() - windowMs);
        if (abuse.attempts.size() >= Math.max(1, p().appealMaxPerWindow)) {
            abuse.blockedUntil = now() + Math.max(1, p().appealAbuseBlockRealDays) * DAY_MS;
            ctx.fines().write(data);
            audit.record("fine_appeal_block", player.name(), key, player.name(), key, "SUCCESS", "appeal_spam attempts=" + abuse.attempts.size());
            player.tell("Contestațiile au fost blocate temporar pentru depuneri repetate.");
            return false;
        }
        long filedAt = now();
        abuse.attempts.add(filedAt);
        Fine.Appeal appeal = new Fine.Appeal();
        appeal.status = "PENDING";
        appeal.id = "FA-" + fine.id;
        appeal.filedAt = filedAt;
        appeal.deadlineAt = filedAt + Math.max(1, p().appealDecisionTimeoutRealDays) * DAY_MS;
        appeal.reason = text;
        appeal.filedBy = player.name();
        appeal.filedByUuid = key;
        appeal.previousStatus = fine.status;
        appeal.previousAmount = fine.amount;
        fine.appeal = appeal;
        fine.status = "APPEAL_PENDING";
        fine.appealSubmittedAt = filedAt;
        fine.appealLastTickAt = filedAt;
        ctx.fines().write(data);
        audit.record("fine_appeal_submit", player.name(), key, fine.issuer, fine.issuerUuid, "SUCCESS", "pending_review fineId=" + fine.id + " appealId=" + appeal.id);
        player.tell("Contestația " + appeal.id + " a fost depusă. Plata și escaladarea sunt suspendate până la decizie.");
        return true;
    }

    public boolean reviewAppeal(PlayerGateway player, String id, String decision, Integer reducedAmount, String reason) {
        if (!players.hasCapability(player, Capability.REVIEW_APPEALS) && !players.isCommissioner(player)) {
            player.tell("Doar Locotenentul sau Comisaru' poate decide contestații.");
            return false;
        }
        FineStore data = ctx.fines().read();
        Fine fine = data.find(id);
        if (fine == null || !"APPEAL_PENDING".equals(fine.status) || fine.appeal == null) {
            player.tell("Contestația nu există sau nu mai este în așteptare.");
            return false;
        }
        if (player.uuid().toString().equals(fine.issuerUuid)) {
            player.tell("Nu îți poți judeca propria amendă.");
            return false;
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (trimmedReason.length() > REVIEW_REASON_LIMIT) {
            player.tell("Motivul deciziei trebuie să aibă maximum " + REVIEW_REASON_LIMIT + " caractere.");
            return false;
        }
        if (!near(player, location("receptionist"), 6)) {
            player.tell("Decizia contestației se dă la recepționistă.");
            return false;
        }
        String action = decision == null ? "" : decision.toLowerCase();
        Fine.Appeal appeal = fine.appeal;
        long reviewAt = now();
        if (List.of("uphold", "mentine", "menține", "confirm").contains(action)) {
            fine.status = appeal.previousStatus == null || appeal.previousStatus.isEmpty() ? "ISSUED" : appeal.previousStatus;
            fine.onlineLastTickAt = reviewAt;
            appeal.decision = "UPHELD";
        } else if (List.of("reduce", "redu", "micsoreaza", "micșorează").contains(action)) {
            if (reducedAmount == null || !p().fineAllowedAmounts.contains(reducedAmount) || reducedAmount >= appeal.previousAmount) {
                player.tell("Reducerea trebuie să fie un tarif standard mai mic decât amenda inițială.");
                return false;
            }
            fine.amount = reducedAmount;
            fine.status = appeal.previousStatus == null || appeal.previousStatus.isEmpty() ? "ISSUED" : appeal.previousStatus;
            fine.onlineLastTickAt = reviewAt;
            appeal.decision = "REDUCED";
            appeal.reducedAmount = reducedAmount;
        } else if (List.of("void", "anuleaza", "anulează", "iertare").contains(action)) {
            fine.status = "WAIVED";
            fine.waivedAt = reviewAt;
            appeal.decision = "VOID";
        } else {
            player.tell("Folosește uphold, reduce <tarif-standard> sau void.");
            return false;
        }
        appeal.status = appeal.decision;
        appeal.reviewedAt = reviewAt;
        appeal.reviewedBy = player.name();
        appeal.reviewedByUuid = player.uuid().toString();
        appeal.decisionReason = trimmedReason;
        ctx.fines().write(data);
        audit.record("fine_appeal_review", player.name(), player.uuid().toString(), fine.target, fine.targetUuid, "SUCCESS", appeal.decision + " fineId=" + fine.id + " appealId=" + appeal.id);
        PlayerGateway target = ctx.server().findPlayer(fine.target);
        if (target != null) {
            target.tell("Contestația " + appeal.id + " a primit decizia: " + appeal.decision + ("WAIVED".equals(fine.status) ? ". Amenda a fost iertată." : "."));
        }
        player.tell("Decizia pentru " + appeal.id + " a fost înregistrată: " + appeal.decision + ".");
        return true;
    }

    // ---------------------------------------------------------------- tasks

    public void listTasks(PlayerGateway player) {
        if (!onDutyGuard(player) && !players.isCommissioner(player)) {
            player.tell("Doar Straja poate vedea misiunile de amenzi.");
            return;
        }
        FineStore data = ctx.fines().read();
        boolean any = false;
        for (FineTask task : data.tasks) {
            if (List.of("ARRESTED", "COMPLETED", "PRESENTED").contains(task.status)) continue;
            if (!task.assignees.isEmpty() && !task.assignees.contains(player.uuid().toString()) && !players.isCommissioner(player)) continue;
            Fine fine = task.fineId == null ? null : data.find(task.fineId);
            String value = fine != null ? String.valueOf(fine.amount) : (task.injuryAmount > 0 ? "vătămare " + task.injuryAmount : "—");
            player.tell(task.id + " | țintă: " + task.target + " | tip: " + task.kind + " | " + value + " | gărzi: " + task.assignees.size() + "/" + Math.max(1, task.maxAssignees));
            any = true;
        }
        if (!any) player.tell("Nu există misiuni de amenzi deschise.");
    }

    public boolean acceptTask(PlayerGateway player, String id) {
        if (!onDutyGuard(player)) {
            player.tell("Doar un Străjer activ poate prelua misiunea.");
            return false;
        }
        FineStore data = ctx.fines().read();
        FineTask task = data.findTask(id);
        if (task == null || !"OPEN".equals(task.status)) {
            player.tell("Misiunea nu există sau este deja închisă.");
            return false;
        }
        if ("JAILER_ASSAULT".equals(task.kind) && !players.hasCapability(player, Capability.EXECUTE_ARRESTS)) {
            player.tell("Misiunile de arestare sunt disponibile de la rangul Străjer în sus.");
            return false;
        }
        String key = player.uuid().toString();
        if (task.assignees.contains(key)) {
            player.tell("Ești deja în echipa acestei misiuni.");
            return false;
        }
        if (task.assignees.size() >= Math.max(1, task.maxAssignees)) {
            player.tell("Misiunea are deja numărul maxim de gărzi.");
            return false;
        }
        task.assignees.add(key);
        if (task.assignee == null) {
            task.assignee = key;
            task.assigneeName = player.name();
        }
        ctx.fines().write(data);
        audit.record("fine_task_accept", player.name(), key, null, null, "SUCCESS", "accepted taskId=" + task.id);
        player.tell("Misiunea " + task.id + " a fost preluată (" + task.assignees.size() + "/" + Math.max(1, task.maxAssignees) + "). Adu cetățeanul la destinație.");
        return true;
    }

    public boolean completeTask(PlayerGateway player, String id) {
        if (!onDutyGuard(player)) {
            player.tell("Doar un Străjer activ poate închide misiunea.");
            return false;
        }
        FineStore data = ctx.fines().read();
        FineTask task = data.findTask(id);
        if (task == null || !"OPEN".equals(task.status)
                || (!task.assignees.contains(player.uuid().toString()) && !players.isCommissioner(player))) {
            player.tell("Misiunea nu există sau nu îți este atribuită.");
            return false;
        }
        if ("HEARING_WARRANT".equals(task.kind)) return completeHearingWarrant(player, data, task);
        if ("JAILER_ASSAULT".equals(task.kind)) return completeJailerAssault(player, data, task);
        PlayerGateway target = ctx.server().findPlayer(task.target);
        if (target == null) {
            player.tell("Ținta trebuie să fie online și adusă la recepționistă.");
            return false;
        }
        var reception = location("receptionist");
        if (reception != null && !near(target, reception, 6)) {
            player.tell("Ținta trebuie adusă la recepționistă.");
            return false;
        }
        Fine fine = data.find(task.fineId);
        if (fine == null) {
            player.tell("Amenda asociată lipsește.");
            return false;
        }
        if (!List.of("ESCALATED", "ARREST_PENDING").contains(fine.status)) {
            player.tell("Amenda nu mai este eligibilă pentru recuperare.");
            return false;
        }
        task.status = "PRESENTED";
        task.presentedAt = now();
        task.presentedBy = player.name();
        fine.status = "ARREST_PENDING";
        ctx.fines().write(data);
        audit.record("fine_present", player.name(), player.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", "payment_requested taskId=" + task.id + " fineId=" + fine.id);
        target.tell("Ești la recepționistă pentru plata amenzii " + fine.id
                + ". Achită amenda la recepționistă sau declară-i refuzul (dosar " + task.id + ").");
        player.tell("Plata a fost solicitată. Arestarea este permisă doar după refuzul explicit al cetățeanului.");
        return true;
    }

    private boolean completeHearingWarrant(PlayerGateway player, FineStore data, FineTask task) {
        if (!players.hasCapability(player, Capability.EXECUTE_ARRESTS)) {
            player.tell("Doar Străjerul sau un rang superior poate executa mandatul de audiere.");
            return false;
        }
        PlayerGateway target = ctx.server().findPlayer(task.target);
        var office = location("commissionerOffice");
        PlayerGateway commissioner = null;
        for (PlayerGateway candidate : ctx.server().onlinePlayers()) {
            if (players.isCommissioner(candidate) && near(candidate, office, 6)) commissioner = candidate;
        }
        if (target == null || office == null || commissioner == null
                || !near(player, office, 6) || !near(target, office, 6)) {
            player.tell("Audierea cere ținta, executantul și Comisaru' prezenți la biroul configurat.");
            return false;
        }
        task.status = "PRESENTED";
        task.presentedAt = now();
        task.presentedBy = player.name();
        ctx.fines().write(data);
        target.tell("Ai fost adus la audiere. Nu ai fost trimis la pușcărie.");
        commissioner.tell("Mandatul " + task.id + " a fost executat.");
        audit.record("hearing_warrant_present", player.name(), player.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", "commissioner_present taskId=" + task.id);
        player.tell("Audierea pentru " + task.id + " a fost înregistrată.");
        return true;
    }

    private boolean completeJailerAssault(PlayerGateway player, FineStore data, FineTask task) {
        if (!players.hasCapability(player, Capability.EXECUTE_ARRESTS)) {
            player.tell("Doar Străjerul sau un rang superior poate executa această arestare.");
            return false;
        }
        if (!players.isCommissioner(player) && !task.assignees.contains(player.uuid().toString())) {
            player.tell("Misiunea nu îți este atribuită.");
            return false;
        }
        PlayerGateway target = ctx.server().findPlayer(task.target);
        if (target == null) {
            player.tell("Ținta trebuie să fie online pentru arestare.");
            return false;
        }
        if (!near(player, target, p().prisonArrestRadius)) {
            player.tell("Trebuie să fii lângă țintă pentru arestare.");
            return false;
        }
        if (prison.activeSentence(target) != null) {
            player.tell("Ținta are deja o sentință activă.");
            return false;
        }
        int days = Math.max(1, task.suggestedSentenceDays > 0 ? task.suggestedSentenceDays : p().prisonDefaultSentenceDays);
        var sentence = prison.arrest(target, task.id, days, player, task.id);
        if (sentence == null) {
            player.tell("Arestarea nu a putut fi înregistrată.");
            return false;
        }
        task.status = "ARRESTED";
        task.completedAt = now();
        task.arrestedBy = player.name();
        task.arrestedByUuid = player.uuid().toString();
        task.arrestedAt = now();
        ctx.fines().write(data);
        audit.record("jailer_assault_arrest", player.name(), player.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", "captured_alive taskId=" + task.id + " sentenceId=" + sentence.id);
        player.tell("Arestarea pentru vătămarea Temnicerului a fost înregistrată.");
        payArrestReward(player, task, null, true);
        return true;
    }

    public boolean refusePayment(PlayerGateway player, String taskId) {
        FineStore data = ctx.fines().read();
        FineTask task = data.findTask(taskId);
        if (task == null || !"PRESENTED".equals(task.status)) {
            player.tell("Nu există o solicitare de plată activă pentru acest ID.");
            return false;
        }
        if (!PlayerService.identityMatches(player, task.targetUuid, task.target)) {
            player.tell("Doar cetățeanul vizat poate refuza plata.");
            return false;
        }
        Fine fine = data.find(task.fineId);
        if (fine == null || !"ARREST_PENDING".equals(fine.status)) {
            player.tell("Amenda nu mai este în așteptarea unei decizii.");
            return false;
        }
        if (!near(player, location("receptionist"), 6)) {
            player.tell("Refuzul plății se declară la recepționistă.");
            return false;
        }
        task.status = "REFUSED";
        task.refusalCount++;
        task.refusedAt = now();
        task.refusedBy = player.name();
        ctx.fines().write(data);
        audit.record("fine_refuse", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "payment_refused taskId=" + task.id + " fineId=" + fine.id);
        if (task.assignee != null) {
            PlayerGateway assignee = ctx.server().findPlayer(task.assignee);
            if (assignee != null) assignee.tell("Cetățeanul a refuzat plata amenzii " + fine.id
                    + ". Poți executa arestarea conform dosarului " + task.id + " lângă țintă.");
        }
        player.tell("Refuzul a fost înregistrat. Garda poate executa arestarea conform misiunii.");
        return true;
    }

    public boolean arrest(PlayerGateway player, String taskId, Integer commissionerDays) {
        if (!players.hasCapability(player, Capability.EXECUTE_ARRESTS)) {
            player.tell("Doar Străjerul sau un rang superior poate executa arestarea.");
            return false;
        }
        FineStore data = ctx.fines().read();
        FineTask task = data.findTask(taskId);
        if (task == null || !List.of("PRESENTED", "REFUSED").contains(task.status)
                || "HEARING_WARRANT".equals(task.kind)
                || (!players.isCommissioner(player) && !task.assignees.contains(player.uuid().toString()))) {
            player.tell("Misiunea nu există sau nu îți este atribuită.");
            return false;
        }
        if (!"REFUSED".equals(task.status)) {
            player.tell("Arestarea este permisă doar după refuzul explicit al cetățeanului la recepționistă "
                    + "(dosar " + task.id + ").");
            return false;
        }
        PlayerGateway target = ctx.server().findPlayer(task.target);
        if (target == null) {
            player.tell("Arestarea pentru refuz se execută doar cât timp ținta este online.");
            return false;
        }
        Fine fine = data.find(task.fineId);
        if (fine == null || !"ARREST_PENDING".equals(fine.status)) {
            player.tell("Amenda nu mai este eligibilă pentru arest.");
            return false;
        }
        var existing = prison.activeSentence(target);
        if (existing != null && !String.valueOf(task.fineId).equals(existing.fineId)) {
            player.tell("Ținta are deja o sentință activă. Nu se pot suprapune sentințele.");
            return false;
        }
        if (!near(player, target, p().prisonArrestRadius)) {
            player.tell("Trebuie să fii lângă țintă pentru a executa arestarea.");
            return false;
        }
        boolean override = players.isCommissioner(player) && commissionerDays != null;
        if (override && commissionerDays < 1) {
            player.tell("Durata Comisarului trebuie să fie un număr întreg pozitiv.");
            return false;
        }
        int days = override ? Math.min(p().prisonMaxSentenceDays, commissionerDays) : sentenceDays(fine.amount);
        var sentence = prison.arrest(target, task.fineId, days, player, task.id);
        if (sentence == null) {
            player.tell("Arestarea nu a putut fi înregistrată.");
            return false;
        }
        task.status = "ARRESTED";
        task.completedAt = now();
        task.arrestedBy = player.name();
        task.arrestedByUuid = player.uuid().toString();
        task.arrestedAt = now();
        task.refusalReason = "payment_refused";
        fine.status = "IN_SENTENCE";
        fine.sentenceId = sentence.id;
        fine.onlineElapsedMs = 0;
        fine.onlineLastTickAt = 0;
        ctx.fines().write(data);
        audit.record("fine_arrest", player.name(), player.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", "payment_refused taskId=" + task.id + " fineId=" + task.fineId + " sentenceId=" + sentence.id);
        player.tell("Arestul a fost înregistrat: " + sentence.id + ".");
        payArrestReward(player, task, fine, true);
        return true;
    }

    // ------------------------------------------------------------ arrest rewards

    /**
     * Computes the bounty for completing an arrest task. The base is the
     * underlying case amount (jailer-assault injury or refused fine); minor
     * fine-recovery cases are divided by {@code arrestMinorDivider}. Alive
     * captures pay {@code arrestAliveMultiplier}×, dead suspects
     * {@code arrestDeathMultiplier}×. The result is clamped to
     * [{@code arrestRewardMinimum}, {@code arrestRewardMaximum}].
     */
    public int arrestRewardAmount(FineTask task, Fine fine, boolean alive) {
        var p = p();
        int base = task.injuryAmount > 0 ? task.injuryAmount
                : fine != null ? Math.max(0, fine.amount) : 0;
        if ("FINE_RECOVERY".equals(task.kind)) {
            base /= Math.max(1, p.arrestMinorDivider);
        }
        long scaled = base * (long) Math.max(0, alive ? p.arrestAliveMultiplier : p.arrestDeathMultiplier);
        return (int) Math.max(p.arrestRewardMinimum, Math.min(p.arrestRewardMaximum, scaled));
    }

    /**
     * Pays the arrest bounty to the officer, enforcing the per-day
     * {@code arrestMaxDailyPayout} cap. The payout is receipt-scoped
     * ("arrest:{taskId}:{uuid}") so a crash or duplicate call cannot pay twice;
     * a surviving receipt is treated as already paid.
     */
    private boolean payArrestReward(PlayerGateway officer, FineTask task, Fine fine, boolean alive) {
        String payoutId = "arrest:" + task.id + ":" + officer.uuid();
        if (ctx.currency().hasReceipt(officer, payoutId)) {
            officer.tell("Recompensa pentru dosarul " + task.id + " a fost deja plătită.");
            return true;
        }
        int reward = arrestRewardAmount(task, fine, alive);
        GuardState state = players.state(officer);
        long day = now() / DAY_MS;
        if (state.arrestRewardDay != day) {
            state.arrestRewardDay = day;
            state.arrestRewardDayTotal = 0;
        }
        int payable = Math.min(reward, Math.max(0, p().arrestMaxDailyPayout - state.arrestRewardDayTotal));
        if (payable <= 0) {
            audit.record("arrest_reward", officer.name(), officer.uuid().toString(),
                    task.target, task.targetUuid, "FAILED", "daily_cap taskId=" + task.id);
            officer.tell("Ai atins limita zilnică de recompense pentru arestări.");
            return false;
        }
        if (!ctx.currency().available()) {
            audit.record("arrest_reward", officer.name(), officer.uuid().toString(),
                    task.target, task.targetUuid, "FAILED", "currency_unavailable taskId=" + task.id);
            officer.tell("Recompensa nu a putut fi livrată acum; dosarul rămâne revendicabil.");
            return false;
        }
        var payout = ctx.currency().deposit(officer, payable, payoutId);
        if (!payout.ok()) {
            audit.record("arrest_reward", officer.name(), officer.uuid().toString(),
                    task.target, task.targetUuid, "FAILED",
                    "taskId=" + task.id + " delivered=" + payout.delivered()
                            + " error=" + payout.error());
            officer.tell("Recompensa nu a putut fi livrată acum; dosarul rămâne revendicabil.");
            return false;
        }
        state.arrestRewardDayTotal += payable;
        players.save(officer.uuid(), state);
        audit.record("arrest_reward", officer.name(), officer.uuid().toString(),
                task.target, task.targetUuid, "SUCCESS",
                (alive ? "captured_alive" : "suspect_killed") + " taskId=" + task.id
                        + " amount=" + payable);
        officer.tell("Recompensă de arestare: " + payable + " monede"
                + (payable < reward ? " (plafon zilnic atins)" : "") + ".");
        return true;
    }

    /**
     * Player-facing reward claim for a terminal arrest task. The persisted
     * task plus the deterministic payout receipt make the claim idempotent:
     * a failed or interrupted delivery stays recoverable on reconnect.
     */
    @Override
    public boolean claimTaskReward(PlayerGateway player, String id) {
        FineStore data = ctx.fines().read();
        FineTask task = data.findTask(id);
        if (task == null || !List.of("ARRESTED", "SUSPECT_KILLED").contains(task.status)) {
            player.tell("Nu există o recompensă de arestare pentru acest dosar.");
            return false;
        }
        String key = player.uuid() == null ? "" : player.uuid().toString();
        boolean assignee = task.assignees != null && task.assignees.contains(key);
        boolean recorded = task.arrestedByUuid != null && task.arrestedByUuid.equals(key);
        if (!assignee && !recorded) {
            player.tell("Doar garda însărcinată cu dosarul poate ridica recompensa.");
            return false;
        }
        Fine fine = task.fineId != null ? data.find(task.fineId) : null;
        return payArrestReward(player, task, fine, "ARRESTED".equals(task.status));
    }

    /**
     * Login recovery: retries arrest bounties whose delivery failed or was
     * interrupted while the officer was offline. The receipt
     * ("arrest:{taskId}:{uuid}") makes the retry idempotent — a surviving
     * receipt is skipped without touching the currency provider.
     */
    @Override
    public void recoverOnLogin(PlayerGateway player) {
        if (player == null || player.uuid() == null) return;
        String key = player.uuid().toString();
        FineStore data = ctx.fines().read();
        for (FineTask task : data.tasks) {
            if (task == null || !List.of("ARRESTED", "SUSPECT_KILLED").contains(task.status)) continue;
            boolean assignee = task.assignees != null && task.assignees.contains(key);
            if (!assignee && !key.equals(task.arrestedByUuid)) continue;
            if (ctx.currency().hasReceipt(player, "arrest:" + task.id + ":" + player.uuid())) continue;
            Fine fine = task.fineId != null ? data.find(task.fineId) : null;
            payArrestReward(player, task, fine, "ARRESTED".equals(task.status));
        }
    }

    /**
     * Suspect killed while an arrest task against them was executable: the task
     * resolves with the reduced death bounty instead of a live capture. Only a
     * guard empowered for the task (assignee or commissioner) collects.
     */
    @Override
    public void suspectKilled(PlayerGateway killer, PlayerGateway victim) {
        if (killer == null || victim == null) return;
        if (!players.hasCapability(killer, Capability.EXECUTE_ARRESTS)) return;
        FineStore data = ctx.fines().read();
        boolean changed = false;
        for (FineTask task : data.tasks) {
            if (!PlayerService.identityMatches(victim, task.targetUuid, task.target)) continue;
            boolean executable = "JAILER_ASSAULT".equals(task.kind) && "OPEN".equals(task.status)
                    || "FINE_RECOVERY".equals(task.kind) && "REFUSED".equals(task.status);
            if (!executable) continue;
            if (!players.isCommissioner(killer) && !task.assignees.contains(killer.uuid().toString())) continue;
            Fine fine = task.fineId != null ? data.find(task.fineId) : null;
            task.status = "SUSPECT_KILLED";
            task.completedAt = now();
            task.arrestedBy = killer.name();
            task.arrestedByUuid = killer.uuid().toString();
            task.arrestedAt = now();
            task.updatedAt = now();
            if (fine != null && "ARREST_PENDING".equals(fine.status)) {
                fine.status = "WRITTEN_OFF";
            }
            changed = true;
            ctx.fines().write(data);
            audit.record("suspect_killed", killer.name(), killer.uuid().toString(),
                    victim.name(), victim.uuid().toString(), "SUCCESS",
                    "taskId=" + task.id + (fine != null ? " fineId=" + fine.id : ""));
            killer.tell("Suspectul " + victim.name() + " a fost ucis. Misiunea " + task.id
                    + " este închisă; recompensa este redusă.");
            payArrestReward(killer, task, fine, false);
        }
        if (changed) ctx.fines().write(data);
    }

    @Override
    public boolean issueHearingWarrant(PlayerGateway player, String targetName, String details) {
        return issueHearingWarrant(player, ctx.server().findPlayer(targetName), details);
    }

    public boolean issueHearingWarrant(PlayerGateway player, PlayerGateway target, String details) {
        if (!players.isCommissioner(player)
                && (!onDutyGuard(player) || players.state(player).rank < 4)) {
            player.tell("Doar Locotenentul activ sau Comisaru' poate emite mandat de audiere.");
            return false;
        }
        if (target == null) {
            player.tell("Ținta mandatului trebuie să fie online.");
            return false;
        }
        if (players.isCommissioner(target)) {
            player.tell("Comisaru' nu poate fi ținta propriului mandat.");
            return false;
        }
        FineStore data = ctx.fines().read();
        for (FineTask task : data.tasks) {
            if ("HEARING_WARRANT".equals(task.kind) && List.of("OPEN", "PRESENTED").contains(task.status)
                    && target.uuid().toString().equals(task.targetUuid)) {
                player.tell("Există deja un mandat de audiere activ pentru această țintă.");
                return false;
            }
        }
        FineTask task = new FineTask();
        task.id = "AW-" + now() + "-" + (data.tasks.size() + 1);
        task.kind = "HEARING_WARRANT";
        task.target = target.name();
        task.targetUuid = target.uuid().toString();
        task.status = "OPEN";
        task.createdAt = now();
        task.missionMinutes = 30;
        task.destination = "commissionerOffice";
        task.warrantReason = details == null || details.isBlank()
                ? "Audiere la biroul Comisaru'."
                : details.substring(0, Math.min(240, details.length()));
        task.signedBy = player.name();
        task.signedByUuid = player.uuid().toString();
        task.maxAssignees = 2;
        data.tasks.add(task);
        ctx.fines().write(data);
        for (PlayerGateway candidate : ctx.server().onlinePlayers()) {
            if (onDutyGuard(candidate)) candidate.tell("Mandat de audiere " + task.id + ": adu " + task.target + " la biroul Comisaru'.");
        }
        target.tell("Un mandat de audiere a fost pus pe numele tău. Gărzile te vor conduce la biroul Comisaru'.");
        audit.record("hearing_warrant_issue", player.name(), player.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", "signed_warrant taskId=" + task.id);
        player.tell("Mandatul " + task.id + " a fost emis și semnat de " + player.name() + ".");
        return true;
    }

    /** Creates a JAILER_ASSAULT task when a civilian hurts/kills the jailer. */
    @Override
    public FineTask createJailerAssaultMission(PlayerGateway attacker, String jailerName, String outcome) {
        if (attacker == null || players.isCommissioner(attacker) || onDutyGuard(attacker)) return null;
        FineStore data = ctx.fines().read();
        String result = "KILLED".equalsIgnoreCase(outcome) ? "KILLED" : "WOUNDED";
        String incidentKey = jailerName + ":" + attacker.uuid();
        for (FineTask task : data.tasks) {
            if (!incidentKey.equals(task.incidentKey) || !"OPEN".equals(task.status)) continue;
            if ("KILLED".equals(result) && !"KILLED".equals(task.jailerOutcome)) {
                task.jailerOutcome = "KILLED";
                task.injuryAmount = jailerAssaultAmount("KILLED");
                task.updatedAt = now();
                ctx.fines().write(data);
                audit.record("jailer_assault_mission", attacker.name(), attacker.uuid().toString(), jailerName, null, "SUCCESS", "severity_upgraded taskId=" + task.id);
            }
            return task;
        }
        FineTask task = new FineTask();
        task.id = "JA-" + now() + "-" + (data.tasks.size() + 1);
        task.kind = "JAILER_ASSAULT";
        task.target = attacker.name();
        task.targetUuid = attacker.uuid().toString();
        task.status = "OPEN";
        task.createdAt = now();
        task.missionMinutes = 30;
        task.maxAssignees = Math.max(1, p().jailerAssaultMissionMaxAssignees);
        task.incidentKey = incidentKey;
        task.jailerOutcome = result;
        task.injuryAmount = jailerAssaultAmount(result);
        task.suggestedSentenceDays = Math.max(1, p().jailerAssaultSentenceDays);
        data.tasks.add(task);
        ctx.fines().write(data);
        for (PlayerGateway candidate : ctx.server().onlinePlayers()) {
            if (players.hasCapability(candidate, Capability.EXECUTE_ARRESTS)) {
                candidate.tell("Misiune urgentă " + task.id + ": arestează " + task.target + " pentru vătămarea Temnicerului.");
            }
        }
        attacker.tell("Un mandat de arest a fost emis pentru vătămarea Temnicerului. Gărzile vor veni după tine.");
        audit.record("jailer_assault_mission", attacker.name(), attacker.uuid().toString(), jailerName, null, "SUCCESS", result.toLowerCase() + " taskId=" + task.id);
        return task;
    }

    private int jailerAssaultAmount(String outcome) {
        return "KILLED".equals(outcome) ? Math.max(0, p().jailerAssaultKilledAmount) : Math.max(0, p().jailerAssaultWoundedAmount);
    }

    public boolean cancelFine(PlayerGateway player, String id) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate anula amenzi.");
            return false;
        }
        FineStore data = ctx.fines().read();
        Fine fine = data.find(id);
        if (fine == null) {
            player.tell("Amenda nu există.");
            return false;
        }
        fine.status = "CANCELLED";
        ctx.fines().write(data);
        player.tell("Amenda a fost anulată.");
        return true;
    }

    public void listFines(PlayerGateway player) {
        FineStore data = ctx.fines().read();
        int shown = 0;
        for (int i = data.fines.size() - 1; i >= 0 && shown < 20; i--) {
            Fine fine = data.fines.get(i);
            if (!players.isCommissioner(player)
                    && !PlayerService.identityMatches(player, fine.targetUuid, fine.target)) continue;
            String remaining = List.of("ISSUED", "GRACE_AFTER_SENTENCE").contains(fine.status)
                    ? (long) Math.ceil(onlineRemainingMs(fine) / 60000.0) + " minute online" : "—";
            player.tell(fine.id + " [" + fine.status + "] " + fine.amount + " monede | " + fine.law + " | termen online rămas: " + remaining);
            shown++;
        }
    }

    public void listAppeals(PlayerGateway player) {
        if (!players.hasCapability(player, Capability.REVIEW_APPEALS) && !players.isCommissioner(player)) {
            player.tell("Doar Locotenentul sau Comisaru' poate vedea contestațiile.");
            return;
        }
        FineStore data = ctx.fines().read();
        int shown = 0;
        for (int i = data.fines.size() - 1; i >= 0 && shown < 30; i--) {
            Fine fine = data.fines.get(i);
            if (!"APPEAL_PENDING".equals(fine.status) || fine.appeal == null) continue;
            player.tell(fine.id + " [APPEAL_PENDING] " + fine.target + " | " + fine.appeal.reason);
            shown++;
        }
    }

    /** Debug-only: force an appeal past its decision deadline and re-tick. */
    public boolean debugAppealTimeout(PlayerGateway player, String id) {
        if (!players.isCommissioner(player) || !p().debugEnabled) {
            player.tell("Testul de timeout pentru contestații este disponibil doar Comisarului în debug local.");
            return false;
        }
        FineStore data = ctx.fines().read();
        Fine fine = data.find(id);
        if (fine == null || !"APPEAL_PENDING".equals(fine.status) || fine.appeal == null) {
            player.tell("Amenda nu are o contestație în așteptare.");
            return false;
        }
        fine.appeal.deadlineAt = now() - 1;
        ctx.fines().write(data);
        lastTickAt = 0;
        tick();
        player.tell("Timeout debug aplicat: verifică dacă " + fine.id + " este WAIVED și AUTO_WAIVED.");
        return true;
    }

    // ---------------------------------------------------------------- tick

    @Override
    public void tick() {
        long current = now();
        if (current - lastTickAt < TICK_MS) return;
        lastTickAt = current;
        FineStore data = ctx.fines().read();
        boolean changed = false;
        boolean persistNow = false;
        for (Fine fine : data.fines) {
            if ("APPEAL_PENDING".equals(fine.status) && fine.appeal != null) {
                if (fine.appeal.deadlineAt <= current) {
                    fine.status = "WAIVED";
                    fine.waivedAt = current;
                    fine.appeal.status = "AUTO_WAIVED";
                    fine.appeal.decision = "AUTO_WAIVED";
                    fine.appeal.reviewedAt = current;
                    fine.appeal.reviewedBy = "SYSTEM";
                    fine.appeal.decisionReason = "Termen de " + p().appealDecisionTimeoutRealDays + " zile reale fără decizie.";
                    persistNow = true;
                    changed = true;
                    PlayerGateway target = ctx.server().findPlayer(fine.target);
                    if (target != null) target.tell("Contestația " + fine.appeal.id + " nu a primit decizie în termen. Amenda " + fine.id + " a fost iertată automat.");
                    audit.record("fine_appeal_auto_waive", null, null, fine.target, fine.targetUuid, "SUCCESS", "review_timeout fineId=" + fine.id);
                }
                continue;
            }
            if ("IN_SENTENCE".equals(fine.status)) {
                var sentence = findSentence(fine.sentenceId);
                if (sentence != null && List.of("SERVED", "FORCED_RELEASE", "CANCELLED").contains(sentence.status)) {
                    fine.status = "GRACE_AFTER_SENTENCE";
                    fine.onlineElapsedMs = 0;
                    fine.onlineLastTickAt = current;
                    fine.graceStartedAt = current;
                    fine.recoveryCycle = Math.max(1, fine.recoveryCycle) + 1;
                    fine.taskId = null;
                    changed = true;
                    persistNow = true;
                    PlayerGateway target = ctx.server().findPlayer(fine.target);
                    if (target != null) target.tell("Ai fost eliberat, dar amenda " + fine.id + " rămâne neachitată. Ai " + onlineDays() + " zile Minecraft online să obții banii înainte de o nouă misiune de recuperare.");
                }
                continue;
            }
            if (!List.of("ISSUED", "GRACE_AFTER_SENTENCE").contains(fine.status)) continue;
            if (fine.onlineGraceMs <= 0) {
                fine.onlineGraceMs = onlineGraceMs();
                changed = true;
            }
            if (fine.onlineElapsedMs < 0) {
                fine.onlineElapsedMs = 0;
                changed = true;
            }
            if (fine.onlineLastTickAt <= 0) {
                fine.onlineLastTickAt = current;
                changed = true;
            }
            if (fine.onlineDays <= 0) {
                fine.onlineDays = onlineDays();
                changed = true;
            }
            if (fine.recoveryCycle <= 0) {
                fine.recoveryCycle = 1;
                changed = true;
            }
            PlayerGateway target = ctx.server().findPlayer(fine.target);
            if (target == null) {
                if (fine.onlineLastTickAt != current) {
                    fine.onlineLastTickAt = current;
                    changed = true;
                }
                continue;
            }
            long previous = fine.onlineLastTickAt > 0 ? fine.onlineLastTickAt : current;
            long elapsed = Math.max(0, Math.min(current - previous, TICK_MS * 5));
            fine.onlineLastTickAt = current;
            fine.onlineElapsedMs = Math.min(fine.onlineGraceMs, fine.onlineElapsedMs + elapsed);
            if (elapsed > 0) changed = true;
            if (fine.onlineElapsedMs < fine.onlineGraceMs) continue;
            fine.status = "ESCALATED";
            fine.escalatedAt = current;
            persistNow = true;
            String taskId = recoveryTaskId(fine);
            fine.taskId = taskId;
            if (data.findTask(taskId) == null) {
                FineTask task = new FineTask();
                task.id = taskId;
                task.kind = "FINE_RECOVERY";
                task.fineId = fine.id;
                task.target = fine.target;
                task.targetUuid = fine.targetUuid;
                task.status = "OPEN";
                task.createdAt = current;
                task.missionMinutes = Math.max(1, p().escalationMissionMinutes);
                task.maxAssignees = fineRecoveryMaxAssignees(fine);
                data.tasks.add(task);
                for (PlayerGateway candidate : ctx.server().onlinePlayers()) {
                    if (onDutyGuard(candidate)) {
                        candidate.tell("Misiune nouă la secretariat: " + task.id + ". Adu " + fine.target + " la recepționistă pentru amenda restantă.");
                    }
                }
            }
            changed = true;
            audit.record("fine_escalate", null, null, fine.target, fine.targetUuid, "SUCCESS", "online_minecraft_days fineId=" + fine.id + " taskId=" + taskId + " cycle=" + fine.recoveryCycle);
            target.tell("Amenda " + fine.id + " a fost escaladată la secretariat; prezintă-te la recepționistă.");
        }
        if (changed && (persistNow || current - lastPersistAt >= PERSIST_MS)) {
            ctx.fines().write(data);
            lastPersistAt = current;
        }
    }

    private String recoveryTaskId(Fine fine) {
        int cycle = Math.max(1, fine.recoveryCycle);
        return cycle == 1 ? "FM-" + fine.id : "FM-" + fine.id + "-R" + cycle;
    }

    private int fineRecoveryMaxAssignees(Fine fine) {
        return Math.max(1, Math.min(4, 1 + fine.amount / 250));
    }

    private int sentenceDays(int amount) {
        Integer configured = p().sentenceDaysByAmount.get(amount);
        if (configured != null && configured > 0) return configured;
        return Math.max(1, p().prisonDefaultSentenceDays);
    }

    public int onlineDays() {
        return Math.max(1, p().fineOnlineDaysUntilEscalation);
    }

    public long onlineGraceMs() {
        return onlineDays() * Math.max(1, p().minecraftDayMinutes) * 60L * 1000L;
    }

    public long onlineRemainingMs(Fine fine) {
        long grace = fine.onlineGraceMs > 0 ? fine.onlineGraceMs : onlineGraceMs();
        return Math.max(0, grace - Math.max(0, fine.onlineElapsedMs));
    }
}
