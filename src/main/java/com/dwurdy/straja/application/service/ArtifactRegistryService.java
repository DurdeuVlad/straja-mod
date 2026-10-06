package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.port.out.ArtifactRegistryRepository;
import com.dwurdy.straja.application.port.out.Clock;
import com.dwurdy.straja.application.port.out.IdGenerator;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ArtifactLicense;
import com.dwurdy.straja.domain.model.ArtifactLicenseType;
import com.dwurdy.straja.domain.model.ArtifactRecord;
import com.dwurdy.straja.domain.model.ArtifactRegistryStore;
import com.dwurdy.straja.domain.model.ArtifactStatus;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * #246 / #245 M1 — central registry of serial-marked regulated artifacts and
 * the licensed professions around them. Licensed inspectors strike authentic
 * marks; licensed transporters seal military crates. New registrations sit
 * PENDING for the configured maturation window — legality costs real time,
 * which is the pressure that makes the black market exist.
 */
public class ArtifactRegistryService {
    public static final String CRATE_ITEM = "straja:sealed_military_crate";
    /** Item custom-data keys written by the seal verb (read back by unseal/M3 scanner). */
    public static final String SEAL_BY = "SealBy";
    public static final String SEAL_NAME = "SealName";
    public static final String SEAL_ID = "SealId";
    public static final String SEAL_AT = "SealAt";
    /** Item custom-data keys binding a physical item to its registry record. */
    public static final String SERIAL_KEY = "ArtifactSerial";
    public static final String MARK_KEY = "ArtifactMark";
    private static final long HOUR_MS = 60L * 60 * 1000;
    private static final Set<String> DOCUMENT_ITEMS = Set.of(
            "straja:identity_card", "straja:official_document",
            "straja:official_instrument", "straja:archive_document",
            "straja:fine_notice", "straja:confiscation_receipt");

    private final ArtifactRegistryRepository repository;
    private final Clock clock;
    private final IdGenerator ids;
    private final StrajaPolicies policies;
    private final PlayerService players;
    private final AuditService audit;

    public ArtifactRegistryService(ArtifactRegistryRepository repository, Clock clock,
                                   IdGenerator ids, StrajaPolicies policies,
                                   PlayerService players, AuditService audit) {
        this.repository = repository;
        this.clock = clock;
        this.ids = ids;
        this.policies = policies;
        this.players = players;
        this.audit = audit;
    }

    private long now() { return clock.nowMillis(); }

