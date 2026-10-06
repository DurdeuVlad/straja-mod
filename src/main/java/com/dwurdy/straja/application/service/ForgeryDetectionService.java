package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ForgeryMarking;
import com.dwurdy.straja.domain.model.ForgeryMarking.MarkClass;
import com.dwurdy.straja.domain.model.SnapshotItem;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.List;

/**
 * #248 / #245 M3 — the keen-eye ladder. Two detection surfaces over one
 * carried-item snapshot:
 *
 * <p><b>The machine</b> ({@link #machineScan}) reads only the physical mark —
 * a barcode scanner, not an archivist. It catches the ABSURD (crude, arrest
 * lane), the MALFORMED (flagged), and the UNMARKED regulated item; anything
 * format-plausible walks through — N3 and better pass the machine by design.
 *
 * <p><b>The inspector</b> ({@link #inspect}) adds the registry cross-check a
 * machine can't do, gated by expertise: JUNIOR catches far-fetched claims and
 * serials only forge shadows ever presented; VETERAN adds near-miss claims
 * (the serial the office hasn't issued yet); EXPERT adds the N1 tell — a real
 * serial bound to a different item or a different holder.</p>
 */
public final class ForgeryDetectionService {

    /** Expertise from a persisted NPC record tag — blank/unknown = JUNIOR. */
    public static Expertise expertiseOf(String tag) {
        if (tag == null) return Expertise.JUNIOR;
        return switch (tag.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "veteran" -> Expertise.VETERAN;
            case "expert" -> Expertise.EXPERT;
            case "none" -> Expertise.NONE;
            default -> Expertise.JUNIOR;
        };
    }

    /** How far past the next authentic allocation a serial can sit while still
     * feeling "almost issued" — the band a veteran eye questions. Mirrors the
     * N2 generator's +1..+9 window so near-miss claims are exactly N2-shaped. */
    public static final long NEAR_MISS_BAND = 9;

    /** An inspection is hands-on — officer and traveler must share this radius. */
    private static final double INSPECT_RADIUS_BLOCKS = 8.0;

    /** Detection depth — each rung sees everything below it. */
    public enum Expertise { NONE, JUNIOR, VETERAN, EXPERT }

    public enum Verdict {
        CLEAN,
        /** Regulated item carrying no mark at all. */
        UNREGISTERED,
        /** Mark fails the official format — machine-flagged. */
        FLAGGED,
        /** Absurd mark — crude forgery, arrest lane. */
        CRUDE,
        /** Format-passable but the registry cross-check burns it. */
        SUSPECT_CLAIM
    }

    /** One flagged item + the tell the detector actually saw. */
    public record Finding(SnapshotItem item, Verdict verdict, String clue) {
        /** The offense kind recorded for this finding. */
        public String offense() {
            return item != null && ArtifactRegistryService.isDocumentItem(item.itemId)
                    ? "document_forgery" : "artifact_forgery";
        }
    }

    public record ScanResult(List<Finding> findings, Verdict worst) {
        public boolean clean() { return findings.isEmpty(); }
    }

    private final StrajaContext ctx;
    private final StrajaPolicies policies;
    private final ForgeryService forgery;
    private final ArtifactRegistryService registry;
    private final PlayerService players;
    private final AuditService audit;

    public ForgeryDetectionService(StrajaContext ctx, ForgeryService forgery,
                                   ArtifactRegistryService registry,
                                   PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.policies = ctx.policies();
        this.forgery = forgery;
        this.registry = registry;
        this.players = players;
        this.audit = audit;
    }

    /** Gate-scanner verdicts — physical mark only, never the registry. */
    public ScanResult machineScan(List<SnapshotItem> snapshot) {
        List<Finding> out = new ArrayList<>();
        for (SnapshotItem item : safe(snapshot)) {
            Finding f = machineVerdict(item);
            if (f != null) out.add(f);
        }
        return new ScanResult(out, worst(out));
    }

    /**
     * The trained eye: machine pass + registry cross-check at the given
     * expertise ceiling. {@code presenterUuid} binds an N1 claim to its
     * holder — a real serial on the wrong man is still a forgery.
     */
    public ScanResult inspect(List<SnapshotItem> snapshot, String presenterUuid,
                              Expertise expertise) {
        List<Finding> out = new ArrayList<>();
        for (SnapshotItem item : safe(snapshot)) {
            Finding f = machineVerdict(item);
            if (f == null && expertise != null && expertise != Expertise.NONE) {
                f = expertVerdict(item, presenterUuid, expertise);
            }
            if (f != null) out.add(f);
        }
        return new ScanResult(out, worst(out));
    }

