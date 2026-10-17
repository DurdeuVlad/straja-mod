package com.dwurdy.straja.application.service;

import static org.junit.jupiter.api.Assertions.*;

import com.dwurdy.straja.adapter.out.persistence.SavedStores;
import com.dwurdy.straja.adapter.out.persistence.StoreAccess;
import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.AppointmentType;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AudienceRequest;
import com.dwurdy.straja.domain.model.CareerGrade;
import com.dwurdy.straja.domain.model.DelegableAction;
import com.dwurdy.straja.domain.model.DelegatedDecision;
import com.dwurdy.straja.domain.model.DelegationMode;
import com.dwurdy.straja.domain.model.DelegationStore;
import com.dwurdy.straja.domain.model.EmploymentMode;
import com.dwurdy.straja.domain.model.JoinRequestStatus;
import com.dwurdy.straja.domain.model.StandingOrder;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.MemoryStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FUN-1 — delegated authority: the institution decides routine matters
 * itself. Covers standing-order matching, timeout presumption across a
 * simulated restart, anti-self-approval, revocation, the audit/decision
 * ledger, and the protected queues that stay human.
 */
class DelegationServiceTest {
    private static final Set<String> PROFESSIONS = Set.of("FIERAR", "TAMPLAR");
    private static final long HOUR = 3_600_000L;

    private final Map<String, MemoryStore> raw = new HashMap<>();
    private final StoreAccess access = name -> raw.computeIfAbsent(name, ignored -> new MemoryStore());
    private FixedClock clock;
    private StrajaContext ctx;
    private TestServer server;
    private PlayerService players;
    private AuditService audit;
    private PersonnelService personnel;
    private JoinRequestService joins;
    private PromotionService promotions;
    private AudienceService audiences;
    private ReportService reports;
    private SavedStores.Delegations delegationRepo;
    private DelegationService delegation;
    private TestPlayer comisar;
    private TestPlayer civilian;
    private TestPlayer member;

    @BeforeEach
    void setup() {
        clock = new FixedClock(1_000_000L);
        server = new TestServer();
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);

        var people = new SavedStores.Personnel(access);
        personnel = new PersonnelService(people, clock, new Fakes.SeqIds());
        var authorization = new AuthorizationService(people, clock);
        joins = new JoinRequestService(new SavedStores.JoinRequests(access), personnel,
                authorization, () -> PROFESSIONS, clock, new Fakes.SeqIds());
        promotions = new PromotionService(new SavedStores.Promotions(access), people,
                authorization, clock, new Fakes.SeqIds());
        players.useV2Authority(personnel, authorization);
        audiences = new AudienceService(ctx, players, audit);
        reports = new ReportService(ctx, players, audit);
        delegationRepo = new SavedStores.Delegations(access);
        delegation = new DelegationService(ctx, players, audit, delegationRepo,
                joins, promotions, audiences, reports, () -> PROFESSIONS, clock);

