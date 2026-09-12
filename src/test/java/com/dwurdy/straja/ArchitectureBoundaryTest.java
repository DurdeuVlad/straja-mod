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
}
