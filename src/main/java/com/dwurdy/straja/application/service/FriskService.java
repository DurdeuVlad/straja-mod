package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.CrossingDirection;
import com.dwurdy.straja.domain.model.CrossingOutcome;
import com.dwurdy.straja.domain.model.InspectionLedgerEntry;
import com.dwurdy.straja.domain.model.SnapshotItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verificare corporală — the consented inventory check. Any player may ask a
 * nearby player "pot să te verific?"; the target gets a timed Acceptă/Refuză
 * notice and the check happens only on explicit consent. On accept the
 * requester receives a read-only snapshot of the target's inventory and the
 * check lands in the inspection ledger + audit — declining is a recorded
 * right, never a mark. Custody, checkpoint and seizure rules are untouched:
 * consent is the only authority a frisk carries.
 */
public class FriskService {
    /** Pseudo-site id used for ledger rows so archivists can filter frisks. */
    public static final String LEDGER_SITE = "frisk";
    private static final String AUDIT_ACTION = "frisk";
    private static final int MAX_LINES = 40;

    private final StrajaContext ctx;
    private final AuditService audit;
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private final Map<String, Long> cooldownUntil = new LinkedHashMap<>();
    private int nextId = 1;

    private record Request(String id, String requesterUuid, String requester,
                           String targetUuid, String target, long expiresAt) {}

    private java.util.function.Predicate<PlayerGateway> inCustody = player -> false;

    /** Custody truth: a restrained player cannot meaningfully consent — the
     *  escort already holds lawful seizure authority over them. */
    public void useCustody(CustodyService custody) {
        this.inCustody = custody == null ? player -> false : custody::isCuffed;
    }