        comisar = server.add("dwurdy");
        civilian = server.add("newbie");
        member = server.add("m1");
        var memberState = ctx.players().read(member.uuid());
        memberState.rank = com.dwurdy.straja.domain.model.Rank.STAGIAR.level();
        ctx.players().write(member.uuid(), memberState);
        personnel.authorize("bootstrap", comisar.uuid().toString(),
                CareerGrade.OFFICER_COLONEL, EmploymentMode.FULL_TIME,
                "TEST", "hq", "auth:commissioner");
        personnel.appoint("bootstrap", comisar.uuid().toString(),
                AppointmentType.COMMISSIONER, "hq", "", null);
        // Late-bound like production: authorization checks activate after the
        // bootstrap record exists.
        personnel.useAuthorization(authorization);
    }

    /** Fresh service over the same stores — simulates a server restart. */
    private DelegationService restarted() {
        return new DelegationService(ctx, players, audit, delegationRepo,
                joins, promotions, audiences, reports, () -> PROFESSIONS, clock);
    }

    private void setMode(TestPlayer actor, String action, DelegationMode target) {
        for (int i = 0; i < 8; i++) {
            var current = delegation.surface(actor).policies().stream()
                    .filter(p -> p.action() == DelegableAction.of(action))
                    .findFirst().orElseThrow().mode();
            if (current == target) return;
            delegation.cycleMode(actor, action);
        }
        fail("mode " + target + " unreachable for " + action);
    }

    // ---------------------------------------------------------------
    // Standing orders
    // ---------------------------------------------------------------

    @Test
    void standingOrderApprovesMatchingPetition() {
        setMode(comisar, "JOIN_PETITION", DelegationMode.STANDING_ORDER);
        delegation.issueOrder(comisar, "JOIN_PETITION", "FIERAR");
        var request = joins.submit("newbie", "fierar", "vreau să muncesc");
        assertEquals(JoinRequestStatus.PENDING, request.status);

        delegation.tick();

        var decided = joins.find(request.requestId);
        assertEquals(JoinRequestStatus.APPROVED, decided.status);
        assertEquals(comisar.uuid().toString(), decided.reviewedBy);
        var record = personnel.find("newbie");
        assertNotNull(record);
        assertEquals(CareerGrade.PROFESSIONAL_ZILIER, record.careerGrade);
        // Decision ledger: the resolution names the order and its author.
        var store = delegationRepo.read();
        assertEquals(1, store.ensureDecisions().size());
        DelegatedDecision decision = store.ensureDecisions().values().iterator().next();
        assertEquals("JOIN_PETITION", decision.action);
        assertEquals(request.requestId, decision.requestId);
        assertEquals("APPROVED", decision.outcome);
        assertEquals(DelegationMode.STANDING_ORDER.name(), decision.mode);
        assertEquals(comisar.uuid().toString(), decision.authorityUuid);
        assertFalse(decision.presumed);
        assertFalse(decision.orderId.isBlank());
        // The approver's act is the standing order — traced in the audit.
        assertTrue(audit.tail(50).stream().anyMatch(e ->
                AuditActions.DELEGATION_RESOLVE.equals(e.action)
                        && request.requestId.equals(e.target)));
    }

    @Test
    void nonMatchingPetitionStaysPending() {
        setMode(comisar, "JOIN_PETITION", DelegationMode.STANDING_ORDER);
        delegation.issueOrder(comisar, "JOIN_PETITION", "TAMPLAR");
        joins.submit("newbie", "fierar", "vreau fierărie");

        delegation.tick();

        assertEquals(1, joins.pending().size(), "unmatched petition waits for a human");
        assertTrue(delegationRepo.read().ensureDecisions().isEmpty());
    }

    @Test
    void revokedOrderStopsResolutions() {
        setMode(comisar, "JOIN_PETITION", DelegationMode.STANDING_ORDER);
        delegation.issueOrder(comisar, "JOIN_PETITION", "FIERAR");
        var view = delegation.surface(comisar);
        String orderId = view.policies().stream()
                .filter(p -> p.action() == DelegableAction.JOIN_PETITION)
                .flatMap(p -> p.orders().stream()).findFirst().orElseThrow().orderId();
        delegation.revokeOrder(comisar, orderId);
        joins.submit("newbie", "fierar", "");

        delegation.tick();

        assertEquals(1, joins.pending().size(), "revoked order resolves nothing");
        assertTrue(audit.tail(50).stream().anyMatch(e ->
                AuditActions.DELEGATION_REVOKE.equals(e.action) && orderId.equals(e.target)));
    }

    @Test
    void standingOrderApprovesPromotionPetition() {
        String officerId = "officer-" + server.add("lt").uuid();
        personnel.authorize(comisar.uuid().toString(), officerId, CareerGrade.OFFICER_LOCOTENENT,
                EmploymentMode.FULL_TIME, "TEST", "hq", "auth:lt");
        promotions.submitPetition(officerId, CareerGrade.OFFICER_CAPITAN, "rezultate bune");
        setMode(comisar, "PROMOTION_PETITION", DelegationMode.STANDING_ORDER);
        delegation.issueOrder(comisar, "PROMOTION_PETITION", "OFFICER_CAPITAN");

        delegation.tick();

        assertTrue(promotions.pendingPetitions().isEmpty());
        assertEquals(CareerGrade.OFFICER_CAPITAN, personnel.find(officerId).careerGrade);
    }

    // ---------------------------------------------------------------
    // Timeout presumption
    // ---------------------------------------------------------------

    @Test
    void timeoutResumesAfterRestartAndResolvesStalePetition() {
        var request = joins.submit("newbie", "fierar", "");
        setMode(comisar, "JOIN_PETITION", DelegationMode.TIMEOUT);
        // Window still open: nothing happens even after a restart.
        clock.advance(10 * HOUR);
        restarted().tick();
        assertEquals(JoinRequestStatus.PENDING, joins.find(request.requestId).status);

        // Past the default window: a freshly started service still resolves it.
        clock.advance(40 * HOUR);
        restarted().tick();

        assertEquals(JoinRequestStatus.APPROVED, joins.find(request.requestId).status);
        DelegatedDecision decision = delegationRepo.read().ensureDecisions().values()
                .iterator().next();
        assertTrue(decision.presumed, "timeout decisions are marked presumed");
        assertEquals(DelegationMode.TIMEOUT.name(), decision.mode);
    }

    @Test
    void timeoutDismissesAudienceNeverResolves() {
        audiences.request(comisar, "vreau să discut ceva"); // own request for self-conflict check below
        assertEquals(AudienceRequest.PENDING,
                ctx.audiences().read().requests.values().iterator().next().status);
        setMode(comisar, "AUDIENCE_REQUEST", DelegationMode.TIMEOUT);
        clock.advance(60 * HOUR);

        restarted().tick();

        // The only pending request was filed by the policy authority —
        // anti-self-approval keeps it waiting for another human.
        assertEquals(AudienceRequest.PENDING,
                ctx.audiences().read().requests.values().iterator().next().status);
    }

    @Test
    void timeoutDismissesOtherMembersAudience() {
        assertTrue(audiences.request(member, "motiv"));
        setMode(comisar, "AUDIENCE_REQUEST", DelegationMode.TIMEOUT);
        clock.advance(60 * HOUR);

        restarted().tick();

        var request = ctx.audiences().read().requests.values().iterator().next();
        assertEquals(AudienceRequest.DISMISSED, request.status);
    }

    // ---------------------------------------------------------------
    // Conflict sets and protected queues
    // ---------------------------------------------------------------

    @Test
    void standingOrderNeverResolvesAuthorsOwnRequest() {
        StandingOrder order = new StandingOrder();
        order.authorUuid = "boss";
        order.filter = "*";
        var policy = new com.dwurdy.straja.domain.model.DelegationPolicy();
        policy.ensureOrders().add(order);
        assertNull(policy.firstMatching("anything", "boss"),
                "an order never resolves its author's own request");
        assertNotNull(policy.firstMatching("anything", "someone-else"));
    }

    @Test
    void contestedJudgmentsStayManual() {
        // Complaint and fine-appeal queues never accept standing orders or
        // timeouts — cycling through every allowed mode only yields
        // manual/digest.
        for (int i = 0; i < 6; i++) {
            delegation.cycleMode(comisar, "COMPLAINT_REVIEW");
            delegation.cycleMode(comisar, "FINE_APPEAL");
        }
        var view = delegation.surface(comisar);
        for (var policy : view.policies()) {
            if (policy.action() == DelegableAction.COMPLAINT_REVIEW
                    || policy.action() == DelegableAction.FINE_APPEAL) {
                assertTrue(policy.mode() == DelegationMode.MANUAL
                        || policy.mode() == DelegationMode.DIGEST);
            }
        }
        delegation.issueOrder(comisar, "FINE_APPEAL", "ANY");
        assertTrue(comisar.messages.stream().anyMatch(m -> m.contains("nu acceptă ordine permanente")),
                "standing orders on appeal queues are refused");
        delegation.cycleTimeout(comisar, "FINE_APPEAL");
        assertTrue(comisar.messages.stream().anyMatch(m -> m.contains("nu acceptă decizii automate")),
                "timeouts on appeal queues are refused");
    }

    @Test
    void nonAuthorityCannotTouchDelegation() {
        delegation.cycleMode(civilian, "JOIN_PETITION");
        assertEquals(DelegationMode.MANUAL,
                delegationRepo.read().policy(DelegableAction.JOIN_PETITION) == null
                        ? DelegationMode.MANUAL
                        : delegationRepo.read().policy(DelegableAction.JOIN_PETITION).mode);
        assertTrue(delegation.surface(civilian).digest().isEmpty());
        assertTrue(delegation.surface(civilian).policies().isEmpty());
    }

    @Test
    void deniedResolutionIsRecordedAndNotRetried() {
        // Order author loses commissioner status before the sweep — the
        // request cannot be resolved, is recorded REFUSED, and stays pending
        // for manual review instead of retry-looping.
        String pretendAuthority = server.add("excomisar").uuid().toString();
        var store = delegationRepo.read();
        var policy = new com.dwurdy.straja.domain.model.DelegationPolicy();
        policy.mode = DelegationMode.STANDING_ORDER;
        StandingOrder order = new StandingOrder();
        order.orderId = "SO-JOIN_PETITION-1";
        order.authorUuid = pretendAuthority;
        order.authorName = "excomisar";
        order.filter = "*";
        order.createdAt = clock.nowMillis();
        order.active = true;
        policy.ensureOrders().add(order);
        store.ensurePolicies().put("JOIN_PETITION", policy);
        delegationRepo.write(store);
        var request = joins.submit("newbie", "fierar", "");

        delegation.tick();
        delegation.tick();

        assertEquals(JoinRequestStatus.PENDING, joins.find(request.requestId).status);
        var decisions = delegationRepo.read().ensureDecisions();
        assertEquals(1, decisions.size(), "a refused delegation is recorded once");
        assertEquals("REFUSED", decisions.values().iterator().next().outcome);
    }

    @Test
    void digestBatchesAllQueuesForTheAuthority() {
        joins.submit("newbie", "fierar", "");
        assertTrue(audiences.request(member, "motiv"));

        var view = delegation.surface(comisar);

        assertEquals(2, view.digest().size());
        assertTrue(view.digest().stream().anyMatch(e ->
                e.action() == DelegableAction.JOIN_PETITION
                        && e.reviewActionId().startsWith("join-review:")));
        assertTrue(view.digest().stream().anyMatch(e ->
                e.action() == DelegableAction.AUDIENCE_REQUEST
                        && e.reviewActionId().startsWith("audience-review:")));
    }
}
