package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.CustodyDeadlineEngine;
import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.CustodyStore;
import com.dwurdy.straja.domain.model.CustodyStatus;
import com.dwurdy.straja.domain.model.CustodyTransition;
import com.dwurdy.straja.domain.model.CustodyTransitionEngine;
import com.dwurdy.straja.domain.model.DamageCategory;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.LethalEventResolver;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.PlayerCondition;
import com.dwurdy.straja.domain.model.RecoveryEvent;
import com.dwurdy.straja.domain.model.RestraintStatus;
import com.dwurdy.straja.domain.model.StateProvider;
import com.dwurdy.straja.domain.model.TransportStatus;
import com.dwurdy.straja.domain.model.VisionStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enforcement mechanics: cuff consent flow, surrender, direct cuffs, cuff keys,
 * rope binding, head sack, distance break, non-lethal baton knockout, downed
 * state and release. Ported from the reference runtime; all checks are
 * server-side and every state transition is persisted + audited.
 */
public class CustodyService implements CustodyRoleplayUseCase {
    public static final String CUFFS = "straja:cuffs";
    public static final String CUFF_KEY = "straja:cuff_key";
    public static final String CROWBAR = "straja:crowbar";
    public static final String BOLT_CUTTERS = "straja:bolt_cutters";
    public static final String KEYCHAIN = "straja:keychain";
    public static final String ROPE = "straja:rope";
    public static final String HEAD_SACK = "straja:head_sack";
    public static final String BATON = "straja:baton";
    private static final double RESUSCITATION_DISTANCE_BLOCKS = 3.0;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public CustodyService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private long now() { return ctx.clock().nowMillis(); }
    private CustodyStore store() { return ctx.custody().read(); }

    private static String key(PlayerGateway p) {
        String uuid = p.uuid() == null ? "" : p.uuid().toString();
        return !uuid.isEmpty() ? uuid : PlayerService.canon(p.name());
    }

    private static boolean matches(PlayerGateway p, String uuid, String name) {
        return PlayerService.identityMatches(p, uuid, name);
    }

    private PlayerGateway findStored(String uuid, String name) {
        for (var p : ctx.server().onlinePlayers()) {
            if (matches(p, uuid, name)) return p;
        }
        return null;
    }

    private record CarrySnapshot(String targetKey, String targetUuid,
                                 String targetName, String carrierId) {}

    private void notify(String uuid, String name, String text) {
        var p = findStored(uuid, name);
        if (p != null) p.tell(text);
    }

    // ------------------------------------------------------------ queries

    public boolean isCuffed(PlayerGateway p) { return store().cuffed.containsKey(key(p)); }
    public boolean isBound(PlayerGateway p) { return store().bound.containsKey(key(p)); }
    public boolean isDowned(PlayerGateway p) { return store().downed.containsKey(key(p)); }
    public boolean hasHeadSack(PlayerGateway p) { return store().headSacks.containsKey(key(p)); }

    public CustodyStore.DownedRecord downedRecord(PlayerGateway p) { return store().downed.get(key(p)); }

    /** Read-only projection of the custody actions this player can take right now. */
    public List<AvailableAction> availableActions(PlayerGateway player) {
        var store = store();
        var actions = new ArrayList<AvailableAction>();
        String playerKey = key(player);
        for (var request : store.cuffRequests.values()) {
            if (request == null || request.expiresAt <= now()
                    || request.id == null || request.id.isEmpty()
                    || !matches(player, request.targetUuid, request.target)) continue;
            actions.add(new AvailableAction(Action.ACCEPT_REQUEST, request.id));
            actions.add(new AvailableAction(Action.REFUSE_REQUEST, request.id));
        }
        if (store.headSacks.containsKey(playerKey) && !store.downed.containsKey(playerKey)) {
            actions.add(new AvailableAction(Action.REMOVE_HEAD_SACK, ""));
        }
        var downed = store.downed.get(playerKey);
        if (downed != null && now() >= downed.wakesAt) {
            actions.add(new AvailableAction(Action.WAKE_DOWNED, ""));
        }
        if (canIssueCuffs(player)) {
            actions.add(new AvailableAction(Action.GIVE_CUFFS, ""));
        }
        if (!"NONE".equals(releaseToolKind(player.mainHand()))) {
            var emitted = new java.util.HashSet<String>();
            for (var record : store.cuffed.values()) {
                if (record != null) {
                    addReleaseTarget(actions, emitted, record.targetUuid, record.target, player);
                }
            }
            for (var record : store.bound.values()) {
                if (record != null) {
                    addReleaseTarget(actions, emitted, record.targetUuid, record.target, player);
                }
            }
        }
        return List.copyOf(actions);
    }

    private void addReleaseTarget(List<AvailableAction> actions, java.util.Set<String> emitted,
                                  String targetUuid, String targetName, PlayerGateway actor) {
        var target = findStored(targetUuid, targetName);
        if (target == null || target.uuid() == null) return;
        String id = target.uuid().toString();
        if (id.equals(uuidOf(actor)) || !emitted.add(id)) return;
        actions.add(new AvailableAction(Action.RELEASE_TARGET, id));
    }

    private static boolean hasItem(PlayerGateway p, String itemId) {
        return p.inventory().contains(itemId);
    }

    /** Removes one unit of {@code itemId} anywhere in the inventory. */
    private static boolean consumeOne(PlayerGateway p, String itemId) {
        var inv = p.inventory();
        for (int i = 0; i < inv.slots(); i++) {
            var stack = inv.stackAt(i);
            if (!stack.isEmpty() && itemId.equals(stack.id())) {
                return !inv.extract(i, 1).isEmpty();
            }
        }
        return false;
    }

    /** Removes one unit of the current main-hand stack; returns its id. */
    private static String consumeMainHand(PlayerGateway p) {
        var held = p.mainHand();
        if (held.isEmpty()) return "";
        var taken = p.inventory().extract(p.selectedSlot(), 1);
        return taken.isEmpty() ? "" : held.id();
    }

    /**
     * Active-roster member at enforcement rank (Străjer+). Deliberately
     * duty-independent: the capability matrix grants USE_CUFFS/USE_BATON by
     * rank, mirroring the reference — on-duty status is a separate concept
     * (see FineService.onDutyGuard).
     */
    private boolean enforcementGuard(PlayerGateway p) {
        if (p == null) return false;
        var state = players.state(p.uuid());
        return !state.suspended && !state.fired && !state.resigned && !state.resignationPending
                && state.rank >= Rank.GUARD.level();
    }

    /** Cuffs may be issued by an active guard or the configured commissioner. */
    private boolean canIssueCuffs(PlayerGateway p) {
        return p != null && (players.isCommissioner(p) || enforcementGuard(p));
    }

    /** Reference policy: guards may not cuff fellow guards without lt+/commissioner. */
    private record Policy(boolean ok, String reason) {}
    private Policy cuffTargetPolicy(PlayerGateway issuer, PlayerGateway target) {
        if (target == null || PlayerService.canon(target.name()).equals(PlayerService.canon(issuer.name()))) {
            return new Policy(false, "self_target");
        }
        var targetState = players.state(target.uuid());
        var issuerState = players.state(issuer.uuid());
        if (targetState.rank >= Rank.STAGIAR.level()
                && !players.isCommissioner(issuer)
                && issuerState.rank < Rank.INSPECTOR.level()) {
            return new Policy(false, "guard_target_policy");
        }
        return new Policy(true, "");
    }

    private void policyDeny(PlayerGateway issuer, Policy policy) {
        issuer.tell("self_target".equals(policy.reason())
                ? "Alege un alt jucător online."
                : "Un străjer nu poate încătușa alt membru al Străjii fără override de Inspector sau Comisaru'.");
    }

    // ------------------------------------------------------------ requests

    private void pruneRequests(CustodyStore store) {
        store.cuffRequests.values().removeIf(r -> r.expiresAt <= now());
    }

    private boolean hasPendingFor(CustodyStore store, String targetKey) {
        return store.cuffRequests.values().stream().anyMatch(r -> r.targetKey.equals(targetKey));
    }

    public boolean requestCuffs(PlayerGateway issuer, PlayerGateway target) {
        if (!canIssueCuffs(issuer)) {
            issuer.tell("Cătușele se acordă de la rangul Străjer în sus.");
            return false;
        }
        if (!hasItem(issuer, CUFFS)) {
            issuer.tell("Nu poți cere încătușarea fără Cătușe în inventar.");
            return false;
        }
        return issueRequest(issuer, target, "CUFF", "cuff_request");
    }

    public boolean requestSurrender(PlayerGateway issuer, PlayerGateway target) {
        if (!canIssueCuffs(issuer)) {
            issuer.tell("Cererea de predare și cătușele se folosesc de la rangul Străjer în sus.");
            return false;
        }
        if (!hasItem(issuer, CUFFS)) {
            issuer.tell("Nu poți cere predarea fără Cătușe în inventar.");
            return false;
        }
        return issueRequest(issuer, target, "SURRENDER", "surrender_request");
    }

    private boolean issueRequest(PlayerGateway issuer, PlayerGateway target, String kind, String auditAction) {
        var policy = cuffTargetPolicy(issuer, target);
        var store = store();
        if (!policy.ok()) {
            policyDeny(issuer, policy);
            audit.record(auditAction, issuer.name(), uuidOf(issuer),
                    target == null ? "" : target.name(), uuidOrEmpty(target), "REFUSED", policy.reason());
            return false;
        }
        String targetKey = key(target);
        if (store.cuffed.containsKey(targetKey)) {
            issuer.tell(target.name() + " este deja încătușat.");
            return false;
        }
        if (store.bound.containsKey(targetKey)) {
            issuer.tell(target.name() + " este deja legat.");
            return false;
        }
        pruneRequests(store);
        if (hasPendingFor(store, targetKey)) {
            issuer.tell("Ținta are deja o cerere în așteptare.");
            return false;
        }
        var request = new CustodyStore.CuffRequest();
        request.id = ("SURRENDER".equals(kind) ? "S" : "C") + store.nextRequestId++;
        request.kind = kind;
        request.issuer = issuer.name();
        request.issuerUuid = uuidOf(issuer);
        request.target = target.name();
        request.targetUuid = uuidOrEmpty(target);
        request.targetKey = targetKey;
        request.issuerRank = players.state(issuer.uuid()).rank;
        request.issuerCapability = "useCuffs";
        request.createdAt = now();
        long timeoutSec = "SURRENDER".equals(kind)
                ? Math.max(5, ctx.policies().surrenderTimeoutSeconds)
                : Math.max(10, ctx.policies().cuffRequestTimeoutSeconds);
        request.expiresAt = now() + timeoutSec * 1000;
        store.cuffRequests.put(request.id, request);
        ctx.custody().write(store);
        if ("SURRENDER".equals(kind)) {
            target.tell("[Straja] " + issuer.name() + " îți cere predarea. Alege Acceptă sau Refuză "
                    + "în cererea din chat (" + request.id + ").");
            issuer.tell("Cererea de predare a fost trimisă lui " + target.name() + ".");
        } else {
            target.tell("[Straja] " + issuer.name() + " vrea să te încătușeze. Alege Acceptă sau Refuză "
                    + "în cererea din chat (" + request.id + ").");
            issuer.tell("Cererea de încătușare a fost trimisă lui " + target.name()
                    + ". Așteaptă acceptarea sau refuzul.");
        }
        audit.record(auditAction, issuer.name(), request.issuerUuid,
                target.name(), request.targetUuid, "SUCCESS",
                ("SURRENDER".equals(kind) ? "knockout_consent_requested" : "consent_requested")
                        + " requestId=" + request.id);
        return true;
    }

