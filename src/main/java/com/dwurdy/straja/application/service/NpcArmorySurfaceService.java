package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.ArmoryUseCase;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Projects the authoritative armory offer list onto a provider-neutral NPC
 * surface. The provider only receives bounded labels and canonical action IDs;
 * rank, currency, stock, delivery, and reward decisions stay in ArmoryService.
 */
public final class NpcArmorySurfaceService {
    private static final int MAX_DYNAMIC_OFFERS = 63;

    public NpcSurfaceSnapshot resolve(
            NpcSurfaceSnapshot published,
            List<ArmoryUseCase.Offer> offers) {
        Objects.requireNonNull(published, "published");
        Objects.requireNonNull(offers, "offers");
        List<ArmoryUseCase.Offer> validOffers = new ArrayList<>();
        Set<String> actionKeys = new HashSet<>();
        for (ArmoryUseCase.Offer offer : offers) {
            if (offer == null || !validKey(offer.key())) continue;
            String operation = offer.reserve() ? "armory-reserve" : "armory-buy";
            if (actionKeys.add(operation + ":" + offer.key())
                    && validOffers.size() < MAX_DYNAMIC_OFFERS) {
                validOffers.add(offer);
            }
        }
        List<NpcSurfaceAction> actions = new ArrayList<>();
        actions.add(NpcSurfaceAction.enabled(NpcContentId.of("duty-kit"), "Request duty kit"));
        for (ArmoryUseCase.Offer offer : validOffers) {
            String operation = offer.reserve() ? "armory-reserve" : "armory-buy";
            NpcContentId actionId = NpcContentId.of(operation + ":" + offer.key());
            String label = (offer.reserve() ? "Reserve " : "Buy ")
                    + offer.itemId() + " x" + offer.count() + " — " + offer.cost()
                    + (offer.reserve() ? " requisition points" : " coins");
            actions.add(NpcSurfaceAction.enabled(actionId, bounded(label)));
        }
        if (actions.size() == 1) {
            actions.add(NpcSurfaceAction.disabled(
                    NpcContentId.of("armory-unavailable"),
                    "No eligible equipment",
                    "The armorer has no offers available for your current status."));
        }
        String body = bounded(published.body() + "\n\nCurrent eligible offers:"
                + (validOffers.isEmpty() ? " none" : " " + validOffers.size()), 8_192);
        List<NpcSurfaceSnapshot.Choice> choices = actions.stream()
                .map(action -> new NpcSurfaceSnapshot.Choice(
                        action.actionId(), action.label(), action.enabled()))
                .toList();
        List<NpcSurfaceSnapshot.DialogueNode> dialogue = List.of(
                new NpcSurfaceSnapshot.DialogueNode(
                        NpcContentId.of("straja.armorer.orders"),
                        "Inspect an offer or request your duty kit.",
                        choices));
        List<NpcSurfaceSnapshot.QuestEntry> quests = published.quests().stream()
                .map(entry -> new NpcSurfaceSnapshot.QuestEntry(
                        entry.questId(), entry.title(), validOffers.isEmpty()
                                ? NpcSurfaceSnapshot.QuestState.LOCKED
                                : NpcSurfaceSnapshot.QuestState.ACTIVE))
                .toList();
        return new NpcSurfaceSnapshot(
                published.binding(), published.profileId(), published.title(), body,
                List.copyOf(actions), dialogue, quests,
                published.requiredCapabilities(), published.optionalCapabilities());
    }

    private static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9_-]{1,80}");
    }

    private static String bounded(String value) {
        return bounded(value, 256);
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }
}
