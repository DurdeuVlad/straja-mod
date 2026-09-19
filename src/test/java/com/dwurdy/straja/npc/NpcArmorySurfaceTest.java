package com.dwurdy.straja.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.application.port.in.ArmoryUseCase;
import com.dwurdy.straja.application.service.NpcArmorySurfaceService;
import com.dwurdy.straja.domain.model.NpcBinding;
import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcProviderId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NpcArmorySurfaceTest {
    private static final NpcContentId PROFILE = NpcContentId.of("straja.armorer.orders");

    @Test
    void eligibleOffersBecomeCanonicalNativeGuiActionsAndActiveQuest() {
        NpcSurfaceSnapshot surface = new NpcArmorySurfaceService().resolve(
                published(), List.of(
                        new ArmoryUseCase.Offer("armor_iron", "minecraft:iron_chestplate", 1, 4, 1, false),
                        new ArmoryUseCase.Offer("reserve_rope", "minecraft:lead", 2, 3, 1, true)));

        assertTrue(action(surface, "armory-buy:armor_iron").enabled());
        assertTrue(action(surface, "armory-reserve:reserve_rope").enabled());
        assertEquals(NpcSurfaceSnapshot.QuestState.ACTIVE, surface.quests().getFirst().state());
        assertEquals(3, surface.dialogue().getFirst().choices().size());
    }

    @Test
    void emptyOffersFailClosedWithoutPublishingAPurchaseAction() {
        NpcSurfaceSnapshot surface = new NpcArmorySurfaceService().resolve(published(), List.of());

        assertEquals(false, action(surface, "armory-unavailable").enabled());
        assertEquals(NpcSurfaceSnapshot.QuestState.LOCKED, surface.quests().getFirst().state());
        assertTrue(surface.dialogue().getFirst().choices().stream()
                .noneMatch(choice -> choice.actionId().value().startsWith("armory-buy:")));
    }

    @Test
    void malformedDuplicateAndExcessOffersCannotBreakTheNativeSurfaceContract() {
        List<ArmoryUseCase.Offer> offers = new java.util.ArrayList<>(List.of(
                new ArmoryUseCase.Offer("duplicate", "minecraft:stick", 1, 1, 1, false),
                new ArmoryUseCase.Offer("duplicate", "minecraft:stone", 1, 2, 1, false),
                new ArmoryUseCase.Offer("bad:key", "minecraft:dirt", 1, 1, 1, false)));
        for (int index = 0; index < 70; index++) {
            offers.add(new ArmoryUseCase.Offer("offer_" + index,
                    "minecraft:stick", 1, 1, 1, false));
        }

        NpcSurfaceSnapshot surface = new NpcArmorySurfaceService().resolve(published(), offers);

        assertEquals(64, surface.actions().size(), "duty kit plus 63 bounded offers");
        assertEquals(1, surface.actions().stream()
                .filter(action -> action.actionId().value().equals("armory-buy:duplicate"))
                .count());
        assertTrue(surface.actions().stream()
                .noneMatch(action -> action.actionId().value().equals("armory-buy:bad:key")));
        assertTrue(surface.body().length() <= 8_192);
    }

    private static NpcSurfaceAction action(NpcSurfaceSnapshot surface, String id) {
        return surface.actions().stream()
                .filter(action -> action.actionId().value().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static NpcSurfaceSnapshot published() {
        NpcBinding binding = new NpcBinding(
                "straja.test.armorer", NpcProviderId.CUSTOM_NPCS,
                UUID.randomUUID().toString(), "", "armorer", "hq", PROFILE, 1);
        return new NpcSurfaceSnapshot(
                binding, PROFILE, "Armorer", "Equipment desk.",
                List.of(NpcSurfaceAction.enabled(NpcContentId.of("duty-kit"), "Duty kit")),
                List.of(new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("straja.armorer.orders"), "Offers", List.of(
                                new NpcSurfaceSnapshot.Choice(
                                        NpcContentId.of("duty-kit"), "Duty kit", true)))),
                List.of(new NpcSurfaceSnapshot.QuestEntry(
                        NpcContentId.of("armory-orders"), "Equipment orders",
                        NpcSurfaceSnapshot.QuestState.LOCKED)),
                Set.of(NpcCapability.DIALOGUE, NpcCapability.GUI,
                        NpcCapability.ACTION_INPUT, NpcCapability.QUEST_JOURNAL),
                Set.of());
    }
}
