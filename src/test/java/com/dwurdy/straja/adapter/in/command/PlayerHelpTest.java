package com.dwurdy.straja.adapter.in.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Player-facing /straja help contract (#201): the orientation is the recovery
 * surface for a player who knows nothing, so its content is pinned — lang keys
 * resolve in both shipped languages, every advertised command is really
 * runnable at permission 0, and no admin command names leak.
 */
class PlayerHelpTest {

    private static JsonObject lang(String code) throws Exception {
        return JsonParser.parseString(Files.readString(
                Path.of("src/main/resources/assets/straja/lang/" + code + ".json"))).getAsJsonObject();
    }

    @Test
    void everyPlayerHelpKeyResolvesInBothLanguages() throws Exception {
        var ro = lang("ro_ro");
        var en = lang("en_us");
        for (String key : StrajaCommands.CommandPolicy.playerHelpKeys()) {
            assertTrue(ro.has(key), "ro_ro is missing " + key);
            assertTrue(en.has(key), "en_us is missing " + key);
            assertFalse(ro.get(key).getAsString().isBlank(), key + " is blank in ro_ro");
            assertFalse(en.get(key).getAsString().isBlank(), key + " is blank in en_us");
        }
    }

    @Test
    void everyAdvertisedCommandIsRunnableAtPermissionZero() {
        for (String command : StrajaCommands.CommandPolicy.playerHelpCommands()) {
            assertTrue(command.startsWith("/straja "), "not a straja command: " + command);
            String rootLiteral = command.substring("/straja ".length()).split(" ")[0];
            assertEquals(0, StrajaCommands.CommandPolicy.permissionLevel(rootLiteral),
                    command + " must be runnable at permission 0");
        }
    }

    @Test
    void advertisedCommandsAreExactlyThePublicGameplayRoots() {
        var expected = new java.util.HashSet<String>();
        for (var e : CommandPermissions.rootLevels().entrySet()) {
            String root = e.getKey().split(" ")[0];
            // ajutor is the help entry itself; npc-action is an internal token endpoint
            if (e.getValue() == 0 && !root.equals("npc-action") && !root.equals("ajutor")) {
                expected.add("/straja " + root);
            }
        }
        assertEquals(expected, new java.util.HashSet<>(StrajaCommands.CommandPolicy.playerHelpCommands()),
                "the player orientation must advertise exactly the permission-0 gameplay commands");
    }

    @Test
    void orientationNamesTheFirstStepAndFaqEntry() throws Exception {
        var ro = lang("ro_ro");
        String firstStep = ro.get("straja.help.player.first_step").getAsString();
        assertTrue(firstStep.contains("Recepție"), "first step must point at the Receptionist");
        assertTrue(firstStep.contains("Depune cererea"), "first step must name the admission action");
        assertTrue(ro.get("straja.help.player.faq").getAsString().contains("Am o întrebare"),
                "FAQ pointer must name the NPC question menu");
        assertFalse(ro.get("straja.help.player.intro").getAsString().isBlank());
    }

    @Test
    void playerHelpLeaksNoAdminCommandNames() throws Exception {
        var ro = lang("ro_ro");
        var en = lang("en_us");
        var adminOnly = new java.util.HashSet<String>();
        for (var e : CommandPermissions.rootLevels().entrySet()) {
            if (e.getValue() >= CommandPermissions.ADMIN) adminOnly.add(e.getKey().split(" ")[0]);
        }
        for (String key : StrajaCommands.CommandPolicy.playerHelpKeys()) {
            for (JsonObject file : List.of(ro, en)) {
                String value = file.get(key).getAsString();
                for (String admin : adminOnly) {
                    assertFalse(value.contains("/straja " + admin)
                                    || value.contains("«" + admin + "»"),
                            key + " points players at admin-only '" + admin + "'");
                }
            }
        }
    }
}
