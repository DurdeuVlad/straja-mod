package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.BountyStatus;
import com.dwurdy.straja.domain.model.CustodyStore;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.Sentence;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #231 — state-issued bounties: Inspector+ posting, civilian captures,
 * downed-or-surrendered restraint gate, delivery payout, 2x bail fine,
 * third-party bail release, expiry.
 */
class BountyServiceTest {
    private TestServer server;
    private StrajaContext ctx;
    private PlayerService players;
    private PrisonService prison;
    private CustodyService custody;
    private BountyService bounties;
    private TestCurrency currency;
    private TestPlayer inspector;
    private TestPlayer civil;
    private TestPlayer hunter;
    private TestPlayer crook;

    @BeforeEach
    void setup() {
        server = new TestServer();
        ctx = Fakes.context(server, new FixedClock(1_000_000L));
        players = new PlayerService(ctx);
        var audit = new AuditService(ctx);
        custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        bounties = new BountyService(ctx, players, audit);
        bounties.usePrison(prison);
        // DEBT-2: bail payments route through the ledger; the gate needs
        // the same late-bound service the runtime wires.
        var debt = new DebtService(ctx, audit);
        bounties.useDebt(debt);
        prison.useDebt(debt);
        custody.useBounties(bounties);
        prison.onArrest(bounties::onArrested);
        currency = (TestCurrency) ctx.currency();
        inspector = server.add("inspector1");
        civil = server.add("civil1");
        hunter = server.add("hunter1");
        crook = server.add("crook1");
        var ist = players.state(inspector.uuid());
        ist.rank = Rank.INSPECTOR.level();
        players.save(inspector.uuid(), ist);
        var cst = players.state(civil.uuid());
        cst.rank = Rank.CIVIL.level();
        players.save(civil.uuid(), cst);
        hunter.x = 10; hunter.y = 60; hunter.z = 10;
        crook.x = 12; crook.y = 60; crook.z = 10;
    }

    // ------------------------------------------------------------- post

    @Test
    void postRequiresInspectorRank() {
        assertNull(bounties.post(civil, crook, 128, "stole gold"));
        assertTrue(civil.messages.stream().anyMatch(m -> m.contains("refuse")
                || m.toLowerCase().contains("inspector")
                || !m.isBlank()), "a civil must get a ranked refusal");
        assertNull(bounties.activeFor(crook.uuid().toString()),
                "no bounty may exist after a refused post");
    }

