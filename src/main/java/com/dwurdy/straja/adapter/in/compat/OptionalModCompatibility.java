package com.dwurdy.straja.adapter.in.compat;

import com.dwurdy.straja.StrajaMod;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import net.neoforged.fml.ModList;

/** Detects optional mods by ID only; no optional classes or internals are linked. */
public final class OptionalModCompatibility {
    public static final String INCAPACITATED = "incapacitated";
    public static final String PIGGYBACK = "piggyback";
    public static final String VAMPIRISM = "vampirism";

    private OptionalModCompatibility() {}

    public record Profile(boolean incapacitated, boolean piggyback, boolean vampirism) {
        public boolean genericDownedConflict() { return incapacitated || vampirism; }
        public boolean nativeCarryAllowed() { return !piggyback; }
        public boolean hasOptionalMods() { return incapacitated || piggyback || vampirism; }

        public List<String> loadedIds() {
            var result = new java.util.ArrayList<String>();
            if (incapacitated) result.add(INCAPACITATED);
            if (piggyback) result.add(PIGGYBACK);
            if (vampirism) result.add(VAMPIRISM);
            return List.copyOf(result);
        }
    }

    public static Profile detect() {
        return fromLoadedIds(Set.of(INCAPACITATED, PIGGYBACK, VAMPIRISM).stream()
                .filter(id -> ModList.get().isLoaded(id)).collect(Collectors.toSet()));
    }

    /**
     * Reflectively loads an optional provider only after its mod ID is known
     * to be present. The caller can safely continue with an empty provider.
     */
    public static java.util.Optional<OptionalCustodyProvider> loadProvider(Profile profile) {
        if (profile == null || !profile.vampirism()) return java.util.Optional.empty();
        try {
            Class<?> type = Class.forName(
                    "com.dwurdy.straja.adapter.in.compat.vampirism.VampirismProvider");
            Object provider = type.getDeclaredConstructor().newInstance();
            return provider instanceof OptionalCustodyProvider typed
                    ? java.util.Optional.of(typed)
                    : java.util.Optional.empty();
        } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            StrajaMod.LOGGER.error(
                    "[Straja] Vampirism detected but its optional provider could not be loaded; "
                            + "generic Straja downed ownership remains disabled");
            return java.util.Optional.empty();
        }
    }

    public static java.util.Optional<OptionalCustodyProvider> loadProvider() {
        return loadProvider(detect());
    }

    /** Pure constructor used by deterministic tests and future loader adapters. */
    public static Profile fromLoadedIds(Collection<String> loadedIds) {
        Set<String> normalized = loadedIds == null ? Set.of() : loadedIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> id.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return new Profile(normalized.contains(INCAPACITATED), normalized.contains(PIGGYBACK),
                normalized.contains(VAMPIRISM));
    }

    /** Disable generic Straja downed ownership while another provider is present. */
    public static void applyFailSafe(Profile profile, StrajaPolicies policies) {
        if (profile != null && policies != null && profile.genericDownedConflict()) {
            policies.downedEnabled = false;
        }
    }

    public static void log(Profile profile) {
        if (profile == null || !profile.hasOptionalMods()) {
            StrajaMod.LOGGER.info("[Straja] Optional custody mods: none detected; native ownership is active");
            return;
        }
        StrajaMod.LOGGER.info("[Straja] Optional custody mods detected: {}", profile.loadedIds());
        if (profile.incapacitated()) {
            StrajaMod.LOGGER.warn("[Straja] Incapacitated detected: Straja downed/death ownership is disabled "
                    + "until an exact integration is verified; use one downed provider only");
        }
        if (profile.vampirism()) {
            StrajaMod.LOGGER.warn("[Straja] Vampirism detected: generic Straja downed ownership is disabled; "
                    + "DBNO/resurrection and stake finishing remain Vampirism-owned");
        }
        if (profile.piggyback()) {
            StrajaMod.LOGGER.warn("[Straja] Piggyback detected: Straja's crouch carry trigger is disabled "
                    + "to prevent competing passenger interactions; use one carry trigger only");
        }
    }
}
