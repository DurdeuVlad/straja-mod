package com.dwurdy.straja.domain.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral projection of the current dialogue, quest, and action
 * surface for one bound NPC.
 */
public record NpcSurfaceSnapshot(
        NpcBinding binding,
        NpcContentId profileId,
        String title,
        String body,
        List<NpcSurfaceAction> actions,
        List<DialogueNode> dialogue,
        List<QuestEntry> quests,
        Set<NpcCapability> requiredCapabilities,
        Set<NpcCapability> optionalCapabilities) {

    public NpcSurfaceSnapshot {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(profileId, "profileId");
        title = requireText(title, 256, "title");
        body = requireText(body, 8_192, "body");
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
        ensureUniqueActionIds(actions);
        ensureBounded(actions, 64, "actions");
        ensureBounded(dialogue, 64, "dialogue");
        ensureBounded(quests, 64, "quests");
        ensureUniqueDialogueIds(dialogue);
        ensureUniqueQuestIds(quests);
        ensureDialogueChoicesReferenceActions(dialogue, actions);
        ensureProfileMatchesBinding(binding, profileId);
    }

    private static String requireText(String value, int maxLength, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must be non-blank and at most "
                    + maxLength + " characters");
        }
        return value;
    }

    private static void ensureUniqueActionIds(List<NpcSurfaceAction> actions) {
        Set<NpcContentId> ids = new HashSet<>();
        for (NpcSurfaceAction action : actions) {
            Objects.requireNonNull(action, "actions cannot contain null");
            if (!ids.add(action.actionId())) {
                throw new IllegalArgumentException("duplicate action id: " + action.actionId().value());
            }
        }
    }

    private static void ensureBounded(List<?> values, int maximum, String name) {
        if (values.size() > maximum) {
            throw new IllegalArgumentException(name + " cannot contain more than " + maximum + " entries");
        }
    }

    private static void ensureUniqueDialogueIds(List<DialogueNode> dialogue) {
        Set<NpcContentId> ids = new HashSet<>();
        for (DialogueNode node : dialogue) {
            Objects.requireNonNull(node, "dialogue cannot contain null");
            if (!ids.add(node.nodeId())) {
                throw new IllegalArgumentException("duplicate dialogue node id: " + node.nodeId().value());
            }
            ensureBounded(node.choices(), 64, "dialogue choices");
        }
    }

    private static void ensureUniqueQuestIds(List<QuestEntry> quests) {
        Set<NpcContentId> ids = new HashSet<>();
        for (QuestEntry quest : quests) {
            Objects.requireNonNull(quest, "quests cannot contain null");
            if (!ids.add(quest.questId())) {
                throw new IllegalArgumentException("duplicate quest id: " + quest.questId().value());
            }
        }
    }

    private static void ensureDialogueChoicesReferenceActions(
            List<DialogueNode> dialogue, List<NpcSurfaceAction> actions) {
        Set<NpcContentId> actionIds = actions.stream()
                .map(NpcSurfaceAction::actionId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (DialogueNode node : dialogue) {
            for (Choice choice : node.choices()) {
                Objects.requireNonNull(choice, "dialogue choices cannot contain null");
                if (!actionIds.contains(choice.actionId())) {
                    throw new IllegalArgumentException(
                            "dialogue choice references an action absent from the surface: "
                                    + choice.actionId().value());
                }
            }
        }
    }

    private static void ensureProfileMatchesBinding(NpcBinding binding, NpcContentId profileId) {
        if (!binding.contentProfileId().equals(profileId)) {
            throw new IllegalArgumentException(
                    "surface profile does not match binding: " + profileId.value());
        }
    }

    public record DialogueNode(
            NpcContentId nodeId,
            String text,
            List<Choice> choices) {
        public DialogueNode {
            Objects.requireNonNull(nodeId, "nodeId");
            if (text == null || text.isBlank() || text.length() > 8_192) {
                throw new IllegalArgumentException("dialogue text must be non-blank and at most 8192 characters");
            }
            choices = List.copyOf(Objects.requireNonNull(choices, "choices"));
        }
    }

    public record Choice(NpcContentId actionId, String label, boolean enabled) {
        public Choice {
            Objects.requireNonNull(actionId, "actionId");
            if (label == null || label.isBlank() || label.length() > 256) {
                throw new IllegalArgumentException("choice label must be non-blank and at most 256 characters");
            }
        }
    }

    public record QuestEntry(NpcContentId questId, String title, QuestState state) {
        public QuestEntry {
            Objects.requireNonNull(questId, "questId");
            if (title == null || title.isBlank() || title.length() > 256) {
                throw new IllegalArgumentException("quest title must be non-blank and at most 256 characters");
            }
            Objects.requireNonNull(state, "state");
        }
    }

    public enum QuestState {
        LOCKED,
        AVAILABLE,
        ACTIVE,
        COMPLETED,
        FAILED
    }
}
