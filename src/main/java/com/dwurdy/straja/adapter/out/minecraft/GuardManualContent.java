package com.dwurdy.straja.adapter.out.minecraft;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.server.network.Filterable;

/** Loads the Romanian guard manual from data so the handbook is maintainable content. */
public final class GuardManualContent {
    private static final String RESOURCE = "/data/straja/manual/guard_manual_ro.json";
    private static final Gson GSON = new Gson();
    private static volatile WrittenBookContent cached;

    private GuardManualContent() {}

    public static WrittenBookContent content() {
        WrittenBookContent current = cached;
        if (current != null) return current;
        synchronized (GuardManualContent.class) {
            if (cached == null) cached = load();
            return cached;
        }
    }

    private static WrittenBookContent load() {
        try (InputStream stream = GuardManualContent.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("Missing " + RESOURCE);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                ManualFile file = GSON.fromJson(reader, ManualFile.class);
                if (file == null || file.pages == null || file.pages.isEmpty()) {
                    throw new IllegalStateException("Manual has no pages");
                }
                var pages = file.pages.stream()
                        .<Filterable<Component>>map(page -> Filterable.passThrough(Component.literal(
                                clean(page.title) + "\n\n" + clean(page.body))))
                        .toList();
                return new WrittenBookContent(
                        Filterable.passThrough(clean(file.title)),
                        clean(file.author).isBlank() ? "Straja" : clean(file.author),
                        0, pages, true);
            }
        } catch (Exception error) {
            com.dwurdy.straja.StrajaMod.LOGGER.error(
                    "[Straja] Could not load the Romanian guard manual; using emergency fallback", error);
            return fallback();
        }
    }

    private static WrittenBookContent fallback() {
        return new WrittenBookContent(
                Filterable.passThrough("Manualul Străjii"),
                "Straja", 0,
                List.of(Filterable.<Component>passThrough(Component.literal(
                        "Manual indisponibil\n\nContactează Comisarul pentru o copie nouă."))),
                true);
    }

    private static String clean(String value) {
        if (value == null) return "";
        return value.replaceAll("\\p{Cntrl}", " ").trim();
    }

    private static final class ManualFile {
        String title;
        String author;
        List<Page> pages;
    }

    private static final class Page {
        String title;
        String body;
    }
}
