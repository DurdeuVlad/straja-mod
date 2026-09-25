package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Projects the authoritative archive workflow onto the provider-neutral NPC surface. */
public final class NpcArchiveSurfaceService {
    private static final int MAX_ACTIONS = 64;
    private static final int MAX_INPUT_LENGTH = 512;
    private static final Pattern SAFE_RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    public NpcSurfaceSnapshot resolve(
            NpcSurfaceSnapshot published,
            List<ArchiveRoleplayUseCase.AvailableAction> available,
            ArchiveRoleplayUseCase.Limits limits) {
        Objects.requireNonNull(published, "published");
        available = List.copyOf(Objects.requireNonNull(available, "available"));
        Objects.requireNonNull(limits, "limits");

        Map<NpcContentId, NpcSurfaceAction> actions = new LinkedHashMap<>();
        Set<String> availableIds = available.stream()
                .filter(entry -> entry != null && entry.action() != null)
                .map(entry -> actionId(entry.action(), entry.recordId()))
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (NpcSurfaceAction authored : published.actions()) {
            boolean enabled = availableIds.contains(authored.actionId().value());
            actions.put(authored.actionId(), new NpcSurfaceAction(
                    authored.actionId(), authored.label(), enabled,
                    enabled ? "" : "This archive action is not currently available.",
                    inputs(authored.actionId().value(), limits)));
        }
        for (ArchiveRoleplayUseCase.AvailableAction entry : available) {
            if (entry == null || entry.action() == null) continue;
            add(actions, entry, limits);
        }

        List<NpcSurfaceAction> result = List.copyOf(actions.values());
        boolean active = result.stream().anyMatch(NpcSurfaceAction::enabled);
        String body = bounded(published.body() + "\n\nArchive: " +
                (active ? "authorized folders, documents, and evidence actions are available."
                        : "no archive action is currently available."), 8_192);
        List<NpcSurfaceSnapshot.QuestEntry> quests = published.quests().stream()
                .map(entry -> entry.questId().equals(NpcContentId.of("archive-records"))
                        ? new NpcSurfaceSnapshot.QuestEntry(
                                entry.questId(), entry.title(),
                                active ? NpcSurfaceSnapshot.QuestState.ACTIVE
                                        : NpcSurfaceSnapshot.QuestState.LOCKED)
                        : entry)
                .toList();
        return new NpcSurfaceSnapshot(
                published.binding(), published.profileId(), published.title(), body, result,
                published.dialogue().stream()
                        .map(node -> new NpcSurfaceSnapshot.DialogueNode(
                                node.nodeId(), node.text(), choices(result)))
                        .toList(),
                quests, published.requiredCapabilities(), published.optionalCapabilities());
    }

    private static void add(
            Map<NpcContentId, NpcSurfaceAction> actions,
            ArchiveRoleplayUseCase.AvailableAction entry,
            ArchiveRoleplayUseCase.Limits limits) {
        String id = actionId(entry.action(), entry.recordId());
        if (id == null || id.isBlank() || actions.size() >= MAX_ACTIONS) return;
        try {
            NpcContentId contentId = NpcContentId.of(id);
            actions.put(contentId, new NpcSurfaceAction(
                    contentId, label(entry.action(), entry.recordId()), true, "",
                    inputs(id, limits)));
        } catch (IllegalArgumentException ignored) {
            // Untrusted persisted record IDs never become provider actions.
        }
    }

    private static String actionId(ArchiveRoleplayUseCase.Action action, String recordId) {
        if (action == null) return null;
        String operation = switch (action) {
            case LIST -> "archive-list";
            case CREATE_FOLDER -> "archive-folder-create";
            case READ_FOLDER -> "archive-folder-read";
            case ISSUE_FOLDER -> "archive-folder-issue";
            case NEW_SHEET -> "archive-sheet-new";
            case READ_SHEET -> "archive-sheet-read";
            case EDIT_SHEET -> "archive-sheet-edit";
            case SET_RECIPIENTS -> "archive-recipients";
            case SUBMIT_SHEET -> "archive-sheet-submit";
            case SIGN_SHEET -> "archive-sheet-sign";
            case COPY_SHEET -> "archive-sheet-copy";
            case PACK_ENVELOPE -> "archive-sheet-envelope";
            case ISSUE_DOCUMENT -> "archive-sheet-issue";
            case REVOKE_SHEET -> "archive-sheet-revoke";
        };
        if (recordId == null || recordId.isBlank()) {
            return switch (action) {
                case LIST, CREATE_FOLDER -> operation;
                default -> null;
            };
        }
        return SAFE_RECORD_ID.matcher(recordId).matches() ? operation + ":" + recordId : null;
    }

    private static String label(ArchiveRoleplayUseCase.Action action, String recordId) {
        String suffix = recordId == null || recordId.isBlank() ? "" : " " + recordId;
        return switch (action) {
            case LIST -> "View archive folders";
            case CREATE_FOLDER -> "Create archive folder";
            case READ_FOLDER -> "Read folder" + suffix;
            case ISSUE_FOLDER -> "Issue folder" + suffix;
            case NEW_SHEET -> "Create sheet in" + suffix;
            case READ_SHEET -> "Read sheet" + suffix;
            case EDIT_SHEET -> "Edit sheet" + suffix;
            case SET_RECIPIENTS -> "Set recipients for" + suffix;
            case SUBMIT_SHEET -> "Submit sheet" + suffix;
            case SIGN_SHEET -> "Sign sheet" + suffix;
            case COPY_SHEET -> "Copy sheet" + suffix;
            case PACK_ENVELOPE -> "Pack official envelope for" + suffix;
            case ISSUE_DOCUMENT -> "Issue document" + suffix;
            case REVOKE_SHEET -> "Revoke sheet" + suffix;
        };
    }

    private static List<NpcSurfaceAction.InputField> inputs(
            String id, ArchiveRoleplayUseCase.Limits limits) {
        if (id == null) return List.of();
        String operation = id.contains(":") ? id.substring(0, id.indexOf(':')) : id;
        return switch (operation) {
            case "archive-folder-create" -> List.of(
                    field("title", "Folder title", limits.title(), true),
                    field("department", "Department", limits.target(), false));
            case "archive-folder-issue" -> List.of(
                    field("target", "Recipient", limits.target(), true));
            case "archive-sheet-new" -> List.of(
                    field("type", "Document type", limits.target(), true),
                    field("title", "Sheet title", limits.title(), true));
            case "archive-sheet-edit" -> List.of(
                    field("content", "Content", limits.content(), true));
            case "archive-recipients" -> List.of(
                    field("recipients", "Recipients", limits.recipients(), true));
            case "archive-sheet-sign" -> List.of(
                    field("reason", "Signature reason", limits.signatureReason(), true));
            case "archive-sheet-copy" -> List.of(
                    field("count", "Copy count", 8, true),
                    field("targets", "Recipients", limits.recipients(), true));
            case "archive-sheet-envelope", "archive-sheet-issue" -> List.of(
                    field("target", "Recipient", limits.target(), true));
            default -> List.of();
        };
    }

    private static NpcSurfaceAction.InputField field(
            String key, String label, int maxLength, boolean required) {
        return new NpcSurfaceAction.InputField(
                key, label, Math.min(MAX_INPUT_LENGTH, Math.max(1, maxLength)), required);
    }

    private static List<NpcSurfaceSnapshot.Choice> choices(List<NpcSurfaceAction> actions) {
        return actions.stream()
                .map(action -> new NpcSurfaceSnapshot.Choice(
                        action.actionId(), action.label(), action.enabled()))
                .toList();
    }

    private static String bounded(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "...";
    }
}
