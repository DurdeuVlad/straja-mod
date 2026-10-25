package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.HearingRecord;
import com.dwurdy.straja.domain.model.ParoleStore;
import com.dwurdy.straja.domain.model.PrisonerStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * FUN-11 parole & appeal: the institutional route out of custody.
 *
 * <p>A prisoner files onto a durable ordered docket; an officer opens the
 * hearing (the prisoner must be online to appear), online victims get a
 * bounded objection window, and the decision lands PAROLED / DENIED /
 * CONDITIONAL. Paroled releases carry live conditions — periodic check-ins
 * inside a parole window — enforced here: a missed check-in flags the
 * parolee wanted so the normal arrest pipeline takes it from there; a fresh
 * arrest is detected and closes the parole as violated.</p>
 *
 * <p>FUN-1 delegation sees the docket as {@code PAROLE_HEARING}: a standing
 * order matching {@code CLEAN} resolves hearings with no victim complainants
 * and still-clean eligibility; everything else stays on the human digest.</p>
 */
public final class ParoleService implements com.dwurdy.straja.application.port.in.ParoleUseCase {

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private PrisonService prison;

    public ParoleService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    public void usePrison(PrisonService service) { this.prison = service; }

    private long now() { return ctx.clock().nowMillis(); }

    // ---------- eligibility ----------

    /** The ledger-derived gate list — order matches the status surface. */
    public List<Check> eligibility(String prisonerUuid) {
        var p = ctx.policies();
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(prisonerUuid);
        var sentence = ctx.prison().read().activeSentenceFor(prisonerUuid);
        List<Check> out = new ArrayList<>();
        boolean inCustody = rec != null && !rec.status.isReleased()
                && rec.status != PrisonerStatus.FUGITIVE;
        out.add(new Check("custody", inCustody && sentence != null, ""));

        int standing = rec == null ? 0 : rec.standing;
        out.add(new Check("standing", standing >= p.paroleMinStanding,
                standing + "/" + p.paroleMinStanding));

        long total = sentence == null ? 0
                : (long) sentence.sentenceDays
                        * Math.max(1, p.prisonActiveMinutesPerMinecraftDay) * 60_000L;
        int served = total <= 0 ? 0
                : (int) Math.min(100, Math.max(0,
                        (total - sentence.remainingActiveMs) * 100 / total));
        out.add(new Check("served", served >= p.paroleMinServedPercent,
                served + "%/" + p.paroleMinServedPercent + "%"));

        int unpaid = unpaidFines(prisonerUuid);
        out.add(new Check("debts", unpaid == 0, unpaid + ""));

        long lastEscape = rec == null ? 0 : rec.lastEscapeAt;
        boolean clean = lastEscape <= 0
                || now() - lastEscape >= p.paroleCleanHours * 3_600_000L;
        out.add(new Check("clean", clean, ""));

        ParoleStore store = ctx.paroles().read();
        HearingRecord last = store.lastDecisionFor(prisonerUuid);
        boolean cooled = last == null || last.refileAfter <= 0 || now() >= last.refileAfter;
        out.add(new Check("cooldown", cooled, ""));
        out.add(new Check("docket", store.pendingFor(prisonerUuid) == null, ""));
        return out;
    }

    public boolean eligible(String prisonerUuid) {
        for (Check c : eligibility(prisonerUuid)) if (!c.ok()) return false;
        return true;
    }

    // ---------- desk surface reads ----------

