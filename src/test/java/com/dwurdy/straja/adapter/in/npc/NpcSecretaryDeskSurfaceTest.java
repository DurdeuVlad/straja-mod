package com.dwurdy.straja.adapter.in.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.AdminRoleplayUseCase;
import com.dwurdy.straja.application.port.in.DelegationUseCase;
import com.dwurdy.straja.domain.model.DelegableAction;
import com.dwurdy.straja.domain.model.DelegationMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Issue-4 surface parity: the delegation console emits on the native
 * Secretary surface exactly like the CNPC provider renders it (service-gated
 * view, digest rows reusing native review actions, per-queue policy rows),
 * and Comisar admin rows carry member names so the desk's confirm page can
 * name its subject.
 */
class NpcSecretaryDeskSurfaceTest {
    private static final Path ADMIN_SERVICE = Path.of(
            "src/main/java/com/dwurdy/straja/application/service/AdminService.java");

    private static List<String> ids(List<NpcPlayerSurface.ChatAction> actions) {
        return actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList();
    }

    private static DelegationUseCase.SurfaceView view(
            List<DelegationUseCase.DigestEntry> digest,
            List<DelegationUseCase.PolicyView> policies,
            List<DelegationUseCase.OrderOption> options) {
        return new DelegationUseCase.SurfaceView(digest, policies, options);
    }

    @Test
    void nonAuthorityViewEmitsNoDelegationRows() {
        // The service hands non-authorities an empty view — mirroring the
        // CNPC provider's gating, nothing renders at all.
        assertTrue(NpcPlayerSurface.delegationActions(
                DelegationUseCase.SurfaceView.empty()).isEmpty());
        assertTrue(NpcPlayerSurface.delegationActions(null).isEmpty());
    }

    @Test
    void authoritySeesTheLedgerAndEveryQueueModeRow() {
        var emitted = NpcPlayerSurface.delegationActions(view(List.of(),
                List.of(new DelegationUseCase.PolicyView(
                                DelegableAction.JOIN_PETITION, DelegationMode.MANUAL, 0, List.of()),
                        new DelegationUseCase.PolicyView(
                                DelegableAction.AUDIENCE_REQUEST, DelegationMode.MANUAL, 0, List.of())),
                List.of()));
        var actionIds = ids(emitted);
        assertTrue(actionIds.contains("delegation-ledger"));
        assertTrue(actionIds.contains("delegation-mode:JOIN_PETITION"));
        assertTrue(actionIds.contains("delegation-mode:AUDIENCE_REQUEST"));
        assertFalse(actionIds.stream().anyMatch(id -> id.startsWith("delegation-order-")),
                "order options only render under a standing-order policy");
        assertFalse(actionIds.stream().anyMatch(id -> id.startsWith("delegation-revoke:")),
                "no active orders — nothing to revoke");
        assertFalse(actionIds.stream().anyMatch(id -> id.startsWith("delegation-window:")),
                "timeout windows only render under a timeout policy");
        var ledger = emitted.get(0);
        assertTrue(ledger.label().contains("0 în așteptare"));
    }

    @Test
    void digestRowsReuseEachQueuesNativeReviewAction() {
        var emitted = NpcPlayerSurface.delegationActions(view(
                List.of(new DelegationUseCase.DigestEntry(DelegableAction.JOIN_PETITION,
                                "JR-1", "Vlad", "", "join-review:JR-1", 1L),
                        new DelegationUseCase.DigestEntry(DelegableAction.PROMOTION_PETITION,
                                "APP-2", "Ana", "", "petition-review:APP-2", 2L)),
                List.of(new DelegationUseCase.PolicyView(
                        DelegableAction.JOIN_PETITION, DelegationMode.DIGEST, 0, List.of())),
                List.of()));
        var actionIds = ids(emitted);
        assertTrue(actionIds.contains("join-review:JR-1"),
                "the digest batches the queues — it never invents a parallel form");
        assertTrue(actionIds.contains("petition-review:APP-2"));
        assertTrue(emitted.stream().anyMatch(a -> a.label().contains("Vlad")),
                "digest rows name their subject");
    }

