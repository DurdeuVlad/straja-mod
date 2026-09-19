package com.dwurdy.straja.adapter.out.npc.content;

import com.dwurdy.straja.domain.model.NpcCapability;
import com.dwurdy.straja.domain.model.NpcContentId;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import com.dwurdy.straja.domain.model.NpcSurfaceAction;
import com.dwurdy.straja.domain.model.NpcSurfaceSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Strict loader for canonical NPC profile resources. */
public final class NpcContentProfileJsonLoader {
    public NpcContentProfile load(Reader reader) throws IOException {
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("NPC profile root must be a JSON object");
            }
            return parse(root.getAsJsonObject());
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException) {
                throw exception;
            }
            throw new IllegalArgumentException("invalid NPC profile JSON", exception);
        }
    }

    private static NpcContentProfile parse(JsonObject json) {
        List<NpcSurfaceAction> actions = new ArrayList<>();
        for (JsonElement element : requiredArray(json, "actions")) {
            JsonObject action = object(element, "actions entry");
            boolean enabled = optionalBoolean(action, "enabled", true);
            actions.add(new NpcSurfaceAction(
                    NpcContentId.of(requiredString(action, "id")),
                    requiredString(action, "label"),
                    enabled,
                    optionalString(action, "disabledReason", "")));
        }

        List<NpcSurfaceSnapshot.DialogueNode> dialogue = new ArrayList<>();
        for (JsonElement element : requiredArray(json, "dialogue")) {
            JsonObject node = object(element, "dialogue entry");
            List<NpcSurfaceSnapshot.Choice> choices = new ArrayList<>();
            for (JsonElement choiceElement : requiredArray(node, "choices")) {
                JsonObject choice = object(choiceElement, "dialogue choice");
                choices.add(new NpcSurfaceSnapshot.Choice(
                        NpcContentId.of(requiredString(choice, "actionId")),
                        requiredString(choice, "label"),
                        optionalBoolean(choice, "enabled", true)));
            }
            dialogue.add(new NpcSurfaceSnapshot.DialogueNode(
                    NpcContentId.of(requiredString(node, "id")),
                    requiredString(node, "text"),
                    choices));
        }

        List<NpcSurfaceSnapshot.QuestEntry> quests = new ArrayList<>();
        for (JsonElement element : requiredArray(json, "quests")) {
            JsonObject quest = object(element, "quests entry");
            quests.add(new NpcSurfaceSnapshot.QuestEntry(
                    NpcContentId.of(requiredString(quest, "id")),
                    requiredString(quest, "title"),
                    NpcSurfaceSnapshot.QuestState.valueOf(
                            requiredString(quest, "state").toUpperCase(java.util.Locale.ROOT))));
        }

        return new NpcContentProfile(
                NpcContentId.of(requiredString(json, "profileId")),
                requiredInt(json, "schemaVersion"),
                requiredString(json, "title"),
                requiredString(json, "body"),
                actions,
                dialogue,
                quests,
                capabilities(json, "requiredCapabilities"),
                capabilities(json, "optionalCapabilities"));
    }

    private static Set<NpcCapability> capabilities(JsonObject json, String name) {
        EnumSet<NpcCapability> result = EnumSet.noneOf(NpcCapability.class);
        for (JsonElement element : requiredArray(json, name)) {
            result.add(NpcCapability.valueOf(element.getAsString().toUpperCase(java.util.Locale.ROOT)));
        }
        return Set.copyOf(result);
    }

    private static JsonArray requiredArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("missing JSON array: " + name);
        }
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonElement element, String name) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(name + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string field: " + name);
        }
        return value.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("missing integer field: " + name);
        }
        return value.getAsInt();
    }

    private static String optionalString(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null ? fallback : requiredString(object, name);
    }

    private static boolean optionalBoolean(JsonObject object, String name, boolean fallback) {
        JsonElement value = object.get(name);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("field must be boolean: " + name);
        }
        return value.getAsBoolean();
    }
}