    /**
     * Desk snapshot — which parole rows the jailer desk shows this player:
     * filing rows for an eligible prisoner, the pending-case row (advocate
     * once filed), the settle row while a CONDITIONAL offer is open, and the
     * check-in row for an active parolee. Pure read — no mutation, no refuse.
     */
    public ParoleSurface surface(String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return new ParoleSurface(false, false, "", "", "", false, "", 0);
        }
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        ParoleStore store = ctx.paroles().read();
        var parole = store.parole(uuid);
        boolean parolee = parole != null && "ACTIVE".equals(parole.status);
        boolean prisoner = rec != null && !rec.status.isReleased()
                && rec.status != PrisonerStatus.FUGITIVE
                && ctx.prison().read().activeSentenceFor(uuid) != null;
        HearingRecord pending = store.pendingFor(uuid);
        String conditionalId = "";
        int bail = 0;
        for (HearingRecord h : store.docket) {
            if (h != null && uuid.equals(h.prisonerUuid)
                    && "CONDITIONAL".equals(h.status)
                    && h.conditionalUntil > now()) {
                conditionalId = h.id;
                bail = h.bailAmount;
                break;
            }
        }
        return new ParoleSurface(
                prisoner, eligible(uuid),
                pending == null ? "" : pending.id,
                pending == null ? "" : pending.kind,
                pending == null ? "" : pending.advocateStatus,
                parolee, conditionalId, bail);
    }

    /**
     * Hearings whose objection window is currently open to this victim — the
     * receptionist desk renders one {@code parole-object:<id>} row each.
     */
    public List<String> openObjectionsFor(String uuid) {
        List<String> out = new ArrayList<>();
        if (uuid == null || uuid.isBlank()) return out;
        long now = now();
        for (HearingRecord h : ctx.paroles().read().docket) {
            if (h == null || !"HEARING".equals(h.status) || now >= h.objectionUntil) {
                continue;
            }
            if (h.objectorUuids != null && h.objectorUuids.contains(uuid)) {
                continue; // already objected — the row's job is done
            }
            if (victimsOf(h.prisonerUuid).contains(uuid)) out.add(h.id);
        }
        return out;
    }

    /**
     * The hearing this player was asked to advocate for, else null — backs
     * both the receptionist answer rows and the advocateAnswer revalidation.
     */
    public String advocacyAskFor(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (var cand : ctx.paroles().read().docket) {
            if (cand != null && uuid.equals(cand.advocateUuid)
                    && "ASKED".equals(cand.advocateStatus)) {
                return cand.id;
            }
        }
        return null;
    }

    /** Live hearing lookup for desk pages and click revalidation. */
    public HearingRecord findHearing(String id) {
        return ctx.paroles().read().hearing(id);
    }

    /** FILED/HEARING docket rows for the officer desk page — same order as {@link #docket}. */
    public List<HearingRecord> reviewDocket() {
        List<HearingRecord> out = new ArrayList<>();
        for (HearingRecord h : ctx.paroles().read().docket) {
            if (h != null && ("FILED".equals(h.status) || "HEARING".equals(h.status))) {
                out.add(h);
            }
        }
        return out;
    }

    /**
     * Silent authority check — the desk uses it to emit rows and revalidate
     * clicks without spamming refusals/audits; {@link #officerGate} keeps the
     * loud variant for actual mutations.
     */
    public boolean officerCapable(PlayerGateway actor) {
        return actor != null && (players.isCommissioner(actor) || actor.isOp()
                || players.permissionLevel(actor, players.state(actor)).atLeast(
                        com.dwurdy.straja.domain.model.PermissionLevel.LIEUTENANT));
    }

    /**
     * The substantive gates a decision re-verifies — custody, standing, served
     * time, debts, clean record. The docket/cooldown checks are filing gates;
     * the case being decided is itself pending by definition.
     */
    private boolean eligibleForDecision(String prisonerUuid) {
        for (Check c : eligibility(prisonerUuid)) {
            if (("docket".equals(c.key()) || "cooldown".equals(c.key()))) continue;
            if (!c.ok()) return false;
        }
        return true;
    }

    private int unpaidFines(String uuid) {
        int count = 0;
        for (var f : ctx.fines().read().fines) {
            if (f == null || !uuid.equals(f.targetUuid)) continue;
            if (List.of("ISSUED", "ESCALATED", "DELIVERY_FAILED",
                    "GRACE_AFTER_SENTENCE", "ARREST_PENDING", "PAYMENT_REVIEW")
                    .contains(f.status)) count++;
        }
        return count;
    }

    // ---------- filing ----------

    /** The prisoner-visible status/eligibility surface (`/straja parole`). */
    public void status(PlayerGateway prisoner) {
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(safeUuid(prisoner));
        ParoleStore store = ctx.paroles().read();
        var parole = store.parole(safeUuid(prisoner));
        if (parole != null && "ACTIVE".equals(parole.status)) {
            long left = Math.max(0, (parole.checkInBy - now()) / 60_000L);
            long until = Math.max(0, (parole.paroleUntil - now()) / 3_600_000L);
            tell(prisoner, "straja.parole.status_parolee", left, until);
            return;
        }
        if (rec == null || rec.status.isReleased()) {
            prisoner.refuse("straja.parole.not_prisoner", "straja.remedy.jailer");
            return;
        }
        HearingRecord pending = store.pendingFor(safeUuid(prisoner));
        if (pending != null) {
            tell(prisoner, "straja.parole.status_pending", pending.id);
        }
        for (Check c : eligibility(safeUuid(prisoner))) {
            tell(prisoner, c.ok() ? "straja.parole.check_ok" : "straja.parole.check_no",
                    checkLabel(c), c.detail());
        }
        if (eligible(safeUuid(prisoner))) {
            tell(prisoner, "straja.parole.status_file_hint");
        }
    }

    private String checkLabel(Check c) {
        return switch (c.key()) {
            case "custody" -> "custodie";
            case "standing" -> "reputație";
            case "served" -> "timp ispășit";
            case "debts" -> "datorii";
            case "clean" -> "cazier curat";
            case "cooldown" -> "așteptare refuz";
            default -> "dosar liber";
        };
    }

    /** `/straja parole file [motiv]` or `/straja parole appeal [motiv]`. */
    public boolean file(PlayerGateway prisoner, String kind, String reason) {
        String uuid = safeUuid(prisoner);
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec == null || rec.status.isReleased()) {
            prisoner.refuse("straja.parole.not_prisoner", "straja.remedy.jailer");
            return false;
        }
        ParoleStore store = ctx.paroles().read();
        if (store.pendingFor(uuid) != null) {
            prisoner.refuse("straja.parole.already_filed", "straja.remedy.wait");
            return false;
        }
        if (!eligible(uuid)) {
            prisoner.refuse("straja.parole.not_eligible", "straja.remedy.earn_blocks");
            return false;
        }
        HearingRecord h = new HearingRecord();
        h.id = "PAR-" + store.nextHearingSeq++;
        h.kind = "APPEAL".equalsIgnoreCase(kind) ? "APPEAL" : "PAROLE";
        h.prisonerUuid = uuid;
        h.prisonerName = prisoner.name();
        h.reason = reason == null ? "" : reason.trim();
        h.filedAt = now();
        h.version = store.version++;
        store.docket.add(h);
        ctx.paroles().write(store);
        audit.record(AuditActions.PRISON_HEARING, prisoner.name(), uuid, h.id, uuid,
                AuditResults.SUCCESS, "filed kind=" + h.kind);
        tell(prisoner, "straja.parole.filed", h.id);
        return true;
    }

    // ---------- officer surface ----------

    /** `/straja parole docket` — the ordered pending queue. */
    public void docket(PlayerGateway officer) {
        if (!officerGate(officer)) return;
        ParoleStore store = ctx.paroles().read();
        int shown = 0;
        for (HearingRecord h : store.docket) {
            if (h == null || !"FILED".equals(h.status) && !"HEARING".equals(h.status)) continue;
            tell(officer, "straja.parole.docket_row", h.id, h.kind,
                    h.prisonerName, h.status);
            shown++;
        }
        if (shown == 0) tell(officer, "straja.parole.docket_empty");
    }

    /** `/straja parole open <id>` — starts the hearing; prisoner must appear. */
    public boolean open(PlayerGateway officer, String id) {
        if (!officerGate(officer)) return false;
        ParoleStore store = ctx.paroles().read();
        HearingRecord h = store.hearing(id);
        if (h == null || !"FILED".equals(h.status)) {
            officer.refuse("straja.parole.no_hearing", "straja.remedy.jailer");
            return false;
        }
        var prisoner = findOnline(h.prisonerUuid);
        if (prisoner == null) {
            officer.refuse("straja.parole.must_appear", "straja.remedy.wait");
            return false;
        }
        var rec = ctx.prisonerRegister().read().prisoner(h.prisonerUuid);
        if (rec == null || rec.status == PrisonerStatus.ESCORTED
                || rec.status == PrisonerStatus.FUGITIVE
                || rec.status.isReleased()) {
            // Mid-ride, escaped or already free — a hearing cannot convene.
            officer.refuse("straja.parole.not_in_custody", "straja.remedy.wait");
            return false;
        }
        h.status = "HEARING";
        h.hearingOpenedAt = now();
        var victims = victimsOf(h.prisonerUuid);
        boolean anyOnline = victims.stream().anyMatch(v -> findOnline(v) != null);
        h.objectionUntil = anyOnline
                ? now() + ctx.policies().paroleObjectionMinutes * 60_000L : now();
        h.version = store.version++;
        ctx.paroles().write(store);
        audit.record(AuditActions.PRISON_HEARING, officer.name(), safeUuid(officer),
                h.id, h.prisonerUuid, AuditResults.SUCCESS, "opened");
        tell(prisoner, "straja.parole.hearing_started", h.id);
        tell(officer, "straja.parole.hearing_opened", h.id);
        for (String v : victims) {
            var g = findOnline(v);
            if (g != null) {
                // One click objects — the link mints a victim-bound token
                // that revalidates against the live docket on dispatch and
                // lives exactly as long as the objection window it gates.
                g.tellAction("straja.parole.victim_notice",
                        List.of(new PlayerGateway.ActionLink(
                                "Obiectează", "parole-object:" + h.id,
                                ctx.policies().paroleObjectionMinutes * 60_000L)),
                        h.id, h.prisonerName,
                        String.valueOf(ctx.policies().paroleObjectionMinutes));
            }
        }
        return true;
    }

    /** `/straja parole object <id>` — a victim registers an objection. */
    public boolean object(PlayerGateway victim, String id) {
        ParoleStore store = ctx.paroles().read();
        HearingRecord h = store.hearing(id);
        if (h == null || !"HEARING".equals(h.status) || now() >= h.objectionUntil) {
            victim.refuse("straja.parole.object_closed", "straja.remedy.wait");
            return false;
        }
        String uuid = safeUuid(victim);
        if (!victimsOf(h.prisonerUuid).contains(uuid)) {
            victim.refuse("straja.parole.not_victim", "straja.remedy.jailer");
            return false;
        }
        if (h.objectorUuids != null && h.objectorUuids.contains(uuid)) {
            // One objection per victim — a repeated click doesn't stack.
            tell(victim, "straja.parole.object_recorded", h.id);
            return true;
        }
        if (h.objectorUuids == null) h.objectorUuids = new java.util.ArrayList<>();
        h.objectorUuids.add(uuid);
        h.objections++;
        h.version = store.version++;
        ctx.paroles().write(store);
        audit.record(AuditActions.PRISON_HEARING, victim.name(), uuid,
                h.id, h.prisonerUuid, AuditResults.SUCCESS, "objection");
        tell(victim, "straja.parole.object_recorded", h.id);
        return true;
    }

    /** `/straja parole advocate <jucător>` — prisoner designates a speaker. */
    public boolean advocate(PlayerGateway prisoner, String advocateName) {
        ParoleStore store = ctx.paroles().read();
        HearingRecord h = store.pendingFor(safeUuid(prisoner));
        if (h == null) {
            prisoner.refuse("straja.parole.no_filing", "straja.remedy.jailer");
            return false;
        }
        var adv = findOnlineByName(advocateName);
        if (adv == null || safeUuid(adv).equals(safeUuid(prisoner))) {
            prisoner.refuse("straja.parole.no_advocate", "straja.remedy.wait");
            return false;
        }
        h.advocateUuid = safeUuid(adv);
        h.advocateName = adv.name();
        h.advocateStatus = "ASKED";
        h.version = store.version++;
        ctx.paroles().write(store);
        // The ask stands until answered or the hearing resolves — a day is
        // a generous bound; the service revalidates the live ask on click.
        long advocateLinkTtl = 24L * 3_600_000L;
        adv.tellAction("straja.parole.advocate_asked",
                List.of(new PlayerGateway.ActionLink(
                                "Acceptă", "parole-advocate-answer:accept",
                                advocateLinkTtl),
                        new PlayerGateway.ActionLink(
                                "Refuză", "parole-advocate-answer:decline",
                                advocateLinkTtl)),
                prisoner.name(), h.id);
        tell(prisoner, "straja.parole.advocate_sent", adv.name());
        return true;
    }

    /** `/straja parole agree|decline` — the advocate consents (or not). */
    public boolean advocateAnswer(PlayerGateway advocate, boolean accept) {
        ParoleStore store = ctx.paroles().read();
        String uuid = safeUuid(advocate);
        String askId = advocacyAskFor(uuid);
        HearingRecord h = askId == null ? null : store.hearing(askId);
        if (h == null) {
            advocate.refuse("straja.parole.no_advocacy", "straja.remedy.wait");
            return false;
        }
        h.advocateStatus = accept ? "AGREED" : "DECLINED";
        h.version = store.version++;
        ctx.paroles().write(store);
        audit.record(AuditActions.PRISON_HEARING, advocate.name(), uuid,
                h.id, h.prisonerUuid, AuditResults.SUCCESS,
                "advocate=" + h.advocateStatus);
        tell(advocate, accept ? "straja.parole.advocate_ok" : "straja.parole.advocate_no", h.id);
        return true;
    }

    /** `/straja parole decide <id> <paroled|denied|conditional>`. */
    public boolean decide(PlayerGateway officer, String id, String outcome) {
        if (!officerGate(officer)) return false;
        ParoleStore store = ctx.paroles().read();
        HearingRecord h = store.hearing(id);
        if (h == null || !"HEARING".equals(h.status)) {
            officer.refuse("straja.parole.not_hearing", "straja.remedy.jailer");
            return false;
        }
        if (now() < h.objectionUntil) {
            officer.refuse("straja.parole.objection_window", "straja.remedy.wait");
            return false;
        }
        String norm = outcome == null ? "" : outcome.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("PAROLED", "DENIED", "CONDITIONAL").contains(norm)) {
            officer.refuse("straja.parole.bad_outcome", "straja.remedy.jailer");
            return false;
        }
        return applyDecision(store, h, norm, safeUuid(officer), officer.name());
    }

    private boolean applyDecision(ParoleStore store, HearingRecord h, String outcome,
                                  String byUuid, String byName) {
        switch (outcome) {
            case "PAROLED" -> {
                if (!grantParole(store, h)) return false;
            }
            case "DENIED" -> h.refileAfter = now()
                    + ctx.policies().paroleRefusalCooldownHours * 3_600_000L;
            case "CONDITIONAL" -> {
                h.conditionalUntil = now()
                        + ctx.policies().paroleConditionalHours * 3_600_000L;
                h.bailAmount = ctx.policies().paroleBailAmount;
                var prisoner = findOnline(h.prisonerUuid);
                if (prisoner != null) {
                    // The pay link lives for the whole conditional window.
                    prisoner.tellAction("straja.parole.conditional_offer",
                            List.of(new PlayerGateway.ActionLink(
                                    "Plătește cauțiunea", "parole-settle",
                                    ctx.policies().paroleConditionalHours * 3_600_000L)),
                            h.bailAmount,
                            String.valueOf(ctx.policies().paroleConditionalHours));
                }
            }
            default -> { return false; }
        }
        h.status = outcome;
        h.decidedByUuid = byUuid;
        h.decidedByName = byName;
        h.decidedAt = now();
        h.version = store.version++;
        ctx.paroles().write(store);
        audit.record(AuditActions.PRISON_HEARING, byName, byUuid,
                h.id, h.prisonerUuid, AuditResults.SUCCESS,
                "decided outcome=" + outcome + " objections=" + h.objections
                        + " advocate=" + h.advocateStatus);
        var prisoner = findOnline(h.prisonerUuid);
        if (prisoner != null) tell(prisoner, "straja.parole.decided_" + outcome.toLowerCase(java.util.Locale.ROOT), h.id);
        return true;
    }

    /** `/straja parole settle` — pay the CONDITIONAL bail from labor credit. */
    public boolean settle(PlayerGateway prisoner) {
        String uuid = safeUuid(prisoner);
        ParoleStore store = ctx.paroles().read();
        HearingRecord h = null;
        for (var cand : store.docket) {
            // An expired offer can never settle — skip it so a stale record
            // doesn't shadow a live refile further down the docket.
            if (cand != null && uuid.equals(cand.prisonerUuid)
                    && "CONDITIONAL".equals(cand.status)
                    && cand.conditionalUntil > now()) { h = cand; break; }
        }
        if (h == null) {
            prisoner.refuse("straja.parole.no_conditional", "straja.remedy.jailer");
            return false;
        }
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec == null || rec.laborAccount < h.bailAmount) {
            prisoner.refuse("straja.parole.bail_short", "straja.remedy.earn_blocks");
            return false;
        }
        if (!releasableNow(h)) {
            // The release check runs before the bail moves — a prisoner who
            // left custody after the decision keeps both the labor credit
            // and the open case.
            prisoner.refuse("straja.parole.not_in_custody", "straja.remedy.jailer");
            audit.record(AuditActions.PRISON_HEARING, prisoner.name(), uuid,
                    h.id, h.prisonerUuid, AuditResults.REFUSED, "settle_not_in_custody");
            return false;
        }
        if (!grantParole(store, h)) {
            // Never confiscate bail for a release that did not happen.
            prisoner.refuse("straja.parole.not_in_custody", "straja.remedy.jailer");
            audit.record(AuditActions.PRISON_HEARING, prisoner.name(), uuid,
                    h.id, h.prisonerUuid, AuditResults.REFUSED, "settle_release_failed");
            return false;
        }
        // Re-read after the release — the grant rewrote the register status.
        var post = ctx.prisonerRegister().read();
        var postRec = post.prisoner(uuid);
        if (postRec != null) {
            postRec.laborAccount -= h.bailAmount;
            ctx.prisonerRegister().write(post);
        }
        return markDecided(store, h, uuid, prisoner.name());
    }

    private boolean markDecided(ParoleStore store, HearingRecord h, String uuid, String name) {
        h.status = "PAROLED";
        if (h.decidedAt <= 0) {
            h.decidedAt = now();
            h.decidedByUuid = uuid;
            h.decidedByName = name;
        }
        h.version = store.version++;
        ctx.paroles().write(store);
        return true;
    }

    /**
     * The release half of {@link #grantParole} without the mutation: in
     * custody, releasable status and an active sentence still present.
     * {@code settle} runs this before the bail moves so a failed release
     * never confiscates labor.
     */
    private boolean releasableNow(HearingRecord h) {
        var rec = ctx.prisonerRegister().read().prisoner(h.prisonerUuid);
        if (rec == null || rec.status.isReleased()
                || rec.status == PrisonerStatus.FUGITIVE
                || rec.status == PrisonerStatus.ESCORTED) {
            return false;
        }
        return prison != null
                && ctx.prison().read().activeSentenceFor(h.prisonerUuid) != null;
    }

    /** Shared release: sentence ends, register flips PAROLED, conditions open. */
    private boolean grantParole(ParoleStore store, HearingRecord h) {
        if (!releasableNow(h)) {
            // Escaped or already out between filing and decision — the case
            // stays HEARING so an officer can still deny it on return.
            return false;
        }
        if (!prison.releaseForParole(h.prisonerUuid, h.id)) {
            return false;
        }
        var condition = new ParoleStore.ParoleCondition();
        condition.hearingId = h.id;
        condition.paroleUntil = now() + ctx.policies().paroleLengthHours * 3_600_000L;
        condition.checkInBy = now() + ctx.policies().paroleCheckInHours * 3_600_000L;
        store.paroles.put(h.prisonerUuid, condition);
        return true;
    }

    /** `/straja parole checkin` — resets the parolee's check-in deadline. */
    public void checkIn(PlayerGateway parolee) {
        String uuid = safeUuid(parolee);
        ParoleStore store = ctx.paroles().read();
        var parole = store.parole(uuid);
        if (parole == null || !"ACTIVE".equals(parole.status)) {
            parolee.refuse("straja.parole.not_parolee", "straja.remedy.jailer");
            return;
        }
        parole.checkInBy = now() + ctx.policies().paroleCheckInHours * 3_600_000L;
        store.version++;
        ctx.paroles().write(store);
        tell(parolee, "straja.parole.checked_in",
                String.valueOf(ctx.policies().paroleCheckInHours));
    }

    // ---------- FUN-1 delegation ----------

    /** Pending docket rows for the delegation sweep, oldest first. */
    public List<HearingRecord> pendingDocket() {
        List<HearingRecord> out = new ArrayList<>();
        for (HearingRecord h : ctx.paroles().read().docket) {
            if (h != null && "FILED".equals(h.status)) out.add(h);
        }
        out.sort(java.util.Comparator.comparingLong(h -> h.filedAt));
        return out;
    }

    /**
     * Standing-order resolution: a CLEAN case (no victim complainants, still
     * fully eligible) is decided PAROLED under the order author's authority.
     * Anything else returns false — contested cases stay human.
     */
    public boolean decideDelegated(String authorityUuid, String authorityName, String hearingId) {
        ParoleStore store = ctx.paroles().read();
        HearingRecord h = store.hearing(hearingId);
        if (h == null || !"FILED".equals(h.status)) return false;
        if (!victimsOf(h.prisonerUuid).isEmpty()
                || !eligibleForDecision(h.prisonerUuid)) {
            return false;
        }
        h.hearingOpenedAt = now();
        return applyDecision(store, h, "PAROLED",
                authorityUuid == null ? "" : authorityUuid,
                authorityName == null ? "" : authorityName);
    }

    /** Digest match value — CLEAN cases have no victims to notify. */
    public String matchValue(String prisonerUuid) {
        return victimsOf(prisonerUuid).isEmpty() ? "CLEAN" : "CONTESTED";
    }

    // ---------- enforcement tick ----------

    /** Deadlines freeze while the parolee is offline or the server is down —
     *  sentences do the same: you cannot miss a check-in you could not make.
     *  The freeze recomputes against the persisted sweep stamp, and the store
     *  only writes on a violation/completion or a coarse cadence. */
    private static final long MAX_OFFLINE_PUSH_MS = 24L * 3_600_000L;
    private static final long SWEEP_WRITE_MS = 30_000L;

    public void tick() {
        long now = now();
        ParoleStore store = ctx.paroles().read();
        // dt measures the unpersisted span — pushes recompute from the last
        // written stamp, so skipped writes never double-count.
        long dt = store.lastTickAt > 0
                ? Math.min(now - store.lastTickAt, MAX_OFFLINE_PUSH_MS) : 0;
        boolean dirty = false;
        boolean urgent = false;
        for (var e : store.paroles.entrySet()) {
            var parole = e.getValue();
            if (parole == null || !"ACTIVE".equals(parole.status)) continue;
            String uuid = e.getKey();
            if (dt > 0 && findOnline(uuid) == null) {
                parole.checkInBy += dt;
                parole.paroleUntil += dt;
                dirty = true;
                continue;
            }
            var sentence = ctx.prison().read().activeSentenceFor(uuid);
            if (sentence != null) {
                // Re-arrested through the normal pipeline — parole violated.
                parole.status = "VIOLATED";
                parole.closedAt = now;
                dirty = true;
                urgent = true;
                audit.record(AuditActions.PRISON_HEARING, "", "", parole.hearingId,
                        uuid, AuditResults.SUCCESS, "violated rearrest");
                continue;
            }
            if (now > parole.paroleUntil) {
                // The window ending completes the parole even on the same
                // sweep a check-in would lapse — conditions end at the wire.
                parole.status = "COMPLETED";
                parole.closedAt = now;
                dirty = true;
                var reg = ctx.prisonerRegister().read();
                var rec = reg.prisoner(uuid);
                if (rec != null && rec.status == PrisonerStatus.PAROLED) {
                    rec.status = PrisonerStatus.RELEASED;
                    ctx.prisonerRegister().write(reg);
                }
                var online = findOnline(uuid);
                if (online != null) tell(online, "straja.parole.completed");
                urgent = true;
                continue;
            }
            if (now > parole.checkInBy) {
                // Missed check-in: the parolee becomes wanted — the existing
                // arrest pipeline does the re-arrest.
                parole.status = "VIOLATED";
                parole.closedAt = now;
                dirty = true;
                var reg = ctx.prisonerRegister().read();
                var rec = reg.prisoner(uuid);
                if (rec != null && rec.status == PrisonerStatus.PAROLED) {
                    rec.status = PrisonerStatus.FUGITIVE;
                    ctx.prisonerRegister().write(reg);
                }
                audit.record(AuditActions.PRISON_FUGITIVE, "", "", parole.hearingId,
                        uuid, AuditResults.SUCCESS, "violated missed_checkin");
                var online = findOnline(uuid);
                if (online != null) tell(online, "straja.parole.violated");
                urgent = true;
            }
        }
        // Lapsed CONDITIONAL offers return the docket row to plain custody.
        for (HearingRecord h : store.docket) {
            if (h != null && "CONDITIONAL".equals(h.status)
                    && h.conditionalUntil > 0 && now > h.conditionalUntil) {
                h.status = "DENIED";
                h.refileAfter = now + ctx.policies().paroleRefusalCooldownHours * 3_600_000L;
                h.version = store.version++;
                dirty = true;
                urgent = true;
            }
        }
        boolean stamp = store.lastTickAt <= 0 || now - store.lastTickAt >= SWEEP_WRITE_MS;
        if (urgent || (dirty && stamp)) {
            store.lastTickAt = now;
            store.version++;
            ctx.paroles().write(store);
        } else if (!dirty && stamp) {
            // Keep the sweep stamp warm so a later offline stretch measures
            // from real time, not from an ancient write.
            store.lastTickAt = now;
            ctx.paroles().write(store);
        }
    }

    // ---------- helpers ----------

    private List<String> victimsOf(String prisonerUuid) {
        List<String> out = new ArrayList<>();
        try {
            for (var c : ctx.complaints().read().ensureComplaints()) {
                if (c == null || c.accusedUuid == null
                        || !c.accusedUuid.equals(prisonerUuid)) continue;
                if ("CANCELLED".equals(c.status) || "WITHDRAWN".equals(c.status)) continue;
                if (c.complainantUuid != null && !c.complainantUuid.isBlank()
                        && !out.contains(c.complainantUuid)) {
                    out.add(c.complainantUuid);
                }
            }
        } catch (RuntimeException ignored) {}
        return out;
    }

    private boolean officerGate(PlayerGateway actor) {
        if (officerCapable(actor)) {
            return true;
        }
        actor.refuse("straja.parole.officer_only", "straja.remedy.jailer");
        audit.record(AuditActions.PRISON_HEARING, actor.name(), safeUuid(actor),
                "", "", AuditResults.REFUSED, "missing_authority");
        return false;
    }

    private PlayerGateway findOnline(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p != null && uuid.equals(safeUuid(p))) return p;
        }
        return null;
    }

    private PlayerGateway findOnlineByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p != null && name.equalsIgnoreCase(p.name())) return p;
        }
        return null;
    }

    private static String safeUuid(PlayerGateway p) {
        return p != null && p.uuid() != null ? p.uuid().toString() : "";
    }

    private void tell(PlayerGateway p, String key, Object... args) {
        p.tellKey(key, args);
    }
}
