package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.DelegationUseCase;
import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.DelegationRepository;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ActivityReport;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.AudienceRequest;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.Complaint;
import com.dwurdy.straja.domain.model.DelegableAction;
import com.dwurdy.straja.domain.model.DelegatedDecision;
import com.dwurdy.straja.domain.model.DelegationMode;
import com.dwurdy.straja.domain.model.DelegationPolicy;
import com.dwurdy.straja.domain.model.DelegationStore;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.JoinRequest;
import com.dwurdy.straja.domain.model.PromotionApplication;
import com.dwurdy.straja.domain.model.StandingOrder;
import com.dwurdy.straja.domain.model.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * FUN-1 delegation engine. The authority publishes a bounded policy per
 * request queue — standing orders that resolve matching requests, or a
 * timeout that presumes the queue's configured outcome — and this service
 * sweeps the real pending queues and resolves under the recorded authority.
 *
 * <p>Invariants the whole design leans on:</p>
 * <ul>
 *   <li>Every resolution routes through the owning service's normal
 *       review path, so conflict sets, version checks, and authority checks
 *       stay the single implementation — delegation can never out-permission
 *       a human review.</li>
 *   <li>Each pending item resolves at most once: a deterministic decision id
 *       dedupes repeat sweeps and a recorded DENIED decision stops retries
 *       (the item then waits for manual review, visibly).</li>
 *   <li>Rejections and contested judgments stay human: delegatable outcomes
 *       are approvals, report acceptance, and audience dismissal only.</li>
 * </ul>
 */
public final class DelegationService implements DelegationUseCase {

    /** Safety bound on resolutions per queue per sweep — a runaway queue
     *  never blocks the tick or starves the other queues. */
    static final int MAX_SWEEP_RESOLUTIONS = 32;
    /** Standing orders kept per queue. */
    static final int MAX_ORDERS_PER_QUEUE = 16;
    /** Timeout windows the surface cycles through, in hours. */
    static final long[] TIMEOUT_STEPS_HOURS = {24, 48, 72, 168};

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final DelegationRepository delegations;
    private final JoinRequestService joinRequests;
    private final PromotionService promotions;
    private final AudienceService audiences;
    private final ReportService reports;
    private final Supplier<java.util.Set<String>> professions;
    private final Clock clock;

    /** FUN-11: bound after construction to keep the ctor stable. */
    private ParoleService paroles;

    public void useParole(ParoleService service) { this.paroles = service; }

    private long lastSweepAt = -1;

    public DelegationService(StrajaContext ctx,
                             PlayerService players,
                             AuditService audit,
                             DelegationRepository delegations,
                             JoinRequestService joinRequests,
                             PromotionService promotions,
                             AudienceService audiences,
                             ReportService reports,
                             Supplier<java.util.Set<String>> professions,
                             Clock clock) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.delegations = delegations;
        this.joinRequests = joinRequests;
        this.promotions = promotions;
        this.audiences = audiences;
        this.reports = reports;
        this.professions = professions;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Surface operations
    // ------------------------------------------------------------------

    @Override
    public SurfaceView surface(PlayerGateway viewer) {
        if (!canDelegate(viewer)) {
            return SurfaceView.empty();
        }
        List<DigestEntry> digest = new ArrayList<>();
        for (DelegableAction action : DelegableAction.values()) {
            for (PendingItem item : pendingFor(action)) {
                digest.add(new DigestEntry(action, item.requestId,
                        players.displayName(item.subjectUuid), item.summary,
                        reviewActionId(action, item.requestId), item.submittedAt));
            }
        }
        digest.sort(java.util.Comparator.comparingLong(DigestEntry::submittedAt));
        DelegationStore store = delegations.read();
        List<PolicyView> policies = new ArrayList<>();
        for (DelegableAction action : DelegableAction.values()) {
            DelegationPolicy policy = store.policy(action);
            List<OrderView> orders = new ArrayList<>();
            if (policy != null) {
                for (StandingOrder order : policy.ensureOrders()) {
                    if (order != null) {
                        orders.add(new OrderView(order.orderId, order.authorName,
                                order.filter, order.active));
                    }
                }
            }
            policies.add(new PolicyView(action,
                    policy == null ? DelegationMode.MANUAL : policy.mode,
                    policy == null ? 0 : policy.timeoutMillis,
                    orders));
        }
        return new SurfaceView(digest, policies, orderOptions());
    }