    private CustodyStore.CuffRequest findRequest(CustodyStore store, PlayerGateway player, String requestedId) {
        String id = requestedId == null ? "" : requestedId.trim().toUpperCase();
        var request = store.cuffRequests.get(id);
        if (request == null || !matches(player, request.targetUuid, request.target)) return null;
        return request;
    }

    /**
     * Persisted issuer authority: the UUID must be present, parseable, and still
     * belong to an enforcement-capable member (commissioner or active Străjer+).
     */
    private boolean issuerEligible(String issuer, String issuerUuid) {
        if (issuerUuid == null || issuerUuid.isBlank()) return false;
        UUID uuid;
        try {
            uuid = UUID.fromString(issuerUuid);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        if (players.isCommissioner(issuer, uuid)) return true;
        var state = players.state(uuid);
        return !state.suspended && !state.fired && !state.resigned && !state.resignationPending
                && state.rank >= Rank.GUARD.level();
    }

    /** Re-checks persisted issuer authority when a request is accepted offline. */
    private boolean storedIssuerEligible(CustodyStore.CuffRequest request) {
        return issuerEligible(request.issuer, request.issuerUuid);
    }

    public boolean accept(PlayerGateway player, String requestId) {
        var store = store();
        pruneRequests(store);
        var request = findRequest(store, player, requestId);
        if (request == null) {
            player.tell("Cererea nu există, a expirat sau nu îți aparține.");
            return false;
        }
        if ("SURRENDER".equals(request.kind)) {
            return acceptSurrender(player, store, request);
        }
        var issuer = findStored(request.issuerUuid, request.issuer);
        boolean issuerEligible = issuer != null
                ? canIssueCuffs(issuer) && hasItem(issuer, CUFFS)
                : storedIssuerEligible(request);
        if (!issuerEligible) {
            store.cuffRequests.remove(request.id);
            ctx.custody().write(store);
            player.tell("Cererea de încătușare nu mai este validă: emitentul nu mai are autoritate sau Cătușe.");
            if (issuer != null) issuer.tell("Cererea de încătușare a fost anulată: nu mai ai autoritate sau Cătușe.");
            audit.record("cuff_accept", issuer != null ? issuer.name() : player.name(),
                    issuer != null ? uuidOf(issuer) : uuidOf(player),
                    player.name(), uuidOf(player), "REFUSED", "issuer_no_longer_eligible requestId=" + request.id);
            return false;
        }
        if (store.cuffed.containsKey(key(player))) {
            store.cuffRequests.remove(request.id);
            ctx.custody().write(store);
            player.tell("Ești deja încătușat.");
            return false;
        }
        if (!applyRecord(store, player, request.issuer, request.issuerUuid, "accepted")) {
            player.tell("Încătușarea a fost refuzată: starea de custodie nu este validă.");
            return false;
        }
        store.cuffRequests.remove(request.id);
        ctx.custody().write(store);
        applyCuffSlowness(player);
        boolean keyDelivered = grantCuffKey(issuer, request);
        player.tell("Ai acceptat. Ești încătușat și primești efect de încetinire. Gardianul a primit cheia.");
        if (!keyDelivered) player.tell("Gardianul este offline; cheia va fi livrată când revine.");
        if (issuer != null) issuer.tell(player.name() + " a acceptat încătușarea.");
        audit.record("cuff_accept", request.issuer, request.issuerUuid,
                player.name(), uuidOf(player), "SUCCESS", "accepted requestId=" + request.id);
        return true;
    }

    private boolean acceptSurrender(PlayerGateway player, CustodyStore store, CustodyStore.CuffRequest request) {
        var issuer = findStored(request.issuerUuid, request.issuer);
        if (issuer == null || !canIssueCuffs(issuer) || !hasItem(issuer, CUFFS)) {
            store.cuffRequests.remove(request.id);
            ctx.custody().write(store);
            player.tell("Cererea de predare nu mai este validă: gardianul nu este disponibil sau nu mai are Cătușe.");
            if (issuer != null) issuer.tell("Cererea de predare a expirat: trebuie să fii activ și să ai Cătușele în inventar.");
            return false;
        }
        var result = applyCuffsDirect(issuer, player, "baton_surrender");
        if (!result.ok()) {
            store.cuffRequests.remove(request.id);
            ctx.custody().write(store);
            return false;
        }
        resolveDowned(player, "CUFFED");
        audit.record("surrender_accept", player.name(), uuidOf(player),
                player.name(), uuidOf(player), "SUCCESS",
                "accepted requestId=" + request.id + " issuer=" + request.issuer);
        return true;
    }

    public boolean refuse(PlayerGateway player, String requestId) {
        var store = store();
        pruneRequests(store);
        var request = findRequest(store, player, requestId);
        if (request == null) {
            player.tell("Cererea nu există, a expirat sau nu îți aparține.");
            return false;
        }
        store.cuffRequests.remove(request.id);
        if (!"SURRENDER".equals(request.kind)) {
            ctx.custody().write(store);
            notify(request.issuerUuid, request.issuer, "[Straja] " + player.name()
                    + " a rezistat și a refuzat încătușarea.");
            player.tell("Ai refuzat încătușarea.");
            return true;
        }
        ctx.custody().write(store);
        var issuer = findStored(request.issuerUuid, request.issuer);
        if (issuer != null) issuer.tell(player.name() + " a refuzat predarea și a rămas inconștient.");
        startDowned(player, issuer != null ? issuer : player, "surrender_refused");
        player.tell("Ai refuzat predarea. Ai leșinat și vei reveni după cooldown.");
        audit.record("surrender_refuse", player.name(), uuidOf(player),
                request.issuer, request.issuerUuid, "SUCCESS",
                "target_refused_and_unconscious requestId=" + request.id);
        return true;
    }

    // ------------------------------------------------------------ cuffs

    /** Applies cuffs without consent (post-surrender/knockout path). */
    public record ApplyResult(boolean ok, String reason) {}

    public ApplyResult applyCuffsDirect(PlayerGateway issuer, PlayerGateway target, String reason) {
        if (!canIssueCuffs(issuer)) {
            return new ApplyResult(false, "issuer_not_active_guard");
        }
        if (!hasItem(issuer, CUFFS)) {
            issuer.tell("Nu poți aplica încătușarea fără Cătușe în inventar.");
            return new ApplyResult(false, "no_cuffs_available");
        }
        var policy = cuffTargetPolicy(issuer, target);
        if (!policy.ok()) {
            policyDeny(issuer, policy);
            audit.record("cuff_apply", issuer.name(), uuidOf(issuer),
                    target.name(), uuidOf(target), "REFUSED", policy.reason());
            return new ApplyResult(false, policy.reason());
        }
        var store = store();
        if (store.cuffed.containsKey(key(target))) {
            issuer.tell(target.name() + " este deja încătușat.");
            return new ApplyResult(true, "already_cuffed");
        }
        if (!applyRecord(store, target, issuer.name(), uuidOf(issuer),
                reason == null ? "direct" : reason)) {
            return new ApplyResult(false, "canonical_state_invalid");
        }
        store.cuffRequests.values().removeIf(r -> matches(target, r.targetUuid, r.target));
        ctx.custody().write(store);
        applyCuffSlowness(target);
        boolean keyDelivered = grantCuffKey(issuer, null);
        boolean surrendered = "baton_surrender".equals(reason);
        target.tell(surrendered
                ? "Te-ai predat. Ești încătușat și primești efect de încetinire."
                : "Bastonul te-a doborât. Ești încătușat și primești efect de încetinire.");
        if (!keyDelivered) target.tell("Gardianul este fără spațiu; cheia va fi livrată când inventarul permite.");
        issuer.tell(target.name() + (surrendered ? " s-a predat și a fost încătușat." : " a fost încătușat automat.")
                + (keyDelivered ? "" : " Cheia a fost pusă în așteptare până când inventarul permite."));
        audit.record("cuff_apply", issuer.name(), uuidOf(issuer),
                target.name(), uuidOf(target), "SUCCESS", reason == null ? "direct" : reason);
        return new ApplyResult(true, "");
    }

    private boolean applyRecord(CustodyStore store, PlayerGateway target,
                                String issuerName, String issuerUuid, String reason) {
        String targetKey = key(target);
        CustodyState canonical = store.states.get(targetKey);
        if (canonical == null) {
            var legacyDowned = store.downed.get(targetKey);
            canonical = legacyDowned == null
                    ? newCanonicalState(target)
                    : canonicalFromLegacyDowned(targetKey, legacyDowned);
        }
        if (canonical.condition == PlayerCondition.RESUSCITATING) {
            var rescuer = findStored(canonical.resuscitatorId, "");
            if (!interruptResuscitation(store, canonical, target, rescuer, "restraint_applied")) {
                return false;
            }
        }
        long at = now();
        var canonicalResult = CustodyTransitionEngine.apply(canonical,
                new CustodyTransition("rp007:cuffs:" + targetKey + ":" + at,
                        CustodyTransition.Action.APPLY_CUFFS, at,
                        issuerUuid == null || issuerUuid.isBlank() ? issuerName : issuerUuid,
                        "", "", StateProvider.NATIVE,
                        reason == null ? "direct" : reason, 0),
                ctx.policies());
        if (!canonicalResult.ok() && !canonicalResult.idempotent()) return false;
        store.states.put(targetKey, canonical);
        var record = new CustodyStore.CuffRecord();
        record.target = target.name();
        record.targetUuid = uuidOf(target);
        record.issuer = issuerName;
        record.issuerUuid = issuerUuid;
        record.cuffedAt = now();
        record.maxDistance = Math.max(8, ctx.policies().cuffBreakDistance);
        record.reason = reason;
        record.originalSelectedSlot = target.selectedSlot();
        hideCuffedHand(target, record);
        store.cuffed.put(targetKey, record);
        if (canonical.condition != PlayerCondition.DOWNED) store.downed.remove(targetKey);
        return true;
    }

    /** Moves the cuffed player's held stack into the record; restored on release. */
    private void hideCuffedHand(PlayerGateway target, CustodyStore.CuffRecord record) {
        if (!ctx.policies().hideHeldItemWhenPossible) return;
        int slot = target.selectedSlot();
        if (slot < 0) return;
        var stack = target.inventory().stackAt(slot);
        if (stack.isEmpty()) return;
        if (record.hiddenItemId == null || record.hiddenItemId.isEmpty()) {
            var taken = target.inventory().extract(slot, stack.count());
            if (taken.isEmpty()) return;
            record.hiddenItemId = taken.id();
            record.hiddenItemCount = taken.count();
            record.hiddenItemData = taken.customData();
            record.originalSelectedSlot = slot;
        }
    }

    private void restoreCuffedHand(CustodyStore store, PlayerGateway target,
                                   CustodyStore.CuffRecord record) {
        if (record.hiddenItemId == null || record.hiddenItemId.isEmpty()) return;
        var spec = new ItemSpec(record.hiddenItemId, record.hiddenItemCount,
                record.hiddenItemData == null ? java.util.Map.of() : record.hiddenItemData,
                record.hiddenItemName == null || record.hiddenItemName.isEmpty() ? null : record.hiddenItemName);
        if (!target.giveVerified(spec)) {
            // Inventory full: keep the item persisted so it is never lost.
            var pending = new CustodyStore.PendingItem();
            pending.itemId = record.hiddenItemId;
            pending.count = record.hiddenItemCount;
            pending.data = record.hiddenItemData;
            pending.name = record.hiddenItemName;
            store.pendingItems.computeIfAbsent(key(target), k -> new ArrayList<>()).add(pending);
            target.tell("Obiectul din mână nu încape în inventar; îl primești înapoi "
                    + "automat când eliberezi un slot.");
        }
        record.hiddenItemId = "";
        record.hiddenItemCount = 0;
        record.hiddenItemData = new java.util.LinkedHashMap<>();
        record.hiddenItemName = "";
    }

    private boolean grantCuffKey(PlayerGateway holder, CustodyStore.CuffRequest request) {
        if (holder != null && holder.isOnline()) {
            if (holder.giveVerified(ItemSpec.of(CUFF_KEY, 1))) {
                holder.tell("Ai primit o Cheie de Cătușe. Ține cheia și fă click dreapta pe persoana încătușată pentru eliberare.");
                return true;
            }
        }
        String holderKey = holder != null ? key(holder)
                : (request != null && !request.issuerUuid.isEmpty() ? request.issuerUuid : "");
        if (holderKey.isEmpty()) return false;
        var store = store();
        store.pendingKeys.merge(holderKey, 1, Integer::sum);
        ctx.custody().write(store);
        return false;
    }

    /** Delivers queued cuff keys; call on player login and inventory-free moments. */
    public void deliverPendingKeys(PlayerGateway player) {
        var store = store();
        Integer count = store.pendingKeys.get(key(player));
        if (count == null || count <= 0) return;
        int delivered = 0;
        while (delivered < count && player.giveVerified(ItemSpec.of(CUFF_KEY, 1))) delivered++;
        if (delivered <= 0) return;
        int remaining = count - delivered;
        if (remaining <= 0) store.pendingKeys.remove(key(player));
        else store.pendingKeys.put(key(player), remaining);
        ctx.custody().write(store);
        player.tell("Ai primit " + delivered + " Cheie/Chei de Cătușe rămase în așteptare.");
    }

    /** Delivers items withheld at cuff release; call on login and from tick. */
    public void deliverPendingItems(PlayerGateway player) {
        var store = store();
        if (deliverPendingItems(store, player)) ctx.custody().write(store);
    }

    private boolean deliverPendingItems(CustodyStore store, PlayerGateway player) {
        var items = store.pendingItems.get(key(player));
        if (items == null || items.isEmpty()) return false;
        boolean changed = false;
        int delivered = 0;
        var iterator = items.iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (item == null || blank(item.itemId) || item.count <= 0) {
                iterator.remove();
                changed = true;
                audit.record("custody_recovery", null, null,
                        player.name(), uuidOf(player), "SUCCESS", "malformed_pending_item");
                continue;
            }
            var spec = new ItemSpec(item.itemId, item.count,
                    item.data == null ? java.util.Map.of() : item.data,
                    item.name == null || item.name.isEmpty() ? null : item.name);
            if (!player.giveVerified(spec)) break;
            iterator.remove();
            delivered++;
        }
        if (items.isEmpty()) store.pendingItems.remove(key(player));
        if (delivered > 0) {
            player.tell("Ai recuperat " + delivered + " obiect(e) reținut(e) în timpul încătușării.");
            return true;
        }
        return changed;
    }