    public FriskService(StrajaContext ctx, AuditService audit) {
        this.ctx = ctx;
        this.audit = audit;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private static String uuidOf(PlayerGateway p) {
        return p.uuid() == null ? "" : p.uuid().toString();
    }

    private PlayerGateway online(String uuid) {
        for (var p : ctx.server().onlinePlayers()) {
            if (p.uuid() != null && p.uuid().toString().equals(uuid)) return p;
        }
        return null;
    }

    private static boolean near(PlayerGateway a, PlayerGateway b, double radius) {
        if (!a.dimension().equals(b.dimension())) return false;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private void prune() {
        long now = now();
        requests.values().removeIf(r -> r.expiresAt <= now);
        cooldownUntil.values().removeIf(until -> until <= now);
    }

    /** {@code /straja verificare <jucător>} — sends the consent offer. */
    public boolean request(PlayerGateway requester, PlayerGateway target) {
        if (requester == null || target == null) return false;
        if (!ctx.policies().friskEnabled) {
            requester.refuse("straja.frisk.disabled", "straja.remedy.ask_admin");
            return false;
        }
        if (requester.uuid() != null && requester.uuid().equals(target.uuid())) {
            requester.refuse("straja.frisk.self", "straja.remedy.fix_retry");
            return false;
        }
        if (inCustody.test(requester)) {
            requester.refuse("straja.frisk.you_cuffed", "straja.remedy.fix_retry");
            return false;
        }
        if (inCustody.test(target)) {
            requester.refuse("straja.frisk.in_custody", "straja.remedy.fix_retry");
            return false;
        }
        double range = Math.max(1.0, ctx.policies().friskRange);
        if (!near(requester, target, range)) {
            requester.refuse("straja.frisk.too_far", "straja.remedy.fix_retry");
            return false;
        }
        prune();
        String requesterUuid = uuidOf(requester);
        Long until = cooldownUntil.get(requesterUuid);
        if (until != null && until > now()) {
            requester.refuse("straja.frisk.cooldown", "straja.remedy.wait");
            return false;
        }
        for (var r : requests.values()) {
            if (r.targetUuid().equals(uuidOf(target))) {
                requester.refuse("straja.frisk.pending", "straja.remedy.wait");
                return false;
            }
        }
        var req = new Request("F" + nextId++, requesterUuid, requester.name(),
                uuidOf(target), target.name(),
                now() + Math.max(5, ctx.policies().friskOfferSeconds) * 1000L);
        requests.put(req.id(), req);
        cooldownUntil.put(requesterUuid,
                now() + Math.max(0, ctx.policies().friskCooldownSeconds) * 1000L);
        long ttl = Math.max(0, req.expiresAt() - now());
        target.tellAction("straja.frisk.offer", List.of(
                new PlayerGateway.ActionLink("Acceptă", "frisk-accept:" + req.id(), ttl),
                new PlayerGateway.ActionLink("Refuză", "frisk-decline:" + req.id(), ttl)),
                requester.name());
        requester.tellKey("straja.frisk.sent", target.name());
        audit.record(AUDIT_ACTION, requester.name(), requesterUuid,
                target.name(), req.targetUuid(), AuditResults.PENDING,
                "consent_requested requestId=" + req.id());
        return true;
    }

    private Request find(PlayerGateway target, String requestId) {
        String id = requestId == null ? "" : requestId.trim().toUpperCase();
        var req = requests.get(id);
        if (req == null || !req.targetUuid().equals(uuidOf(target))) return null;
        return req;
    }

    /** Stale-click gate for the npc-action dispatch: the id must still name a
     *  live offer addressed to this player. */
    public boolean hasPending(PlayerGateway target, String requestId) {
        prune();
        return find(target, requestId) != null;
    }

    /** {@code /straja verificare accept|decline} — the typed fallback for
     *  players who cannot click the links; resolves their newest live offer. */
    public boolean acceptLatest(PlayerGateway target) {
        String id = latestId(target);
        if (id == null) {
            target.refuse("straja.frisk.no_pending", "straja.remedy.ask_again");
            return false;
        }
        return accept(target, id);
    }

    public boolean declineLatest(PlayerGateway target) {
        String id = latestId(target);
        if (id == null) {
            target.refuse("straja.frisk.no_pending", "straja.remedy.ask_again");
            return false;
        }
        return decline(target, id);
    }

    private String latestId(PlayerGateway target) {
        prune();
        String found = null;
        for (var r : requests.values()) {
            if (r.targetUuid().equals(uuidOf(target))) found = r.id();
        }
        return found;
    }

    /** {@code frisk-accept:<id>} — consent granted: snapshot + ledger + audit. */
    public boolean accept(PlayerGateway target, String requestId) {
        prune();
        var req = find(target, requestId);
        if (req == null) {
            target.refuse("straja.frisk.no_pending", "straja.remedy.ask_again");
            return false;
        }
        requests.remove(req.id());
        var requester = online(req.requesterUuid());
        if (requester == null) {
            target.refuse("straja.frisk.requester_gone", "straja.remedy.retry");
            audit.record(AUDIT_ACTION, req.requester(), req.requesterUuid(),
                    req.target(), req.targetUuid(), AuditResults.REFUSED,
                    "requester_offline requestId=" + req.id());
            return false;
        }
        if (inCustody.test(requester) || inCustody.test(target)) {
            target.refuse("straja.frisk.in_custody", "straja.remedy.fix_retry");
            audit.record(AUDIT_ACTION, req.requester(), req.requesterUuid(),
                    req.target(), req.targetUuid(), AuditResults.REFUSED,
                    "custody_block requestId=" + req.id());
            return false;
        }
        if (!ctx.policies().friskEnabled
                || !near(requester, target, Math.max(1.0, ctx.policies().friskRange))) {
            target.refuse("straja.frisk.too_far", "straja.remedy.retry");
            requester.refuse("straja.frisk.target_left", "straja.remedy.fix_retry",
                    target.name());
            audit.record(AUDIT_ACTION, req.requester(), req.requesterUuid(),
                    req.target(), req.targetUuid(), AuditResults.REFUSED,
                    "range_or_policy_failed requestId=" + req.id());
            return false;
        }
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(target.uuid());
        var entry = new InspectionLedgerEntry(UUID.randomUUID().toString(), now(),
                LEDGER_SITE, req.targetUuid(), req.target(),
                CrossingDirection.INCOMING, CrossingOutcome.PASS);
        entry.detail = "verificare corporală — acord " + req.id()
                + " — verificator " + req.requester();
        entry.inventorySnapshot.addAll(snapshot);
        var ledger = ctx.inspectionLedger().read();
        ledger.append(entry, ctx.policies().inspectionLedgerLimit);
        ctx.inspectionLedger().write(ledger);
        showInventory(requester, target, snapshot, entry.id);
        target.tellKey("straja.frisk.checked", requester.name());
        audit.record(AUDIT_ACTION, req.requester(), req.requesterUuid(),
                req.target(), req.targetUuid(), AuditResults.SUCCESS,
                "consent_granted requestId=" + req.id()
                        + " items=" + snapshot.size() + " ledgerId=" + entry.id);
        return true;
    }

    /** {@code frisk-decline:<id>} — consent refused: recorded, never punished. */
    public boolean decline(PlayerGateway target, String requestId) {
        prune();
        var req = find(target, requestId);
        if (req == null) {
            target.refuse("straja.frisk.no_pending", "straja.remedy.ask_again");
            return false;
        }
        requests.remove(req.id());
        var requester = online(req.requesterUuid());
        if (requester != null) requester.tellKey("straja.frisk.declined", target.name());
        target.tellKey("straja.frisk.declined_self");
        audit.record(AUDIT_ACTION, req.requester(), req.requesterUuid(),
                req.target(), req.targetUuid(), AuditResults.REFUSED,
                "consent_declined requestId=" + req.id());
        return true;
    }

    /** Read-only display: lines stay in chat; the full evidence row lives in
     *  the inspection ledger (id shown so the requester can cite it). */
    private void showInventory(PlayerGateway requester, PlayerGateway target,
                               List<SnapshotItem> snapshot, String entryId) {
        if (snapshot.isEmpty()) {
            requester.tellKey("straja.frisk.result_empty", target.name());
            return;
        }
        requester.tellKey("straja.frisk.result_header", target.name(), entryId);
        int shown = 0;
        for (SnapshotItem item : snapshot) {
            if (shown >= MAX_LINES) break;
            String label = item.name != null && !item.name.isBlank()
                    ? item.name : item.itemId;
            requester.tell("§7- §f" + item.count + "× " + label
                    + " §8(" + item.slot + ")");
            shown++;
        }
        int rest = snapshot.size() - shown;
        if (rest > 0) requester.tellKey("straja.frisk.result_more", rest, entryId);
    }
}