    private boolean administrator(PlayerGateway player) {
        return player != null && (players.isCommissioner(player) || player.isOp());
    }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    private static String safeName(PlayerGateway player) {
        String name = player == null || player.name() == null ? "" : player.name().trim();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    private static ArtifactRegistryStore normalized(ArtifactRegistryStore store) {
        if (store == null) store = new ArtifactRegistryStore();
        if (store.artifacts == null) store.artifacts = new LinkedHashMap<>();
        if (store.licenses == null) store.licenses = new LinkedHashMap<>();
        if (store.nextSerial < 1) store.nextSerial = 1;
        if (store.nextLicense < 1) store.nextLicense = 1;
        return store;
    }

    private static String licenseKey(String holderUuid, ArtifactLicenseType type) {
        return holderUuid + ":" + type.name();
    }

    /** Read-side used by scanners/forgery checks; no player required. */
    public boolean enabled() { return policies.artifactRegistryEnabled; }

    public boolean isLicensed(String holderUuid, ArtifactLicenseType type) {
        if (holderUuid == null || holderUuid.isBlank() || type == null) return false;
        ArtifactLicense license = normalized(repository.read()).licenses
                .get(licenseKey(holderUuid, type));
        return license != null && license.active();
    }

    public boolean isLicensed(PlayerGateway player, ArtifactLicenseType type) {
        return player != null && isLicensed(uuid(player), type);
    }

    // ---------------------------------------------------------------- licenses

    public boolean grantLicense(PlayerGateway actor, PlayerGateway target, String typeName) {
        if (actor == null || target == null) return false;
        if (!enabled()) {
            actor.refuse("straja.artifact.disabled", "straja.remedy.ask_comisar");
            return false;
        }
        if (!administrator(actor)) {
            actor.refuse("straja.license.auth", "straja.remedy.ask_comisar");
            return false;
        }
        ArtifactLicenseType type = ArtifactLicenseType.parse(typeName);
        if (type == null) {
            actor.refuse("straja.license.type_invalid", "straja.remedy.fix_retry");
            return false;
        }
        if (!target.isOnline() || target.uuid() == null) {
            actor.refuse("straja.idcard.holder_offline", "straja.remedy.wait");
            return false;
        }
        ArtifactRegistryStore store = normalized(repository.read());
        if (isLicensed(uuid(target), type)) {
            actor.refuse("straja.license.already", "straja.remedy.fix_retry", type.name());
            return false;
        }
        ArtifactLicense license = new ArtifactLicense();
        license.licenseId = "LIC-" + store.nextLicense++;
        license.type = type.name();
        license.holderUuid = uuid(target);
        license.holderName = safeName(target);
        license.grantedByUuid = uuid(actor);
        license.grantedByName = safeName(actor);
        license.grantedAt = now();
        store.licenses.put(licenseKey(license.holderUuid, type), license);
        store.storeRevision++;
        repository.write(store);
        audit.record("license_grant", actor.name(), uuid(actor),
                target.name(), uuid(target), "SUCCESS",
                "licenseId=" + license.licenseId + " type=" + type.name());
        target.tell("Ai primit licența de " + licenseLabel(type) + " (" + license.licenseId + ").");
        if (!uuid(actor).equalsIgnoreCase(uuid(target))) {
            actor.tell("Licența " + license.licenseId + " (" + licenseLabel(type)
                    + ") a fost emisă pentru " + safeName(target) + ".");
        }
        return true;
    }

    public boolean revokeLicense(PlayerGateway actor, PlayerGateway target, String typeName) {
        if (actor == null || target == null) return false;
        if (!enabled()) {
            actor.refuse("straja.artifact.disabled", "straja.remedy.ask_comisar");
            return false;
        }
        if (!administrator(actor)) {
            actor.refuse("straja.license.auth", "straja.remedy.ask_comisar");
            return false;
        }
        ArtifactLicenseType type = ArtifactLicenseType.parse(typeName);
        if (type == null) {
            actor.refuse("straja.license.type_invalid", "straja.remedy.fix_retry");
            return false;
        }
        ArtifactRegistryStore store = normalized(repository.read());
        ArtifactLicense license = store.licenses.get(licenseKey(uuid(target), type));
        if (license == null || !license.active()) {
            actor.refuse("straja.license.missing", "straja.remedy.fix_retry", type.name());
            return false;
        }
        license.revokedAt = now();
        license.revokedByUuid = uuid(actor);
        store.storeRevision++;
        repository.write(store);
        audit.record("license_revoke", actor.name(), uuid(actor),
                target.name(), uuid(target), "SUCCESS",
                "licenseId=" + license.licenseId + " type=" + type.name());
        target.tell("Licența ta de " + licenseLabel(type) + " (" + license.licenseId
                + ") a fost revocată.");
        actor.tell("Licența " + license.licenseId + " a fost revocată.");
        return true;
    }

    /** Admin view: every active license in the registry. */
    public void listLicenses(PlayerGateway actor) {
        if (actor == null) return;
        if (!administrator(actor)) {
            actor.refuse("straja.license.auth", "straja.remedy.ask_comisar");
            return;
        }
        List<ArtifactLicense> active = activeLicenses(normalized(repository.read()));
        if (active.isEmpty()) {
            actor.refuse("straja.license.none", "straja.remedy.ask_comisar");
            return;
        }
        actor.tell("Licențe active (" + active.size() + "):");
        for (ArtifactLicense license : active) {
            actor.tell("  " + license.licenseId + " — " + licenseLabel(
                    ArtifactLicenseType.parse(license.type)) + " — " + license.holderName);
        }
    }

    /** Self-service status: which licenses the caller personally holds. */
    public void ownLicenses(PlayerGateway player) {
        if (player == null) return;
        List<ArtifactLicense> mine = new ArrayList<>();
        for (ArtifactLicense license : activeLicenses(normalized(repository.read()))) {
            if (license.holderUuid.equalsIgnoreCase(uuid(player))) mine.add(license);
        }
        if (mine.isEmpty()) {
            player.refuse("straja.license.none_own", "straja.remedy.ask_comisar");
            return;
        }
        player.tell("Licențele tale:");
        for (ArtifactLicense license : mine) {
            player.tell("  " + license.licenseId + " — " + licenseLabel(
                    ArtifactLicenseType.parse(license.type)));
        }
    }

    private static List<ArtifactLicense> activeLicenses(ArtifactRegistryStore store) {
        List<ArtifactLicense> out = new ArrayList<>();
        for (ArtifactLicense license : store.licenses.values()) {
            if (license != null && license.active()) out.add(license);
        }
        return out;
    }

    private static String licenseLabel(ArtifactLicenseType type) {
        if (type == null) return "necunoscută";
        return switch (type) {
            case INSPECTOR -> "Inspector";
            case TRANSPORTER -> "Transportator";
        };
    }

    // ---------------------------------------------------------------- registry

    /**
     * Strike an authentic mark: allocates the next serial and records the
     * artifact as PENDING for the configured maturation window. Requires an
     * INSPECTOR license — administrators may register directly for staging.
     * Returns the serial on success, null otherwise.
     */
    public String register(PlayerGateway actor, PlayerGateway holder, String itemId) {
        if (actor == null || holder == null) return null;
        if (!enabled()) {
            actor.refuse("straja.artifact.disabled", "straja.remedy.ask_comisar");
            return null;
        }
        if (itemId == null || itemId.isBlank()) {
            actor.refuse("straja.artifact.item_invalid", "straja.remedy.fix_retry");
            return null;
        }
        if (!administrator(actor) && !isLicensed(actor, ArtifactLicenseType.INSPECTOR)) {
            actor.refuse("straja.artifact.need_inspector", "straja.remedy.inspector");
            return null;
        }
        if (holder.uuid() == null || safeName(holder).isBlank()) {
            actor.refuse("straja.idcard.holder_invalid", "straja.remedy.fix_retry");
            return null;
        }
        ArtifactRegistryStore store = normalized(repository.read());
        long number = store.nextSerial;
        String serial = policies.artifactSerialPrefix + number;
        while (store.artifacts.containsKey(serial)) {
            if (number == Long.MAX_VALUE) {
                actor.refuse("straja.artifact.exhausted", "straja.remedy.wait");
                return null;
            }
            number++;
            serial = policies.artifactSerialPrefix + number;
        }
        long registeredAt = now();
        ArtifactRecord record = new ArtifactRecord();
        record.serial = serial;
        record.marking = "#" + serial;
        record.itemId = itemId.trim();
        record.artifactKind = classify(record.itemId);
        record.holderUuid = uuid(holder);
        record.holderName = safeName(holder);
        record.issuerUuid = uuid(actor);
        record.issuerName = safeName(actor);
        record.registeredAt = registeredAt;
        record.pendingUntil = registeredAt + maturationMs();
        store.artifacts.put(serial, record);
        store.nextSerial = number + 1;
        store.storeRevision++;
        repository.write(store);
        audit.record("artifact_register", actor.name(), uuid(actor),
                holder.name(), uuid(holder), "SUCCESS",
                "serial=" + serial + " item=" + record.itemId
                        + " pendingUntil=" + record.pendingUntil);
        actor.tell("Marca " + record.marking + " înregistrată pentru " + record.holderName
                + " — devine legală peste " + policies.artifactPendingHours + "h.");
        return serial;
    }

    /**
     * The M1-usable verb: registers the artifact held in the actor's main hand
     * and stamps the allocated serial back onto the item, so the physical mark
     * and the ledger entry are bound from the start. A stamped item cannot be
     * registered again — re-striking it is a refusal, not a new serial. If the
     * item cannot take the mark, the fresh record is revoked immediately so no
     * unbound serial is left behind.
     */
    public String registerHeld(PlayerGateway actor, PlayerGateway holder) {
        if (actor == null || holder == null) return null;
        ItemView held = actor.mainHand();
        if (held.isEmpty()) {
            actor.refuse("straja.artifact.item_invalid", "straja.remedy.fix_retry");
            return null;
        }
        String existing = held.data(SERIAL_KEY);
        if (existing != null && !existing.isBlank()) {
            actor.refuse("straja.artifact.already_marked", "straja.remedy.fix_retry",
                    "#" + existing);
            return null;
        }
        String serial = register(actor, holder, held.id());
        if (serial == null) return null;
        var data = new LinkedHashMap<>(held.customData());
        data.put(SERIAL_KEY, serial);
        data.put(MARK_KEY, "#" + serial);
        if (replaceHeld(actor, held, new ItemSpec(held.id(), 1, data, null))) {
            return serial;
        }
        // The item would not take the mark; burn the serial so the registry
        // never claims a mark that no item carries.
        ArtifactRegistryStore store = normalized(repository.read());
        ArtifactRecord record = store.artifacts.get(serial);
        if (record != null) {
            record.status = ArtifactStatus.REVOKED.name();
            store.storeRevision++;
            repository.write(store);
        }
        audit.record("artifact_mark_failed", actor.name(), uuid(actor),
                holder.name(), uuid(holder), "FAIL", "serial=" + serial);
        actor.refuse("straja.artifact.mark_failed", "straja.remedy.retry", "#" + serial);
        return null;
    }

    /** Officer tool: registry truth for a serial — the cross-check that burns forgeries. */
    public void info(PlayerGateway viewer, String serial) {
        if (viewer == null || serial == null) return;
        ArtifactRecord record = normalized(repository.read()).artifacts.get(serial.trim());
        boolean authority = administrator(viewer) || players.isOnDutyGuard(viewer);
        if (record == null) {
            if (authority) viewer.refuse("straja.artifact.unknown_serial", "straja.remedy.fix_retry", serial.trim());
            else viewer.refuse("straja.artifact.no_access", "straja.remedy.inspector");
            return;
        }
        if (!authority && !record.holderUuid.equalsIgnoreCase(uuid(viewer))) {
            viewer.refuse("straja.artifact.no_access", "straja.remedy.inspector");
            return;
        }
        String state = record.statusAt(now());
        String line = "Marca " + record.marking + " — " + state
                + "; tip: " + record.artifactKind
                + "; titular: " + record.holderName
                + "; emisă de: " + record.issuerName;
        if (ArtifactStatus.PENDING.name().equals(state)) {
            line += "; legală peste " + prettyTime(record.pendingUntil - now());
        }
        viewer.tell(line + ".");
    }

    /** Lookup for scanners/inspection: the record or null. */
    public ArtifactRecord find(String serial) {
        if (serial == null || serial.isBlank()) return null;
        return normalized(repository.read()).artifacts.get(serial.trim());
    }

    /** A record that exists, is not revoked, and has finished maturing. */
    public boolean isLegal(String serial) {
        ArtifactRecord record = find(serial);
        return record != null && record.legalAt(now());
    }

    public boolean revokeArtifact(PlayerGateway actor, String serial) {
        if (actor == null || serial == null) return false;
        if (!enabled()) {
            actor.refuse("straja.artifact.disabled", "straja.remedy.ask_comisar");
            return false;
        }
        if (!administrator(actor)) {
            actor.refuse("straja.license.auth", "straja.remedy.ask_comisar");
            return false;
        }
        ArtifactRegistryStore store = normalized(repository.read());
        ArtifactRecord record = store.artifacts.get(serial.trim());
        if (record == null) {
            actor.refuse("straja.artifact.unknown_serial", "straja.remedy.fix_retry", serial.trim());
            return false;
        }
        if (record.revoked()) {
            actor.refuse("straja.artifact.already_revoked", "straja.remedy.fix_retry", record.marking);
            return false;
        }
        record.status = ArtifactStatus.REVOKED.name();
        store.storeRevision++;
        repository.write(store);
        audit.record("artifact_revoke", actor.name(), uuid(actor),
                record.holderName, record.holderUuid, "SUCCESS", "serial=" + record.serial);
        actor.tell("Marca " + record.marking + " a fost revocată.");
        return true;
    }

    private String classify(String itemId) {
        if (itemId == null) return "other";
        if (policies.artifactRegulatedItemIds.contains(itemId)) return "weapon";
        if (DOCUMENT_ITEMS.contains(itemId)) return "document";
        return "other";
    }

    private long maturationMs() {
        return Math.max(0, policies.artifactPendingHours) * HOUR_MS;
    }

    private static String prettyTime(long ms) {
        long hours = Math.max(0, ms) / HOUR_MS;
        return hours > 0 ? hours + "h" : (Math.max(0, ms) / 60000) + "min";
    }

    // ---------------------------------------------------------------- crate seals

    /** TRANSPORTER-licensed verb: seals the military crate held in the main hand. */
    public boolean sealHeld(PlayerGateway actor) {
        if (actor == null) return false;
        if (!enabled()) {
            actor.refuse("straja.artifact.disabled", "straja.remedy.ask_comisar");
            return false;
        }
        if (!administrator(actor) && !isLicensed(actor, ArtifactLicenseType.TRANSPORTER)) {
            actor.refuse("straja.artifact.need_transporter", "straja.remedy.inspector");
            return false;
        }
        ItemView held = actor.mainHand();
        if (held.isEmpty() || !CRATE_ITEM.equals(held.id())) {
            actor.refuse("straja.artifact.need_crate", "straja.remedy.fix_retry");
            return false;
        }
        if (held.data(SEAL_BY) != null && !held.data(SEAL_BY).isBlank()) {
            actor.refuse("straja.crate.already_sealed", "straja.remedy.fix_retry",
                    java.util.Objects.requireNonNullElse(held.data(SEAL_NAME), "?"));
            return false;
        }
        var data = new LinkedHashMap<>(held.customData());
        data.put(SEAL_BY, uuid(actor));
        data.put(SEAL_NAME, safeName(actor));
        data.put(SEAL_ID, ids.newId("SEAL"));
        data.put(SEAL_AT, Long.toString(now()));
        if (!replaceHeld(actor, held, new ItemSpec(CRATE_ITEM, 1, data, null))) {
            actor.refuse("straja.artifact.seal_failed", "straja.remedy.retry");
            return false;
        }
        audit.record("crate_seal", actor.name(), uuid(actor),
                actor.name(), uuid(actor), "SUCCESS", "sealId=" + data.get(SEAL_ID));
        actor.tell("Lada militară a fost sigilată pe numele tău.");
        return true;
    }

    /** Breaks the seal on a held crate; transporters seal-break their own, authority any. */
    public boolean unsealHeld(PlayerGateway actor) {
        if (actor == null) return false;
        if (!enabled()) {
            actor.refuse("straja.artifact.disabled", "straja.remedy.ask_comisar");
            return false;
        }
        ItemView held = actor.mainHand();
        if (held.isEmpty() || !CRATE_ITEM.equals(held.id())) {
            actor.refuse("straja.artifact.need_crate", "straja.remedy.fix_retry");
            return false;
        }
        String sealedBy = held.data(SEAL_BY);
        if (sealedBy == null || sealedBy.isBlank()) {
            actor.refuse("straja.artifact.not_sealed", "straja.remedy.fix_retry");
            return false;
        }
        boolean ownSeal = sealedBy.equalsIgnoreCase(uuid(actor));
        if (!administrator(actor)
                && !(ownSeal && isLicensed(actor, ArtifactLicenseType.TRANSPORTER))) {
            actor.refuse("straja.artifact.not_your_seal", "straja.remedy.inspector");
            return false;
        }
        var data = new LinkedHashMap<>(held.customData());
        data.remove(SEAL_BY);
        data.remove(SEAL_NAME);
        data.remove(SEAL_ID);
        data.remove(SEAL_AT);
        if (!replaceHeld(actor, held, new ItemSpec(CRATE_ITEM, 1, data, null))) {
            actor.refuse("straja.artifact.unseal_failed", "straja.remedy.retry");
            return false;
        }
        audit.record("crate_unseal", actor.name(), uuid(actor),
                actor.name(), uuid(actor), "SUCCESS", "sealedBy=" + sealedBy);
        actor.tell("Sigiliul lăzii a fost rupt.");
        return true;
    }

    /**
     * Extract the held stack and hand back its replacement; rolls the extract
     * back on failure. The extract runs first — it frees exactly the slot the
     * replacement needs, so a full inventory does not produce a spurious
     * refusal. The replacement is rebuilt from custom data only, so components
     * outside {@code customData} (e.g. an anvil rename) do not survive the
     * swap — a known M1 limitation shared by every ItemView-mutating verb.
     */
    private boolean replaceHeld(PlayerGateway actor, ItemView held, ItemSpec replacement) {
        ItemView removed = actor.inventory().extract(actor.selectedSlot(), 1);
        if (removed.isEmpty()) return false;
        if (actor.giveVerified(replacement)) return true;
        actor.give(new ItemSpec(held.id(), 1, held.customData(), null));
        return false;
    }
}
