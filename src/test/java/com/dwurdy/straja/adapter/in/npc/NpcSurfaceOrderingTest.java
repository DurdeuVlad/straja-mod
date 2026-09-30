package com.dwurdy.straja.adapter.in.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Rank;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NpcSurfaceOrderingTest {
    private static final List<String> ROLES = List.of(
            NpcRoles.RECEPTIONIST, NpcRoles.SECRETARY, NpcRoles.JAILER,
            NpcRoles.ARCHIVIST, NpcRoles.TRAINER, NpcRoles.ARMORER);

    private static NpcFaqSurface.Context civil() {
        return NpcFaqSurface.Context.from(new GuardState(), false, "Civil");
    }

    private static NpcFaqSurface.Context applicant() {
        var state = new GuardState();
        state.applicationState = "APPLIED";
        return NpcFaqSurface.Context.from(state, false, "Civil");
    }

    private static NpcFaqSurface.Context member() {
        var state = new GuardState();
        state.rank = Rank.STAGIAR.level();
        return NpcFaqSurface.Context.from(state, false, "Stagiar");
    }

    private static NpcFaqSurface.Context commissioner() {
        return NpcFaqSurface.Context.from(new GuardState(), true, "Comisar");
    }

    static Stream<Arguments> roleAndPhase() {
        return ROLES.stream().flatMap(role -> Stream.of(
                Arguments.of(role, civil()),
                Arguments.of(role, applicant()),
                Arguments.of(role, member()),
                Arguments.of(role, commissioner())));
    }

    private static List<String> ids(List<NpcPlayerSurface.ChatAction> actions) {
        return actions.stream().map(NpcPlayerSurface.ChatAction::actionId).toList();
    }

    private static List<String> primaryIds(NpcPlayerSurface.OrderedSurface ordered) {
        return ids(ordered.surface().actions());
    }

    private static String faqId(NpcPlayerSurface.RoleRoute route) {
        return NpcPlayerSurface.faqEntry(route).actionId();
    }

    @ParameterizedTest
    @MethodSource("roleAndPhase")
    void primaryPageNeverExceedsSixActions(String roleId, NpcFaqSurface.Context context) {
        var ordered = NpcPlayerSurface.orderedFor(roleId, List.of(), context);
        assertTrue(ordered.surface().actions().size() <= NpcPlayerSurface.PRIMARY_CAP,
                () -> roleId + " primary page must be at most 6 incl. Mai multe");
    }

    @ParameterizedTest
    @MethodSource("roleAndPhase")
    void faqIsAlwaysWithinTheFirstThreeActions(String roleId, NpcFaqSurface.Context context) {
        var ordered = NpcPlayerSurface.orderedFor(roleId, List.of(), context);
        String faq = faqId(ordered.surface().route());
        int index = primaryIds(ordered).indexOf(faq);
        assertTrue(index >= 0 && index < 3,
                () -> roleId + " FAQ must be in the first three primary actions, got index " + index);
    }

    @ParameterizedTest
    @MethodSource("roleAndPhase")
    void orderingNeverDropsAnAction(String roleId, NpcFaqSurface.Context context) {
        var ordered = NpcPlayerSurface.orderedFor(roleId, List.of(), context);
        var base = ids(NpcPlayerSurface.surfaceFor(roleId).actions());
        var content = ids(ordered.contentActions());
        assertEquals(base.size(), content.size(),
                () -> roleId + " must keep every action reachable");
        assertTrue(content.containsAll(base),
                () -> roleId + " content must contain every base action");
    }

    @ParameterizedTest
    @MethodSource("roleAndPhase")
    void orderingIsDeterministic(String roleId, NpcFaqSurface.Context context) {
        var first = NpcPlayerSurface.orderedFor(roleId, List.of(), context);
        var second = NpcPlayerSurface.orderedFor(roleId, List.of(), context);
        assertEquals(primaryIds(first), primaryIds(second));
        assertEquals(ids(first.overflow()), ids(second.overflow()));
    }

    @Test
    void civilReceptionistLeadsWithAdmission() {
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.RECEPTIONIST, List.of(), civil());
        var primary = primaryIds(ordered);
        assertEquals("application-submit", primary.get(0),
                "a civilian's first action must be «Depune cererea»");
        assertEquals("rules", primary.get(1));
        assertEquals(faqId(NpcPlayerSurface.RoleRoute.RECEPTIONIST), primary.get(2));
        assertTrue(ids(ordered.overflow()).containsAll(
                List.of("guard-status", "v2-personnel-status", "room-status", "faction-declare")),
                "member bookkeeping moves under «Mai multe…» for civilians");
        assertTrue(primary.get(primary.size() - 1).startsWith("more:"),
                "the last primary action is the overflow link");
    }

    @Test
    void applicantReceptionistKeepsOrientationWithoutReapply() {
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.RECEPTIONIST, List.of(), applicant());
        var primary = primaryIds(ordered);
        assertFalse(primary.contains("application-submit"),
                "an applicant cannot re-apply — admission moves to overflow");
        assertTrue(ids(ordered.overflow()).contains("application-submit"));
        assertEquals("rules", primary.get(0));
        assertEquals(faqId(NpcPlayerSurface.RoleRoute.RECEPTIONIST), primary.get(1));
    }

    @Test
    void memberReceptionistLeadsWithStatusAndBuriesAdmission() {
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.RECEPTIONIST, List.of(), member());
        var primary = primaryIds(ordered);
        assertEquals("guard-status", primary.get(0));
        assertFalse(primary.contains("application-submit"),
                "members must not see «Depune cererea» on the primary page");
        assertTrue(ids(ordered.overflow()).contains("application-submit"));
        assertTrue(primary.subList(0, 5).containsAll(
                List.of("guard-status", "v2-personnel-status", "v2-promotion-status",
                        "v2-document-status")),
                "member page leads with status/bookkeeping");
    }

    @Test
    void memberSeesDynamicActionsAheadOfBookkeeping() {
        var extras = List.of(
                new NpcPlayerSurface.ChatAction("Plătește amenda", "fine-pay:9"),
                new NpcPlayerSurface.ChatAction("Reclamă plângerea", "complaint-claim:2"));
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.RECEPTIONIST, extras, member());
        var primary = primaryIds(ordered);
        assertEquals(List.of("fine-pay:9", "complaint-claim:2"), primary.subList(0, 2),
                "dynamic actionable-now actions lead the member page");
    }

    @Test
    void threePlusDynamicActionsStillKeepFaqInTopThree() {
        var extras = List.of(
                new NpcPlayerSurface.ChatAction("A", "fine-pay:1"),
                new NpcPlayerSurface.ChatAction("B", "fine-pay:2"),
                new NpcPlayerSurface.ChatAction("C", "fine-pay:3"),
                new NpcPlayerSurface.ChatAction("D", "fine-pay:4"));
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.RECEPTIONIST, extras, member());
        var primary = primaryIds(ordered);
        assertTrue(primary.indexOf(faqId(NpcPlayerSurface.RoleRoute.RECEPTIONIST)) < 3,
                "FAQ stays within the first three even when four dynamics lead");
    }

    @Test
    void civilSecretaryShowsOnlyPublicWorkAndOrientation() {
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.SECRETARY, List.of(), civil());
        var primary = primaryIds(ordered);
        assertTrue(primary.containsAll(List.of("mission-list")));
        assertFalse(primary.contains("bolo-create"),
                "civilians must not lead with duty tooling");
        assertTrue(ids(ordered.overflow()).containsAll(
                List.of("bolo-create", "duty-roster", "guard-status")));
    }

    @Test
    void civilTrainerKeepsManualDiscoverable() {
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.TRAINER, List.of(), civil());
        assertEquals(faqId(NpcPlayerSurface.RoleRoute.TRAINER),
                primaryIds(ordered).get(0));
        assertTrue(ids(ordered.contentActions()).contains("training-manual"));
    }

    @Test
    void jailerKeepsCustodyOrderForEveryPhase() {
        for (var context : List.of(civil(), applicant(), member())) {
            var ordered = NpcPlayerSurface.orderedFor(NpcRoles.JAILER, List.of(), context);
            assertEquals(List.of("cuffs-status", "prison-status",
                            faqId(NpcPlayerSurface.RoleRoute.JAILER)),
                    primaryIds(ordered).subList(0, 3),
                    "jailer is custody-driven, not membership-driven");
        }
    }

    @Test
    void overflowLinkParsesBackToTheSameRole() {
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.RECEPTIONIST, List.of(), civil());
        var more = ordered.surface().actions().stream()
                .filter(a -> a.actionId().startsWith("more:")).findFirst().orElseThrow();
        assertEquals("straja.ui.more", more.labelKey(),
                "the overflow link renders through a language key");
        var parsed = NpcPlayerSurface.parseActionId(more.actionId()).orElseThrow();
        assertEquals("more", parsed.operation());
        assertEquals(NpcPlayerSurface.RoleRoute.RECEPTIONIST,
                NpcFaqSurface.roleForKey(parsed.recordId()));

        var back = NpcPlayerSurface.backLink(parsed.recordId());
        var backParsed = NpcPlayerSurface.parseActionId(back.actionId()).orElseThrow();
        assertEquals("main", backParsed.operation());
        assertEquals(parsed.recordId(), backParsed.recordId());
        assertEquals("straja.ui.back", back.labelKey());
    }

    @Test
    void overflowDoesNotDuplicatePrimaryActions() {
        var ordered = NpcPlayerSurface.orderedFor(NpcRoles.SECRETARY, List.of(), member());
        var primary = ids(ordered.contentActions());
        var content = ids(ordered.contentActions());
        assertEquals(primary.size(), content.stream().distinct().count(),
                "no action appears twice across primary and overflow");
    }

    @Test
    void unknownRouteProducesNoActionsAndNoOverflow() {
        var ordered = NpcPlayerSurface.orderedFor("not-a-role", List.of(), civil());
        assertTrue(ordered.surface().actions().isEmpty());
        assertTrue(ordered.overflow().isEmpty());
    }
}
