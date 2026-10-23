package com.dwurdy.straja.adapter.out.npc.content;

import com.dwurdy.straja.application.service.NpcContentCatalog;
import com.dwurdy.straja.domain.model.NpcContentProfile;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the bundled canonical NPC profiles into an {@link NpcContentCatalog}.
 * {@link #BUNDLED_PROFILES} must contain one basename per
 * {@code data/straja/npc/<name>.json} resource — a file shipped without an
 * entry here can never bind (provisioning rejects it as unknown-profile), so
 * {@code NpcContentCatalogLoaderTest} pins the list against the directory
 * contents. Minecraft-free so the headless test classpath can run it.
 */
public final class NpcContentCatalogLoader {
    public static final List<String> BUNDLED_PROFILES = List.of(
            "straja.reception.admission",
            "straja.instructor.admission",
            "straja.secretary.workflows",
            "straja.armorer.orders",
            "straja.jailer.custody",
            "straja.archivist.archive",
            "straja.inspector.documents",
            "straja.commander.army");

    private NpcContentCatalogLoader() {}

    public static NpcContentCatalog load() {
        var loader = new NpcContentProfileJsonLoader();
        var profiles = new ArrayList<NpcContentProfile>();
        for (String name : BUNDLED_PROFILES) {
            String resource = "data/straja/npc/" + name + ".json";
            try (var stream = NpcContentCatalogLoader.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (stream == null) {
                    throw new IllegalStateException("NPC profile resource is missing: " + resource);
                }
                profiles.add(loader.load(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("NPC content catalog failed to load", exception);
            }
        }
        return new NpcContentCatalog(profiles);
    }
}
