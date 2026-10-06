package com.dwurdy.straja.domain.model;

/**
 * One serial-marked regulated artifact in the central registry. New
 * registrations stay PENDING for the configured maturation window before they
 * count as legal — the time cost that makes forgery tempting (#246 / #245 M1).
 * {@code forgeryTier} is reserved for the M2 forging engine; authentic records
 * leave it blank.
 */
public class ArtifactRecord {
    public String serial = "";
    /** Physical mark displayed on the item, e.g. "#RC-15". */
    public String marking = "";
    /** Registry item id, e.g. "minecraft:iron_sword" or "straja:identity_card". */
    public String itemId = "";
    /** "weapon" | "document" | "instrument" — classified from item id at registration. */
    public String artifactKind = "";
    public String holderUuid = "";
    public String holderName = "";
    /** Licensed inspector who struck the mark (or admin override). */
    public String issuerUuid = "";
    public String issuerName = "";
    public long registeredAt;
    public long pendingUntil;
    /** Persisted lifecycle: ACTIVE or REVOKED (PENDING is computed from time). */
    public String status = ArtifactStatus.ACTIVE.name();
    /** Blank for authentic artifacts; "N1".."N5" for forged ones (M2). */
    public String forgeryTier = "";

    public boolean revoked() {
        return ArtifactStatus.REVOKED.name().equals(status);
    }

    /** Effective legal state at {@code now}: PENDING | ACTIVE | REVOKED. */
    public String statusAt(long now) {
        if (revoked()) return ArtifactStatus.REVOKED.name();
        return now >= pendingUntil ? ArtifactStatus.ACTIVE.name() : ArtifactStatus.PENDING.name();
    }

    /** Counts as a legal registered artifact only after maturation. */
    public boolean legalAt(long now) {
        return !revoked() && now >= pendingUntil;
    }
}
