package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.BoloRecord;
import com.dwurdy.straja.domain.model.BoloStatus;
import com.dwurdy.straja.domain.model.BountyRecord;
import com.dwurdy.straja.domain.model.BountyStatus;
import com.dwurdy.straja.domain.model.BountyStore;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.Sentence;
import java.util.ArrayList;

/**
 * #231 state-issued player bounties. Inspector+ posts a price on a wanted
 * player; any player may capture them with the criminal restraint tools and
 * deliver them to a checkpoint for a full arrest. The state mints the payout
 * to the capturing hunter; the prisoner owes {@code amount * fineMultiplier}
 * as physical-coin bail — payable by anyone — or goes to the mines.
 *
 * <p>CustodyService consults this service through the {@link RestraintGate}
 * callback for the downed-or-surrendered rule, and the tether marks bounty
 * escorts via the {@code "bounty_capture"} bound-record reason.
 */
public final class BountyService {
    /** Bound-record reason marking a bounty escort (set at applyRope time). */
    public static final String BOUNTY_CAPTURE_REASON = "bounty_capture";
    /** Minimum posting rank: Inspector. Comisar is above. */
    public static final int MIN_POSTER_RANK =
            com.dwurdy.straja.domain.model.Rank.INSPECTOR.level();

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private PrisonService prison;

    public BountyService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    public void usePrison(PrisonService prison) { this.prison = prison; }

    private long now() { return ctx.clock().nowMillis(); }

    private static String uuid(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }

    private BountyStore store() {
        BountyStore store = ctx.bounties().read();
        if (store.records == null) store.records = new ArrayList<>();
        if (store.surrenders == null) store.surrenders = new java.util.LinkedHashMap<>();
        return store;
    }

    // ------------------------------------------------------------ posting

    /**
     * Posts a bounty on a wanted player. Rank >= Inspector only. The target
     * must already carry a criminal mark — otherwise the post needs an
     * explicit reason, which auto-issues the linked BOLO. One ACTIVE bounty
     * per target.
     */
    public synchronized BountyRecord post(PlayerGateway issuer, PlayerGateway target,
                                          int amount, String reason) {
        if (issuer == null || target == null) return null;
        if (!ctx.policies().bountyEnabled) {
            issuer.refuse("straja.bounty.disabled", "straja.remedy.ask_comisar");
            return null;
        }
        var issuerState = players.state(issuer);
        if ((issuerState == null || issuerState.rank < MIN_POSTER_RANK)
                && !players.isCommissioner(issuer)) {
            issuer.refuse("straja.bounty.post_rank", "straja.remedy.inspector");
            audit.record("bounty_post", issuer.name(), uuid(issuer), target.name(),
                    uuid(target), "REFUSED", "issuer_below_inspector");
            return null;
        }
        if (uuid(issuer).equals(uuid(target))) {
            issuer.refuse("straja.bounty.self_post", "straja.remedy.fix_retry");
            return null;
        }
        if (amount < ctx.policies().bountyMinAmount
                || amount > ctx.policies().bountyMaxAmount) {
            issuer.refuse("straja.bounty.amount_range", "straja.remedy.fix_retry",
                    ctx.policies().bountyMinAmount, ctx.policies().bountyMaxAmount);
            return null;
        }
        var store = store();
        if (store.activeFor(uuid(target)) != null) {
            issuer.refuse("straja.bounty.already_posted", "straja.remedy.fix_retry",
                    target.name());
            return null;
        }
        boolean wanted = isWanted(target);
        if (!wanted && (reason == null || reason.isBlank())) {
            issuer.refuse("straja.bounty.needs_reason", "straja.remedy.fix_retry");
            return null;
        }
        var record = new BountyRecord();
        record.id = store.nextBountyId();
        record.targetUuid = uuid(target);
        record.targetName = target.name();
        record.amount = amount;
        record.postedBy = issuer.name();
        record.postedByUuid = uuid(issuer);
        record.postedByRank = issuerState.rank;
        record.reason = reason == null ? "" : reason.trim();
        record.postedAt = now();
        record.expiresAt = now() + ctx.policies().bountyTtlDays * 86_400_000L;
        // A bountied player is wanted-on-sight at every arresting gate: seed
        // the authoritative BOLO directly (bypasses the on-duty issuer gate —
        // posting is an office act, not a field action).
        var boloStore = ctx.bolos().read();
        var bolo = new BoloRecord();
        bolo.id = boloStore.nextBoloId();
        bolo.subjectUuid = record.targetUuid;
        bolo.subjectName = record.targetName;
        bolo.reason = "bounty:" + record.id + (record.reason.isBlank()
                ? "" : " " + record.reason);
        bolo.issuerUuid = record.postedByUuid;
        bolo.issuerName = record.postedBy;
        bolo.issuerRank = record.postedByRank;
        bolo.createdAt = now();
        bolo.status = BoloStatus.ACTIVE;
        boloStore.records.add(bolo);
        ctx.bolos().write(boloStore);
        record.linkedBoloId = bolo.id;
        store.records.add(record);
        ctx.bounties().write(store);
        audit.record("bounty_post", issuer.name(), uuid(issuer), target.name(),
                uuid(target), "SUCCESS",
                record.id + " amount=" + amount + " bolo=" + bolo.id
                        + (wanted ? "" : " auto_bolo"));
        issuer.tell("Recompensa " + record.id + " pe numele lui " + target.name()
                + ": " + amount + " unități. Orice jucător îl poate captura.");
        return record;
    }

