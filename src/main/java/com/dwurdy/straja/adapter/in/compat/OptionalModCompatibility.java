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

    /** Pure constructor used by deterministic tests and future loader adapters. */
    public static Profile fromLoadedIds(Collection<String> loadedIds) {
        Set<String> normalized = loadedIds == null ? Set.of() : loadedIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> id.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return new Profile(normalized.contains(INCAPACITATED), normalized.contains(PIGGYBACK),
                normalized.contains(VAMPIRISM));
    }

    /** Disable Straja's generic downed owner until an exact provider integration is verified. */
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
            StrajaMod.LOGGER.warn("[Straja] Vampirism detected: Straja downed/death ownership is disabled "
                    + "until the target Vampirism build is verified; stake/provider behavior remains external");
        }
        if (profile.piggyback()) {
            StrajaMod.LOGGER.warn("[Straja] Piggyback detected: Straja's crouch carry trigger is disabled "
                    + "to prevent competing passenger interactions; use one carry trigger only");
        }
    }
}
