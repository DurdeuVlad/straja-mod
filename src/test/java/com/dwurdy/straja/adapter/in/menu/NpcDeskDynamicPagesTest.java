package com.dwurdy.straja.adapter.in.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.ParoleUseCase;
import com.dwurdy.straja.domain.model.BountyRecord;
import com.dwurdy.straja.domain.model.DispatchCall;
import com.dwurdy.straja.domain.model.HearingRecord;
import com.dwurdy.straja.domain.model.ManhuntRecord;
import com.dwurdy.straja.domain.model.TransferRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The dynamic pages are pure projections over live service snapshots:
 * a row must appear exactly when its state exists and must carry the
 * right parameterized action — never a typed command.
 */
class NpcDeskDynamicPagesTest {

    /** Content actions only — footer chrome (desk:back/prev/next) is not a verb. */
    private static List<String> actionIds(DeskPage page) {
        return page.buttons().stream()
                .map(DeskButton::actionId)
                .filter(a -> a != null && !a.startsWith("desk:")
                        && !a.startsWith("main:") && !a.startsWith("more:"))
                .toList();
    }

    private static ParoleUseCase.ParoleSurface surface(
            boolean prisoner, boolean eligible, String pendingId,
            String advocateStatus, boolean parolee,
            String conditionalId, int bail) {
        return new ParoleUseCase.ParoleSurface(prisoner, eligible, pendingId,
                "PAROLE", advocateStatus, parolee, conditionalId, bail);
    }

    // ---------- sourceFor ----------

