package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Complaint;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.support.Fakes;
import com.dwurdy.straja.support.Fakes.FixedClock;
import com.dwurdy.straja.support.Fakes.TestPlayer;
import com.dwurdy.straja.support.Fakes.TestServer;
import com.dwurdy.straja.support.Fakes.TestWorld;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** FUN-11: eligibility, docket, hearings, conditions, delegation. */
class ParoleServiceTest {
    private TestServer server;
    private FixedClock clock;
    private com.dwurdy.straja.application.StrajaContext ctx;
    private PlayerService players;
    private AuditService audit;
    private PrisonService prison;
    private ParoleService parole;
    private TestPlayer officer;
    private TestPlayer inmate;

    @BeforeEach
    void setup() {
        server = new TestServer();
        clock = new FixedClock(1_000_000L);
        ctx = Fakes.context(server, clock);
        players = new PlayerService(ctx);
        audit = new AuditService(ctx);
        var custody = new CustodyService(ctx, players, audit);
        prison = new PrisonService(ctx, players, audit, custody);
        prison.useSeizure(new SeizureService(ctx, audit));
        prison.useBolos(new BoloService(ctx, players, audit));
        prison.useCamps(new LaborCampService(ctx, players, audit));
        parole = new ParoleService(ctx, players, audit);
        parole.usePrison(prison);

        // "dwurdy" is the commissioner name the test harness recognizes.
        officer = server.add("dwurdy");
        officer.op = true;

        ((TestWorld) ctx.world()).room("minecraft:overworld", -1, 59, -1, 6, 66, 6,
                -1, 61, 2);
        assertTrue(prison.createCell(officer, "celula_1",
                "minecraft:overworld", 0, 60, 0, 5, 65, 5));
        inmate = server.add("inmate");
        inmate.dimension = "minecraft:overworld";
        prison.arrest(inmate, null, 1, officer, null);
        inmate.x = 2; inmate.y = 61; inmate.z = 2;
    }

    private void makeEligible() {
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(inmate.uuid().toString());
        rec.standing = 80;
        ctx.prisonerRegister().write(reg);
        var store = ctx.prison().read();
        var sentence = store.activeSentenceFor(inmate.uuid().toString());
        // Serve 60% of the active time.
        long total = sentence.sentenceDays
                * Math.max(1, ctx.policies().prisonActiveMinutesPerMinecraftDay) * 60_000L;
        sentence.remainingActiveMs = (long) (total * 0.4);
        ctx.prison().write(store);
    }

    private String status(TestPlayer p) {
        var rec = ctx.prisonerRegister().read().prisoner(p.uuid().toString());
        return rec == null || rec.status == null ? "" : rec.status.name();
    }

    private com.dwurdy.straja.domain.model.HearingRecord file() {
        assertTrue(parole.file(inmate, "PAROLE", "am muncit cinstit"));
        return ctx.paroles().read().pendingFor(inmate.uuid().toString());
    }

    // ------------------------------------------------------------ eligibility

    @Test
    void eligibilityBreakdownShowsBlockers() {
        var checks = parole.eligibility(inmate.uuid().toString());
        assertFalse(parole.eligible(inmate.uuid().toString()));
        assertTrue(checks.stream().anyMatch(c -> c.key().equals("standing") && !c.ok()));
        assertTrue(checks.stream().anyMatch(c -> c.key().equals("served") && !c.ok()));
        makeEligible();
        assertTrue(parole.eligible(inmate.uuid().toString()),
                "standing + served time unlock the filing gate");
    }

    @Test
    void unpaidFineBlocksEligibility() {
        makeEligible();
        var fines = ctx.fines().read();
        var fine = new com.dwurdy.straja.domain.model.Fine();
        fine.id = "F-1";
        fine.targetUuid = inmate.uuid().toString();
        fine.status = "ISSUED";
        fines.fines.add(fine);
        ctx.fines().write(fines);
        assertFalse(parole.eligible(inmate.uuid().toString()));
        assertFalse(parole.file(inmate, "PAROLE", ""));
    }

    @Test
    void recentEscapeBlocksEligibility() {
        makeEligible();
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(inmate.uuid().toString());
        rec.lastEscapeAt = clock.nowMillis();
        ctx.prisonerRegister().write(reg);
        assertFalse(parole.eligible(inmate.uuid().toString()));
    }

    // ------------------------------------------------------------ docket

