package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.RollSource;
import com.dwurdy.straja.domain.model.ArtifactLicenseType;
import com.dwurdy.straja.domain.model.ArtifactRecord;
import com.dwurdy.straja.domain.model.ForgeryMarking;
import com.dwurdy.straja.domain.model.ForgeryMarking.MarkClass;
import com.dwurdy.straja.domain.model.ForgeryTier;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.List;

/**
 * #247 / #245 M2 — the black-market forging engine. Unlicensed attempts to
 * strike a mark roll the locked pyramid (45/30/15/7/3) and produce an item
 * whose physical marking carries the tier's tell; the registry keeps a
 * FORGED shadow record so truth stays server-side while the item lies.
 * Licensed inspectors never roll — their strike is an authentic
 * registration handled by {@link ArtifactRegistryService#register}.
 *
 * <p>Material consumption and the physical take are owned by the crafting
 * adapters (smithing table for documents, anvil for artifacts); this service
 * only decides quality, marking, claimed serial, and the shadow record.</p>
 */
public class ForgeryService {
    /** Custom-data keys written onto forged items — they lie in authentic shape. */
    public static final String CLAIMED_SERIAL_KEY = ArtifactRegistryService.SERIAL_KEY;
    public static final String MARK_KEY = ArtifactRegistryService.MARK_KEY;
    public static final String TIER_KEY = "ArtifactForgery";

    private final StrajaPolicies policies;
    private final RollSource rng;
    private final Clock clock;
    private final ArtifactRegistryService registry;
    private final PlayerService players;
    private final AuditService audit;

    public ForgeryService(StrajaPolicies policies, RollSource rng, Clock clock,
                          ArtifactRegistryService registry, PlayerService players,
                          AuditService audit) {
        this.policies = policies;
        this.rng = rng;
        this.clock = clock;
        this.registry = registry;
        this.players = players;
        this.audit = audit;
    }

    public boolean enabled() {
        return policies.forgeryEnabled && registry.enabled();
    }

    /** An inspector-licensed actor never rolls — their strike is authentic. */
    public boolean strikeIsAuthentic(PlayerGateway actor) {
        return administrator(actor)
                || registry.isLicensed(actor, ArtifactLicenseType.INSPECTOR);
    }

    private boolean administrator(PlayerGateway player) {
        return player != null && (players.isCommissioner(player) || player.isOp());
    }

    /** One draw on the configured pyramid. Exposed for the Monte Carlo suite. */
    public ForgeryTier rollTier() {
        return ForgeryTier.roll(rng::nextInt, policies.forgeryTierWeights);
    }

    /** The outcome of one forge attempt — what the adapter writes on the item. */
    public record ForgeOutcome(
            ForgeryTier tier,
            String marking,
            /** The serial the item claims to carry (spoofed or garbage). */
            String claimedSerial,
            /** FRG-n key of the shadow record written for this attempt. */
            String shadowSerial) {}

    /**
     * Resolve a complete forge attempt for an unlicensed actor: roll the tier,
     * generate the malformed mark, claim a serial, and write the shadow
     * record. Called once, at the physical take — never on a UI preview.
     */
    public ForgeOutcome forge(PlayerGateway actor, String itemId) {
        if (actor == null) return null;
        ForgeryTier tier = rollTier();
        String marking = ForgeryMarking.markingFor(tier, rng::nextInt,
                policies.artifactSerialPrefix, registry.nextSerialNumber(),
                registry.authenticSerials());
        String claimed = ForgeryMarking.claimedSerialFor(marking);
        ArtifactRecord shadow = registry.recordForgery(actor, itemId,
                marking, claimed, tier);
        audit.record("artifact_forge", actor.name(), uuid(actor),
                actor.name(), uuid(actor), "SUCCESS",
                "tier=" + tier.name() + " marking=" + marking
                        + " claimed=" + claimed + " item=" + itemId
                        + " shadow=" + shadow.serial);
        return new ForgeOutcome(tier, marking, claimed, shadow.serial);
    }

    /** What an anvil stamp strike resolves to for one actor. */
    public record AnvilStrike(int xpLevels, boolean authentic) {}

    /**
     * Plans a seal-stamp strike on the anvil. Null means "not a forging
     * operation" — the item is unregulated or already carries a mark.
     * Licensed inspectors get the authentic strike (registry-backed);
     * everyone else rolls the pyramid on take.
     */
    public AnvilStrike planAnvilStrike(PlayerGateway actor, String itemId,
                                       boolean alreadyMarked) {
        if (!enabled() || itemId == null || alreadyMarked) return null;
        if (!policies.artifactRegulatedItemIds.contains(itemId)) return null;
        return strikeIsAuthentic(actor)
                ? new AnvilStrike(policies.forgeryAnvilXpLicensed, true)
                : new AnvilStrike(policies.forgeryAnvilXpUnlicensed, false);
    }

    /** Bare-look classification of a marking — the M3 scanner's first pass. */
    public MarkClass classifyMarking(String marking) {
        return ForgeryMarking.classify(marking, policies.artifactSerialPrefix);
    }

    /**
     * Second-pass truth: what the registry says about the claimed serial.
     * ABSENT = no record at all (unregistered), CONFLICT = record exists but
     * describes a different item or holder, KNOWN_FORGED = shadow record.
     */
    public enum RegistryCheck { AUTHENTIC, ABSENT, CONFLICT, KNOWN_FORGED }

    public RegistryCheck checkClaim(String claimedSerial, String itemId) {
        ArtifactRecord record = registry.recordForClaim(claimedSerial);
        if (record == null) return RegistryCheck.ABSENT;
        if (record.forged()) return RegistryCheck.KNOWN_FORGED;
        if (!record.itemId.equals(itemId)) return RegistryCheck.CONFLICT;
        return RegistryCheck.AUTHENTIC;
    }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    /**
     * The exemplar rule: does this stack count as a genuine reference a forger
     * can copy? Only items already carrying registry/issuance data — a blank
     * item can never teach a serial's shape. Consumed by the smithing
     * ingredient predicate.
     */
    public static boolean isExemplarData(java.util.Map<String, String> data) {
        if (data == null) return false;
        for (String key : List.of("IdentityCardId", "DocumentId",
                "InstrumentId", ArtifactRegistryService.SERIAL_KEY)) {
            String value = data.get(key);
            if (value != null && !value.isBlank()) return true;
        }
        return false;
    }

    /** Which document class an exemplar stack represents (id -> kind). */
    public static String exemplarKind(String itemId,
                                      java.util.Map<String, String> data) {
        if (itemId == null || !isExemplarData(data)) return null;
        return switch (itemId) {
            case "straja:identity_card" ->
                    data.get("IdentityCardId") != null ? "card" : null;
            case "straja:official_document" ->
                    data.get("DocumentId") != null ? "document" : null;
            case "straja:official_instrument" ->
                    data.get("InstrumentId") != null ? "instrument" : null;
            default -> null;
        };
    }

}
