package com.dwurdy.straja.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral authored content. A profile contains no host-NPC id,
 * CustomNPCs object, script, command, or provider-specific visual setting.
 */
public record NpcContentProfile(
        NpcContentId profileId,
        int schemaVersion,
        String title,
        String body,
        List<NpcSurfaceAction> actions,
        List<NpcSurfaceSnapshot.DialogueNode> dialogue,
        List<NpcSurfaceSnapshot.QuestEntry> quests,
        Set<NpcCapability> requiredCapabilities,
        Set<NpcCapability> optionalCapabilities) {

    public NpcContentProfile {
        Objects.requireNonNull(profileId, "profileId");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        title = text(title, 256, "title");
        body = text(body, 8_192, "body");
        actions = List.copyOf(Objects.requireNonNull(actions, "actions"));
        dialogue = List.copyOf(Objects.requireNonNull(dialogue, "dialogue"));
        quests = List.copyOf(Objects.requireNonNull(quests, "quests"));
        requiredCapabilities = Set.copyOf(
                Objects.requireNonNull(requiredCapabilities, "requiredCapabilities"));
        optionalCapabilities = Set.copyOf(
                Objects.requireNonNull(optionalCapabilities, "optionalCapabilities"));
        if (!java.util.Collections.disjoint(requiredCapabilities, optionalCapabilities)) {
            throw new IllegalArgumentException("required and optional capabilities must be disjoint");
        }
        uniqueActions(actions);
        uniqueDialogue(dialogue);
        uniqueQuests(quests);
        ensureDialogueActionsExist(dialogue, actions);
    }

    /** Binds canonical content to a provider-neutral logical NPC identity. */
    public NpcSurfaceSnapshot bind(NpcBinding binding) {
        Objects.requireNonNull(binding, "binding");
        return new NpcSurfaceSnapshot(
                binding,
                profileId,
                title,
                body,
                actions,
                dialogue,
                quests,
                requiredCapabilities,
                optionalCapabilities);
    }

    private static String text(String value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be non-blank and at most "
                    + maximum + " characters");
        }
        return value;
    }

    private static void uniqueActions(List<NpcSurfaceAction> actions) {
        Set<NpcContentId> ids = new HashSet<>();
        for (NpcSurfaceAction action : actions) {
            Objects.requireNonNull(action, "actions cannot contain null");
            if (!ids.add(action.actionId())) {
                throw new IllegalArgumentException("duplicate action id: " + action.actionId().value());
            }
        }
        if (actions.size() > 64) {
            throw new IllegalArgumentException("actions cannot contain more than 64 entries");
        }
    }

    private static void uniqueDialogue(List<NpcSurfaceSnapshot.DialogueNode> dialogue) {
        Set<NpcContentId> ids = new HashSet<>();
        for (NpcSurfaceSnapshot.DialogueNode node : dialogue) {
            Objects.requireNonNull(node, "dialogue cannot contain null");
            if (!ids.add(node.nodeId())) {
                throw new IllegalArgumentException("duplicate dialogue node id: " + node.nodeId().value());
            }
        }
        if (dialogue.size() > 64) {
            throw new IllegalArgumentException("dialogue cannot contain more than 64 entries");
        }
    }

    private static void uniqueQuests(List<NpcSurfaceSnapshot.QuestEntry> quests) {
        Set<NpcContentId> ids = new HashSet<>();
        for (NpcSurfaceSnapshot.QuestEntry quest : quests) {
            Objects.requireNonNull(quest, "quests cannot contain null");
            if (!ids.add(quest.questId())) {
                throw new IllegalArgumentException("duplicate quest id: " + quest.questId().value());
            }
        }
        if (quests.size() > 64) {
            throw new IllegalArgumentException("quests cannot contain more than 64 entries");
        }
    }

    private static void ensureDialogueActionsExist(
            List<NpcSurfaceSnapshot.DialogueNode> dialogue,
            List<NpcSurfaceAction> actions) {
        Set<NpcContentId> actionIds = actions.stream()
                .map(NpcSurfaceAction::actionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (NpcSurfaceSnapshot.DialogueNode node : dialogue) {
            for (NpcSurfaceSnapshot.Choice choice : node.choices()) {
                Objects.requireNonNull(choice, "dialogue choices cannot contain null");
                if (!actionIds.contains(choice.actionId())) {
                    throw new IllegalArgumentException(
                            "dialogue choice references an action absent from the profile: "
                                    + choice.actionId().value());
                }
            }
        }
    }
}