    @Test
    void filingCreatesOrderedDurableDocket() {
        makeEligible();
        var h = file();
        assertNotNull(h);
        assertEquals("FILED", h.status);
        assertFalse(parole.file(inmate, "PAROLE", "again"),
                "one pending case per prisoner");

        // A fresh service instance over the same stores sees the row —
        // a prisoner's place in line survives restarts.
        var parole2 = new ParoleService(ctx, players, audit);
        parole2.usePrison(prison);
        var pending = parole2.pendingDocket();
        assertEquals(1, pending.size());
        assertEquals(h.id, pending.get(0).id);
    }

    @Test
    void openRequiresThePrisonerToAppear() {
        makeEligible();
        var h = file();
        inmate.online = false;
        assertFalse(parole.open(officer, h.id), "an offline prisoner cannot appear");
        inmate.online = true;
        assertTrue(parole.open(officer, h.id));
        assertEquals("HEARING", ctx.paroles().read().hearing(h.id).status);
    }

    @Test
    void decisionDeniedStartsRefileCooldown() {
        makeEligible();
        var h = file();
        assertTrue(parole.open(officer, h.id));
        assertTrue(parole.decide(officer, h.id, "denied"));
        var decided = ctx.paroles().read().hearing(h.id);
        assertEquals("DENIED", decided.status);
        assertTrue(decided.refileAfter > clock.nowMillis());
        assertFalse(parole.file(inmate, "PAROLE", "din nou"),
                "a denied prisoner waits out the cooldown");
        clock.advance(25L * 3_600_000L);
        assertTrue(parole.file(inmate, "PAROLE", "după cooldown"));
    }

    // ------------------------------------------------------------ outcomes