    /** The issuable order vocabulary per queue: "*" plus each concrete filter
     *  value the queue knows how to match. */
    private List<OrderOption> orderOptions() {
        List<OrderOption> out = new ArrayList<>();
        for (DelegableAction action : DelegableAction.values()) {
            if (!action.allowsStandingOrders()) continue;
            out.add(new OrderOption(action.name(), "ANY", "orice cerere"));
            switch (action) {
                case JOIN_PETITION -> {
                    java.util.Set<String> known = professions.get();
                    if (known != null) {
                        for (String profession : known) {
                            out.add(new OrderOption(action.name(), profession,
                                    "meseria " + Text.displayLower(profession)));
                        }
                    }
                }
                case PROMOTION_PETITION -> {
                    for (CareerGrade grade : CareerGrade.values()) {
                        out.add(new OrderOption(action.name(), grade.name(),
                                "gradul " + ctx.policies().rankName(grade)));
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    /** Most recent delegated decisions, newest last — the review surface for
     *  "what has the institution decided under delegation." */
    public List<DelegatedDecision> recentDecisions(PlayerGateway viewer, int limit) {
        if (!canDelegate(viewer)) {
            viewer.refuse("straja.delegation.not_authority", "straja.remedy.ask_comisar");
            return List.of();
        }
        List<DelegatedDecision> all = new ArrayList<>(delegations.read().ensureDecisions().values());
        return all.size() <= limit ? all : all.subList(all.size() - limit, all.size());
    }

    @Override
    public synchronized void cycleMode(PlayerGateway actor, String actionName) {
        DelegableAction action = requireAction(actor, actionName);
        if (action == null || !canDelegate(actor)) {
            if (action != null) actor.refuse("straja.delegation.not_authority", "straja.remedy.ask_comisar");
            return;
        }
        DelegationStore store = delegations.read();
        DelegationPolicy policy = policyFor(store, action, actor);
        List<DelegationMode> allowed = orderedModes(action);
        int next = (allowed.indexOf(policy.mode) + 1) % allowed.size();
        policy.mode = allowed.get(next);
        stamp(policy, actor);
        delegations.write(store);
        audit.record(AuditActions.DELEGATION_POLICY, actor.name(), actor.uuid().toString(),
                action.name(), actor.name(), AuditResults.SUCCESS, policy.mode.name());
        actor.tell("Delegare «" + action.displayName() + "»: mod " + describeMode(policy.mode, policy));
    }

    @Override
    public synchronized void cycleTimeout(PlayerGateway actor, String actionName) {
        DelegableAction action = requireAction(actor, actionName);
        if (action == null || !canDelegate(actor)) {
            if (action != null) actor.refuse("straja.delegation.not_authority", "straja.remedy.ask_comisar");
            return;
        }
        if (action.timeoutOutcome() == null) {
            actor.refuse("straja.delegation.no_timeout", "straja.remedy.retry");
            return;
        }
        DelegationStore store = delegations.read();
        DelegationPolicy policy = policyFor(store, action, actor);
        long current = policy.timeoutMillis > 0
                ? policy.timeoutMillis : defaultTimeoutMillis();
        policy.timeoutMillis = nextTimeoutStep(current);
        stamp(policy, actor);
        delegations.write(store);
        audit.record(AuditActions.DELEGATION_POLICY, actor.name(), actor.uuid().toString(),
                action.name(), actor.name(), AuditResults.SUCCESS,
                "timeout=" + (policy.timeoutMillis / 3_600_000L) + "h");
        actor.tell("Fereastră «" + action.displayName() + "»: " + (policy.timeoutMillis / 3_600_000L) + "h");
    }

    @Override
    public synchronized void issueOrder(PlayerGateway actor, String actionName, String filter) {
        DelegableAction action = requireAction(actor, actionName);
        if (action == null || !canDelegate(actor)) {
            if (action != null) actor.refuse("straja.delegation.not_authority", "straja.remedy.ask_comisar");
            return;
        }
        if (!action.allowsStandingOrders()) {
            actor.refuse("straja.delegation.no_orders", "straja.remedy.retry");
            return;
        }
        DelegationStore store = delegations.read();
        DelegationPolicy policy = policyFor(store, action, actor);
        if (policy.mode != DelegationMode.STANDING_ORDER) {
            actor.refuse("straja.delegation.order_needs_mode", "straja.remedy.retry");
            return;
        }
        String normalized = normalizeFilter(action, filter);
        if (normalized == null) {
            actor.refuse("straja.delegation.bad_filter", "straja.remedy.fix_retry");
            return;
        }
        List<StandingOrder> orders = policy.ensureOrders();
        long active = orders.stream().filter(o -> o != null && o.active).count();
        if (active >= MAX_ORDERS_PER_QUEUE) {
            actor.refuse("straja.delegation.too_many_orders", "straja.remedy.retry");
            return;
        }
        StandingOrder order = new StandingOrder();
        order.orderId = "SO-" + action.name() + "-" + (orders.size() + 1);
        order.authorUuid = actor.uuid().toString();
        order.authorName = actor.name();
        order.filter = normalized;
        order.createdAt = now();
        order.active = true;
        orders.add(order);
        delegations.write(store);
        audit.record(AuditActions.DELEGATION_ORDER, actor.name(), actor.uuid().toString(),
                action.name(), actor.name(), AuditResults.SUCCESS,
                order.orderId + " filter=" + normalized);
        actor.tell("Ordin permanent «" + action.displayName() + "»: " + normalized
                + " — cererile potrivite se rezolvă singure.");
    }

    @Override
    public synchronized void revokeOrder(PlayerGateway actor, String orderId) {
        if (!canDelegate(actor)) {
            actor.refuse("straja.delegation.not_authority", "straja.remedy.ask_comisar");
            return;
        }
        DelegationStore store = delegations.read();
        for (var entry : store.ensurePolicies().entrySet()) {
            for (StandingOrder order : entry.getValue().ensureOrders()) {
                if (order != null && orderId != null && orderId.equals(order.orderId) && order.active) {
                    order.active = false;
                    delegations.write(store);
                    audit.record(AuditActions.DELEGATION_REVOKE, actor.name(), actor.uuid().toString(),
                            orderId, actor.name(), AuditResults.SUCCESS, entry.getKey());
                    actor.tell("Ordinul " + orderId + " a fost revocat. Deciziile anterioare rămân valabile.");
                    return;
                }
            }
        }
        actor.refuse("straja.delegation.order_gone", "straja.remedy.retry");
    }

    // ------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------

    @Override
    public synchronized void tick() {
        long now = now();
        long sweepMillis = Math.max(5, ctx.policies().delegationSweepSeconds) * 1000L;
        if (lastSweepAt >= 0 && now - lastSweepAt < sweepMillis) return;
        lastSweepAt = now;
        DelegationStore store = delegations.read();
        long revisionBefore = store.storeRevision;
        for (DelegableAction action : DelegableAction.values()) {
            DelegationPolicy policy = store.policy(action);
            if (policy == null) continue;
            try {
                if (policy.mode == DelegationMode.STANDING_ORDER) {
                    applyStandingOrders(action, policy, store, now);
                } else if (policy.mode == DelegationMode.TIMEOUT) {
                    applyTimeout(action, policy, store, now);
                }
            } catch (RuntimeException error) {
                // One queue's failure must not wedge the rest of the sweep
                // — or the server tick that hosts it.
            }
        }
        if (store.storeRevision != revisionBefore) {
            delegations.write(store);
        }
    }

    private void applyStandingOrders(DelegableAction action, DelegationPolicy policy,
                                     DelegationStore store, long now) {
        if (!policy.hasActiveOrder()) return;
        int resolved = 0;
        for (PendingItem item : pendingFor(action)) {
            if (resolved >= MAX_SWEEP_RESOLUTIONS) break;
            if (deniedBefore(store, action, item.requestId)) continue;
            StandingOrder order = policy.firstMatching(item.matchValue, item.subjectUuid);
            if (order == null) continue;
            String outcome = standingOutcome(action);
            if (outcome == null) continue;
            try {
                if (resolve(item, outcome, order.authorUuid, order.authorName,
                        DelegationMode.STANDING_ORDER, order.orderId, false, store, now)) {
                    resolved++;
                }
            } catch (RuntimeException error) {
                // Skip this item; the rest of the queue still sweeps.
            }
        }
    }

    private void applyTimeout(DelegableAction action, DelegationPolicy policy,
                              DelegationStore store, long now) {
        String outcome = action.timeoutOutcome();
        if (outcome == null || policy.setByUuid == null || policy.setByUuid.isBlank()) return;
        long window = policy.timeoutMillis > 0 ? policy.timeoutMillis : defaultTimeoutMillis();
        int resolved = 0;
        for (PendingItem item : pendingFor(action)) {
            if (resolved >= MAX_SWEEP_RESOLUTIONS) break;
            if (item.submittedAt <= 0 || now - item.submittedAt < window) continue;
            if (deniedBefore(store, action, item.requestId)) continue;
            // Anti-self-approval under the timeout authority too.
            if (policy.setByUuid.equals(item.subjectUuid)) continue;
            try {
                if (resolve(item, outcome, policy.setByUuid, policy.setByName,
                        DelegationMode.TIMEOUT, "", true, store, now)) {
                    resolved++;
                }
            } catch (RuntimeException error) {
                // Skip this item; the rest of the queue still sweeps.
            }
        }
    }

    /** Routes one pending item through its queue's normal review path under
     *  the delegated authority. Returns true on success; failures record a
     *  DENIED decision so the sweep does not retry-and-spam. */
    private boolean resolve(PendingItem item, String outcome,
                            String authorityUuid, String authorityName,
                            DelegationMode mode, String orderId, boolean presumed,
                            DelegationStore store, long now) {
        boolean ok;
        try {
            ok = switch (item.action) {
                case JOIN_PETITION -> "APPROVED".equals(outcome)
                        && joinRequests.approve(authorityUuid, item.requestId, item.version) != null;
                case PROMOTION_PETITION -> "APPROVED".equals(outcome)
                        && promotions.approve(authorityUuid, item.requestId, item.version) != null;
                case AUDIENCE_REQUEST -> "DISMISSED".equals(outcome)
                        && audiences.resolveDelegated(authorityUuid, authorityName,
                                item.requestId, AudienceRequest.DISMISSED,
                                "Respinsă automat — cererea a așteptat peste fereastra configurată.");
                case REPORT_REVIEW -> "ACCEPTED".equals(outcome)
                        && reports.reviewDelegated(authorityUuid, authorityName, item.requestId);
                case COMPLAINT_REVIEW, FINE_APPEAL -> false;
                case PAROLE_HEARING -> "APPROVED".equals(outcome) && paroles != null
                        && paroles.decideDelegated(authorityUuid, authorityName, item.requestId);
            };
        } catch (RuntimeException error) {
            ok = false;
        }
        DelegatedDecision decision = new DelegatedDecision();
        decision.decisionId = "DD-" + item.action.name() + "-" + item.requestId
                + "-" + outcome + "-" + orderId;
        decision.action = item.action.name();
        decision.requestId = item.requestId;
        decision.subjectUuid = item.subjectUuid;
        decision.outcome = ok ? outcome : AuditResults.REFUSED;
        decision.mode = mode.name();
        decision.orderId = orderId;
        decision.authorityUuid = authorityUuid;
        decision.authorityName = authorityName;
        decision.decidedAt = now;
        decision.presumed = presumed;
        store.record(decision);
        audit.record(AuditActions.DELEGATION_RESOLVE, authorityName, authorityUuid,
                item.requestId, item.subjectUuid,
                ok ? outcome : AuditResults.REFUSED,
                "action=" + item.action.name() + " mode=" + mode.name()
                        + (orderId.isEmpty() ? "" : " order=" + orderId)
                        + (presumed ? " presumed" : ""));
        if (ok) deliverOutcome(item.subjectUuid, item.action);
        return ok;
    }

    /** Pushes the persisted outcome to the subject when they are online —
     *  same delivery surfaces the manual review paths use. */
    private void deliverOutcome(String subjectUuid, DelegableAction action) {
        try {
            PlayerGateway subject = ctx.server().findPlayer(subjectUuid);
            if (subject == null) return;
            switch (action) {
                case JOIN_PETITION -> joinRequests.deliverOutcome(subject);
                case PROMOTION_PETITION -> promotions.deliverOutcome(subject);
                default -> { }
            }
        } catch (RuntimeException ignored) {}
    }

    // ------------------------------------------------------------------
    // Pending-queue enumeration
    // ------------------------------------------------------------------

    private record PendingItem(DelegableAction action, String requestId, String subjectUuid,
                               String matchValue, String summary, long submittedAt, long version) {}

    private List<PendingItem> pendingFor(DelegableAction action) {
        try {
            List<PendingItem> out = new ArrayList<>();
            switch (action) {
                case JOIN_PETITION -> {
                    for (JoinRequest r : joinRequests.pending()) {
                        out.add(new PendingItem(action, r.requestId, r.subjectUuid,
                                r.profession, r.profession, r.submittedAt, r.version));
                    }
                }
                case PROMOTION_PETITION -> {
                    for (PromotionApplication a : promotions.pendingPetitions()) {
                        out.add(new PendingItem(action, a.applicationId, a.subjectUuid,
                                a.targetGrade == null ? "" : a.targetGrade.name(),
                                a.targetGrade == null ? "?" : ctx.policies().rankName(a.targetGrade),
                                a.submittedAt, a.version));
                    }
                }
                case AUDIENCE_REQUEST -> {
                    for (AudienceRequest r : ctx.audiences().read().pending()) {
                        out.add(new PendingItem(action, r.id, r.requesterUuid,
                                "", r.reason, r.createdAt, 0));
                    }
                }
                case REPORT_REVIEW -> {
                    for (ActivityReport r : ctx.reports().read().pendingReview()) {
                        out.add(new PendingItem(action, r.id, r.authorUuid,
                                "", r.authorName, r.submittedAt, 0));
                    }
                }
                case COMPLAINT_REVIEW -> {
                    for (Complaint c : ctx.complaints().read().ensureComplaints()) {
                        if (c != null && ("REPORT_SUBMITTED".equals(c.status)
                                || "UNDER_REVIEW".equals(c.status))) {
                            out.add(new PendingItem(action, c.id, c.complainantUuid,
                                    "", c.category, c.createdAt, 0));
                        }
                    }
                }
                case FINE_APPEAL -> {
                    for (Fine f : ctx.fines().read().fines) {
                        if (f != null && "APPEAL_PENDING".equals(f.status)
                                && f.appeal != null && "PENDING".equals(f.appeal.status)) {
                            // Digest rows key on the fine id — every
                            // downstream consumer (staleness check, appeal
                            // review, ledger) expects the fine's own id.
                            out.add(new PendingItem(action, f.id, f.targetUuid,
                                    "", f.law, f.appeal.filedAt, 0));
                        }
                    }
                }
                case PAROLE_HEARING -> {
                    if (paroles != null) {
                        for (var h : paroles.pendingDocket()) {
                            out.add(new PendingItem(action, h.id, h.prisonerUuid,
                                    paroles.matchValue(h.prisonerUuid), h.prisonerName,
                                    h.filedAt, h.version));
                        }
                    }
                }
            }
            out.sort(java.util.Comparator.comparingLong(PendingItem::submittedAt));
            return out;
        } catch (RuntimeException error) {
            return List.of();
        }
    }

    /** The native review action for a digest row — the existing per-queue
     *  decision form, so the digest never invents a parallel review. */
    private static String reviewActionId(DelegableAction action, String requestId) {
        return switch (action) {
            case JOIN_PETITION -> "join-review";
            case PROMOTION_PETITION -> "petition-review";
            case AUDIENCE_REQUEST -> "audience-review";
            case REPORT_REVIEW -> "report-review";
            case COMPLAINT_REVIEW -> "complaint-review";
            case FINE_APPEAL -> "fine-appeal-review";
            case PAROLE_HEARING -> "parole-review";
        } + ":" + requestId;
    }

    private static String standingOutcome(DelegableAction action) {
        return switch (action) {
            case JOIN_PETITION, PROMOTION_PETITION -> "APPROVED";
            case REPORT_REVIEW -> "ACCEPTED";
            case PAROLE_HEARING -> "APPROVED";
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private boolean deniedBefore(DelegationStore store, DelegableAction action, String requestId) {
        for (DelegatedDecision d : store.ensureDecisions().values()) {
            if (d != null && AuditResults.REFUSED.equals(d.outcome)
                    && action.name().equals(d.action) && requestId.equals(d.requestId)) {
                return true;
            }
        }
        return false;
    }

    private boolean canDelegate(PlayerGateway actor) {
        // v1 intent: the Comisar is the delegation authority. Capability.DELEGATE
        // is the forward-grant seam — the authorization matrix currently grants
        // it to nobody below the Comisar, so this reads commissioner-only today
        // and gains a real population the day DELEGATE becomes grantable.
        return players.isCommissioner(actor) || players.hasCapability(actor, Capability.DELEGATE);
    }

    private DelegableAction requireAction(PlayerGateway actor, String actionName) {
        DelegableAction action = DelegableAction.of(actionName);
        if (action == null) {
            actor.refuse("straja.delegation.unknown", "straja.remedy.retry");
        }
        return action;
    }

    private DelegationPolicy policyFor(DelegationStore store, DelegableAction action,
                                       PlayerGateway actor) {
        DelegationPolicy policy = store.ensurePolicies()
                .computeIfAbsent(action.name(), k -> new DelegationPolicy());
        return policy;
    }

    private void stamp(DelegationPolicy policy, PlayerGateway actor) {
        policy.setByUuid = actor.uuid().toString();
        policy.setByName = actor.name();
        policy.updatedAt = now();
    }

    private String normalizeFilter(DelegableAction action, String filter) {
        String raw = filter == null ? "" : filter.trim();
        if (raw.isEmpty() || "*".equals(raw) || "ANY".equalsIgnoreCase(raw)) {
            return "*";
        }
        return switch (action) {
            case JOIN_PETITION -> {
                String normalized = Text.normalize(raw);
                yield professions.get() != null && professions.get().contains(normalized)
                        ? normalized : null;
            }
            case PROMOTION_PETITION -> {
                try {
                    yield CareerGrade.valueOf(raw.toUpperCase(java.util.Locale.ROOT)).name();
                } catch (IllegalArgumentException error) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private String describeMode(DelegationMode mode, DelegationPolicy policy) {
        return switch (mode) {
            case MANUAL -> "Manual — fiecare cerere trece prin om.";
            case DIGEST -> "Digest — cererile se adună într-o listă de semnat.";
            case STANDING_ORDER -> "Ordin permanent — cererile potrivite se rezolvă singure.";
            case TIMEOUT -> "Presupus — cererile nerezolvate în "
                    + ((policy.timeoutMillis > 0 ? policy.timeoutMillis : defaultTimeoutMillis()) / 3_600_000L)
                    + "h se decid automat.";
        };
    }

    private static List<DelegationMode> orderedModes(DelegableAction action) {
        List<DelegationMode> out = new ArrayList<>();
        for (DelegationMode mode : new DelegationMode[]{
                DelegationMode.MANUAL, DelegationMode.STANDING_ORDER,
                DelegationMode.TIMEOUT, DelegationMode.DIGEST}) {
            if (action.allows(mode)) out.add(mode);
        }
        return out;
    }

    private long nextTimeoutStep(long currentMillis) {
        long currentHours = currentMillis / 3_600_000L;
        for (long step : TIMEOUT_STEPS_HOURS) {
            if (step > currentHours) return step * 3_600_000L;
        }
        return TIMEOUT_STEPS_HOURS[0] * 3_600_000L;
    }

    private long defaultTimeoutMillis() {
        return Math.max(1, ctx.policies().delegationTimeoutHours) * 3_600_000L;
    }

    private long now() {
        return clock == null ? System.currentTimeMillis() : clock.nowMillis();
    }
}