    /**
     * The full inspector-booth session: deep-scan the traveler, evaluate at
     * the NPC's expertise ceiling, narrate what was seen (the denial-stamp
     * feedback), then on any catch confiscate the flagged stock + record the
     * offense + raise the system BOLO. The expertise ladder is per-NPC —
     * an expert booth burns an N1, a junior one waves it through.
     */
    public record NpcInspectOutcome(List<Finding> findings, int seized,
                                    boolean flagged, String narration) {}

    public NpcInspectOutcome npcInspect(PlayerGateway traveler,
                                        Expertise expertise, String npcName,
                                        SeizureService seizure, BoloService bolos) {
        if (traveler == null) {
            return new NpcInspectOutcome(List.of(), 0, false, "");
        }
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(traveler.uuid());
        var scan = inspect(snapshot, traveler.uuid().toString(), expertise);
        String npc = npcName == null || npcName.isBlank() ? "Inspectorul" : npcName;
        if (scan.findings().isEmpty()) {
            traveler.tell(npc + " trece documentele prin lumină. „Totul e în regulă. Poți trece.”");
            return new NpcInspectOutcome(List.of(), 0, false, "clean");
        }
        int seized = enforceFindings(traveler, scan, npc, "", seizure, bolos);
        StringBuilder narration = new StringBuilder();
        for (Finding f : scan.findings()) {
            String id = f.item() != null && f.item().name != null && !f.item().name.isBlank()
                    ? f.item().name : (f.item() == null ? "?" : f.item().itemId);
            narration.append(npc).append(" ridică ").append(id)
                    .append(": „").append(f.clue()).append(".” ");
        }
        traveler.tell(narration.toString().trim());
        traveler.tell(npc + " confiscă marfa contrasemnată. Ești în evidențele Străjii.");
        return new NpcInspectOutcome(scan.findings(), seized, true, narration.toString());
    }

    /**
     * Player/officer parity: an on-duty officer inspects a traveler's stock
     * at their own ceiling — duty guard JUNIOR, quiz-trained or sergent+
     * VETERAN, commissioner EXPERT. The narration goes to the officer (the
     * traveler sees only the consequence), and enforcement is identical to
     * the NPC booth: targeted seizure + offense audit + system BOLO.
     */
    public NpcInspectOutcome officerInspect(PlayerGateway officer,
                                            PlayerGateway traveler,
                                            SeizureService seizure,
                                            BoloService bolos) {
        if (officer == null || traveler == null) {
            return new NpcInspectOutcome(List.of(), 0, false, "");
        }
        var expertise = officerExpertise(officer);
        if (expertise == Expertise.NONE) {
            officer.refuse("straja.inspect.not_on_duty", "straja.remedy.duty");
            return new NpcInspectOutcome(List.of(), 0, false, "not_on_duty");
        }
        if (officer.uuid() != null && officer.uuid().equals(traveler.uuid())) {
            officer.refuse("straja.inspect.self", "straja.remedy.fix_retry");
            return new NpcInspectOutcome(List.of(), 0, false, "self");
        }
        // A document inspection is a hands-on check — the officer must stand
        // next to the traveler, not call it in from across the map.
        if (!near(officer, traveler, INSPECT_RADIUS_BLOCKS)) {
            officer.refuse("straja.inspect.too_far", "straja.remedy.fix_retry");
            return new NpcInspectOutcome(List.of(), 0, false, "too_far");
        }
        List<SnapshotItem> snapshot = ctx.deepScan().deepScan(traveler.uuid());
        var scan = inspect(snapshot, traveler.uuid().toString(), expertise);
        String who = "ofițerul " + officer.name();
        if (scan.findings().isEmpty()) {
            officer.tell("Treci inventarul lui " + traveler.name()
                    + " prin lumină — nimic nereglementat la vedere.");
            return new NpcInspectOutcome(List.of(), 0, false, "clean");
        }
        int seized = enforceFindings(traveler, scan, who,
                officer.uuid() == null ? "" : officer.uuid().toString(),
                seizure, bolos);
        officer.tell("Depistat la " + traveler.name() + ":");
        for (Finding f : scan.findings()) {
            String id = f.item() != null && f.item().name != null && !f.item().name.isBlank()
                    ? f.item().name : (f.item() == null ? "?" : f.item().itemId);
            officer.tell("§7- §f" + id + " — " + f.clue());
        }
        traveler.tell(who + " îți ridică marfa contrasemnată. Ești în evidențele Străjii.");
        return new NpcInspectOutcome(scan.findings(), seized, true, "officer");
    }