    @Test
    void standingOrderAndTimeoutPoliciesRenderTheirControls() {
        var emitted = NpcPlayerSurface.delegationActions(view(List.of(),
                List.of(new DelegationUseCase.PolicyView(DelegableAction.JOIN_PETITION,
                                DelegationMode.STANDING_ORDER, 0,
                                List.of(new DelegationUseCase.OrderView(
                                        "SO-JOIN_PETITION-1", "Vlad", "ANY", true),
                                        new DelegationUseCase.OrderView(
                                                "SO-JOIN_PETITION-2", "Vlad", "FIERAR", false))),
                        new DelegationUseCase.PolicyView(DelegableAction.AUDIENCE_REQUEST,
                                DelegationMode.TIMEOUT, 48L * 3_600_000L, List.of())),
                List.of(new DelegationUseCase.OrderOption("JOIN_PETITION", "ANY", "orice cerere"),
                        new DelegationUseCase.OrderOption("JOIN_PETITION", "FIERAR",
                                "meseria fierar"),
                        new DelegationUseCase.OrderOption("PROMOTION_PETITION", "CAPORAL",
                                "gradul Caporal"))));
        var actionIds = ids(emitted);
        assertTrue(actionIds.contains("delegation-order-join_petition:ANY"));
        assertTrue(actionIds.contains("delegation-order-join_petition:FIERAR"));
        assertFalse(actionIds.contains("delegation-order-promotion_petition:CAPORAL"),
                "order options only render for queues in standing-order mode");
        assertTrue(actionIds.contains("delegation-revoke:SO-JOIN_PETITION-1"));
        assertFalse(actionIds.contains("delegation-revoke:SO-JOIN_PETITION-2"),
                "inactive orders are not revocable");
        assertTrue(actionIds.contains("delegation-window:AUDIENCE_REQUEST"));
        // Every parameterized row the console emits still dispatches.
        for (String id : actionIds) {
            if (!"delegation-ledger".equals(id)) {
                assertTrue(NpcPlayerSurface.parseActionId(id).isPresent(),
                        id + " must round-trip through the action parser");
            }
        }
    }

    /**
     * The Comisar drawer only exists while authority exists — the service
     * emits zero admin rows for everyone else, so no drawer and no member
     * picker can ever render for them.
     */
    @Test
    void adminRowsRequireCommissionerAuthority() throws IOException {
        String service = Files.readString(ADMIN_SERVICE);
        assertTrue(service.contains("if (!authorized(player)) return List.of();"),
                "availableActions must emit nothing without authority");
        assertTrue(service.contains("isCommissioner(player)"),
                "authority today is the Comisar");
    }

    @Test
    void adminMutationLabelsNameTheMemberForTheConfirmPage() {
        var emitted = NpcPlayerSurface.adminActions(List.of(
                new AdminRoleplayUseCase.AvailableAction(
                        AdminRoleplayUseCase.Action.DOSSIER, "uuid-1", "Vlad"),
                new AdminRoleplayUseCase.AvailableAction(
                        AdminRoleplayUseCase.Action.PROMOTE, "uuid-1", "Vlad"),
                new AdminRoleplayUseCase.AvailableAction(
                        AdminRoleplayUseCase.Action.FIRE, "uuid-2", "Ana")));
        var dossier = emitted.get(0);
        assertEquals("admin-dossier:uuid-1", dossier.actionId());
        assertTrue(dossier.label().contains("Vlad"),
                "the member picker names members, never bare uuids");
        assertTrue(emitted.get(1).label().contains("Vlad"),
                "confirm-gated mutations carry the member name into the DA/NU page");
        assertTrue(emitted.get(2).label().contains("Ana"));
    }

    @Test
    void secretaryRouteKeepsItsStaticVocabularyIntact() {
        var surface = NpcPlayerSurface.surfaceFor(NpcRoles.SECRETARY);
        var actionIds = surface.actions().stream()
                .map(NpcPlayerSurface.ChatAction::actionId).toList();
        for (String expected : new String[]{"mission-list", "mission-carnet",
                "guard-status", "v2-personnel-status", "v2-campaign-status",
                "v2-professional-work", "incident-list", "dispatch-board",
                "duty-roster", "bolo-list", "bolo-create"}) {
            assertTrue(actionIds.contains(expected), expected + " stays on the desk");
        }
    }
}
