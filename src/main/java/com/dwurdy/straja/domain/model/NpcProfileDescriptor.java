package com.dwurdy.straja.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Provider-neutral catalog metadata used by admin provisioning surfaces.
 *
 * <p>The profile id and content remain Straja-owned. Provider adapters only
 * receive this descriptor when they need to render a selection surface.</p>
 */
public record NpcProfileDescriptor(
        NpcProfileId profileId,
        NpcContentId contentProfileId,
        int schemaVersion,
        String title,
        String summary,
        String roleId,
        String stationId,
        Set<NpcCapability> requiredCapabilities,
        Set<NpcCapability> optionalCapabilities,
        java.util.List<NpcContentId> actionContentIds,
        java.util.List<NpcContentId> dialogueContentIds,
        java.util.List<NpcContentId> questContentIds) {

    private static final Pattern ROLE_OR_STATION = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

    public NpcProfileDescriptor {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(contentProfileId, "contentProfileId");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        title = text(title, 256, "title");
        summary = text(summary, 512, "summary");
        roleId = id(roleId, "roleId");
        stationId = id(stationId, "stationId");
        requiredCapabilities = Set.copyOf(Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities"));
        optionalCapabilities = Set.copyOf(Objects.requireNonNull(
                optionalCapabilities, "optionalCapabilities"));
        actionContentIds = List.copyOf(Objects.requireNonNull(actionContentIds, "actionContentIds"));
        dialogueContentIds = List.copyOf(Objects.requireNonNull(dialogueContentIds, "dialogueContentIds"));
        questContentIds = List.copyOf(Objects.requireNonNull(questContentIds, "questContentIds"));
        unique(actionContentIds, "actionContentIds");
        unique(dialogueContentIds, "dialogueContentIds");
        unique(questContentIds, "questContentIds");
        if (!java.util.Collections.disjoint(requiredCapabilities, optionalCapabilities)) {
            throw new IllegalArgumentException("required and optional capabilities must be disjoint");
        }
    }

    public static NpcProfileDescriptor from(
            NpcContentProfile profile, String roleId, String stationId) {
        Objects.requireNonNull(profile, "profile");
        return new NpcProfileDescriptor(
                profile.profileId(),
                profile.contentId(),
                profile.schemaVersion(),
                profile.title(),
                profile.body().length() > 512 ? profile.body().substring(0, 512) : profile.body(),
                roleId,
                stationId,
                profile.requiredCapabilities(),
                profile.optionalCapabilities(),
                profile.actions().stream().map(NpcSurfaceAction::actionId).toList(),
                profile.dialogue().stream().map(NpcSurfaceSnapshot.DialogueNode::nodeId).toList(),
                profile.quests().stream().map(NpcSurfaceSnapshot.QuestEntry::questId).toList());
    }

    private static void unique(java.util.List<NpcContentId> ids, String name) {
        if (new java.util.HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(name + " cannot contain duplicate IDs");
        }
    }

    private static String text(String value, int maximum, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " must be non-blank and at most "
                    + maximum + " characters");
        }
        return value;
    }

    private static String id(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!ROLE_OR_STATION.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has invalid format: " + value);
        }
        return value;
    }
}