    // ------------------------------------------------------------ recovery

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private CustodyState newCanonicalState(PlayerGateway target) {
        var state = new CustodyState();
        state.playerId = key(target);
        state.playerUuid = uuidOf(target);
        state.playerName = target.name();
        return state;
    }

    /** Builds a canonical state from an older RP-007 downed projection. */
    private CustodyState canonicalFromLegacyDowned(String playerKey,
                                                    CustodyStore.DownedRecord record) {
        var state = new CustodyState();
        state.playerId = playerKey;
        state.playerUuid = record.targetUuid == null ? "" : record.targetUuid;
        state.playerName = record.target == null ? "" : record.target;
        state.condition = PlayerCondition.DOWNED;
        state.custody = CustodyStatus.FREE;
        state.transport = TransportStatus.NONE;
        state.restraint = RestraintStatus.NONE;
        state.vision = VisionStatus.NORMAL;
        state.provider = StateProvider.MIGRATION;
        state.source = record.source == null ? "rp-007" : record.source;
        state.sourceUuid = record.sourceUuid == null ? "" : record.sourceUuid;
        state.enteredAt = record.startedAt > 0 ? record.startedAt : now();
        state.downedDeadlineAt = record.wakesAt;
        state.transitionId = "legacy:downed:" + playerKey + ":" + record.startedAt;
        return state;
    }

    /** Builds a canonical state from an older RP-007 restraint projection. */
    private CustodyState canonicalFromLegacyCuff(String playerKey,
                                                  CustodyStore.CuffRecord record,
                                                  CustodyStore.DownedRecord downed) {
        var state = new CustodyState();
        state.playerId = playerKey;
        state.playerUuid = record.targetUuid == null ? "" : record.targetUuid;
        state.playerName = record.target == null ? "" : record.target;
        state.condition = downed == null
                ? PlayerCondition.CONSCIOUS_RESTRAINED : PlayerCondition.UNCONSCIOUS_CUSTODY;
        state.custody = CustodyStatus.ARRESTED;
        state.transport = TransportStatus.NONE;
        state.restraint = RestraintStatus.CUFFED;
        state.vision = VisionStatus.NORMAL;
        state.provider = StateProvider.MIGRATION;
        state.source = "rp-007:cuffs";
        state.sourceUuid = record.issuerUuid == null ? "" : record.issuerUuid;
        state.enteredAt = record.cuffedAt > 0 ? record.cuffedAt : now();
        state.restraintActorId = record.issuerUuid == null ? record.issuer : record.issuerUuid;
        state.custodyActorId = state.restraintActorId;
        state.jailDeliveryDeadlineAt = deadlineAt(state.enteredAt,
                ctx.policies().jailDeliveryDeadlineSeconds);
        if (downed != null) {
            state.unconsciousCustodyDeadlineAt = deadlineAt(state.enteredAt,
                    ctx.policies().unconsciousCustodyDurationSeconds);
        }
        state.transitionId = "legacy:cuffs:" + playerKey + ":" + record.cuffedAt;
        return state;
    }

    /** Imports old projections once; new flows write canonical state directly. */
    private boolean importLegacyProjections(CustodyStore store) {
        if (store.states == null) store.states = new java.util.LinkedHashMap<>();
        boolean changed = false;
        for (var entry : new ArrayList<>(store.downed.entrySet())) {
            if (store.states.containsKey(entry.getKey()) || malformedDowned(entry.getValue())) continue;
            store.states.put(entry.getKey(), canonicalFromLegacyDowned(entry.getKey(), entry.getValue()));
            changed = true;
        }
        for (var entry : new ArrayList<>(store.cuffed.entrySet())) {
            var record = entry.getValue();
            if (record == null || (blank(record.targetUuid) && blank(record.target))) continue;
            if (!store.states.containsKey(entry.getKey())) {
                store.states.put(entry.getKey(), canonicalFromLegacyCuff(
                        entry.getKey(), record, store.downed.get(entry.getKey())));
                changed = true;
            }
        }
        for (var entry : new ArrayList<>(store.bound.entrySet())) {
            var record = entry.getValue();
            if (store.states.containsKey(entry.getKey())
                    || record == null || (blank(record.targetUuid) && blank(record.target))) continue;
            var state = newCanonicalStateForLegacy(entry.getKey(), record.targetUuid, record.target);
            state.condition = PlayerCondition.CONSCIOUS_RESTRAINED;
            state.custody = CustodyStatus.HOSTAGE;
            state.restraint = RestraintStatus.ROPE_BOUND;
            state.provider = StateProvider.MIGRATION;
            state.source = "rp-007:rope";
            state.sourceUuid = record.issuerUuid == null ? "" : record.issuerUuid;
            state.enteredAt = record.boundAt > 0 ? record.boundAt : now();
            state.restraintActorId = record.issuerUuid == null ? record.issuer : record.issuerUuid;
            state.custodyActorId = state.restraintActorId;
            state.transitionId = "legacy:rope:" + entry.getKey() + ":" + record.boundAt;
            store.states.put(entry.getKey(), state);
            changed = true;
        }
        return changed;
    }

    private CustodyState newCanonicalStateForLegacy(String playerKey, String targetUuid,
                                                      String targetName) {
        var state = new CustodyState();
        state.playerId = playerKey;
        state.playerUuid = targetUuid == null ? "" : targetUuid;
        state.playerName = targetName == null ? "" : targetName;
        state.transport = TransportStatus.NONE;
        state.restraint = RestraintStatus.NONE;
        state.custody = CustodyStatus.FREE;
        state.vision = VisionStatus.NORMAL;
        return state;
    }

    private long deadlineAt(long at, int seconds) {
        long duration = Math.max(1, seconds) * 1000L;
        return Long.MAX_VALUE - at < duration ? Long.MAX_VALUE : at + duration;
    }

    private Map<String, CarrySnapshot> carriedSnapshots(CustodyStore store) {
        importLegacyProjections(store);
        var result = new java.util.LinkedHashMap<String, CarrySnapshot>();
        for (var entry : store.states.entrySet()) {
            var state = entry.getValue();
            if (state != null && state.transport == TransportStatus.CARRIED) {
                result.put(entry.getKey(), new CarrySnapshot(entry.getKey(),
                        state.playerUuid, state.playerName, state.carrierId));
            }
        }
        return result;
    }

    /** Applies the canonical STOP_CARRY transition without touching entities. */
    private boolean stopCarriedState(CustodyStore store, CustodyState state,
                                     String actorId, String reason) {
        long at = now();
        var result = CustodyTransitionEngine.apply(state,
                new CustodyTransition("carry:stop:" + state.playerId + ":" + at + ":"
                        + (reason == null ? "recovery" : reason),
                        CustodyTransition.Action.STOP_CARRY, at,
                        actorId == null || actorId.isBlank() ? "system" : actorId,
                        "", "", StateProvider.SYSTEM,
                        reason == null ? "recovery" : reason, 0),
                ctx.policies());
        return result.ok() && !result.idempotent();
    }

    private boolean stopAllCarried(CustodyStore store, String reason) {
        boolean changed = false;
        for (var entry : new ArrayList<>(store.states.entrySet())) {
            var state = entry.getValue();
            if (state == null || state.transport != TransportStatus.CARRIED) continue;
            var target = findStored(state.playerUuid, state.playerName);
            if (target != null) target.stopRiding();
            if (stopCarriedState(store, state, "system", reason)) {
                audit.record("carry_recovery", "system", "",
                        state.playerName, state.playerUuid, "SUCCESS", reason);
                changed = true;
            }
        }
        return changed;
    }

    private boolean stopCarriesInvolving(CustodyStore store, PlayerGateway player, String reason) {
        if (player == null) return false;
        String playerKey = key(player);
        String playerUuid = uuidOf(player);
        boolean changed = false;
        for (var entry : new ArrayList<>(store.states.entrySet())) {
            var state = entry.getValue();
            if (state == null || state.transport != TransportStatus.CARRIED
                    || (!playerKey.equals(entry.getKey()) && !playerUuid.equals(state.carrierId))) {
                continue;
            }
            var target = findStored(state.playerUuid, state.playerName);
            if (target != null) target.stopRiding();
            if (stopCarriedState(store, state, playerUuid, reason)) {
                audit.record("carry_recovery", player.name(), playerUuid,
                        state.playerName, state.playerUuid, "SUCCESS", reason);
                changed = true;
            }
        }
        return changed;
    }

    /** Reconciles canonical carry state with the physical passenger relation. */
    private boolean reconcileCarried(CustodyStore store, Map<String, CarrySnapshot> before) {
        boolean changed = false;
        for (var snapshot : before.values()) {
            var state = store.states.get(snapshot.targetKey());
            var target = findStored(snapshot.targetUuid(), snapshot.targetName());
            if (state == null || state.transport != TransportStatus.CARRIED) {
                if (target != null) target.stopRiding();
                continue;
            }
            var carrier = findStored(state.carrierId, "");
            boolean physical = target != null && carrier != null
                    && target.dimension().equals(carrier.dimension())
                    && target.isPassengerOf(carrier.uuid())
                    && carrier.hasPassenger(target.uuid());
            if (physical) continue;
            if (target != null) target.stopRiding();
            if (stopCarriedState(store, state, "system", "physical_desync")) {
                audit.record("carry_recovery", "system", "",
                        state.playerName, state.playerUuid, "SUCCESS", "physical_desync");
                changed = true;
            }
        }
        return changed;
    }