    /** Cancels an ACTIVE bounty (rank >= Inspector). The linked BOLO resolves. */
    public synchronized boolean cancel(PlayerGateway actor, String targetIdOrBountyId) {
        if (actor == null) return false;
        var actorState = players.state(actor);
        if ((actorState == null || actorState.rank < MIN_POSTER_RANK)
                && !players.isCommissioner(actor)) {
            actor.refuse("straja.bounty.post_rank", "straja.remedy.inspector");
            return false;
        }
        var store = store();
        BountyRecord record = store.find(targetIdOrBountyId);
        if (record == null || record.status != BountyStatus.ACTIVE) {
            record = store.activeFor(targetIdOrBountyId);
        }
        if (record == null) {
            actor.refuse("straja.bounty.not_found", "straja.remedy.fix_retry",
                    targetIdOrBountyId);
            return false;
        }
        record.status = BountyStatus.CANCELLED;
        ctx.bounties().write(store);
        resolveLinkedBolo(record, "bounty_cancelled");
        audit.record("bounty_cancel", actor.name(), uuid(actor), record.targetName,
                record.targetUuid, "SUCCESS", record.id);
        actor.tell("Recompensa " + record.id + " a fost retrasă.");
        return true;
    }

    /** Public board: every ACTIVE bounty (name, amount, remaining time). */
    public void list(PlayerGateway player) {
        if (player == null) return;
        var store = store();
        boolean any = false;
        long remainingMs;
        for (BountyRecord record : store.records) {
            if (record == null || record.status != BountyStatus.ACTIVE) continue;
            any = true;
            remainingMs = Math.max(0, record.expiresAt - now());
            player.tell(record.id + " — " + record.targetName + ": "
                    + record.amount + " unități (expiră în "
                    + Math.max(1, remainingMs / 3_600_000L) + "h)");
        }
        if (!any) player.tellKey("straja.bounty.list_empty");
    }

    // ------------------------------------------------------------ queries

    public BountyRecord activeFor(String targetUuidOrName) {
        return store().activeFor(targetUuidOrName);
    }

    public boolean isBountied(PlayerGateway target) {
        return target != null && activeFor(uuid(target)) != null;
    }

