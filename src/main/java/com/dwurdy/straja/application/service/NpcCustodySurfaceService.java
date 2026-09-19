package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.CustodyRoleplayUseCase;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import com.dwurdy.straja.domain.model.Sentence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Projects authoritative custody and prison state onto the jailer GUI. */
public final class NpcCustodySurfaceService {
    private static final int MAX_ACTIONS = 64;
    private static final Pattern SAFE_RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    public NpcSurfaceSnapshot resolve(
            NpcSurfaceSnapshot published,
            List<CustodyRoleplayUseCase.AvailableAction> available,
            boolean cuffed,
            boolean bound,
            boolean downed,
            Sentence sentence) {
        Objects.requireNonNull(published, "published");
        available = List.copyOf(Objects.requireNonNull(available, "available"));
        Map<NpcContentId, NpcSurfaceAction> actions = new LinkedHashMap<>();
        for (NpcSurfaceAction action : published.actions()) {
            actions.put(action.actionId(), authored(action, available));
        }
        for (CustodyRoleplayUseCase.AvailableAction entry : available) {
            if (entry == null || entry.action() == null) continue;
            add(actions, entry);
        }
        List<NpcSurfaceAction> result = List.copyOf(actions.values());
        boolean recovery = cuffed || bound || downed || sentence != null;
        String sentenceStatus = sentence == null ? "No active sentence." :
                "Sentence " + safe(sentence.id) + ": " + safe(sentence.status) +
                        ", remaining active time " + Math.max(0, sentence.remainingActiveMs) + " ms.";
        String body = bounded(published.body() + "\n\nCustody: " +
                (cuffed ? "cuffed " : "") + (bound ? "bound " : "") +
                (downed ? "downed " : "normal") + "\n" + sentenceStatus, 8_192);
        List<NpcSurfaceSnapshot.QuestEntry> quests = published.quests().stream()
                .map(entry -> new NpcSurfaceSnapshot.QuestEntry(
                        entry.questId(), entry.title(), recovery
                                ? NpcSurfaceSnapshot.QuestState.ACTIVE
                                : NpcSurfaceSnapshot.QuestState.AVAILABLE))
                .toList();
        return new NpcSurfaceSnapshot(
                published.binding(), published.profileId(), published.title(), body, result,
                published.dialogue().stream()
                        .map(node -> new NpcSurfaceSnapshot.DialogueNode(
                                node.nodeId(), node.text(), choices(result)))
                        .toList(),
                quests, published.requiredCapabilities(), published.optionalCapabilities());
    }

    private static NpcSurfaceAction authored(
            NpcSurfaceAction action,
            List<CustodyRoleplayUseCase.AvailableAction> available) {
        return switch (action.actionId().value()) {
            case "cuffs-item" -> stateful(action, has(available, CustodyRoleplayUseCase.Action.GIVE_CUFFS));
            case "custody-remove-head-sack" ->
                    stateful(action, has(available, CustodyRoleplayUseCase.Action.REMOVE_HEAD_SACK));
            case "custody-wake-downed" ->
                    stateful(action, has(available, CustodyRoleplayUseCase.Action.WAKE_DOWNED));
            default -> action;
        };
    }

    private static NpcSurfaceAction stateful(NpcSurfaceAction action, boolean enabled) {
        return new NpcSurfaceAction(
                action.actionId(), action.label(), enabled,
                enabled ? "" : "This custody action is not currently available.", action.inputs());
    }

    private static void add(
            Map<NpcContentId, NpcSurfaceAction> actions,
            CustodyRoleplayUseCase.AvailableAction entry) {
        String recordId = entry.recordId();
        switch (entry.action()) {
            case ACCEPT_REQUEST -> add(actions, parameterized("custody-accept", recordId),
                    "Accept cuff request " + safe(recordId), List.of());
            case REFUSE_REQUEST -> add(actions, parameterized("custody-refuse", recordId),
                    "Refuse cuff request " + safe(recordId), List.of());
            case RELEASE_TARGET -> add(actions, parameterized("custody-release", recordId),
                    "Release target " + safe(recordId), List.of());
            case REMOVE_HEAD_SACK, WAKE_DOWNED, GIVE_CUFFS -> { }
        }
    }

    private static void add(
            Map<NpcContentId, NpcSurfaceAction> actions,
            String id,
            String label,
            List<NpcSurfaceAction.InputField> fields) {
        if (id == null || id.isBlank() || actions.size() >= MAX_ACTIONS) return;
        try {
            NpcContentId contentId = NpcContentId.of(id);
            actions.putIfAbsent(contentId,
                    new NpcSurfaceAction(contentId, bounded(label, 256), true, "", fields));
        } catch (IllegalArgumentException ignored) {
            // Provider-facing actions never carry malformed persisted IDs.
        }
    }

    private static boolean has(
            List<CustodyRoleplayUseCase.AvailableAction> available,
            CustodyRoleplayUseCase.Action wanted) {
        return available.stream().anyMatch(entry -> entry != null && entry.action() == wanted);
    }

    private static String parameterized(String operation, String recordId) {
        return recordId != null && SAFE_RECORD_ID.matcher(recordId).matches()
                ? operation + ":" + recordId : "";
    }

    private static List<NpcSurfaceSnapshot.Choice> choices(List<NpcSurfaceAction> actions) {
        return actions.stream().map(action -> new NpcSurfaceSnapshot.Choice(
                action.actionId(), action.label(), action.enabled())).toList();
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : bounded(value, 80);
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "...";
    }
}