    /**
     * Shared consequence pipeline — targeted seizure to evidence, one
     * {@code forgery_detected} audit row per finding (detector + verdict +
     * offense recorded), then the system BOLO marker that hunts the carrier.
     * Returns stacks seized.
     */
    private int enforceFindings(PlayerGateway traveler,
                                ScanResult scan, String detector,
                                String actorUuid,
                                SeizureService seizure, BoloService bolos) {
        int seized = seizure == null ? 0
                : seizure.seizeFlagged(traveler, null, scan.findings().stream()
                        .map(Finding::item).toList());
        StringBuilder offenses = new StringBuilder();
        for (Finding f : scan.findings()) {
            offenses.append(f.offense()).append(' ');
            // The claimed serial/mark rides every row — a detection is
            // traceable to the exact series the forger presented (#248 AC5).
            String claim = f.item() == null ? ""
                    : f.item().data(ArtifactRegistryService.SERIAL_KEY).isBlank()
                            ? f.item().data(ArtifactRegistryService.MARK_KEY)
                            : f.item().data(ArtifactRegistryService.SERIAL_KEY);
            audit.record("forgery_detected", detector, actorUuid, traveler.name(),
                    traveler.uuid().toString(), "SUCCESS",
                    "inspector=" + detector + " offense=" + f.offense()
                            + " item=" + (f.item() == null ? "" : f.item().itemId)
                            + " verdict=" + f.verdict()
                            + (claim.isBlank() ? "" : " claim=" + claim));
        }
        if (bolos != null) {
            bolos.flagForgery(traveler.uuid().toString(), traveler.name(),
                    "falsificare depistată de " + detector + ": " + offenses.toString().trim());
        }
        return seized;
    }

    /** The player-officer parity ladder: comisar → EXPERT (rank authority,
     * not duty state), quiz-trained or sergent+ on duty → VETERAN, any other
     * on-duty guard → JUNIOR, civilians and off-duty ranks → NONE. */
    public Expertise officerExpertise(PlayerGateway officer) {
        if (officer == null) return Expertise.NONE;
        if (players.isCommissioner(officer)) return Expertise.EXPERT;
        if (!players.isOnDutyGuard(officer)) return Expertise.NONE;
        var state = players.state(officer);
        if (state != null && (state.quizPassed
                || state.rank >= com.dwurdy.straja.domain.model.Rank.SERGENT.level())) {
            return Expertise.VETERAN;
        }
        return Expertise.JUNIOR;
    }

    // ------------------------------------------------------------ internals

    private Finding machineVerdict(SnapshotItem item) {
        if (item == null || item.itemId == null || item.itemId.isBlank()) return null;
        // A sealed crate's Seal stamp IS its registration — transporter stock
        // never carries a serial mark. The exemption is bound to the crate
        // item id AND a complete seal so a stray SealBy tag on a forged
        // rifle can't launder it.
        if (ArtifactRegistryService.CRATE_ITEM.equals(item.itemId)
                && !item.data(ArtifactRegistryService.SEAL_BY).isBlank()
                && !item.data(ArtifactRegistryService.SEAL_ID).isBlank()) return null;
        String mark = item.data(ArtifactRegistryService.MARK_KEY);
        String serial = item.data(ArtifactRegistryService.SERIAL_KEY);
        boolean marked = !mark.isBlank() || !serial.isBlank();
        if (!marked) {
            // ">deep" rows surface from a structural NBT walk over foreign
            // storage — when the walk couldn't read ANY custom data the
            // scanner can't distinguish "unmarked" from "mark invisible",
            // so absence of evidence is not evidence. A row that did expose
            // data and still shows no mark is genuinely unmarked.
            if (item.slot != null && item.slot.endsWith(">deep")
                    && (item.data == null || item.data.isEmpty())) return null;
            // A regulated artifact with no mark is "unregistered stock" —
            // legal blanks never reach this list because they aren't in
            // artifactRegulatedItemIds.
            return policies.artifactRegulatedItemIds.contains(item.itemId)
                    ? new Finding(item, Verdict.UNREGISTERED,
                            "obiect reglementat fără marcă de serie")
                    : null;
        }
        MarkClass cls = forgery.classifyMarking(mark.isBlank() ? "#" + serial : mark);
        return switch (cls) {
            case ABSURD -> new Finding(item, Verdict.CRUDE,
                    "marcă grosolană — nici nu semnă cu o serie oficială");
            case MALFORMED -> new Finding(item, Verdict.FLAGGED,
                    "marca nu respectă tiparul oficial — format greșit");
            case PLAUSIBLE -> null;
        };
    }