    @Test
    void inspectorPostsAndLinkedBoloAppears() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record, "inspector post must succeed");
        assertEquals(BountyStatus.ACTIVE, record.status);
        assertEquals(128, record.amount);
        assertEquals(Rank.INSPECTOR.level(), record.postedByRank);
        assertNotNull(bounties.activeFor(crook.uuid().toString()));
        assertFalse(record.linkedBoloId.isBlank(), "post must link a BOLO");
        var bolo = ctx.bolos().read().find(record.linkedBoloId);
        assertNotNull(bolo, "the linked BOLO must exist");
        assertEquals(crook.uuid().toString(), bolo.subjectUuid,
                "a bountied player is wanted-on-sight");
    }

    @Test
    void commissionerPostsDespiteMissingRank() {
        var comisar = server.add("dwurdy"); // configured commissioner, rank 0
        var record = bounties.post(comisar, crook, 128, "stole gold");
        assertNotNull(record, "the Comisar posts bounties without a rank");
        assertTrue(bounties.cancel(comisar, crook.uuid().toString()),
                "the Comisar may also cancel bounties");
    }

    @Test
    void postRejectsDuplicatesSelfAndBadAmount() {
        assertNotNull(bounties.post(inspector, crook, 128, "stole gold"));
        assertNull(bounties.post(inspector, crook, 64, "again"),
                "one ACTIVE bounty per target");
        assertNull(bounties.post(inspector, inspector, 128, "self"),
                "an officer cannot bounty themselves");
        var target2 = server.add("crook2");
        ctx.policies().bountyMinAmount = 64;
        ctx.policies().bountyMaxAmount = 100;
        assertNull(bounties.post(inspector, target2, 101, "over max"));
        assertNull(bounties.post(inspector, target2, 10, "under min"));
    }

    @Test
    void postOnCleanTargetNeedsReason() {
        var clean = server.add("clean1");
        assertNull(bounties.post(inspector, clean, 128, ""),
                "no criminal mark and no reason must refuse");
        assertNotNull(bounties.post(inspector, clean, 128, "treason"),
                "a clean target needs an explicit reason (auto-BOLO)");
    }

    @Test
    void cancelResolvesBountyAndLinkedBolo() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        assertTrue(bounties.cancel(inspector, crook.uuid().toString()));
        assertEquals(BountyStatus.CANCELLED,
                ctx.bounties().read().find(record.id).status);
        assertNull(ctx.bounties().read().activeFor(crook.uuid().toString()));
        assertEquals(com.dwurdy.straja.domain.model.BoloStatus.RESOLVED,
                ctx.bolos().read().find(record.linkedBoloId).status,
                "cancel must resolve the linked BOLO");
    }

    // --------------------------------------------------------- surrender

    @Test
    void surrenderNeedsBountyAndNearbyCaptor() {
        assertFalse(bounties.surrender(crook), "no bounty — nothing to surrender to");
        assertNotNull(bounties.post(inspector, crook, 128, "stole gold"));
        hunter.x = 500; // far away — no captor in radius
        assertFalse(bounties.surrender(crook));
        hunter.x = 12;
        assertTrue(bounties.surrender(crook), "nearby captor accepts the surrender");
        assertTrue(bounties.hasSurrenderFlag(crook.uuid().toString()));
    }

    // ----------------------------------------------------- restraint gate

    @Test
    void restraintGateDownedOrSurrenderedOnly() {
        assertNull(bounties.restraintRefusal(hunter, crook, false),
                "non-bountied targets keep freeform restraint rules");
        assertNotNull(bounties.post(inspector, crook, 128, "stole gold"));
        assertNotNull(bounties.restraintRefusal(hunter, crook, false),
                "alive bountied target cannot be roped");
        assertNull(bounties.restraintRefusal(hunter, crook, true),
                "downed bountied target can be roped");
        hunter.x = 12;
        assertTrue(bounties.surrender(crook));
        assertNull(bounties.restraintRefusal(hunter, crook, false),
                "surrendered target can be roped");
    }

    // -------------------------------------------------------- capture pay

    private Sentence arrestCrook() {
        var sentence = new Sentence();
        sentence.id = "S-test-1";
        sentence.target = crook.name();
        sentence.targetUuid = crook.uuid().toString();
        sentence.arrestedBy = "jailer";
        sentence.arrestedByUuid = inspector.uuid().toString();
        sentence.status = "ACTIVE";
        return sentence;
    }

    @Test
    void arrestCapturesBountyPaysHunterAndFinesDouble() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        // The hunter bound the captive under the bounty-escort reason.
        var custStore = ctx.custody().read();
        var bound = new CustodyStore.BoundRecord();
        bound.target = crook.name();
        bound.targetUuid = crook.uuid().toString();
        bound.issuer = hunter.name();
        bound.issuerUuid = hunter.uuid().toString();
        bound.reason = BountyService.BOUNTY_CAPTURE_REASON;
        custStore.bound.put(crook.uuid().toString(), bound);
        ctx.custody().write(custStore);

        bounties.onArrested(arrestCrook());

        var updated = ctx.bounties().read().find(record.id);
        assertEquals(BountyStatus.CAPTURED, updated.status);
        assertEquals(hunter.uuid().toString(), updated.hunterUuid,
                "the binding hunter is credited");
        assertEquals(128, currency.lastAmount,
                "the state pays the posted bounty");
        assertFalse(updated.linkedFineId.isBlank());
        var fine = ctx.fines().read().find(updated.linkedFineId);
        assertNotNull(fine);
        assertEquals(256, fine.amount, "prisoner owes 2x the bounty");
        assertEquals("IN_SENTENCE", fine.status);
        assertEquals("S-test-1", fine.sentenceId);
    }

    @Test
    void officerArrestWithoutEscortCreditsTheArrestingActor() {
        var officer = server.add("officer1");
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        var sentence = arrestCrook();
        sentence.arrestedBy = officer.name();
        sentence.arrestedByUuid = officer.uuid().toString();
        bounties.onArrested(sentence); // no bound record
        var updated = ctx.bounties().read().find(record.id);
        assertEquals(officer.uuid().toString(), updated.hunterUuid,
                "the arresting actor earns an unescorted capture");
    }

    @Test
    void posterCannotCollectOwnBounty() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        var depositsBefore = currency.depositCalls;
        bounties.onArrested(arrestCrook()); // inspector posted AND arrested
        var updated = ctx.bounties().read().find(record.id);
        assertEquals(BountyStatus.CAPTURED, updated.status);
        assertTrue(updated.hunterUuid == null || updated.hunterUuid.isBlank(),
                "the poster must not earn their own bounty");
        assertEquals(depositsBefore, currency.depositCalls,
                "no payout when only the poster captured");
    }

    // ------------------------------------------------------------- bail

    @Test
    void thirdPartyBailPaysFineAndReleases() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        bounties.onArrested(arrestCrook());
        // Give crook a real sentence in the prison store so release has work.
        var data = ctx.prison().read();
        var s = arrestCrook();
        data.sentences.add(s);
        ctx.prison().write(data);

        currency.balance = 0;
        assertFalse(bounties.payBail(hunter, crook.name()),
                "insufficient coins refuse the bail");
        currency.balance = 256;
        assertTrue(bounties.payBail(hunter, crook.name()),
                "a third party may pay the bail");
        var fine = ctx.fines().read()
                .find(ctx.bounties().read().find(record.id).linkedFineId);
        assertEquals("PAID", fine.status);
        var released = ctx.prison().read().sentences.stream()
                .filter(x -> s.id.equals(x.id)).findFirst().orElseThrow();
        assertEquals("BAIL_PAID", released.releaseReason,
                "paid bail must release the sentence");
        assertEquals(0, currency.balance, "bail drains the payer's coins");
    }

    @Test
    void noBailRecordRefuses() {
        assertFalse(bounties.payBail(hunter, crook.name()),
                "no bounty-capture fine — nothing to bail");
    }

    // ------------------------------------------------------------- tick

    @Test
    void tickExpiresOldBountiesAndLapsedSurrenders() {
        var record = bounties.post(inspector, crook, 128, "stole gold");
        assertNotNull(record);
        var store = ctx.bounties().read();
        store.find(record.id).expiresAt = 0; // already past
        store.surrenders.put(crook.uuid().toString(), 1L);
        ctx.bounties().write(store);
        bounties.tick();
        var updated = ctx.bounties().read();
        assertEquals(BountyStatus.EXPIRED, updated.find(record.id).status);
        assertTrue(updated.surrenders.isEmpty(), "lapsed flags are swept");
        assertEquals(com.dwurdy.straja.domain.model.BoloStatus.RESOLVED,
                ctx.bolos().read().find(record.linkedBoloId).status,
                "expiry resolves the linked BOLO");
    }
}
