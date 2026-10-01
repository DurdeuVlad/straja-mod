package com.dwurdy.straja.adapter.in.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves lang keys against the bundled ro_ro.json so headless gateways render
 * the same Romanian text a player would see, including refusal remedies.
 */
public final class VirtualLang {
    private static final Map<String, String> STRINGS = load();

    private VirtualLang() {
    }

    private static Map<String, String> load() {
        try (var in = VirtualLang.class.getResourceAsStream("/assets/straja/lang/ro_ro.json")) {
            Map<String, String> out = new ConcurrentHashMap<>();
            if (in == null) {
                return out;
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : root.entrySet()) {
                if (e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public static String text(String key) {
        return STRINGS.getOrDefault(key, key);
    }

    /** Substitute {@code %s} placeholders in order, mirroring translatable rendering. */
    public static String format(String key, Object... args) {
        String text = text(key);
        if (args == null) {
            return text;
        }
        for (Object arg : args) {
            text = text.replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(
                    String.valueOf(arg)));
        }
        return text;
    }

    /** Render a refusal exactly like {@code straja.refusal.format}: reason → remedy. */
    public static String refusal(String reasonKey, String remedyKey, Object... reasonArgs) {
        return format(reasonKey, reasonArgs) + " → " + text(remedyKey);
    }
}