    private boolean isWanted(PlayerGateway target) {
        String id = uuid(target);
        for (BoloRecord bolo : ctx.bolos().read().records) {
            if (bolo != null && bolo.status == BoloStatus.ACTIVE
                    && (id.equals(bolo.subjectUuid)
                            || target.name().equalsIgnoreCase(bolo.subjectName))) {
                return true;
            }
        }
        var rec = ctx.prisonerRegister().read().prisoner(id);
        return rec != null
                && rec.status == com.dwurdy.straja.domain.model.PrisonerStatus.FUGITIVE;
    }

    // ------------------------------------------------------------ surrender

    /**
     * {@code /straja surrender}: a bountied target within surrenderRadius of
     * any other online player flags voluntary capture — the next restraint
     * application (rope/sack) succeeds without the downed requirement.
     */
    public synchronized boolean surrender(PlayerGateway target) {
        if (target == null) return false;
        var record = activeFor(uuid(target));
        if (record == null) {
            target.refuse("straja.bounty.not_hunted", "straja.remedy.retry");
            return false;
        }
        double radius = ctx.policies().bountySurrenderRadius;
        PlayerGateway captor = null;
        for (var p : ctx.server().onlinePlayers()) {
            if (p == null || uuid(p).equals(uuid(target))
                    || !p.dimension().equals(target.dimension())) continue;
            double dx = p.x() - target.x(), dy = p.y() - target.y(),
                    dz = p.z() - target.z();
            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                captor = p;
                break;
            }
        }
        if (captor == null) {
            target.refuse("straja.bounty.surrender_alone", "straja.remedy.retry");
            return false;
        }
        var store = store();
        store.surrenders.put(uuid(target),
                now() + ctx.policies().bountySurrenderSeconds * 1000L);
        ctx.bounties().write(store);
        target.tell("Te-ai predat. " + captor.name() + " te poate lega și preda.");
        captor.tell(target.name() + " s-a predat. Îl poți lega cu Frânghia.");
        audit.record("bounty_surrender", captor.name(), uuid(captor),
                target.name(), uuid(target), "SUCCESS", record.id);
        return true;
    }

    /** Valid (unexpired) surrender flag for a target uuid. */
    public boolean hasSurrenderFlag(String targetUuid) {
        Long until = store().surrenders.get(targetUuid);
        return until != null && now() <= until;
    }

    /** Consumes the surrender flag after a successful restraint application. */
    public void consumeSurrender(String targetUuid) {
        var store = store();
        if (store.surrenders.remove(targetUuid) != null) ctx.bounties().write(store);
    }

    // ------------------------------------------------- restraint gate SPI

    /**
     * Bounty-scoped gate consulted by CustodyService before a civilian applies
     * rope/sack to a bountied target. Returns null when the restraint is
     * allowed (target is downed or surrendered); a refusal reason key when not.
     */
    public String restraintRefusal(PlayerGateway issuer, PlayerGateway target,
                                   boolean downed) {
        var record = target == null ? null : activeFor(uuid(target));
        if (record == null) return null; // not bountied — freeform rules apply
        if (downed || hasSurrenderFlag(uuid(target))) return null;
        if (issuer != null) {
            issuer.refuse("straja.bounty.restraint_fight", "straja.remedy.downed_or_surrender",
                    target.name());
        }
        return "bounty_target_not_subdued";
    }

    // --------------------------------------------------- capture -> money

    /**
     * Arrest hook (wired via {@code prison.onArrest}): a successfully arrested
     * bounty target completes the contract — payout to the delivering hunter
     * (bounty-escort issuer, else the arresting actor), the linked BOLO
     * resolves through the normal release path, and a 2x bail fine lands on
     * the prisoner.
     */
    public synchronized void onArrested(Sentence sentence) {
        if (sentence == null || !ctx.policies().bountyEnabled) return;
        var store = store();
        var record = store.activeFor(sentence.targetUuid);
        if (record == null) return;
        record.status = BountyStatus.CAPTURED;
        record.capturedAt = now();
        record.bailDeadlineAt = now() + ctx.policies().bountyBailWindowHours * 3_600_000L;
        // Hunter: the civilian who bound the captive for bounty, else the
        // arresting actor (an officer who catches a bounty earns it too).
        var bound = ctx.custody().read().bound.get(sentence.targetUuid);
        if (bound != null && BOUNTY_CAPTURE_REASON.equals(bound.reason)
                && bound.issuerUuid != null && !bound.issuerUuid.isBlank()) {
            record.hunterUuid = bound.issuerUuid;
            record.hunterName = bound.issuer;
        } else if (sentence.arrestedByUuid != null && !sentence.arrestedByUuid.isBlank()
                && !sentence.arrestedByUuid.equals(record.postedByUuid)) {
            record.hunterUuid = sentence.arrestedByUuid;
            record.hunterName = sentence.arrestedBy;
        }
        // Bail fine: 2x bounty, issued in-sentence so it escalates only if it
        // survives the sentence unpaid.
        var fineData = ctx.fines().read();
        var fine = new Fine();
        fine.id = fineData.nextFineId();
        fine.target = sentence.target;
        fine.targetUuid = sentence.targetUuid;
        fine.issuer = "Straja";
        fine.issuerUuid = "";
        fine.law = "bounty_capture";
        fine.description = "Cautionare recompensă " + record.id
                + " (2× recompensa de " + record.amount + ")";
        fine.amount = (int) Math.round(record.amount * ctx.policies().bountyFineMultiplier);
        fine.status = "IN_SENTENCE";
        fine.issuedAt = now();
        fine.sentenceId = sentence.id;
        fineData.fines.add(fine);
        ctx.fines().write(fineData);
        record.linkedFineId = fine.id;
        payout(record);
        ctx.bounties().write(store);
        audit.record("bounty_capture", record.hunterName, record.hunterUuid,
                sentence.target, sentence.targetUuid, "SUCCESS",
                record.id + " payout=" + record.amount + " fine=" + fine.id
                        + "@" + fine.amount);
    }

    private void payout(BountyRecord record) {
        if (record.hunterUuid == null || record.hunterUuid.isBlank()) return;
        var hunter = ctx.server().findPlayer(record.hunterUuid);
        if (hunter == null) {
            record.payoutPending = true;
            return;
        }
        var deposit = ctx.currency().deposit(hunter, record.amount,
                "bounty:" + record.id);
        if (deposit.ok()) {
            hunter.tell("Recompensa " + record.id + " plătită: " + record.amount
                    + " unități pentru capturarea lui " + record.targetName + ".");
        } else {
            // Coin provider unavailable or inventory full — retry on login.
            record.payoutPending = true;
        }
    }

    /** Pending-payout delivery on login (hunter was offline at capture). */
    public synchronized void recoverOnLogin(PlayerGateway player) {
        if (player == null) return;
        var store = store();
        boolean changed = false;
        for (BountyRecord record : store.records) {
            if (record == null || !record.payoutPending
                    || !uuid(player).equals(record.hunterUuid)) continue;
            var deposit = ctx.currency().deposit(player, record.amount,
                    "bounty:" + record.id);
            if (deposit.ok()) {
                record.payoutPending = false;
                changed = true;
                player.tell("Recompensa " + record.id + " plătită: " + record.amount
                        + " unități pentru capturarea lui " + record.targetName + ".");
            }
        }
        if (changed) ctx.bounties().write(store);
    }

    // ------------------------------------------------------------- bail

    /**
     * Third-party bail: {@code payer} covers the bounty-capture fine of
     * {@code targetName} in physical coins — paid bail releases the prisoner
     * immediately. Anyone may pay for anyone.
     */
    public synchronized boolean payBail(PlayerGateway payer, String targetName) {
        if (payer == null) return false;
        var fineData = ctx.fines().read();
        Fine fine = null;
        for (Fine f : fineData.fines) {
            if (f == null || !"bounty_capture".equals(f.law)) continue;
            if (!targetName.equals(f.targetUuid)
                    && !targetName.equalsIgnoreCase(f.target)) continue;
            if ("ISSUED".equals(f.status) || "IN_SENTENCE".equals(f.status)) {
                fine = f;
                break;
            }
        }
        if (fine == null) {
            payer.refuse("straja.bounty.no_bail", "straja.remedy.fix_retry", targetName);
            return false;
        }
        if (ctx.currency().balanceOf(payer) < fine.amount) {
            payer.refuse("straja.fine.exact_coins", "straja.remedy.retry");
            return false;
        }
        var withdrawal = ctx.currency().withdraw(payer, fine.amount);
        if (!withdrawal.ok()) {
            payer.refuse("straja.fine.pay_failed", "straja.remedy.retry");
            audit.record("bounty_bail", payer.name(), uuid(payer), fine.target,
                    fine.targetUuid, "FAILED", "no_debit fineId=" + fine.id);
            return false;
        }
        fine.status = "PAID";
        fine.paidAt = now();
        ctx.fines().write(fineData);
        audit.record("bounty_bail", payer.name(), uuid(payer), fine.target,
                fine.targetUuid, "SUCCESS",
                "fineId=" + fine.id + " amount=" + fine.amount
                        + " thirdParty=" + !uuid(payer).equals(fine.targetUuid));
        payer.tell("Cauțiunea lui " + fine.target + " a fost plătită ("
                + fine.amount + " unități). Eliberarea se procesează.");
        // Paid bail releases immediately — the sentence ends BAIL_PAID.
        if (prison != null) prison.releaseForBail(fine.targetUuid);
        return true;
    }

    // ------------------------------------------------------------- tick

    /** Periodic sweep: TTL expiry + lapsed surrender flags + bail-window
     *  camp transfers. Wired into the server tick. */
    public synchronized void tick() {
        var store = store();
        boolean changed = false;
        var it = store.surrenders.entrySet().iterator();
        while (it.hasNext()) {
            if (now() > it.next().getValue()) {
                it.remove();
                changed = true;
            }
        }
        for (BountyRecord record : store.records) {
            if (record == null || record.status != BountyStatus.ACTIVE) continue;
            if (now() >= record.expiresAt) {
                record.status = BountyStatus.EXPIRED;
                changed = true;
                resolveLinkedBolo(record, "bounty_expired");
                audit.record("bounty_expire", "system", "", record.targetName,
                        record.targetUuid, "SUCCESS", record.id);
            }
        }
        // Bail window lapsed, fine still unpaid -> send to the mines.
        for (BountyRecord record : store.records) {
            if (record == null || record.status != BountyStatus.CAPTURED
                    || record.campTransferAttempted || record.bailDeadlineAt == 0
                    || now() < record.bailDeadlineAt) continue;
            record.campTransferAttempted = true;
            changed = true;
            var fine = ctx.fines().read().find(record.linkedFineId);
            if (fine != null && "PAID".equals(fine.status)) continue;
            if (prison != null) {
                String campId = ctx.policies().bountyDefaultCampId;
                if (campId == null || campId.isBlank()) {
                    var camps = ctx.laborCamps().read().camps();
                    campId = camps.isEmpty() ? "" : camps.keySet().iterator().next();
                }
                if (!campId.isBlank()) {
                    prison.systemTransferToCamp(record.targetUuid, campId);
                }
            }
        }
        if (changed) ctx.bounties().write(store);
    }

    private void resolveLinkedBolo(BountyRecord record, String reason) {
        if (record.linkedBoloId == null || record.linkedBoloId.isBlank()) return;
        var boloStore = ctx.bolos().read();
        var bolo = boloStore.find(record.linkedBoloId);
        if (bolo != null && bolo.status == BoloStatus.ACTIVE) {
            bolo.status = BoloStatus.RESOLVED;
            ctx.bolos().write(boloStore);
        }
    }
}