    /** Projects canonical terminal/control changes back to RP-007 records. */
    private boolean projectCanonicalStates(CustodyStore store) {
        boolean changed = false;
        for (var entry : new ArrayList<>(store.states.entrySet())) {
            var state = entry.getValue();
            if (state == null) continue;
            if (state.restraint == RestraintStatus.NONE) {
                var target = findStored(state.playerUuid, state.playerName);
                if (store.cuffed.containsKey(entry.getKey())) {
                    clearLegacyCuff(store, entry.getKey(), target);
                    changed = true;
                }
                if (store.bound.remove(entry.getKey()) != null) changed = true;
                if (store.headSacks.remove(entry.getKey()) != null) changed = true;
            }
            var downed = store.downed.get(entry.getKey());
            if (state.condition == PlayerCondition.DEAD) {
                if (downed != null) {
                    var target = findStored(state.playerUuid, state.playerName);
                    if (target != null && target.health() > 0) target.setHealth(0);
                    store.downed.remove(entry.getKey());
                    changed = true;
                }
            } else if (state.condition != PlayerCondition.DOWNED
                    && state.condition != PlayerCondition.RESUSCITATING && downed != null) {
                store.downed.remove(entry.getKey());
                changed = true;
            }
        }
        return changed;
    }

    private void clearLegacyCuff(CustodyStore store, String playerKey, PlayerGateway target) {
        var record = store.cuffed.remove(playerKey);
        if (record == null || blank(record.hiddenItemId)) return;
        if (target != null && target.health() > 0) {
            restoreCuffedHand(store, target, record);
            return;
        }
        var pending = new CustodyStore.PendingItem();
        pending.itemId = record.hiddenItemId;
        pending.count = record.hiddenItemCount;
        pending.data = record.hiddenItemData;
        pending.name = record.hiddenItemName;
        String owner = blank(record.targetUuid) ? playerKey : record.targetUuid;
        store.pendingItems.computeIfAbsent(owner, k -> new ArrayList<>()).add(pending);
    }

    /**
     * Runs the canonical deadline evaluator through the existing custody
     * service tick. Repeating passes are bounded so a persisted state with
     * several overdue independent deadlines converges without creating a
     * second scheduler or timer loop.
     */
    private boolean processCanonicalDeadlines(CustodyStore store) {
        boolean changed = importLegacyProjections(store);
        var activeResuscitations = new java.util.HashSet<String>();
        for (var entry : store.states.entrySet()) {
            if (entry.getValue() != null
                    && entry.getValue().condition == PlayerCondition.RESUSCITATING) {
                activeResuscitations.add(entry.getKey());
            }
        }
        for (int pass = 0; pass < 8; pass++) {
            var sweep = CustodyDeadlineEngine.tick(store, now(), ctx.policies());
            changed |= projectCanonicalStates(store);
            if (!sweep.changed()) break;
            changed = true;
        }
        changed |= projectCanonicalStates(store);
        for (String playerKey : activeResuscitations) {
            var state = store.states.get(playerKey);
            if (state != null && state.condition == PlayerCondition.DOWNED
                    && store.downed.containsKey(playerKey)) {
                var target = findStored(state.playerUuid, state.playerName);
                if (target != null) {
                    target.setHealth(1);
                    target.tell("Resuscitarea a expirat. Ai rămas inconștient.");
                }
                audit.record("resuscitation_timeout", "system", "",
                        state.playerName, state.playerUuid, "SUCCESS", "timeout");
            }
        }
        return changed;
    }

    /** Restart recovery preserves absolute deadlines and resolves anything
     * already due before the first player login event. */
    public void recoverOnRestart() {
        var store = store();
        boolean changed = importLegacyProjections(store);
        if (store.states != null) {
            for (var state : store.states.values()) {
                var recovery = CustodyDeadlineEngine.recover(
                        state, RecoveryEvent.RESTART, now(), ctx.policies());
                changed |= recovery.changed();
            }
        }
        changed |= processCanonicalDeadlines(store);
        changed |= interruptAllResuscitations(store, "restart");
        changed |= stopAllCarried(store, "restart");
        if (changed) ctx.custody().write(store);
    }

    /**
     * Login recovery: revalidates the issuer of any persisted cuff, drops
     * malformed restraint records, reapplies restraint effects through the
     * normal helpers and delivers queued keys/items exactly once.
     */
    public void recoverOnLogin(PlayerGateway player) {
        var store = store();
        String playerKey = key(player);
        var carried = carriedSnapshots(store);
        boolean changed = processCanonicalDeadlines(store);
        changed |= reconcileCarried(store, carried);
        changed |= processResuscitations(store);
        var record = store.cuffed.get(playerKey);
        if (record != null && !issuerStillEligible(record)) {
            recoverCuff(store, record, player);
            changed = true;
        }
        var bound = store.bound.get(playerKey);
        if (bound != null) {
            boolean malformed = blank(bound.targetUuid) && blank(bound.target);
            if (malformed || !issuerEligible(bound.issuer, bound.issuerUuid)) {
                store.bound.remove(playerKey);
                audit.record("custody_recovery", bound.issuer, bound.issuerUuid,
                        player.name(), uuidOf(player), "SUCCESS",
                        malformed ? "malformed_record kind=bound" : "ineligible_issuer kind=bound");
                changed = true;
            }
        }
        var sack = store.headSacks.get(playerKey);
        if (sack != null) {
            boolean malformed = blank(sack.targetUuid) && blank(sack.target);
            boolean badIssuer = !issuerEligible(sack.issuer, sack.issuerUuid);
            boolean orphan = !store.cuffed.containsKey(playerKey)
                    && !store.bound.containsKey(playerKey);
            if (malformed || badIssuer || orphan) {
                store.headSacks.remove(playerKey);
                audit.record("custody_recovery", sack.issuer, sack.issuerUuid,
                        player.name(), uuidOf(player), "SUCCESS",
                        malformed ? "malformed_record kind=head_sack"
                                : badIssuer ? "ineligible_issuer kind=head_sack"
                                : "orphan_head_sack");
                changed = true;
            }
        }
        var downed = store.downed.get(playerKey);
        if (malformedDowned(downed)) {
            store.downed.remove(playerKey);
            audit.record("custody_recovery", null, null,
                    player.name(), uuidOf(player), "SUCCESS", "malformed_record kind=downed");
            changed = true;
        }
        if (changed) ctx.custody().write(store);
        if (store.cuffed.containsKey(playerKey)) applyCuffSlowness(player);
        if (store.bound.containsKey(playerKey)) {
            player.applyEffect("minecraft:slowness",
                    Math.max(20, ctx.policies().ropeSlownessTicks), ctx.policies().ropeSlownessAmplifier);
        }
        if (store.headSacks.containsKey(playerKey)) {
            player.applyEffect("minecraft:blindness",
                    Math.max(20, ctx.policies().headSackBlindnessTicks), 0);
        }
        if (store.downed.containsKey(playerKey)) {
            player.applyEffect("minecraft:slowness",
                    Math.max(20, ctx.policies().downedSlownessTicks), ctx.policies().downedSlownessAmplifier);
        }
        deliverPendingKeys(player);
        deliverPendingItems(player);
    }

    /** Logout recovery: drops only transient cuff/surrender requests. */
    public void recoverOnLogout(PlayerGateway player) {
        var store = store();
        boolean changed = importLegacyProjections(store);
        changed |= interruptResuscitationsInvolving(store, player, "logout");
        changed |= stopCarriesInvolving(store, player, "logout");
        if (store.states != null) {
            var state = store.states.get(key(player));
            if (state != null) {
                var recovery = CustodyDeadlineEngine.recover(
                        state, RecoveryEvent.LOGOUT, now(), ctx.policies());
                changed |= recovery.changed();
            }
        }
        var discarded = new ArrayList<String>();
        store.cuffRequests.values().removeIf(request -> {
            if (request == null) return false;
            boolean mine = matches(player, request.targetUuid, request.target)
                    || matches(player, request.issuerUuid, request.issuer);
            if (mine) discarded.add(request.id);
            return mine;
        });
        changed |= !discarded.isEmpty();
        if (!changed) return;
        ctx.custody().write(store);
        for (String id : discarded) {
            audit.record("custody_request_discard", player.name(), uuidOf(player),
                    "", "", "SUCCESS", "logout requestId=" + id);
        }
    }

    /** A dimension transfer ends carry and restores the target's own timer. */
    public void recoverOnDimensionChange(PlayerGateway player) {
        var store = store();
        boolean changed = importLegacyProjections(store);
        changed |= interruptResuscitationsInvolving(store, player, "dimension_change");
        changed |= stopCarriesInvolving(store, player, "dimension_change");
        changed |= processCanonicalDeadlines(store);
        if (changed) ctx.custody().write(store);
    }

    /**
     * Death recovery: ends all restraint state for the victim and queues any
     * hidden item by UUID — nothing is injected into the dying inventory.
     * Idempotent: a second call finds nothing to change.
     */
    public void recoverAfterDeath(PlayerGateway player) {
        var store = store();
        String playerKey = key(player);
        boolean changed = interruptResuscitationsInvolving(store, player, "death");
        changed |= stopCarriesInvolving(store, player, "death");
        var canonical = store.states.get(playerKey);
        if (canonical != null && canonical.condition != PlayerCondition.DEAD) {
            long at = now();
            var result = CustodyTransitionEngine.apply(canonical,
                    new CustodyTransition("rp007:death:" + playerKey + ":" + at,
                            CustodyTransition.Action.DIE, at, uuidOf(player),
                            "", "", StateProvider.NATIVE, "death_event", 0),
                    ctx.policies());
            if (result.ok()) changed = true;
        }
        var record = store.cuffed.remove(playerKey);
        if (record != null) {
            changed = true;
            if (!blank(record.hiddenItemId)) {
                var pending = new CustodyStore.PendingItem();
                pending.itemId = record.hiddenItemId;
                pending.count = record.hiddenItemCount;
                pending.data = record.hiddenItemData;
                pending.name = record.hiddenItemName;
                store.pendingItems.computeIfAbsent(
                        !blank(record.targetUuid) ? record.targetUuid : playerKey,
                        k -> new ArrayList<>()).add(pending);
            }
        }
        if (store.bound.remove(playerKey) != null) changed = true;
        if (store.headSacks.remove(playerKey) != null) changed = true;
        if (store.downed.remove(playerKey) != null) changed = true;
        var discarded = new ArrayList<String>();
        if (store.cuffRequests.values().removeIf(request -> {
            if (request == null) return false;
            boolean mine = matches(player, request.targetUuid, request.target)
                    || matches(player, request.issuerUuid, request.issuer);
            if (mine) discarded.add(request.id);
            return mine;
        })) {
            changed = true;
        }
        if (!changed) return;
        ctx.custody().write(store);
        audit.record("custody_death_recovery", player.name(), uuidOf(player),
                player.name(), uuidOf(player), "SUCCESS",
                "restraint_cleared discardedRequests=" + discarded);
    }

    // ------------------------------------------------------------ release

    private boolean isGenericKey(ItemView stack) {
        if (stack.isEmpty()) return false;
        String id = stack.id().toLowerCase();
        for (String token : ctx.policies().genericKeyTokens) {
            if (id.contains(token.toLowerCase())) return true;
        }
        return false;
    }

