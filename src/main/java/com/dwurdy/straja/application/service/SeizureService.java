package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.LawCheckpointStore;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.SeizedStack;
import com.dwurdy.straja.domain.model.SnapshotItem;
import com.dwurdy.straja.domain.model.StoragePoint;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LAW-004 seizure engine: the authoritative arrest-time inventory pipeline.
 * Every carried stack is confiscated via {@code DeepScanGateway.seizeAll}
 * (nested containers travel inside the parent's serialized data), then
 * routed by classification — contraband into the checkpoint's evidence
 * chest chain, personal goods into the prisoner's assigned locker chests.
 *
 * <p>Conservation invariants (prototype {@code cpSeizeAll}/{@code cpPourPChest}
 * parity): a stack is placed into containers first; leftover count overflows
 * to the next destination in the chain; anything no container accepts is
 * dropped as an item entity beside the last evidence point — never voided,
 * never silently kept on the prisoner.</p>
 */
public final class SeizureService {
    private final StrajaContext ctx;
    private final AuditService audit;

    public SeizureService(StrajaContext ctx, AuditService audit) {
        this.ctx = ctx;
        this.audit = audit;
    }

    /**
     * Booking-time seizure. Upserts the register record (status, locker,
     * summary, snapshot fallback), writes it, and hands the prisoner the
     * written confiscation report. Returns the record so callers can chain.
     */
    public PrisonerRegisterRecord onArrest(PlayerGateway target, LawCheckpointRecord site,
                                           String reason) {
        if (target == null) return null;
        String uuid = target.uuid() == null ? "" : target.uuid().toString();
        long now = ctx.clock().nowMillis();
        var reg = ctx.prisonerRegister().read();
        var rec = uuid.isEmpty() ? reg.prisonerByName(target.name()) : reg.prisoner(uuid);
        if (rec == null) {
            rec = new PrisonerRegisterRecord(uuid, target.name(), reason == null ? "" : reason);
            reg.put(rec);
        }
        if (rec.releasedAt > 0 || rec.status.isReleased()) {
            // Fresh booking after a release: the evidentiary snapshot belongs
            // to THIS arrest — a stale snapshot/summary would misattribute
            // the prior arrest's belongings to the new seizure. A stale camp
            // assignment or labor balance would also corrupt the new custody:
            // an old camp id misroutes recapture, and a carried-over balance
            // could meet the freedom price on the first tick.
            rec.arrestSnapshot.clear();
            rec.confiscatedSummary.clear();
            rec.assignedCampId = "";
            rec.laborAccount = 0;
        }
        rec.status = PrisonerStatus.IN_CELL;
        rec.priorGameMode = safeGameMode(target);
        rec.detaineeUuid = uuid.isEmpty() ? rec.detaineeUuid : uuid;
        rec.detaineeName = target.name();
        if (reason != null && !reason.isBlank()) rec.detentionReason = reason;
        if (site != null) rec.arrestSite = site.id;
        rec.bookedAt = now;
        rec.releasedAt = 0;
        rec.arrestCount++;

        var stacks = ctx.deepScan().seizeAll(target.uuid());
        var checkpoints = ctx.lawCheckpoints().read();
        int contraband = 0, personal = 0, dropped = 0;
        List<String> contrabandLines = new ArrayList<>();
        for (SeizedStack stack : stacks) {
            if (stack == null || stack.count() <= 0
                    || stack.itemId() == null || stack.itemId().isBlank()) continue;
            try {
                boolean illegal = checkpoints.isIllegal(site, stack.itemId())
                        || containsContraband(stack, site, checkpoints);
                int leftover;
                if (illegal) {
                    contraband += stack.count();
                    contrabandLines.add(stack.count() + " x " + stack.itemId());
                    leftover = routeEvidence(site, checkpoints, stack);
                } else {
                    personal += stack.count();
                    leftover = routeLocker(rec, stack);
                    if (leftover > 0) {
                        // Locker overflow spills to evidence, never back on the prisoner.
                        leftover = routeEvidence(site, checkpoints,
                                withCount(stack, leftover));
                    }
                }
                if (leftover > 0) {
                    dropped += leftover;
                    dropAtEvidenceOrPlayer(target, site, checkpoints,
                            stack.itemId(), leftover);
                }
            } catch (RuntimeException ex) {
                // A broken container must never void a seized stack — drop
                // the whole count at the prisoner instead.
                dropped += stack.count();
                try {
                    dropAtEvidenceOrPlayer(target, site, checkpoints,
                            stack.itemId(), stack.count());
                } catch (RuntimeException ignored) {}
                audit.record("seizure_route_failure", "system", "", target.name(),
                        uuid, "FAIL", stack.itemId() + " x" + stack.count());
            }
        }

        // Checkpoint arrests write the richer deep-scan snapshot themselves
        // (nested rows included); officer arrests fall back to seized rows.
        if (site == null && rec.arrestSnapshot.isEmpty()) {
            for (SeizedStack s : stacks) {
                if (s != null && s.count() > 0 && !s.itemId().isBlank()) {
                    rec.arrestSnapshot.add(new SnapshotItem(s.slotPath(), s.itemId(),
                            s.count(), s.snbt(), s.itemId()));
                }
            }
        }
        if (!contrabandLines.isEmpty()) {
            rec.confiscatedSummary = new ArrayList<>(contrabandLines);
        }
        rec.confiscatedFully = dropped == 0;
        reg.legacyWantedUntil().remove(uuid);
        ctx.prisonerRegister().write(reg);
        audit.record("seizure", "system", "", target.name(), uuid, "SUCCESS",
                "contraband=" + contraband + " personal=" + personal
                        + " dropped=" + dropped + " lockers=" + rec.personalLocker.size());
        giveReport(target, rec, site, contraband, personal, dropped);
        return rec;
    }

    /**
     * Restores locker belongings on release. Online prisoners get items
     * poured straight into the inventory (overflow drops at their feet —
     * prototype {@code cpGivePlayer}); an offline release keeps the locker
     * reservation under {@code pendingLockers} so a later login collects it.
     * Mutates {@code rec} inside the caller's {@code reg} so a single write
     * commits status, locker clear and pending reservation atomically.
     */
    public void releaseLocker(com.dwurdy.straja.domain.model.PrisonerRegisterStore reg,
                              PlayerGateway target, PrisonerRegisterRecord rec) {
        if (rec == null || rec.personalLocker == null || rec.personalLocker.isEmpty()) return;
        if (target == null || !target.isOnline()) {
            reg.reserveLockers(rec.detaineeUuid, rec.personalLocker.stream()
                    .map(StoragePoint::key).toList());
            rec.personalLocker = new ArrayList<>();
            return;
        }
        int restored = 0;
        for (StoragePoint point : rec.personalLocker) {
            if (point == null) continue;
            for (SeizedStack stack : ctx.containers().drain(
                    point.dimension(), point.x(), point.y(), point.z())) {
                target.giveStack(stack.itemId(), stack.count(), stack.snbt());
                restored += stack.count();
            }
        }
        rec.personalLocker = new ArrayList<>();
        audit.record("locker_release", "system", "", target.name(),
                uuidOf(target), "SUCCESS", "restored=" + restored);
    }

    /** Delivers locker contents reserved for a player who was released while offline. */
    public void deliverPendingLockers(PlayerGateway target) {
        if (target == null || target.uuid() == null) return;
        var reg = ctx.prisonerRegister().read();
        var keys = reg.drainPendingLockers(target.uuid().toString());
        if (keys.isEmpty()) return;
        int restored = 0;
        for (String key : keys) {
            StoragePoint point = StoragePoint.fromKey(key);
            if (point == null) continue;
            for (SeizedStack stack : ctx.containers().drain(
                    point.dimension(), point.x(), point.y(), point.z())) {
                target.giveStack(stack.itemId(), stack.count(), stack.snbt());
                restored += stack.count();
            }
        }
        ctx.prisonerRegister().write(reg);
        if (restored > 0) {
            target.tell("Bunurile tale din dulapul personal au fost restituite.");
            audit.record("locker_release", "system", "", target.name(),
                    uuidOf(target), "SUCCESS", "pending restored=" + restored);
        }
    }

    /**
     * LAW-006 exit-gate confiscation: pulls every inventory stack whose item
     * id is on {@code itemIds} (the checkpoint's ban/illegal hit-list) out of
     * the prisoner's hands and into the site's evidence chain. Leftovers and
     * failures drop at the player — confiscation never voids items.
     * Returns the count actually removed from the inventory.
     */
    public int confiscateItems(PlayerGateway target, LawCheckpointRecord site,
                               java.util.Collection<String> itemIds) {
        if (target == null || !target.isOnline()
                || itemIds == null || itemIds.isEmpty()) return 0;
        var inv = target.inventory();
        if (inv == null) return 0;
        var checkpoints = ctx.lawCheckpoints().read();
        var wanted = new java.util.HashSet<String>(itemIds);
        int seized = 0, dropped = 0;
        for (int slot = 0; slot < inv.slots(); slot++) {
            var stack = inv.stackAt(slot);
            if (stack == null || stack.count() <= 0 || stack.id() == null) continue;
            String snbt = inv.snbtAt(slot);
            // A container stack (shulker, bundle) holding a wanted item is
            // seized whole — the deep scan already proved the id lives inside
            // its SNBT, so leaving it would let the ore walk out the gate.
            if (!wanted.contains(stack.id())
                    && (snbt == null || wanted.stream().noneMatch(snbt::contains))) {
                continue;
            }
            var taken = inv.extract(slot, stack.count());
            if (taken == null || taken.count() <= 0) continue;
            seized += taken.count();
            var seized_ = new SeizedStack("inv/" + slot, taken.id(), taken.count(),
                    snbt, List.of());
            int leftover;
            try {
                leftover = routeEvidence(site, checkpoints, seized_);
            } catch (RuntimeException ex) {
                leftover = taken.count();
            }
            if (leftover > 0) {
                dropped += leftover;
                try {
                    dropAtEvidenceOrPlayer(target, site, checkpoints, taken.id(), leftover);
                } catch (RuntimeException ignored) {}
            }
        }
        if (seized > 0) {
            audit.record("camp_exit_seizure", "system", "", target.name(),
                    uuidOf(target), "SUCCESS",
                    "site=" + (site == null ? "" : site.id)
                            + " seized=" + seized + " dropped=" + dropped);
        }
        return seized;
    }

    // ------------------------------------------------------------ routing

    private boolean containsContraband(SeizedStack stack, LawCheckpointRecord site,
                                       LawCheckpointStore checkpoints) {
        for (String id : stack.containedIds()) {
            if (checkpoints.isIllegal(site, id)) return true;
        }
        return false;
    }

    private static SeizedStack withCount(SeizedStack stack, int count) {
        return new SeizedStack(stack.slotPath(), stack.itemId(), count,
                stack.snbt(), stack.containedIds());
    }

    /** Sequential ordered fill across the site's evidence chain (all sites when the arrest had none). */
    private int routeEvidence(LawCheckpointRecord site, LawCheckpointStore checkpoints,
                              SeizedStack stack) {
        List<StoragePoint> evidence = evidenceChain(site, checkpoints);
        int remaining = stack.count();
        for (StoragePoint point : evidence) {
            if (remaining <= 0) break;
            int left = ctx.containers().insertStack(point.dimension(), point.x(), point.y(),
                    point.z(), stack.itemId(), remaining, stack.snbt());
            remaining = left < 0 ? remaining : left;
        }
        return remaining;
    }

    private List<StoragePoint> evidenceChain(LawCheckpointRecord site,
                                             LawCheckpointStore checkpoints) {
        if (site != null && site.evidenceChests != null && !site.evidenceChests.isEmpty()) {
            return site.evidenceChests;
        }
        List<StoragePoint> all = new ArrayList<>();
        for (var rec : checkpoints.checkpoints().values()) {
            if (rec != null && rec.evidenceChests != null) all.addAll(rec.evidenceChests);
        }
        return all;
    }

    /**
     * Personal-locker fill; allocates locker chests from the prison pool on
     * first use. Returns leftover count (caller spills it to evidence).
     */
    private int routeLocker(PrisonerRegisterRecord rec, SeizedStack stack) {
        if (rec.personalLocker == null) rec.personalLocker = new ArrayList<>();
        if (rec.personalLocker.isEmpty()) allocLocker(rec);
        int remaining = stack.count();
        for (StoragePoint point : rec.personalLocker) {
            if (remaining <= 0) break;
            int left = ctx.containers().insertStack(point.dimension(), point.x(), point.y(),
                    point.z(), stack.itemId(), remaining, stack.snbt());
            remaining = left < 0 ? remaining : left;
        }
        return remaining;
    }

    /**
     * Stable sequential allocation: free pool chests (not owned by any
     * register record or pending reservation) are claimed in pool order —
     * never renumbered while in use, mirroring the prototype's pchests.
     */
    private void allocLocker(PrisonerRegisterRecord rec) {
        var data = ctx.prison().read();
        if (data.lockerPool == null || data.lockerPool.isEmpty()) return;
        var reg = ctx.prisonerRegister().read();
        Set<String> claimed = new LinkedHashSet<>();
        for (var other : reg.prisoners().values()) {
            if (other == null || other.personalLocker == null) continue;
            for (StoragePoint p : other.personalLocker) {
                if (p != null) claimed.add(p.key());
            }
        }
        for (List<String> keys : reg.pendingLockers().values()) {
            if (keys != null) claimed.addAll(keys);
        }
        int want = Math.max(1, ctx.policies().lockerChestsPerPrisoner);
        for (StoragePoint point : data.lockerPool) {
            if (point == null || rec.personalLocker.size() >= want) break;
            if (claimed.contains(point.key())) continue;
            if (!ctx.containers().isContainer(point.dimension(), point.x(), point.y(), point.z())) {
                continue; // missing/destroyed locker — skip, never claim air
            }
            rec.personalLocker.add(point);
        }
    }

    /** Overflow spill: beside the first evidence point when configured, else at the prisoner. */
    private void dropAtEvidenceOrPlayer(PlayerGateway target, LawCheckpointRecord site,
                                        LawCheckpointStore checkpoints,
                                        String itemId, int count) {
        var evidence = evidenceChain(site, checkpoints);
        if (!evidence.isEmpty()) {
            var first = evidence.get(0);
            ctx.containers().dropItem(first.dimension(), first.x(), first.y(), first.z(),
                    itemId, count);
        } else {
            ctx.containers().dropItem(target.dimension(), (int) Math.floor(target.x()),
                    (int) Math.floor(target.y()), (int) Math.floor(target.z()), itemId, count);
        }
    }

    /** Written confiscation report (prototype cpGiveReport) — handed to the prisoner. */
    private void giveReport(PlayerGateway target, PrisonerRegisterRecord rec,
                            LawCheckpointRecord site, int contraband, int personal, int dropped) {
        List<String> pages = new ArrayList<>();
        pages.add("PROCES-VERBAL DE CONFISCARE\n\nDeținut: " + target.name()
                + "\nMotiv: " + (rec.detentionReason.isBlank() ? "arest" : rec.detentionReason)
                + (site != null ? "\nLoc: " + site.id : ""));
        StringBuilder inventoryPage = new StringBuilder();
        inventoryPage.append("Contraband: ").append(contraband).append(" obiecte\n")
                .append("Bunuri personale: ").append(personal).append(" obiecte\n");
        if (dropped > 0) {
            inventoryPage.append("Resturi nedepozitate: ").append(dropped);
        }
        if (!rec.confiscatedSummary.isEmpty()) {
            inventoryPage.append("\n\n").append(String.join("\n", rec.confiscatedSummary));
        }
        pages.add(inventoryPage.toString());
        pages.add("Bunurile personale sunt păstrate în dulapul personal și îți sunt "
                + "restituite la eliberare. Contrabandul este reținut ca probă.");
        try {
            target.giveWrittenBook("Proces-verbal Straja", "Straja", pages);
        } catch (RuntimeException ignored) {
            // A report that cannot be delivered must never undo the seizure.
        }
    }

    private static String safeGameMode(PlayerGateway p) {
        try {
            String mode = p.gameModeName();
            return mode == null || mode.isBlank() ? "survival" : mode;
        } catch (RuntimeException ex) {
            return "survival";
        }
    }

    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }
}