    @Test
    void sourceForMapsActionIdsToLivePages() {
        assertEquals(DeskDynamicPages.PAROLE_STATUS,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("parole-status")).kind());
        assertEquals(DeskDynamicPages.PAROLE_DOCKET,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("parole-docket")).kind());
        assertEquals(DeskDynamicPages.TRADES,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("trades-list")).kind());
        assertEquals(DeskDynamicPages.DISPATCH,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("dispatch-board")).kind());
        assertEquals(DeskDynamicPages.ARMY_STATUS,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("army-status")).kind());
        assertEquals(DeskDynamicPages.ARMY_HISTORY,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("army-history")).kind());
        assertEquals(DeskDynamicPages.MANHUNT,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("manhunt-board")).kind());
        assertEquals(DeskDynamicPages.TRANSFER,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("transfer-board")).kind());
        assertEquals(DeskDynamicPages.BOUNTY,
                ((DeskPageSource.DynamicPage) DeskDynamicPages
                        .sourceFor("bounty-board")).kind());
        var hearing = (DeskPageSource.DynamicPage)
                DeskDynamicPages.sourceFor("parole-review:PAR-9");
        assertEquals(DeskDynamicPages.PAROLE_HEARING, hearing.kind());
        assertEquals("PAR-9", hearing.id(), "the record id must ride the page");
        assertNull(DeskDynamicPages.sourceFor("fine-pay:F-1"),
                "ordinary actions never spawn a dynamic page");
        assertNull(DeskDynamicPages.sourceFor(null));
    }

    // ---------- parole status ----------

    @Test
    void paroleStatusRendersChecksAndTheRightVerbs() {
        var checks = List.of(
                new ParoleUseCase.Check("custody", true, ""),
                new ParoleUseCase.Check("standing", false, "3/10"));
        var eligible = surface(true, true, "", "", false, "", 0);
        var page = DeskDynamicPages.paroleStatus(checks, eligible);
        var ids = actionIds(page);
        assertTrue(ids.contains("parole-file") && ids.contains("parole-appeal"),
                "an eligible prisoner files or appeals from the status page");
        assertFalse(ids.contains("parole-settle"));
        assertTrue(page.buttons().stream().anyMatch(b ->
                        b.icon().name().contains("✓") || b.icon().name().contains("✗")),
                "every check renders as a pass/fail tile");

        var filed = surface(true, false, "PAR-1", "", false, "", 0);
        ids = actionIds(DeskDynamicPages.paroleStatus(checks, filed));
        assertEquals(List.of("parole-advocate"), ids,
                "a filed case only offers the advocate row");
        assertTrue(DeskDynamicPages.paroleStatus(checks, filed).buttons().stream()
                        .anyMatch(b -> b.icon().name().contains("PAR-1")),
                "the pending file shows its id");

        var conditional = surface(true, false, "", "", false, "PAR-2", 500);
        assertEquals(List.of("parole-settle"),
                actionIds(DeskDynamicPages.paroleStatus(checks, conditional)));

        var paroled = surface(false, false, "", "", true, "", 0);
        assertEquals(List.of("parole-checkin"),
                actionIds(DeskDynamicPages.paroleStatus(checks, paroled)));
    }

    @Test
    void paroleStatusToleratesNulls() {
        var page = DeskDynamicPages.paroleStatus(null, null);
        assertFalse(page.buttons().isEmpty(), "the frame always renders");
        assertTrue(actionIds(page).isEmpty(),
                "no state means no actionable rows");
    }

    // ---------- docket & hearing ----------

    private static HearingRecord hearing(String id, String status) {
        var h = new HearingRecord();
        h.id = id;
        h.prisonerName = "Puscarias";
        h.status = status;
        return h;
    }

    @Test
    void paroleDocketLinksEveryCaseToItsPage() {
        var page = DeskDynamicPages.paroleDocket(
                List.of(hearing("PAR-1", "FILED"), hearing("PAR-2", "HEARING")), 0);
        assertEquals(List.of("parole-review:PAR-1", "parole-review:PAR-2"),
                actionIds(page));

        var empty = DeskDynamicPages.paroleDocket(List.of(), 0);
        assertTrue(actionIds(empty).isEmpty(),
                "an empty docket renders only the info tile");
    }

    @Test
    void paroleHearingExposesVerbsByStatus() {
        var filed = DeskDynamicPages.paroleHearing(hearing("PAR-1", "FILED"), 0L);
        assertEquals(List.of("parole-open:PAR-1"), actionIds(filed),
                "a filed case only offers opening the hearing");

        var live = DeskDynamicPages.paroleHearing(hearing("PAR-1", "HEARING"), 0L);
        assertTrue(actionIds(live).containsAll(List.of(
                        "parole-decide-paroled:PAR-1",
                        "parole-decide-conditional:PAR-1",
                        "parole-decide-denied:PAR-1")),
                "a live hearing offers all three verdicts");

        var decided = DeskDynamicPages.paroleHearing(hearing("PAR-1", "DENIED"), 0L);
        assertTrue(actionIds(decided).isEmpty(),
                "a decided case keeps only fact tiles");
    }

    @Test
    void paroleHearingDegradesForAVanishedRecord() {
        var page = DeskDynamicPages.paroleHearing(null, 0L);
        assertTrue(actionIds(page).isEmpty(),
                "a stale click renders the missing-record page, no verbs");
    }

    // ---------- trades / dispatch / v2 ----------

    @Test
    void tradesPageEmitsEnrolButtonsPerProfession() {
        var page = DeskDynamicPages.trades(
                List.of("FARMER", "MINER"), "");
        assertEquals(List.of("trades-enroll:FARMER", "trades-enroll:MINER"),
                actionIds(page));

        var pending = DeskDynamicPages.trades(List.of("FARMER"), "JR-7");
        assertTrue(pending.buttons().stream()
                        .anyMatch(b -> b.icon().name().contains("JR-7")),
                "a pending request renders as an info tile");
        assertEquals(List.of("trades-enroll:FARMER"), actionIds(pending));
    }

    @Test
    void dispatchPageOffersVerbsOnlyOnOpenCalls() {
        var open = new DispatchCall();
        open.id = "DSP-1";
        open.title = "Fur la târg";
        open.status = "OPEN";
        var taken = new DispatchCall();
        taken.id = "DSP-2";
        taken.title = " scandal";
        taken.status = "ACCEPTED";
        taken.acceptedByName = "Mihai";

        var ids = actionIds(DeskDynamicPages.dispatch(List.of(open, taken)));
        assertTrue(ids.containsAll(List.of(
                        "dispatch-accept:DSP-1", "dispatch-decline:DSP-1")),
                "an open call offers the accept/decline pair");
        assertFalse(ids.stream().anyMatch(a -> a.contains("DSP-2")),
                "a taken call stays an info tile — nobody double-takes it");
    }

    @Test
    void v2OfferCarriesExactlyTheClaimVerb() {
        var page = DeskDynamicPages.v2Offer("M-42", "Patrulează portul");
        assertEquals(List.of("v2-mission-claim:M-42"), actionIds(page));
    }

    // ---------- FUN-7/8 live boards ----------

    private static ManhuntRecord hunt(String id) {
        var h = new ManhuntRecord();
        h.id = id;
        h.coolAt = 120_000L;
        var r = new ManhuntRecord.Runner();
        r.name = "Evadat";
        h.runners.add(r);
        return h;
    }

    @Test
    void manhuntBoardLimitsCallOffToSeniors() {
        var junior = actionIds(DeskDynamicPages.manhuntBoard(
                List.of(hunt("MH-1")), false, 0L));
        assertTrue(junior.isEmpty(),
                "a junior officer reads the board but cannot call the hunt off");

        var senior = actionIds(DeskDynamicPages.manhuntBoard(
                List.of(hunt("MH-1")), true, 0L));
        assertEquals(List.of("manhunt-calloff:MH-1"), senior,
                "the senior verb rides the live record id");
    }

    private static TransferRecord ride(String id, String status, String escortUuid) {
        var t = new TransferRecord();
        t.id = id;
        t.status = status;
        t.prisonerName = "Puscarias";
        t.destLabel = "Tabăra Nord";
        t.escortDeadlineAt = 60_000L;
        if (escortUuid != null) {
            var e = new TransferRecord.Escort();
            e.uuid = escortUuid;
            t.escorts.add(e);
        }
        return t;
    }

    @Test
    void transferBoardGatesPickupToTheEscort() {
        var ordered = ride("TR-1", "ORDERED", "guard-1");
        var outsider = actionIds(DeskDynamicPages.transferBoard(
                List.of(ordered), "guard-2", false, 0L));
        assertEquals(List.of("transfer-join:TR-1"), outsider,
                "any on-duty guard may join an ORDERED ride");

        var escort = actionIds(DeskDynamicPages.transferBoard(
                List.of(ordered), "guard-1", false, 0L));
        assertTrue(escort.containsAll(
                        List.of("transfer-join:TR-1", "transfer-pickup:TR-1")),
                "the assigned escort also gets the pickup verb");

        var rolling = ride("TR-2", "IN_TRANSIT", "guard-1");
        assertTrue(actionIds(DeskDynamicPages.transferBoard(
                        List.of(rolling), "guard-1", false, 0L)).isEmpty(),
                "a moving convoy offers no verbs");
    }

    @Test
    void bountyBoardIsPublicAndCancelsForSeniorsOnly() {
        var r = new BountyRecord();
        r.id = "BTY-1";
        r.targetName = "Hotul";
        r.amount = 200;
        r.postedBy = "Comisar";
        r.expiresAt = 7_200_000L;

        assertTrue(actionIds(DeskDynamicPages.bountyBoard(
                        List.of(r), false, 0L)).isEmpty(),
                "the public board is read-only for ordinary players");
        assertEquals(List.of("bounty-cancel:BTY-1"),
                actionIds(DeskDynamicPages.bountyBoard(List.of(r), true, 0L)),
                "seniors retract the bounty and its linked BOLO");
    }

    @Test
    void liveBoardsDegradeToAnEmptyState() {
        assertTrue(actionIds(DeskDynamicPages.manhuntBoard(List.of(), false, 0L)).isEmpty());
        assertTrue(actionIds(DeskDynamicPages.transferBoard(List.of(), "g", false, 0L)).isEmpty());
        assertTrue(actionIds(DeskDynamicPages.bountyBoard(List.of(), false, 0L)).isEmpty());
    }
}