    private Finding expertVerdict(SnapshotItem item, String presenterUuid,
                                  Expertise expertise) {
        // The claim the registry checks: the explicit serial, else a
        // well-formed mark's implied serial (#RC-42 -> RC-42) so a
        // mark-only item still gets cross-checked.
        String serial = item.data(ArtifactRegistryService.SERIAL_KEY);
        if (serial.isBlank()) {
            String mark = item.data(ArtifactRegistryService.MARK_KEY);
            if (mark.startsWith("#")) serial = mark.substring(1);
        }
        if (serial.isBlank()) {
            // A malformed mark carries no checkable claim — the machine
            // already spoke for it. Only well-formed claims reach this path.
            return null;
        }
        var check = forgery.checkClaim(serial, item.itemId, presenterUuid);
        switch (check) {
            case KNOWN_FORGED:
                return new Finding(item, Verdict.SUSPECT_CLAIM,
                        "această serie a mai fost reclamată de un fals cunoscut");
            case CONFLICT:
                if (expertise == Expertise.EXPERT) {
                    return new Finding(item, Verdict.SUSPECT_CLAIM,
                            "seria e reală — dar aparține altui obiect sau altui deținător");
                }
                return null;
            case RETIRED:
                if (expertise == Expertise.EXPERT) {
                    return new Finding(item, Verdict.SUSPECT_CLAIM,
                            "seria a fost retrasă din registru — marca e moartă");
                }
                return null;
            case ABSENT:
                long claim = claimedNumber(serial);
                long next = registry.nextSerialNumber();
                // The near-miss window starts AT the next unissued serial —
                // claiming the very number the office is about to allocate
                // is the strongest N2-shaped tell a veteran can read.
                if (claim > 0 && claim >= next) {
                    if (claim <= next + NEAR_MISS_BAND) {
                        if (expertise != Expertise.JUNIOR) {
                            return new Finding(item, Verdict.SUSPECT_CLAIM,
                                    "seria încă nu a fost emisă — stă prea aproape de alocare");
                        }
                        return null;
                    }
                    return new Finding(item, Verdict.SUSPECT_CLAIM,
                            "seria nu există în registrul artefactelor");
                }
                // Claim numbers below the watermark are retired/revoked
                // serials — only the expert reads the ledger that closely.
                if (expertise == Expertise.EXPERT) {
                    return new Finding(item, Verdict.SUSPECT_CLAIM,
                            "seria nu există în registrul artefactelor");
                }
                return null;
            default:
                return null; // AUTHENTIC — claim and record agree
        }
    }

    /** The numeric tail of a claimed serial ("RC-0042" -> 42); 0 when the
     * claim isn't shaped like a serial number at all. */
    private long claimedNumber(String serial) {
        String prefix = policies.artifactSerialPrefix;
        String tail = serial != null && prefix != null && serial.startsWith(prefix)
                ? serial.substring(prefix.length()) : serial;
        try {
            return Long.parseLong(tail == null ? "" : tail.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean near(PlayerGateway a, PlayerGateway b, double radius) {
        if (a == null || b == null) return false;
        if (a.dimension() != null && b.dimension() != null
                && !a.dimension().equals(b.dimension())) return false;
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private static Verdict worst(List<Finding> findings) {
        Verdict worst = Verdict.CLEAN;
        for (Finding f : findings) {
            if (f.verdict == Verdict.CRUDE) return Verdict.CRUDE;
            if (f.verdict == Verdict.FLAGGED
                    || f.verdict == Verdict.UNREGISTERED
                    || f.verdict == Verdict.SUSPECT_CLAIM) worst = Verdict.FLAGGED;
        }
        return worst;
    }

    private static List<SnapshotItem> safe(List<SnapshotItem> snapshot) {
        return snapshot == null ? List.of() : snapshot;
    }
}