    @Test
    void paroledReleasesIntoConditions() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        assertTrue(parole.decide(officer, h.id, "paroled"));
        assertEquals("PAROLED", status(inmate), "the parolee marker stays visible");
        var condition = ctx.paroles().read().parole(inmate.uuid().toString());
        assertNotNull(condition);
        assertEquals("ACTIVE", condition.status);
        assertNull(ctx.prison().read().activeSentenceFor(inmate.uuid().toString()),
                "the sentence is consumed by the release");
    }

    @Test
    void conditionalSettleBuysRelease() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        assertTrue(parole.decide(officer, h.id, "conditional"));
        assertFalse(parole.settle(inmate), "labor account starts empty");
        assertEquals("IN_CELL", status(inmate));

        var reg = ctx.prisonerRegister().read();
        reg.prisoner(inmate.uuid().toString()).laborAccount =
                ctx.policies().paroleBailAmount + 5;
        ctx.prisonerRegister().write(reg);
        assertTrue(parole.settle(inmate));
        assertEquals("PAROLED", status(inmate));
        assertEquals(5, ctx.prisonerRegister().read()
                .prisoner(inmate.uuid().toString()).laborAccount,
                "bail debits exactly the asked amount");
    }

    @Test
    void conditionalOfferLapsesIntoDenial() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "conditional");
        clock.advance(25L * 3_600_000L);
        parole.tick();
        assertEquals("DENIED", ctx.paroles().read().hearing(h.id).status);
    }

    @Test
    void settleNeverConfiscatesBailForAFailedRelease() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        assertTrue(parole.decide(officer, h.id, "conditional"));
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(inmate.uuid().toString());
        rec.laborAccount = ctx.policies().paroleBailAmount + 5;
        rec.status = PrisonerStatus.FUGITIVE;
        ctx.prisonerRegister().write(reg);

        assertFalse(parole.settle(inmate),
                "a runner cannot buy release — and must not lose the bail");
        assertEquals(ctx.policies().paroleBailAmount + 5,
                ctx.prisonerRegister().read()
                        .prisoner(inmate.uuid().toString()).laborAccount,
                "the labor credit stays when the release cannot happen");
        assertEquals("CONDITIONAL", ctx.paroles().read().hearing(h.id).status,
                "the case stays open for an officer decision");
    }

    @Test
    void expiredConditionalCannotShadowALiveRefile() {
        makeEligible();
        var first = file();
        parole.open(officer, first.id);
        assertTrue(parole.decide(officer, first.id, "conditional"));
        clock.advance(25L * 3_600_000L);
        parole.tick();
        assertEquals("DENIED", ctx.paroles().read().hearing(first.id).status,
                "the first offer lapses into denial past its window");
        clock.advance(25L * 3_600_000L); // past the DENIED refile cooldown

        var second = file();
        parole.open(officer, second.id);
        assertTrue(parole.decide(officer, second.id, "conditional"));
        var reg = ctx.prisonerRegister().read();
        reg.prisoner(inmate.uuid().toString()).laborAccount =
                ctx.policies().paroleBailAmount;
        ctx.prisonerRegister().write(reg);
        assertTrue(parole.settle(inmate),
                "the live offer settles even with a stale record ahead of it");
        assertEquals("PAROLED", status(inmate));
    }

    @Test
    void objectionRecordsOncePerVictim() {
        makeEligible();
        var victim = server.add("victim");
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-1";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "UNDER_REVIEW";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        assertTrue(parole.open(officer, h.id));
        assertTrue(parole.object(victim, h.id));
        assertTrue(parole.object(victim, h.id),
                "a repeat objection inside the window stays idempotent");
        assertEquals(1, ctx.paroles().read().hearing(h.id).objections,
                "one victim records one objection");
        assertTrue(parole.openObjectionsFor(victim.uuid().toString()).isEmpty(),
                "the desk row clears once the objection is recorded");
    }

    @Test
    void noticeLinksLiveAsLongAsTheWindowTheyGate() {
        makeEligible();
        var victim = new RecordingPlayer("victim");
        victim.register();
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-1";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "UNDER_REVIEW";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        parole.open(officer, h.id);
        assertEquals(ctx.policies().paroleObjectionMinutes * 60_000L,
                victim.lastLinkTtl,
                "the objection link expires with the objection window");

        var adv = new RecordingPlayer("adv");
        adv.register();
        assertTrue(parole.advocate(inmate, "adv"));
        assertEquals(24L * 3_600_000L, adv.lastLinkTtl,
                "the advocate ask stands until answered");

        // Take over the prisoner's online gateway so the offer's ttl shows.
        var inmateRec = new RecordingPlayer("inmate", inmate.uuid());
        inmateRec.register();
        clock.advance(6L * 60_000L);
        parole.decide(officer, h.id, "conditional");
        assertEquals(ctx.policies().paroleConditionalHours * 3_600_000L,
                inmateRec.lastLinkTtl,
                "the bail link expires with the conditional window");
    }

    // ------------------------------------------------------------ conditions

    @Test
    void missedCheckInFlagsTheParoleeWanted() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        parole.checkIn(inmate);
        clock.advance(13L * 3_600_000L);
        parole.tick();
        assertEquals("FUGITIVE", status(inmate),
                "a missed check-in routes through the normal arrest pipeline");
        assertEquals("VIOLATED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status);
    }

    @Test
    void paroleWindowCompletesIntoFreedom() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        parole.checkIn(inmate);
        clock.advance(73L * 3_600_000L);
        parole.tick();
        assertEquals("COMPLETED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status);
        assertEquals("RELEASED", status(inmate));
    }

    @Test
    void rearrestViolatesActiveParole() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        prison.arrest(inmate, null, 1, officer, "fresh crime");
        parole.tick();
        assertEquals("VIOLATED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status);
    }

    @Test
    void offlineParoleeDeadlinesFreeze() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        parole.decide(officer, h.id, "paroled");
        parole.tick(); // stamps the persisted sweep baseline while online
        var before = ctx.paroles().read().parole(inmate.uuid().toString()).checkInBy;
        inmate.online = false;
        clock.advance(60_000L);
        parole.tick();
        var after = ctx.paroles().read().parole(inmate.uuid().toString());
        assertEquals(before + 60_000L, after.checkInBy,
                "offline time does not burn the check-in clock");
        assertEquals("ACTIVE", after.status);
        inmate.online = true;
        clock.advance(14L * 3_600_000L);
        parole.tick();
        assertEquals("VIOLATED", ctx.paroles().read()
                .parole(inmate.uuid().toString()).status,
                "back online, the clock runs again");
        assertEquals("FUGITIVE", status(inmate));
    }

    @Test
    void escapeBetweenFilingAndDecisionBlocksParole() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        var reg = ctx.prisonerRegister().read();
        reg.prisoner(inmate.uuid().toString()).status = PrisonerStatus.FUGITIVE;
        ctx.prisonerRegister().write(reg);
        assertFalse(parole.decide(officer, h.id, "paroled"),
                "a runner cannot be paroled — the case stays for a denial");
        assertEquals("HEARING", ctx.paroles().read().hearing(h.id).status);
    }

    // ------------------------------------------------------------ hearing sides

    @Test
    void victimObjectionBoundsTheDecision() {
        makeEligible();
        var victim = server.add("victim");
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-1";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "UNDER_REVIEW";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        assertTrue(parole.open(officer, h.id));
        assertFalse(parole.decide(officer, h.id, "paroled"),
                "the objection window must run before any decision");
        assertTrue(parole.object(victim, h.id));
        assertEquals(1, ctx.paroles().read().hearing(h.id).objections);
        clock.advance(6L * 60_000L);
        assertTrue(parole.decide(officer, h.id, "paroled"));
        var decided = ctx.paroles().read().hearing(h.id);
        assertEquals("PAROLED", decided.status);
        assertFalse(parole.object(victim, h.id), "a decided case takes no objections");
    }

    @Test
    void advocateConsentIsRecordedWithoutAuthority() {
        makeEligible();
        var h = file();
        var advocate = server.add("avocat");
        assertTrue(parole.advocate(inmate, "avocat"));
        assertEquals("ASKED", ctx.paroles().read().hearing(h.id).advocateStatus);
        assertTrue(parole.advocateAnswer(advocate, true));
        var decided = ctx.paroles().read().hearing(h.id);
        assertEquals("AGREED", decided.advocateStatus);
        assertEquals(advocate.uuid().toString(), decided.advocateUuid);
        // The advocate speaks — they cannot open or decide a case.
        assertFalse(parole.open(advocate, h.id));
    }

    // ------------------------------------------------------------ delegation

    @Test
    void delegationApprovesOnlyCleanCases() {
        makeEligible();
        var h = file();
        assertEquals("CLEAN", parole.matchValue(inmate.uuid().toString()));
        assertTrue(parole.decideDelegated("comisar-uuid", "Comisar", h.id));
        assertEquals("PAROLED", ctx.paroles().read().hearing(h.id).status);
        assertEquals("PAROLED", status(inmate));
    }

    @Test
    void delegationSkipsContestedCases() {
        makeEligible();
        var victim = server.add("victim");
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-9";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "SUBMITTED";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        assertEquals("CONTESTED", parole.matchValue(inmate.uuid().toString()));
        assertFalse(parole.decideDelegated("comisar-uuid", "Comisar", h.id),
                "a victim exists to notify — the case waits for a human");
        assertEquals("FILED", ctx.paroles().read().hearing(h.id).status);
    }

    // ------------------------------------------------------------ desk reads

    @Test
    void surfaceFollowsThePrisonerThroughTheLoop() {
        var s0 = parole.surface(inmate.uuid().toString());
        assertTrue(s0.prisoner(), "sentenced custody registers as a prisoner row");
        assertFalse(s0.eligible());
        assertEquals("", s0.pendingId());
        assertFalse(s0.parolee());
        assertEquals("", s0.conditionalId());

        makeEligible();
        assertTrue(parole.surface(inmate.uuid().toString()).eligible());

        var h = file();
        var s1 = parole.surface(inmate.uuid().toString());
        assertEquals(h.id, s1.pendingId());
        assertEquals("PAROLE", s1.pendingKind());

        parole.open(officer, h.id);
        parole.decide(officer, h.id, "conditional");
        var s2 = parole.surface(inmate.uuid().toString());
        assertEquals(h.id, s2.conditionalId(), "the bail window surfaces for settle");
        assertTrue(s2.bailAmount() > 0);
        assertEquals("", s2.pendingId(), "a decided case is no longer pending");

        var reg = ctx.prisonerRegister().read();
        reg.prisoner(inmate.uuid().toString()).laborAccount =
                ctx.policies().paroleBailAmount;
        ctx.prisonerRegister().write(reg);
        parole.settle(inmate);
        var s3 = parole.surface(inmate.uuid().toString());
        assertTrue(s3.parolee(), "post-release the check-in row appears");
        assertFalse(s3.prisoner(), "released is no longer a prisoner row");
    }

    @Test
    void officerCapableIsSilentAndScoped() {
        assertTrue(parole.officerCapable(officer), "an op reviews the docket");
        int messages = inmate.messages.size();
        assertFalse(parole.officerCapable(inmate), "the prisoner never reviews");
        assertFalse(parole.officerCapable(server.add("civil")),
                "a civilian never reviews");
        assertEquals(messages, inmate.messages.size(),
                "the silent check must not spam refusals");
    }

    @Test
    void reviewDocketListsOnlyOpenCases() {
        makeEligible();
        var h = file();
        assertEquals(1, parole.reviewDocket().size());
        parole.open(officer, h.id);
        assertEquals(1, parole.reviewDocket().size(),
                "a live hearing still sits on the docket");
        parole.decide(officer, h.id, "denied");
        assertTrue(parole.reviewDocket().isEmpty(),
                "decided cases leave the review page");
        assertEquals(h.id, parole.findHearing(h.id).id,
                "the record stays findable after the decision");
    }

    @Test
    void objectionWindowOpensOnlyForRealVictims() {
        makeEligible();
        var victim = server.add("victim");
        var bystander = server.add("bystander");
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-7";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "UNDER_REVIEW";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        assertTrue(parole.openObjectionsFor(victim.uuid().toString()).isEmpty(),
                "no window before the hearing opens");
        parole.open(officer, h.id);
        assertEquals(java.util.List.of(h.id),
                parole.openObjectionsFor(victim.uuid().toString()),
                "the victim's desk row appears while the window runs");
        assertTrue(parole.openObjectionsFor(bystander.uuid().toString()).isEmpty(),
                "a non-victim never sees an objection row");
        clock.advance(6L * 60_000L);
        assertTrue(parole.openObjectionsFor(victim.uuid().toString()).isEmpty(),
                "the row leaves when the window closes");
    }

    @Test
    void advocacyAskTracksTheAskedHearing() {
        makeEligible();
        var h = file();
        var advocate = server.add("avocat");
        assertNull(parole.advocacyAskFor(advocate.uuid().toString()));
        parole.advocate(inmate, "avocat");
        assertEquals(h.id, parole.advocacyAskFor(advocate.uuid().toString()),
                "the desk pair appears while consent is pending");
        parole.advocateAnswer(advocate, false);
        assertNull(parole.advocacyAskFor(advocate.uuid().toString()),
                "an answered ask drops off the desk");
    }

    // ------------------------------------------------------------ action links

    @Test
    void victimNoticeCarriesAnObjectLink() {
        makeEligible();
        var victim = new RecordingPlayer("victim");
        victim.register();
        var complaints = ctx.complaints().read();
        var c = new Complaint();
        c.id = "C-8";
        c.complainantUuid = victim.uuid().toString();
        c.accusedUuid = inmate.uuid().toString();
        c.status = "SUBMITTED";
        complaints.ensureComplaints().add(c);
        ctx.complaints().write(complaints);

        var h = file();
        parole.open(officer, h.id);
        assertEquals("straja.parole.victim_notice", victim.lastActionKey);
        assertEquals(java.util.List.of("parole-object:" + h.id), victim.lastLinks,
                "the notice must hand the victim the objection click");
    }

    @Test
    void advocateAskCarriesBothAnswers() {
        makeEligible();
        file();
        var advocate = new RecordingPlayer("avocat");
        advocate.register();
        parole.advocate(inmate, "avocat");
        assertEquals("straja.parole.advocate_asked", advocate.lastActionKey);
        assertEquals(java.util.List.of(
                        "parole-advocate-answer:accept", "parole-advocate-answer:decline"),
                advocate.lastLinks, "the ask hands over the one-click answer");
    }

    @Test
    void conditionalOfferCarriesTheSettleLink() {
        makeEligible();
        var h = file();
        parole.open(officer, h.id);
        var recording = new RecordingPlayer(inmate.name, inmate.uuid());
        recording.register();
        parole.decide(officer, h.id, "conditional");
        assertEquals("straja.parole.conditional_offer", recording.lastActionKey);
        assertEquals(java.util.List.of("parole-settle"), recording.lastLinks,
                "the bail offer must carry its own payment click");
    }

    /** Captures tellAction deliveries — the TestPlayer default drops links. */
    private final class RecordingPlayer extends TestPlayer {
        private final UUID overrideUuid;
        String lastActionKey;
        java.util.List<String> lastLinks = java.util.List.of();
        long lastLinkTtl = -1;

        RecordingPlayer(String name) {
            this(name, null);
        }

        RecordingPlayer(String name, UUID uuid) {
            super(name, 36);
            this.overrideUuid = uuid;
            this.online = true;
        }

        void register() {
            this.server = ParoleServiceTest.this.server;
            ParoleServiceTest.this.server.players.put(uuid(), this);
        }

        @Override public UUID uuid() {
            return overrideUuid != null ? overrideUuid : super.uuid();
        }

        @Override public void tellAction(String translationKey,
                java.util.List<PlayerGateway.ActionLink> links, Object... args) {
            lastActionKey = translationKey;
            lastLinks = links == null ? java.util.List.of()
                    : links.stream()
                            .map(PlayerGateway.ActionLink::actionId).toList();
            lastLinkTtl = links == null || links.isEmpty() || links.get(0) == null
                    ? -1 : links.get(0).ttlMillis();
        }
    }
}
