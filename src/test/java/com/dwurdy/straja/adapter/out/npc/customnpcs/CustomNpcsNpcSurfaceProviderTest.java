package com.dwurdy.straja.adapter.out.npc.customnpcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.NpcProvisioningUseCase;
import com.dwurdy.straja.domain.model.NpcContentId;
import java.util.List;
import org.junit.jupiter.api.Test;

class CustomNpcsNpcSurfaceProviderTest {
    @Test
    void selectorShowsStableIdAndAvailabilityReason() {
        NpcProvisioningUseCase.ProfileOption unavailable = option(false, "missing GUI capability");

        String label = CustomNpcsNpcSurfaceProvider.profileOptionLabel(unavailable, false, false);
        String[] hover = CustomNpcsNpcSurfaceProvider.profileOptionHoverText(unavailable, false);

        assertTrue(label.contains("Jailer"));
        assertTrue(label.contains("straja:jailer"));
        assertTrue(label.contains("unavailable"));
        assertTrue(List.of(hover).contains("Purpose: Custody and prison service"));
        assertTrue(List.of(hover).contains("Availability: Unavailable: missing GUI capability"));
    }

    @Test
    void selectorMarksCurrentProfileAndBlocksAllChoicesDuringRecovery() {
        NpcProvisioningUseCase.ProfileOption available = option(true, "");

        assertTrue(CustomNpcsNpcSurfaceProvider.profileOptionLabel(available, true, false)
                .contains("(current)"));
        assertTrue(CustomNpcsNpcSurfaceProvider.profileOptionLabel(available, false, true)
                .contains("unavailable"));
        assertTrue(List.of(CustomNpcsNpcSurfaceProvider.profileOptionHoverText(available, true))
                .contains("Availability: Unavailable: provider recovery is pending"));
    }

    @Test
    void confirmationListsEveryDialogueQuestAndActionReference() {
        NpcProvisioningUseCase.ProfileOption option = option(true, "");

        assertEquals(List.of(
                "Dialogue nodes (1):",
                "  straja.jailer.intro",
                "Quests (1):",
                "  straja.jailer.custody",
                "Actions (2):",
                "  straja.jailer.status",
                "  straja.jailer.release"),
                CustomNpcsNpcSurfaceProvider.profileReferenceRows(option));
    }

    @Test
    void selectorKeepsSafeUnassignAvailableDuringProviderRecovery() {
        assertEquals("Unassign",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(false, ""));
        assertEquals("Cancel pending",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(true, "BIND"));
        assertEquals("Cancel pending",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(true, "PUBLISH"));
        assertEquals("Retry unassign",
                CustomNpcsNpcSurfaceProvider.unassignButtonLabel(true, "UNBIND"));
    }

    private static NpcProvisioningUseCase.ProfileOption option(boolean enabled, String disabledReason) {
        return new NpcProvisioningUseCase.ProfileOption(
                "straja:jailer",
                "Jailer",
                "Custody and prison service",
                "jailer",
                "hq",
                1,
                enabled,
                disabledReason,
                List.of(NpcContentId.of("straja.jailer.intro")),
                List.of(NpcContentId.of("straja.jailer.custody")),
                List.of(NpcContentId.of("straja.jailer.status"), NpcContentId.of("straja.jailer.release")));
    }
}
