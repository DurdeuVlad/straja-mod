package com.dwurdy.straja;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Hexagonal boundary enforcement: domain and application code must not import
 * Minecraft, NeoForge, Envelope or Ady's Decorations classes.
 */
class ArchitectureBoundaryTest {
    private static final Path SRC = Path.of("src/main/java/com/dwurdy/straja");
    private static final List<String> FORBIDDEN = List.of(
            "net.minecraft.", "net.neoforged.", "com.mojang.",
            "io.github.mortuusars.envelope.", "net.mcreator.adysdecorations.");

    private static List<String> violations(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int line = 0;
                for (String text : Files.readAllLines(file)) {
                    line++;
                    if (FORBIDDEN.stream().anyMatch(text::contains)) {
                        violations.add(file.getFileName() + ":" + line + " -> " + text.trim());
                    }
                }
            }
        }
        return violations;
    }

    @Test
    void domainMustNotDependOnPlatform() throws IOException {
        var violations = violations(SRC.resolve("domain"));
        assertTrue(violations.isEmpty(),
                "domain imports platform classes:\n" + String.join("\n", violations));
    }

    @Test
    void applicationMustNotDependOnPlatform() throws IOException {
        var violations = violations(SRC.resolve("application"));
        assertTrue(violations.isEmpty(),
                "application imports platform classes:\n" + String.join("\n", violations));
    }

    @Test
    void domainMustNotDependOnApplicationOrAdapters() throws IOException {
        Path domain = SRC.resolve("domain");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(domain)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String text : Files.readAllLines(file)) {
                    if (text.contains("com.dwurdy.straja.application.")
                            || text.contains("com.dwurdy.straja.adapter.")) {
                        violations.add(file.getFileName() + " -> " + text.trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "domain depends outward:\n" + String.join("\n", violations));
    }

    @Test
    void applicationMustNotDependInwardOnOuterLayers() throws IOException {
        Path application = SRC.resolve("application");
        List<String> outward = List.of(
                "com.dwurdy.straja.adapter.",
                "com.dwurdy.straja.bootstrap.",
                "com.dwurdy.straja.config.");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(application)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                int line = 0;
                for (String text : Files.readAllLines(file)) {
                    line++;
                    if (outward.stream().anyMatch(text::contains)) {
                        violations.add(file.getFileName() + ":" + line + " -> " + text.trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "application depends on outer layers:\n" + String.join("\n", violations));
    }

    @Test
    void formSubmissionRouterStaysOnInboundPorts() throws IOException {
        Path router = SRC.resolve("adapter/in/form/FormSubmissionRouter.java");
        assertTrue(Files.exists(router), "FormSubmissionRouter must exist");
        List<String> violations = new ArrayList<>();
        int line = 0;
        for (String text : Files.readAllLines(router)) {
            line++;
            if (text.contains("com.dwurdy.straja.application.service.")
                    || text.contains("com.dwurdy.straja.adapter.out.persistence")) {
                violations.add(router.getFileName() + ":" + line + " -> " + text.trim());
            }
        }
        assertTrue(violations.isEmpty(),
                "FormSubmissionRouter reaches past the inbound port:\n"
                        + String.join("\n", violations));
    }

    @Test
    void strajaEventsStaysOnInboundPorts() throws IOException {
        Path events = SRC.resolve("adapter/in/event/StrajaEvents.java");
        assertTrue(Files.exists(events), "StrajaEvents must exist");
        List<String> violations = new ArrayList<>();
        int line = 0;
        for (String text : Files.readAllLines(events)) {
            line++;
            if (text.contains("com.dwurdy.straja.application.service.")
                    || text.contains("com.dwurdy.straja.adapter.out.persistence")) {
                violations.add(events.getFileName() + ":" + line + " -> " + text.trim());
            }
        }
        assertTrue(violations.isEmpty(),
                "StrajaEvents reaches past the inbound ports:\n"
                        + String.join("\n", violations));
    }

    @Test
    void playerFacingAdaptersNeverReachConcreteServices() throws IOException {
        // Concrete runtime accessors and outbound-port context access bypass
        // the inbound ports; player-facing adapters must go through
        // runtime.<x>Roleplay()/playerQueries()/npcRegistry() etc.
        List<String> concreteAccessors = List.of(
                "runtime.guards()", "runtime.missions()", "runtime.fines()",
                "runtime.complaints()", "runtime.custody()", "runtime.prison()",
                "runtime.rooms()", "runtime.archive()", "runtime.players()",
                "runtime.npcs()", "runtime.audit()", "runtime.equipment()",
                "runtime.migration()", "runtime.context()");
        List<Path> surfaces = List.of(
                SRC.resolve("adapter/in/event/StrajaEvents.java"),
                SRC.resolve("adapter/in/npc/NpcRoles.java"),
                SRC.resolve("adapter/in/npc/NpcPlayerSurface.java"),
                SRC.resolve("adapter/in/item/PhysicalItemSurface.java"),
                SRC.resolve("adapter/in/form/FormSubmissionRouter.java"),
                SRC.resolve("adapter/in/form/FormSessionBridge.java"));
        List<String> violations = new ArrayList<>();
        for (Path file : surfaces) {
            if (!Files.exists(file)) continue;
            int line = 0;
            for (String text : Files.readAllLines(file)) {
                line++;
                for (String accessor : concreteAccessors) {
                    if (text.contains(accessor)) {
                        violations.add(file.getFileName() + ":" + line
                                + " -> " + text.trim());
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "player-facing adapters reach concrete services:\n"
                        + String.join("\n", violations));
    }

    @Test
    void npcPlayerSurfaceStaysOnPortsAndDomain() throws IOException {
        Path surface = SRC.resolve("adapter/in/npc/NpcPlayerSurface.java");
        assertTrue(Files.exists(surface), "NpcPlayerSurface must exist");
        List<String> violations = new ArrayList<>();
        int line = 0;
        for (String text : Files.readAllLines(surface)) {
            line++;
            if (text.contains("com.dwurdy.straja.application.service.")
                    || text.contains("com.dwurdy.straja.adapter.out.persistence")) {
                violations.add(surface.getFileName() + ":" + line + " -> " + text.trim());
            }
        }
        assertTrue(violations.isEmpty(),
                "NpcPlayerSurface reaches past the inbound port:\n"
                        + String.join("\n", violations));
    }
}
