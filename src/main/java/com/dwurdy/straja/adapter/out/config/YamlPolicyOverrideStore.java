package com.dwurdy.straja.adapter.out.config;

import com.dwurdy.straja.application.port.out.PolicyOverrideStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Flat YAML override file ({@code config/straja-policies.yaml}):
 *
 * <pre>
 * salary.perBlock: "1=20;2=30;3=40;4=50"
 * timers.quizCooldownMinutes: "10"
 * </pre>
 *
 * Deliberately flat — override keys are opaque paths and every value is a
 * quoted string, so a hand-rolled writer/parser stays exact and dependency
 * free. A malformed file never throws: the last-good overrides remain loaded.
 */
public final class YamlPolicyOverrideStore implements PolicyOverrideStore {
    private final Path file;

    public YamlPolicyOverrideStore(Path file) {
        this.file = file;
    }

    @Override
    public Map<String, String> read() {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int sep = trimmed.indexOf(':');
                if (sep <= 0) continue;
                String key = trimmed.substring(0, sep).trim();
                String value = trimmed.substring(sep + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = unescape(value.substring(1, value.length() - 1));
                }
                out.put(key, value);
            }
        } catch (IOException ignored) {
            // unreadable file → last-good in-memory state stays in effect
        }
        return out;
    }

    @Override
    public void write(Map<String, String> overrides) {
        var lines = new java.util.ArrayList<String>();
        lines.add("# Straja runtime policy overrides — edited in-game via /straja policy set|reset.");
        lines.add("# Values are strings; remove a line to return that key to its TOML default.");
        for (var entry : new TreeMap<>(overrides).entrySet()) {
            lines.add(entry.getKey() + ": \"" + escape(entry.getValue()) + "\"");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // write failure is surfaced to the actor by the caller context
        }
    }

    @Override
    public String describe() {
        return file.getFileName().toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
