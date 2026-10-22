package com.dwurdy.straja.adapter.out.npc.content;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwurdy.straja.domain.model.NpcProfileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the bind contract end to end: every profile resource bundled under
 * {@code data/straja/npc/} must reach the {@code NpcContentCatalog}, and every
 * role {@code bind-desk}/{@code bind-custom} advertise must map to a cataloged
 * profile. The inspector profile shipped on disk yet was never loaded — the
 * commands rejected it as unknown-profile while listing it as supported.
 * These tests make a forgotten resource a build failure, not a runtime refusal.
 */
class NpcContentCatalogLoaderTest {

    private static final Path NPC_RESOURCES =
            Path.of("src/main/resources/data/straja/npc");

    private static final Path RUNTIME_SOURCE = Path.of(
            "src/main/java/com/dwurdy/straja/bootstrap/NpcPresentationRuntime.java");

    /**
     * role → canonical content profile: the oracle for what the bind commands
     * must provision. Restated here on purpose — the contract is pinned
     * against the implementation, not reusing it.
     */
    private static final Map<String, String> PROFILE_BY_ROLE = Map.of(
            "receptionist", "straja.reception.admission",
            "trainer", "straja.instructor.admission",
            "secretary", "straja.secretary.workflows",
            "jailer", "straja.jailer.custody",
            "armorer", "straja.armorer.orders",
            "archivist", "straja.archivist.archive",
            "inspector", "straja.inspector.documents");

    /**
     * The load list must equal the bundled directory contents — a file shipped
     * without an entry can never bind, and an entry without a file fails the
     * server on startup.
     */
    @Test
    void bundledResourcesMatchTheLoadList() throws IOException {
        var bundled = new TreeSet<String>();
        try (var files = Files.list(NPC_RESOURCES)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".json")) {
                    bundled.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        }
        assertFalse(bundled.isEmpty(), "no bundled NPC profiles found under " + NPC_RESOURCES);
        assertEquals(bundled, new TreeSet<>(NpcContentCatalogLoader.BUNDLED_PROFILES),
                "load list and bundled resources diverged");
    }

    /** Every listed profile parses and indexes under its declared content id. */
    @Test
    void everyListedProfileLoadsAndResolves() throws IOException {
        var catalog = NpcContentCatalogLoader.load();
        var loader = new NpcContentProfileJsonLoader();
        for (String name : NpcContentCatalogLoader.BUNDLED_PROFILES) {
            Path file = NPC_RESOURCES.resolve(name + ".json");
            assertTrue(Files.exists(file), "listed profile missing on disk: " + name);
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                var profile = loader.load(reader);
                assertDoesNotThrow(() -> catalog.require(profile.contentId()),
                        name + " parses but is not resolvable in the catalog");
            }
        }
    }

    /** Every bindable role's profile resolves in the catalog, tagged with the role. */
    @Test
    void everyBindableRoleResolvesACatalogProfile() {
        var catalog = NpcContentCatalogLoader.load();
        for (Map.Entry<String, String> entry : PROFILE_BY_ROLE.entrySet()) {
            NpcProfileDescriptor descriptor = assertDoesNotThrow(
                    () -> catalog.descriptor(entry.getValue()),
                    "bind-desk/bind-custom accept " + entry.getKey() + " but "
                            + entry.getValue() + " is absent from the catalog");
            assertEquals(entry.getKey(), descriptor.roleId(),
                    "profile " + entry.getValue() + " reports a different role");
        }
    }

    /**
     * Source-level pin: {@code NpcPresentationRuntime} links Minecraft types
     * and cannot load on the headless test classpath, so the
     * {@code BINDABLE_ROLES}/{@code profileForRole} contract is pinned on its
     * source text — the advertised role set must stay equal to the
     * catalog-backed oracle and keep mapping each role to a bundled profile.
     */
    @Test
    void bindPathsAdvertiseOnlyCatalogedRoles() throws IOException {
        String source = Files.readString(RUNTIME_SOURCE);
        var literal = Pattern.compile("BINDABLE_ROLES\\s*=\\s*Set\\.of\\(([^;]*?)\\);")
                .matcher(source);
        assertTrue(literal.find(), "BINDABLE_ROLES literal must be findable");
        var roles = new TreeSet<String>();
        var quoted = Pattern.compile("\"([^\"]+)\"").matcher(literal.group(1));
        while (quoted.find()) roles.add(quoted.group(1));
        assertEquals(PROFILE_BY_ROLE.keySet(), roles,
                "the advertised bindable role set drifted from catalog-backed profiles");
        for (String profile : PROFILE_BY_ROLE.values()) {
            assertTrue(source.contains("NpcContentId.of(\"" + profile + "\")"),
                    "profileForRole lost the " + profile + " mapping");
        }
        assertTrue(source.contains("\"inspector\".equals(role)"),
                "profileForRole must keep its inspector branch");
    }
}