    private String releaseToolKind(ItemView held) {
        if (held == null || held.isEmpty()) return "NONE";
        if (CUFF_KEY.equals(held.id())) return "KEY";
        if (CROWBAR.equals(held.id()) || BOLT_CUTTERS.equals(held.id())) return "FANTASY_CUTTERS";
        if (KEYCHAIN.equals(held.id())) return "KEYCHAIN";
        if (isGenericKey(held)) return "GENERIC_KEY";
        return "NONE";
    }

    public boolean releaseById(PlayerGateway issuer, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            issuer.tell("Alege persoana încătușată online.");
            return false;
        }
        var target = ctx.server().findPlayer(targetId);
        if (target == null) {
            issuer.tell("Alege persoana încătușată online.");
            return false;
        }
        return release(issuer, target);
    }

    /** Keeps canonical custody in lockstep with the legacy release projection. */
    private boolean releaseCanonicalRestraint(CustodyStore store, PlayerGateway actor,
                                              PlayerGateway target, boolean leaveRope) {
        importLegacyProjections(store);
        var state = store.states.get(key(target));
        if (state == null || state.restraint == RestraintStatus.NONE) return true;
        if (leaveRope && !ctx.policies().criminalRopeEnabled) return false;
        long at = now();
        var released = CustodyTransitionEngine.apply(state,
                new CustodyTransition("rp007:release:" + key(target) + ":" + at,
                        CustodyTransition.Action.RELEASE_RESTRAINT, at,
                        uuidOf(actor), "", "", StateProvider.NATIVE, "release", 0),
                ctx.policies());
        if (!released.ok() && !released.idempotent()) return false;
        if (!leaveRope) return true;
        var rope = store.bound.get(key(target));
        if (rope == null) return true;
        var roped = CustodyTransitionEngine.apply(state,
                new CustodyTransition("rp007:rope-after-release:" + key(target) + ":" + at,
                        CustodyTransition.Action.APPLY_ROPE, at,
                        uuidOf(actor), "", "", StateProvider.NATIVE, "release", 0),
                ctx.policies());
        return roped.ok() || roped.idempotent();
    }

    private void recoverCanonicalRestraint(CustodyStore store, String playerKey, String source) {
        var state = store.states.get(playerKey);
        if (state == null || state.restraint == RestraintStatus.NONE) return;
        long at = now();
        CustodyTransitionEngine.apply(state,
                new CustodyTransition("rp007:recover-restraint:" + playerKey + ":" + at,
                        CustodyTransition.Action.RECOVER_RELEASE_RESTRAINTS, at,
                        "system", "", "", StateProvider.SYSTEM,
                        source == null ? "recovery" : source, 0),
                ctx.policies());
    }

    public boolean release(PlayerGateway issuer, PlayerGateway target) {
        if (target == null) {
            issuer.tell("Alege persoana încătușată.");
            return false;
        }
        if (PlayerService.canon(issuer.name()).equals(PlayerService.canon(target.name()))) {
            issuer.tell("Cătușele pot fi rupte doar de alt jucător.");
            return false;
        }
        String kind = releaseToolKind(issuer.mainHand());
        if ("NONE".equals(kind)) {
            issuer.tell("Ține o Cheie, Foarfeca sau Brelocul Temnicerului în mâna principală.");
            return false;
        }
        var store = store();
        String targetKey = key(target);
        var record = store.cuffed.get(targetKey);
        var boundRecord = store.bound.get(targetKey);
        if (record == null && boundRecord == null) {
            issuer.tell(target.name() + " nu este încătușat.");
            return false;
        }
        if (("KEY".equals(kind) || "GENERIC_KEY".equals(kind) || "KEYCHAIN".equals(kind)) && record == null) {
            issuer.tell("Cheia poate desface cătușele, dar nu poate tăia frânghia.");
            return false;
        }
        if (("KEY".equals(kind) || "GENERIC_KEY".equals(kind)) && consumeMainHand(issuer).isEmpty()) {
            issuer.tell("Cheia nu a putut fi consumată; eliberarea a fost anulată.");
            return false;
        }
        boolean openedCuffs = record != null;
        boolean cutRope = boundRecord != null && "FANTASY_CUTTERS".equals(kind);
        if (!releaseCanonicalRestraint(store, issuer, target, boundRecord != null && !cutRope)) {
            issuer.tell("Eliberarea a fost refuzată: starea de custodie nu este validă.");
            return false;
        }
        if (record != null) {
            restoreCuffedHand(store, target, record);
            store.cuffed.remove(targetKey);
        }
        if (cutRope) store.bound.remove(targetKey);
        ctx.custody().write(store);
        target.tell(openedCuffs && cutRope ? "Cătușele și frânghia au fost tăiate de " + issuer.name() + "."
                : cutRope ? "Frânghia a fost tăiată de " + issuer.name() + "."
                : "Cătușele au fost deschise de " + issuer.name() + " cu " + kind.toLowerCase() + ".");
        issuer.tell(target.name() + (openedCuffs && cutRope ? " a fost eliberat complet."
                : cutRope ? " nu mai este legat." : " nu mai este încătușat."));
        audit.record("restraint_release", issuer.name(), uuidOf(issuer),
                target.name(), uuidOf(target), "SUCCESS",
                kind.toLowerCase() + " openedCuffs=" + openedCuffs + " cutRope=" + cutRope);
        return true;
    }

    public boolean emergencyRelease(PlayerGateway actor, PlayerGateway target) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate folosi eliberarea de urgență.");
            return false;
        }
        if (target == null) {
            actor.tell("Alege persoana încătușată.");
            return false;
        }
        var store = store();
        String targetKey = key(target);
        var record = store.cuffed.get(targetKey);
        if (record == null) {
            actor.tell(target.name() + " nu este încătușat.");
            return false;
        }
        if (!releaseCanonicalRestraint(store, actor, target, false)) {
            actor.tell("Eliberarea a fost refuzată: starea de custodie nu este validă.");
            return false;
        }
        store.cuffed.remove(targetKey);
        restoreCuffedHand(store, target, record);
        store.bound.remove(targetKey);
        var sack = store.headSacks.remove(targetKey);
        if (sack != null) target.tell("Sacul de Captiv a fost îndepărtat.");
        store.pendingKeys.remove(record.issuerUuid.isEmpty()
                ? PlayerService.canon(record.issuer) : record.issuerUuid);
        ctx.custody().write(store);
        target.tell("Cătușele au fost eliberate de Comisaru'.");
        actor.tell(target.name() + " a fost eliberat de urgență.");
        audit.record("cuff_emergency_release", actor.name(), uuidOf(actor),
                target.name(), uuidOf(target), "SUCCESS", "commissioner_override");
        return true;
    }

    public boolean giveCuffs(PlayerGateway player) {
        if (!canIssueCuffs(player)) {
            player.tell("Cătușele sunt disponibile de la rangul Străjer în sus.");
            return false;
        }
        if (!player.giveVerified(ItemSpec.of(CUFFS, 1))) {
            player.tell("Cătușele nu au putut fi predate. Eliberează un slot și încearcă din nou.");
            return false;
        }
        player.tell("Ai primit Cătușe Straja. Itemul este reutilizabil și nu se consumă.");
        return true;
    }

    // ------------------------------------------------------------ rope & sack

    public boolean applyRope(PlayerGateway issuer, PlayerGateway target) {
        if (!enforcementGuard(issuer)) {
            issuer.tell("Doar un străjer activ poate folosi frânghia de imobilizare.");
            return false;
        }
        var policy = cuffTargetPolicy(issuer, target);
        if (!policy.ok()) {
            policyDeny(issuer, policy);
            return false;
        }
        var store = store();
        if (ctx.policies().ropeRequiresCuffs && !store.cuffed.containsKey(key(target))) {
            issuer.tell("Frânghia se aplică doar unui suspect deja încătușat.");
            return false;
        }
        if (!issuer.mainHand().id().equals(ROPE)) {
            issuer.tell("Ține Frânghia de Imobilizare în mâna principală.");
            return false;
        }
        if (store.bound.containsKey(key(target))) {
            issuer.tell(target.name() + " este deja legat.");
            return true;
        }
        if (consumeMainHand(issuer).isEmpty()) {
            issuer.tell("Frânghia nu a putut fi consumată; legarea a fost anulată.");
            return false;
        }
        var record = new CustodyStore.BoundRecord();
        record.target = target.name();
        record.targetUuid = uuidOf(target);
        record.issuer = issuer.name();
        record.issuerUuid = uuidOf(issuer);
        record.boundAt = now();
        store.bound.put(key(target), record);
        ctx.custody().write(store);
        target.applyEffect("minecraft:slowness",
                Math.max(20, ctx.policies().ropeSlownessTicks), ctx.policies().ropeSlownessAmplifier);
        target.tell("Ai fost legat cu Frânghia de Imobilizare. Nu poți folosi inventarul sau obiectele.");
        issuer.tell(target.name() + " a fost legat. Frânghia nu oferă cheie și poate fi tăiată doar cu foarfeca.");
        audit.record("rope_apply", issuer.name(), uuidOf(issuer),
                target.name(), uuidOf(target), "SUCCESS", record.reason);
        return true;
    }

    public boolean applyHeadSack(PlayerGateway issuer, PlayerGateway target) {
        if (!enforcementGuard(issuer)) {
            issuer.tell("Doar un străjer activ poate pune Sacul de Captiv.");
            return false;
        }
        var policy = cuffTargetPolicy(issuer, target);
        if (!policy.ok()) {
            policyDeny(issuer, policy);
            return false;
        }
        var store = store();
        String targetKey = key(target);
        if (!store.cuffed.containsKey(targetKey) && !store.bound.containsKey(targetKey)) {
            issuer.tell("Sacul se poate pune doar unui suspect încătușat sau legat.");
            return false;
        }
        if (!issuer.mainHand().id().equals(HEAD_SACK)) {
            issuer.tell("Ține Sacul de Captiv în mâna principală.");
            return false;
        }
        if (store.headSacks.containsKey(targetKey)) {
            issuer.tell(target.name() + " are deja sacul pe cap.");
            return true;
        }
        var record = new CustodyStore.HeadSackRecord();
        record.target = target.name();
        record.targetUuid = uuidOf(target);
        record.issuer = issuer.name();
        record.issuerUuid = uuidOf(issuer);
        record.appliedAt = now();
        store.headSacks.put(targetKey, record);
        ctx.custody().write(store);
        target.applyEffect("minecraft:blindness",
                Math.max(20, ctx.policies().headSackBlindnessTicks), 0);
        target.tell("Ți s-a pus Sacul de Captiv. Pentru îndepărtare, cere ajutorul temnicerului sau al Comisarului.");
        issuer.tell(target.name() + " poartă acum Sacul de Captiv.");
        audit.record("head_sack_apply", issuer.name(), uuidOf(issuer),
                target.name(), uuidOf(target), "SUCCESS", "restrained_target");
        return true;
    }

    public boolean removeHeadSack(PlayerGateway player) {
        var store = store();
        String playerKey = key(player);
        var record = store.headSacks.get(playerKey);
        if (record == null) {
            player.tell("Nu ai Sacul de Captiv pe cap.");
            return false;
        }
        if (store.downed.containsKey(playerKey)) {
            player.tell("Ești inconștient și nu poți da jos sacul încă.");
            return false;
        }
        store.headSacks.remove(playerKey);
        ctx.custody().write(store);
        player.tell("Ți-ai dat jos Sacul de Captiv.");
        audit.record("head_sack_remove", player.name(), uuidOf(player),
                player.name(), uuidOf(player), "SUCCESS", "self_remove issuer=" + record.issuer);
        return true;
    }

    // ------------------------------------------------------------ downed

    public CustodyStore.DownedRecord startDowned(PlayerGateway player, PlayerGateway source, String reason) {
        if (!ctx.policies().downedEnabled) return null;
        var store = store();
        String playerKey = key(player);
        var existing = store.downed.get(playerKey);
        if (existing != null) {
            if (!store.states.containsKey(playerKey) && !malformedDowned(existing)) {
                store.states.put(playerKey, canonicalFromLegacyDowned(playerKey, existing));
                ctx.custody().write(store);
            }
            return existing;
        }
        long startedAt = now();
        CustodyState canonical = store.states.get(playerKey);
        if (canonical == null) canonical = newCanonicalState(player);
        var downedAction = canonical.condition == PlayerCondition.CONSCIOUS_RESTRAINED
                ? CustodyTransition.Action.ENTER_UNCONSCIOUS_CUSTODY
                : CustodyTransition.Action.ENTER_DOWNED;
        var canonicalResult = CustodyTransitionEngine.apply(canonical,
                new CustodyTransition("rp007:downed:" + playerKey + ":" + startedAt,
                        downedAction, startedAt,
                        source == null ? "system" : uuidOf(source),
                        "", "", StateProvider.NATIVE,
                        reason == null ? "knockout" : reason, 0),
                ctx.policies());
        if (!canonicalResult.ok() && !canonicalResult.idempotent()) return null;
        store.states.put(playerKey, canonical);
        var record = new CustodyStore.DownedRecord();
        record.target = player.name();
        record.targetUuid = uuidOf(player);
        record.dimension = player.dimension();
        record.x = player.x();
        record.y = player.y();
        record.z = player.z();
        record.startedAt = startedAt;
        long cooldown = Math.max(5, ctx.policies().downedCooldownSeconds) * 1000L;
        record.wakesAt = startedAt + cooldown;
        record.source = source == null ? "" : source.name();
        record.sourceUuid = source == null ? "" : uuidOf(source);
        record.reason = reason == null ? "knockout" : reason;
        store.downed.put(playerKey, record);
        ctx.custody().write(store);
        player.setHealth(Math.max(1, Math.min(player.maxHealth(), 1)));
        player.closeMenu();
        player.applyEffect("minecraft:slowness",
                Math.max(20, ctx.policies().downedSlownessTicks), ctx.policies().downedSlownessAmplifier);
        player.tell("Ai leșinat. Te vei trezi peste " + (cooldown / 1000) + " secunde în același loc.");
        if (source != null) source.tell(player.name() + " a leșinat și nu poate fi ucis de mecanica Străjii.");
        audit.record("downed_start", record.source, record.sourceUuid,
                player.name(), record.targetUuid, "SUCCESS", record.reason);
        return record;
    }

    public boolean wakeDowned(PlayerGateway player, String reason) {
        var store = store();
        String playerKey = key(player);
        var record = store.downed.get(playerKey);
        if (record == null) return false;
        player.teleport(record.dimension, record.x, record.y, record.z);
        double ratio = Math.max(0.1, Math.min(1, ctx.policies().downedWakeHealthRatio));
        player.setHealth(Math.max(1, player.maxHealth() * ratio));
        player.closeMenu();
        player.tell("Ți-ai revenit. Poți acționa din nou.");
        store.downed.remove(playerKey);
        ctx.custody().write(store);
        audit.record("downed_wake", player.name(), uuidOf(player),
                player.name(), uuidOf(player), "SUCCESS",
                (reason == null ? "cooldown_complete" : reason));
        return true;
    }

    /** Consumes the downed state when an authorized transport happens. */
    public boolean resolveDowned(PlayerGateway player, String destination) {
        var store = store();
        String playerKey = key(player);
        var record = store.downed.get(playerKey);
        if (record == null) return false;
        var canonical = store.states.get(playerKey);
        if (canonical != null && "PRISON".equals(destination)) {
            long at = now();
            CustodyTransition.Action action = canonical.condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                    && canonical.custody == CustodyStatus.ARRESTED
                    ? CustodyTransition.Action.DELIVER_TO_JAIL
                    : CustodyTransition.Action.RECOVER_CLEAR_ALL;
            var transition = new CustodyTransition(
                    "rp007:resolve:" + playerKey + ":" + at,
                    action, at, "system", "", "prison",
                    StateProvider.SYSTEM, "prison", 0);
            var result = CustodyTransitionEngine.apply(canonical, transition, ctx.policies());
            if (!result.ok() && !result.idempotent()) return false;
        }
        store.downed.remove(playerKey);
        ctx.custody().write(store);
        player.closeMenu();
        audit.record("downed_transport", player.name(), uuidOf(player),
                player.name(), uuidOf(player), "SUCCESS",
                "destination=" + destination + " reason=" + record.reason);
        return true;
    }

    /** Starts one server-authorized carry using the canonical state machine. */
    @Override
    public boolean startCarry(PlayerGateway carrier, PlayerGateway target) {
        if (carrier == null || target == null || !carrier.isOnline() || !target.isOnline()) {
            if (carrier != null) carrier.tell("Transportul cere doi jucători online.");
            return false;
        }
        if (key(carrier).equals(key(target))) {
            carrier.tell("Nu te poți transporta singur.");
            return false;
        }
        if (actionBlocked(carrier, "carry")) return false;
        if (carrier.isPassenger() || carrier.hasPassenger(target.uuid()) || target.isPassenger()) {
            carrier.tell("Transportul nu poate fi suprapus peste alt transport.");
            return false;
        }

        var store = store();
        importLegacyProjections(store);
        String targetKey = key(target);
        var state = store.states.get(targetKey);
        if (state == null || (state.condition != PlayerCondition.DOWNED
                && state.condition != PlayerCondition.UNCONSCIOUS_CUSTODY
                && state.condition != PlayerCondition.CONSCIOUS_RESTRAINED)) {
            carrier.tell("Poți transporta doar un jucător inconștient sau imobilizat.");
            return false;
        }
        if (state.transport != TransportStatus.NONE) {
            carrier.tell("Ținta este deja transportată.");
            return false;
        }

        long at = now();
        var transition = new CustodyTransition(
                "carry:start:" + targetKey + ":" + at + ":" + uuidOf(carrier),
                CustodyTransition.Action.START_CARRY, at, uuidOf(carrier), uuidOf(carrier),
                "", StateProvider.NATIVE, "carry", 0);
        var result = CustodyTransitionEngine.apply(state, transition, ctx.policies());
        if (!result.ok() && !result.idempotent()) {
            carrier.tell("Transportul nu poate începe: " + result.code());
            return false;
        }
        if (!target.startRiding(carrier.uuid())) {
            stopCarriedState(store, state, uuidOf(carrier), "mount_failed");
            ctx.custody().write(store);
            carrier.tell("Transportul nu a putut fi sincronizat cu serverul.");
            audit.record("carry_start", carrier.name(), uuidOf(carrier),
                    target.name(), uuidOf(target), "REFUSED", "mount_failed");
            return false;
        }

        ctx.custody().write(store);
        target.tell("Ești transportat de " + carrier.name()
                + ". Timpul tău de inconștiență este pus pe pauză temporar.");
        carrier.tell("Transporți pe " + target.name() + ".");
        audit.record("carry_start", carrier.name(), uuidOf(carrier),
                target.name(), uuidOf(target), "SUCCESS",
                "deadline=" + state.transportDeadlineAt);
        return true;
    }

    /** Drops a carried target, or ends carry from a lifecycle recovery path. */
    @Override
    public boolean dropCarry(PlayerGateway carrier, PlayerGateway target, String reason) {
        if (target == null) return false;
        var store = store();
        importLegacyProjections(store);
        String targetKey = key(target);
        var state = store.states.get(targetKey);
        if (state == null || state.transport != TransportStatus.CARRIED) return false;
        String expectedCarrier = state.carrierId == null ? "" : state.carrierId;
        if (carrier != null && !expectedCarrier.equals(uuidOf(carrier))) {
            carrier.tell("Nu ești persoana care transportă această țintă.");
            return false;
        }
        if (target.isOnline()) target.stopRiding();
        boolean changed = stopCarriedState(store, state,
                carrier == null ? "system" : uuidOf(carrier),
                reason == null ? "manual_drop" : reason);
        if (!changed) return false;
        ctx.custody().write(store);
        target.tell("Ai fost lăsat jos și revii la starea ta de custodie.");
        if (carrier != null) carrier.tell("Ai lăsat jos pe " + target.name() + ".");
        audit.record("carry_stop", carrier == null ? "system" : carrier.name(),
                carrier == null ? "" : uuidOf(carrier), target.name(), uuidOf(target),
                "SUCCESS", reason == null ? "manual_drop" : reason);
        return true;
    }

    /** Starts a bounded, server-authorized resuscitation of a nearby downed player. */
    @Override
    public boolean startResuscitation(PlayerGateway rescuer, PlayerGateway target) {
        if (rescuer == null || target == null || !rescuer.isOnline() || !target.isOnline()) {
            if (rescuer != null) rescuer.tell("Resuscitarea cere doi jucători online.");
            return false;
        }
        if (key(rescuer).equals(key(target))) {
            rescuer.tell("Nu te poți resuscita singur.");
            return false;
        }
        if (actionBlocked(rescuer, "resuscitate")) return false;
        if (!nearby(rescuer, target)) {
            rescuer.tell("Trebuie să fii lângă persoana leșinată pentru a o resuscita.");
            return false;
        }

        var store = store();
        importLegacyProjections(store);
        String targetKey = key(target);
        CustodyState state = store.states.get(targetKey);
        if (state == null || state.condition != PlayerCondition.DOWNED
                || state.custody != CustodyStatus.FREE
                || state.restraint != RestraintStatus.NONE
                || state.transport != TransportStatus.NONE
                || state.resuscitatorId != null && !state.resuscitatorId.isBlank()) {
            rescuer.tell("Această persoană nu poate fi resuscitată acum.");
            return false;
        }
        if (!downedAtRecordedLocation(target, store)) {
            rescuer.tell("Persoana leșinată nu mai este la locul în care a căzut.");
            return false;
        }
        long at = now();
        var result = CustodyTransitionEngine.apply(state,
                new CustodyTransition("resus:start:" + targetKey + ":" + at + ":" + uuidOf(rescuer),
                        CustodyTransition.Action.START_RESUSCITATION, at, uuidOf(rescuer),
                        "", "", StateProvider.NATIVE, "resuscitation", 0),
                ctx.policies());
        if (!result.ok() && !result.idempotent()) {
            rescuer.tell("Resuscitarea nu poate începe: " + result.code());
            return false;
        }
        store.states.put(targetKey, state);
        ctx.custody().write(store);
        target.setHealth(1);
        target.closeMenu();
        target.tell("Resuscitarea a început. Nu te mișca și așteaptă ajutorul.");
        rescuer.tell("Resuscitezi pe " + target.name() + ". Rămâi aproape până la final.");
        audit.record("resuscitation_start", rescuer.name(), uuidOf(rescuer),
                target.name(), uuidOf(target), "SUCCESS",
                "deadline=" + state.resuscitationDeadlineAt);
        return true;
    }

    /** Advances resuscitation progress; completion restores controlled health exactly once. */
    @Override
    public boolean advanceResuscitation(PlayerGateway rescuer, PlayerGateway target, int progress) {
        if (rescuer == null || target == null || !rescuer.isOnline() || !target.isOnline()) return false;
        if (progress < 0 || progress > 100) return false;
        var store = store();
        importLegacyProjections(store);
        String targetKey = key(target);
        CustodyState state = store.states.get(targetKey);
        if (state == null || state.condition != PlayerCondition.RESUSCITATING
                || !uuidOf(rescuer).equals(state.resuscitatorId)) return false;
        String interruption = resuscitationInterruptionReason(rescuer, target, store, state);
        if (interruption != null) {
            interruptResuscitation(store, state, target, rescuer, interruption);
            ctx.custody().write(store);
            return false;
        }

        long at = now();
        var result = CustodyTransitionEngine.apply(state,
                new CustodyTransition("resus:advance:" + targetKey + ":" + at + ":" + progress,
                        CustodyTransition.Action.ADVANCE_RESUSCITATION, at, uuidOf(rescuer),
                        "", "", StateProvider.NATIVE, "resuscitation", progress),
                ctx.policies());
        if (!result.ok() && !result.idempotent()) return false;
        boolean completed = state.condition == PlayerCondition.ALIVE;
        if (completed) {
            store.downed.remove(targetKey);
            double ratio = Math.max(0.1, Math.min(1, ctx.policies().downedWakeHealthRatio));
            target.setHealth(Math.max(1, target.maxHealth() * ratio));
            target.closeMenu();
            target.tell("Resuscitarea a reușit. Poți acționa din nou.");
            rescuer.tell(target.name() + " și-a revenit.");
            audit.record("resuscitation_complete", rescuer.name(), uuidOf(rescuer),
                    target.name(), uuidOf(target), "SUCCESS", "progress=" + progress);
        }
        ctx.custody().write(store);
        return true;
    }

    private boolean nearby(PlayerGateway first, PlayerGateway second) {
        if (!first.dimension().equals(second.dimension())) return false;
        double dx = first.x() - second.x();
        double dy = first.y() - second.y();
        double dz = first.z() - second.z();
        return dx * dx + dy * dy + dz * dz <= RESUSCITATION_DISTANCE_BLOCKS
                * RESUSCITATION_DISTANCE_BLOCKS;
    }

    private boolean downedAtRecordedLocation(PlayerGateway target, CustodyStore store) {
        var record = store.downed.get(key(target));
        return record != null && target.dimension().equals(record.dimension)
                && Math.abs(target.x() - record.x) + Math.abs(target.y() - record.y)
                + Math.abs(target.z() - record.z) <= 0.05;
    }

    private String resuscitationInterruptionReason(PlayerGateway rescuer, PlayerGateway target,
                                                    CustodyStore store, CustodyState state) {
        if (!rescuer.isOnline()) return "rescuer_logout";
        if (!target.isOnline()) return "target_logout";
        if (!nearby(rescuer, target)) return "distance_or_dimension";
        if (!downedAtRecordedLocation(target, store)) return "target_moved";
        return null;
    }

    /** Interrupts one active resuscitation and resumes the paused downed timer. */
    private boolean interruptResuscitation(CustodyStore store, CustodyState state,
                                           PlayerGateway target, PlayerGateway rescuer,
                                           String reason) {
        long at = now();
        var result = CustodyTransitionEngine.apply(state,
                new CustodyTransition("resus:interrupt:" + state.playerId + ":" + at + ":"
                        + (reason == null ? "recovery" : reason),
                        CustodyTransition.Action.INTERRUPT_RESUSCITATION, at, "system",
                        "", "", StateProvider.SYSTEM, reason == null ? "recovery" : reason, 0),
                ctx.policies());
        if (!result.ok() && !result.idempotent()) return false;
        if (target != null && target.isOnline()) {
            target.setHealth(1);
            target.tell("Resuscitarea a fost întreruptă. Ai rămas inconștient.");
        }
        if (rescuer != null && rescuer.isOnline()) {
            rescuer.tell("Resuscitarea a fost întreruptă: " + reason + ".");
        }
        audit.record("resuscitation_interrupt",
                rescuer == null ? "system" : rescuer.name(),
                rescuer == null ? "" : uuidOf(rescuer),
                state.playerName, state.playerUuid, "SUCCESS", reason);
        return true;
    }

    private boolean interruptResuscitationsInvolving(CustodyStore store, PlayerGateway player,
                                                      String reason) {
        if (player == null) return false;
        String playerKey = key(player);
        String playerUuid = uuidOf(player);
        boolean changed = false;
        for (var entry : new ArrayList<>(store.states.entrySet())) {
            var state = entry.getValue();
            if (state == null || state.condition != PlayerCondition.RESUSCITATING
                    || (!playerKey.equals(entry.getKey()) && !playerUuid.equals(state.resuscitatorId))) {
                continue;
            }
            var target = findStored(state.playerUuid, state.playerName);
            var rescuer = findStored(state.resuscitatorId, "");
            if (interruptResuscitation(store, state, target, rescuer, reason)) changed = true;
        }
        return changed;
    }

    private boolean interruptAllResuscitations(CustodyStore store, String reason) {
        boolean changed = false;
        for (var state : new ArrayList<>(store.states.values())) {
            if (state != null && state.condition == PlayerCondition.RESUSCITATING) {
                var target = findStored(state.playerUuid, state.playerName);
                var rescuer = findStored(state.resuscitatorId, "");
                if (interruptResuscitation(store, state, target, rescuer, reason)) changed = true;
            }
        }
        return changed;
    }

    private boolean processResuscitations(CustodyStore store) {
        boolean changed = false;
        for (var state : new ArrayList<>(store.states.values())) {
            if (state == null || state.condition != PlayerCondition.RESUSCITATING) continue;
            var target = findStored(state.playerUuid, state.playerName);
            var rescuer = findStored(state.resuscitatorId, "");
            String reason = target == null || rescuer == null
                    ? "participant_unavailable"
                    : resuscitationInterruptionReason(rescuer, target, store, state);
            if (reason != null && interruptResuscitation(store, state, target, rescuer, reason)) {
                changed = true;
                continue;
            }
            if (target != null) {
                target.setHealth(1);
                target.closeMenu();
                target.applyEffect("minecraft:slowness",
                        Math.max(20, ctx.policies().downedSlownessTicks),
                        ctx.policies().downedSlownessAmplifier);
            }
        }
        return changed;
    }

    /**
     * Baton strike semantics. The event adapter computes whether the incoming
     * damage would be lethal; this returns what the adapter must do.
     */
    public DamageDecision batonStrike(PlayerGateway issuer, PlayerGateway target,
                                      double targetHealth, double targetAbsorption, double damage) {
        var held = issuer.mainHand();
        if (held.isEmpty() || !BATON.equals(held.id())) {
            return new DamageDecision(DamageAction.NOT_BATON, "not_baton");
        }
        if (!enforcementGuard(issuer)) {
            issuer.tell("Bastonul poate fi folosit doar de un străjer activ.");
            return new DamageDecision(DamageAction.CANCEL, "issuer_not_active_guard");
        }
        if (!players.hasCapability(issuer, Capability.USE_BATON)) {
            issuer.tell("Bastonul de Poliție se folosește de la rangul Străjer în sus.");
            return new DamageDecision(DamageAction.CANCEL, "issuer_rank_not_authorized");
        }
        if (isCuffed(target)) {
            issuer.tell(target.name() + " este deja încătușat; bastonul nu mai poate porni un al doilea flow.");
            return new DamageDecision(DamageAction.CANCEL, "already_cuffed");
        }
        if (isBound(target)) {
            issuer.tell(target.name() + " este deja legat; bastonul nu mai poate porni un al doilea flow.");
            return new DamageDecision(DamageAction.CANCEL, "already_bound");
        }
        if (isDowned(target)) {
            issuer.tell(target.name() + " este deja inconștient. Nu mai poate primi lovituri.");
            audit.record("baton_knockout", issuer.name(), uuidOf(issuer),
                    target.name(), uuidOf(target), "REFUSED", "already_downed");
            return new DamageDecision(DamageAction.CANCEL, "already_downed");
        }
        boolean lethal = targetHealth > 0 && damage >= targetHealth + targetAbsorption;
        if (!lethal) {
            // Non-lethal hit passes through; the adapter caps damage so a baton
            // hit alone can never drop the target below 1 health.
            return new DamageDecision(DamageAction.ALLOW_NONLETHAL, "nonlethal");
        }
        // Lethal strike is converted into a knockout.
        target.setHealth(1);
        startDowned(target, issuer, "baton_lethal_hit");
        if (!hasItem(issuer, CUFFS)) {
            issuer.tell("Lovitura a fost un knockout, dar nu ai Cătușe în inventar; ținta rămâne inconștientă.");
            target.tell("Bastonul te-a doborât, dar gardianul nu avea Cătușe disponibile.");
            audit.record("baton_knockout", issuer.name(), uuidOf(issuer),
                    target.name(), uuidOf(target), "SUCCESS", "no_cuffs_available");
            return new DamageDecision(DamageAction.CANCEL, "no_cuffs_available");
        }
        requestSurrender(issuer, target);
        return new DamageDecision(DamageAction.CANCEL, "surrender_requested");
    }

    /** Maximum baton damage so the hit never kills on its own. */
    public double capBatonDamage(double health, double absorption) {
        return Math.max(0, health + absorption - 1);
    }

    /**
     * Resolves one potentially lethal event and starts Straja downed only when
     * Straja is the selected owner. Provider flags are supplied by optional
     * compatibility adapters; false is the safe absence case.
     */
    @Override
    public LethalEventResolver.Decision resolveLethalEvent(
            PlayerGateway target,
            PlayerGateway source,
            DamageCategory category,
            boolean explicitHardKill,
            boolean vampireEligible,
            boolean vampireDbnoActive) {
        if (target == null) {
            return LethalEventResolver.resolve(null, ctx.policies());
        }
        var store = store();
        String targetKey = key(target);
        CustodyState state = store.states.get(targetKey);
        if (state == null) {
            state = newCanonicalState(target);
            if (store.downed.containsKey(targetKey)) {
                state.condition = PlayerCondition.DOWNED;
            }
            var cuffed = store.cuffed.containsKey(targetKey);
            var bound = store.bound.containsKey(targetKey);
            if (cuffed) {
                state.custody = CustodyStatus.ARRESTED;
                state.restraint = RestraintStatus.CUFFED;
            } else if (bound) {
                state.custody = CustodyStatus.HOSTAGE;
                state.restraint = RestraintStatus.ROPE_BOUND;
            }
        }
        var context = new LethalEventResolver.Context(
                state.condition, state.custody, state.restraint, category,
                explicitHardKill, vampireEligible, vampireDbnoActive);
        var decision = LethalEventResolver.resolve(context, ctx.policies());
        if (decision.outcome() != LethalEventResolver.Outcome.STRAJA_DOWNED) {
            audit.record("lethal_resolve",
                    source == null ? "system" : source.name(),
                    source == null ? "" : uuidOf(source),
                    target.name(), uuidOf(target), decision.outcome().name(),
                    "code=" + decision.code());
            return decision;
        }
        var record = startDowned(target, source, "lethal_damage");
        if (record == null) {
            decision = new LethalEventResolver.Decision(
                    LethalEventResolver.Outcome.VANILLA_DEATH,
                    "STRAJA_DOWNED_TRANSITION_FAILED",
                    false);
        }
        audit.record("lethal_resolve",
                source == null ? "system" : source.name(),
                source == null ? "" : uuidOf(source),
                target.name(), uuidOf(target), decision.outcome().name(),
                "code=" + decision.code());
        return decision;
    }

    // ------------------------------------------------------------ action locks

    /** True when the action must be cancelled (cuffed/bound/downed lock). */
    public boolean actionBlocked(PlayerGateway player, String action) {
        var store = store();
        String playerKey = key(player);
        if (store.downed.containsKey(playerKey)) {
            if (!ctx.policies().downedActionLock) return false;
            var record = store.downed.get(playerKey);
            if (now() - record.lastBlockedNoticeAt >= 5000) {
                record.lastBlockedNoticeAt = now();
                ctx.custody().write(store);
                player.tell("Ești inconștient. Nu poți ataca, interacționa, deschide inventarul sau folosi obiecte până te trezești.");
            }
            player.closeMenu();
            return true;
        }
        if ((store.cuffed.containsKey(playerKey) && ctx.policies().cuffActionLock)
                || (store.bound.containsKey(playerKey) && ctx.policies().restraintActionLock)) {
            var bound = store.bound.get(playerKey);
            if (bound != null && now() - bound.lastBlockedNoticeAt >= 5000) {
                bound.lastBlockedNoticeAt = now();
                ctx.custody().write(store);
            }
            player.closeMenu();
            return true;
        }
        return false;
    }

    public void cuffStatus(PlayerGateway player) {
        var record = store().cuffed.get(key(player));
        if (record == null) player.tell("Nu ești încătușat.");
        else player.tell("Ești încătușat de " + record.issuer + ". Cheia trebuie folosită de acel gardian.");
    }

    public void downedStatus(PlayerGateway player) {
        var record = downedRecord(player);
        if (record == null) player.tell("Nu ești inconștient.");
        else player.tell("Ești inconștient până la " + record.wakesAt + ", la locul leșinului.");
    }

    // ------------------------------------------------------------ tick

    private void applyCuffSlowness(PlayerGateway player) {
        player.applyEffect("minecraft:slowness",
                Math.max(20, ctx.policies().cuffSlownessTicks), ctx.policies().cuffSlownessAmplifier);
    }

    /**
     * Releases a cuff whose issuer no longer has authority. Hidden items are
     * restored immediately or queued by UUID, and the transition is audited.
     */
    private void recoverCuff(CustodyStore store, CustodyStore.CuffRecord record,
                             PlayerGateway target) {
        String playerKey = record.targetUuid == null || record.targetUuid.isEmpty()
                ? PlayerService.canon(record.target) : record.targetUuid;
        recoverCanonicalRestraint(store, playerKey, "issuer_recovery");
        if (target != null) {
            restoreCuffedHand(store, target, record);
            target.tell("Cătușele au fost eliberate: emitentul nu mai este eligibil.");
        } else if (record.hiddenItemId != null && !record.hiddenItemId.isEmpty()
                && record.targetUuid != null && !record.targetUuid.isEmpty()) {
            var pending = new CustodyStore.PendingItem();
            pending.itemId = record.hiddenItemId;
            pending.count = record.hiddenItemCount;
            pending.data = record.hiddenItemData;
            pending.name = record.hiddenItemName;
            store.pendingItems.computeIfAbsent(record.targetUuid, k -> new ArrayList<>()).add(pending);
        }
        String targetKey = record.targetUuid == null || record.targetUuid.isEmpty()
                ? PlayerService.canon(record.target) : record.targetUuid;
        store.cuffed.remove(targetKey);
        store.bound.remove(targetKey);
        store.headSacks.remove(targetKey);
        audit.record("cuff_recovery", record.issuer, record.issuerUuid,
                record.target, record.targetUuid, "SUCCESS", "issuer_no_longer_eligible");
    }

    private boolean issuerStillEligible(CustodyStore.CuffRecord record) {
        // Legacy records without a stable issuer identity cannot be safely
        // attributed to an eligible guard. Recover them closed rather than
        // leaving a restraint active indefinitely.
        return issuerEligible(record.issuer, record.issuerUuid);
    }

    /** A downed record that cannot be safely applied is discarded closed. */
    private static boolean malformedDowned(CustodyStore.DownedRecord record) {
        return record == null
                || (blank(record.targetUuid) && blank(record.target))
                || blank(record.dimension)
                || !Double.isFinite(record.x) || !Double.isFinite(record.y)
                || !Double.isFinite(record.z)
                || record.wakesAt <= 0;
    }

    /** Per-tick custody maintenance: expiry, distance break, effects, wake. */
    public void tick() {
        var store = store();
        var carried = carriedSnapshots(store);
        boolean changed = processCanonicalDeadlines(store);
        changed |= reconcileCarried(store, carried);
        changed |= processResuscitations(store);
        for (var entry : new java.util.ArrayList<>(store.cuffRequests.entrySet())) {
            var request = entry.getValue();
            if (request == null || blank(request.id)) {
                store.cuffRequests.remove(entry.getKey());
                audit.record("custody_recovery", "", "", "", "",
                        "SUCCESS", "malformed_record kind=request key=" + entry.getKey());
                changed = true;
            } else if (request.expiresAt <= now()) {
                store.cuffRequests.remove(entry.getKey());
                changed = true;
            }
        }

        // Withheld release items retry delivery whenever the owner is online.
        if (!store.pendingItems.isEmpty()) {
            for (var p : ctx.server().onlinePlayers()) {
                if (store.pendingItems.containsKey(key(p))
                        && deliverPendingItems(store, p)) changed = true;
            }
        }

        for (var entry : new java.util.ArrayList<>(store.cuffed.entrySet())) {
            var record = entry.getValue();
            if (record == null || (blank(record.targetUuid) && blank(record.target))) {
                store.cuffed.remove(entry.getKey());
                audit.record("custody_recovery", "", "", "", "",
                        "SUCCESS", "malformed_record kind=cuffed key=" + entry.getKey());
                changed = true;
                continue;
            }
            var target = findStored(record.targetUuid, record.target);
            if (!issuerStillEligible(record)) {
                recoverCuff(store, record, target);
                store.cuffed.remove(entry.getKey());
                changed = true;
                continue;
            }
            if (target == null) continue;
            var issuer = findStored(record.issuerUuid, record.issuer);
            if (issuer != null && issuer.dimension().equals(target.dimension())) {
                double maxDistance = Math.max(8, record.maxDistance);
                double dx = target.x() - issuer.x();
                double dy = target.y() - issuer.y();
                double dz = target.z() - issuer.z();
                boolean farAway = dx * dx + dy * dy + dz * dz > maxDistance * maxDistance;
                if (farAway) {
                    if (record.outOfRangeAt == null) {
                        record.outOfRangeAt = now();
                        target.tell("Cătușele se slăbesc: ești prea departe de gardian.");
                        changed = true;
                    } else if (now() - record.outOfRangeAt
                            >= Math.max(1, ctx.policies().cuffBreakGraceSeconds) * 1000L) {
                        recoverCanonicalRestraint(store, entry.getKey(), "distance_timeout");
                        restoreCuffedHand(store, target, record);
                        store.cuffed.remove(entry.getKey());
                        target.tell("Cătușele s-au rupt după ce ai ieșit din raza gardianului.");
                        issuer.tell(target.name() + " a ieșit din raza cătușelor; acestea s-au rupt.");
                        audit.record("cuff_expire", issuer.name(), record.issuerUuid,
                                target.name(), record.targetUuid, "SUCCESS",
                                "distance_timeout distance=" + (int) maxDistance);
                        changed = true;
                        continue;
                    }
                } else if (record.outOfRangeAt != null) {
                    record.outOfRangeAt = null;
                    changed = true;
                }
            }
            hideCuffedHand(target, record);
            target.closeMenu();
            applyCuffSlowness(target);
        }

        for (var entry : new java.util.ArrayList<>(store.bound.entrySet())) {
            var record = entry.getValue();
            if (record == null || (blank(record.targetUuid) && blank(record.target))) {
                store.bound.remove(entry.getKey());
                audit.record("custody_recovery", "", "", "", "",
                        "SUCCESS", "malformed_record kind=bound key=" + entry.getKey());
                changed = true;
                continue;
            }
            if (!issuerEligible(record.issuer, record.issuerUuid)) {
                store.bound.remove(entry.getKey());
                audit.record("custody_recovery",
                        record.issuer, record.issuerUuid,
                        record.target, record.targetUuid, "SUCCESS",
                        "ineligible_issuer kind=bound key=" + entry.getKey());
                changed = true;
                continue;
            }
            var target = findStored(record.targetUuid, record.target);
            if (target == null) continue;
            target.closeMenu();
            target.applyEffect("minecraft:slowness",
                    Math.max(20, ctx.policies().ropeSlownessTicks), ctx.policies().ropeSlownessAmplifier);
        }
        for (var entry : new java.util.ArrayList<>(store.headSacks.entrySet())) {
            var record = entry.getValue();
            boolean malformed = record == null
                    || (blank(record.targetUuid) && blank(record.target));
            boolean badIssuer = !malformed
                    && !issuerEligible(record.issuer, record.issuerUuid);
            boolean orphan = !store.cuffed.containsKey(entry.getKey())
                    && !store.bound.containsKey(entry.getKey());
            if (malformed || badIssuer || orphan) {
                store.headSacks.remove(entry.getKey());
                audit.record("custody_recovery",
                        record == null ? "" : record.issuer,
                        record == null ? "" : record.issuerUuid, "", "",
                        "SUCCESS", (malformed ? "malformed_record kind=head_sack"
                                : badIssuer ? "ineligible_issuer kind=head_sack"
                                : "orphan_head_sack")
                                + " key=" + entry.getKey());
                changed = true;
                continue;
            }
            var target = findStored(record.targetUuid, record.target);
            if (target == null) continue;
            target.applyEffect("minecraft:blindness",
                    Math.max(20, ctx.policies().headSackBlindnessTicks), 0);
        }
        for (var entry : new java.util.ArrayList<>(store.downed.entrySet())) {
            var record = entry.getValue();
            if (malformedDowned(record)) {
                store.downed.remove(entry.getKey());
                audit.record("custody_recovery", "", "", "", "",
                        "SUCCESS", "malformed_record kind=downed key=" + entry.getKey());
                changed = true;
                continue;
            }
            var target = findStored(record.targetUuid, record.target);
            if (target == null) continue;
            var canonical = store.states.get(entry.getKey());
            if (canonical != null && (canonical.transport == TransportStatus.CARRIED
                    || canonical.condition == PlayerCondition.RESUSCITATING)) {
                // The canonical transport deadline owns carried timing. The
                // resuscitation deadline owns active rescue timing. The legacy
                // wakesAt projection must not resolve either state early.
                continue;
            }
            if (now() >= record.wakesAt) {
                // Inline wake on the same store instance: a nested read/write
                // here would be clobbered by the outer store write at the end.
                target.teleport(record.dimension, record.x, record.y, record.z);
                double ratio = Math.max(0.1, Math.min(1, ctx.policies().downedWakeHealthRatio));
                target.setHealth(Math.max(1, target.maxHealth() * ratio));
                target.closeMenu();
                target.tell("Ți-ai revenit. Poți acționa din nou.");
                store.downed.remove(entry.getKey());
                audit.record("downed_wake", target.name(), uuidOf(target),
                        target.name(), uuidOf(target), "SUCCESS", "cooldown_complete");
                changed = true;
                continue;
            }
            if (ctx.policies().downedFreezeInPlace) {
                double moved = Math.abs(target.x() - record.x) + Math.abs(target.y() - record.y)
                        + Math.abs(target.z() - record.z);
                if (moved > 0.05 || !target.dimension().equals(record.dimension)) {
                    target.teleport(record.dimension, record.x, record.y, record.z);
                }
            }
            if (target.health() <= 0 || target.health() > 1) target.setHealth(1);
            target.closeMenu();
            target.applyEffect("minecraft:slowness",
                    Math.max(20, ctx.policies().downedSlownessTicks), ctx.policies().downedSlownessAmplifier);
        }
        if (changed) ctx.custody().write(store);
    }

    private static String uuidOf(PlayerGateway p) {
        return p.uuid() == null ? "" : p.uuid().toString();
    }

    private static String uuidOrEmpty(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }
}
