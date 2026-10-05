package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.ProtocolRepository;
import com.dwurdy.straja.domain.model.Cell;
import com.dwurdy.straja.domain.model.CheckpointMode;
import com.dwurdy.straja.domain.model.CrossingDirection;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.FineStore;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.LaborCampRecord;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.LawCheckpointRecord;
import com.dwurdy.straja.domain.model.LawCheckpointStore;
import com.dwurdy.straja.domain.model.ProtocolStore;
import com.dwurdy.straja.domain.model.PushbackPoint;
import com.dwurdy.straja.domain.model.Rank;
import com.dwurdy.straja.domain.model.StoragePoint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * #242 guided tester protocol. A written-book walkthrough of every tester-
 * facing surface: the tester enrolls with {@code /straja protocol start},
 * receives the dossier cover, and advances chapter by chapter with
 * {@code next}; each chapter hands a book whose steps name the action and
 * the expected result. Progress persists per-uuid across relogs.
 *
 * <p>The run is solo-completeable: scripted beats play the state (a fine
 * lands on the tester, a rank is granted, a "friend" is emulated through the
 * tester paying the suspect's debt directly), and a joined fake player —
 * {@link ActorSpawner} — plays the suspect for the custody and bounty
 * chapters. Everything the protocol grants is test-build material: coins
 * minted for the kit, a fake suspect, a rank grant — which is why the
 * surface needs operator rights (or {@code protocol.enabled} for non-op
 * testers). {@link #ensureFixtures} provisions a missing gate/cell/camp
 * around the tester, so a fresh world needs no manual setup.
 *
 * <p>Hooks run once per chapter per tester (the {@code hooksDone} bitmask):
 * re-reading a chapter via {@code back} + {@code next} never re-issues a
 * scripted fine or re-grants rank.
 */
public final class ProtocolService {

    /** Minecraft-side fake-suspect plumbing; unit tests substitute a fake. */
    public interface ActorSpawner {
        /** Joins a fake player near the anchor; returns its uuid or null. */
        UUID spawn(PlayerGateway anchor, String name);
        void dismiss(UUID actorUuid);
    }

    private static final int CHAPTER_COUNT = 9;
    /** Coins minted for the fine-payment chapter. */
    private static final int FINE_KIT = 64;
    /** Coins minted so the tester can contribute to the suspect's debt. */
    private static final int DEBT_KIT = 128;
    /** The scripted fine landing on the tester in chapter 3. */
    private static final int TESTER_FINE = 32;
    /** The scripted fine landing on the suspect in chapter 6. */
    private static final int SUSPECT_FINE = 96;
    /** Chapter 7 suggestion for the bounty the tester posts. */
    private static final int BOUNTY_SUGGESTED = 128;

    private final StrajaContext ctx;
    private final ProtocolRepository repo;
    private final PlayerService players;
    private final AuditService audit;
    private PrisonService prison;
    private ActorSpawner spawner;

    public ProtocolService(StrajaContext ctx, ProtocolRepository repo,
                           PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.repo = repo;
        this.players = players;
        this.audit = audit;
    }

    public void usePrison(PrisonService prison) { this.prison = prison; }
    public void useSpawner(ActorSpawner spawner) { this.spawner = spawner; }

    private long now() { return ctx.clock().nowMillis(); }

    private static String uuid(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }

    private ProtocolStore store() {
        ProtocolStore store = repo.read();
        if (store.testers == null) store.testers = new java.util.LinkedHashMap<>();
        return store;
    }

    // ------------------------------------------------------------ commands

    /** Enrolls the tester and issues the dossier cover. */
    public void start(PlayerGateway tester) {
        if (!enabled(tester)) return;
        ProtocolStore store = store();
        ProtocolStore.Entry entry = store.find(uuid(tester));
        if (entry != null && !entry.finished) {
            tell(tester, "straja.protocol.already_running",
                    entry.chapter + 1, CHAPTER_COUNT + 1);
            issueCurrent(tester, entry.chapter);
            return;
        }
        ensureFixtures(tester);
        entry = store.enroll(uuid(tester), tester.name(), now());
        repo.write(store);
        audit.record("protocol_start", tester.name(), uuid(tester), "", "", "OK",
                "dossier issued");
        issueCover(tester);
        tell(tester, "straja.protocol.started");
    }

    private void issueCurrent(PlayerGateway tester, int chapterIndex) {
        if (chapterIndex == 0) issueCover(tester);
        else issueChapterBook(tester, chapterIndex);
    }

    /** Chat status card: progress bar + current chapter objective. */
    public void status(PlayerGateway tester) {
        if (!enabled(tester)) return;
        ProtocolStore.Entry entry = store().find(uuid(tester));
        if (entry == null) { tell(tester, "straja.protocol.not_enrolled"); return; }
        if (entry.finished) { tell(tester, "straja.protocol.already_finished"); return; }
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i <= CHAPTER_COUNT; i++) bar.append(i < entry.chapter ? '█' : '░');
        tester.tell("§6§lPROTOCOL STRAJA §r§7— Capitolul " + entry.chapter + "/" + CHAPTER_COUNT
                + " §8[" + bar + "]");
        Chapter chapter = CHAPTERS.get(entry.chapter);
        tester.tell("§f" + chapter.title + "§7 — " + chapter.objective);
        tester.tell("§7Avansezi cu §f/straja protocol next§7, relui capitolul cu §f/straja protocol back§7.");
    }

    /** Completes the current chapter, issues the next chapter's book. */
    public void next(PlayerGateway tester) {
        if (!enabled(tester)) return;
        ProtocolStore store = store();
        ProtocolStore.Entry entry = store.find(uuid(tester));
        if (entry == null) { tell(tester, "straja.protocol.not_enrolled"); return; }
        if (entry.finished) { tell(tester, "straja.protocol.already_finished"); return; }
        if (entry.chapter > CHAPTER_COUNT) {
            finish(tester, entry, store);
            return;
        }
        entry.chapter++;
        entry.updatedAt = now();
        if (entry.chapter > CHAPTER_COUNT) {
            finish(tester, entry, store);
            return;
        }
        repo.write(store);
        runHook(tester, store, entry);
        issueChapterBook(tester, entry.chapter);
    }

    /** Re-issues the previous chapter's book; hooks never re-fire. */
    public void back(PlayerGateway tester) {
        if (!enabled(tester)) return;
        ProtocolStore store = store();
        ProtocolStore.Entry entry = store.find(uuid(tester));
        if (entry == null) { tell(tester, "straja.protocol.not_enrolled"); return; }
        if (entry.chapter <= 0) { tell(tester, "straja.protocol.at_cover"); return; }
        entry.chapter--;
        entry.updatedAt = now();
        repo.write(store);
        issueCurrent(tester, entry.chapter);
    }

    /** Leaves the protocol: dismisses the suspect and purges its records. */
    public void stop(PlayerGateway tester) {
        if (!enabled(tester)) return;
        ProtocolStore store = store();
        ProtocolStore.Entry entry = store.find(uuid(tester));
        if (entry == null) { tell(tester, "straja.protocol.not_enrolled"); return; }
        teardown(tester, entry);
        store.testers.remove(uuid(tester));
        repo.write(store);
        audit.record("protocol_stop", tester.name(), uuid(tester), "", "", "OK",
                "stopped at chapter " + entry.chapter);
        tell(tester, "straja.protocol.stopped");
    }

    /** Back to the cover without losing the enrollment slot. */
    public void reset(PlayerGateway tester) {
        if (!enabled(tester)) return;
        ProtocolStore store = store();
        ProtocolStore.Entry entry = store.find(uuid(tester));
        if (entry == null) { tell(tester, "straja.protocol.not_enrolled"); return; }
        teardown(tester, entry);
        entry.chapter = 0;
        entry.finished = false;
        entry.hooksDone = 0;
        entry.updatedAt = now();
        repo.write(store);
        issueCover(tester);
        tell(tester, "straja.protocol.reset");
    }

    /** Manual suspect summon/dismiss (re-spawns a dead or wandered actor). */
    public void actor(PlayerGateway tester) {
        if (!enabled(tester)) return;
        ProtocolStore store = store();
        ProtocolStore.Entry entry = store.find(uuid(tester));
        if (entry == null) { tell(tester, "straja.protocol.not_enrolled"); return; }
        if (!entry.actorUuid.isEmpty()) {
            dismissActor(entry.actorUuid);
            entry.actorUuid = "";
            entry.updatedAt = now();
            repo.write(store);
            tell(tester, "straja.protocol.actor_dismissed");
            return;
        }
        spawnActor(tester, store, entry);
    }

    /** GameTest hygiene: forget tester rows matching a stale-fixture name. */
    public void purgeTesters(java.util.function.Predicate<String> staleName) {
        ProtocolStore store = store();
        store.testers.values().removeIf(e -> staleName.test(e.playerName));
        repo.write(store);
    }

    // ------------------------------------------------------------ chapters

    private record Chapter(String title, String objective, Hook hook, String[] pages) {}
    private enum Hook { NONE, FINE_TESTER, GRANT_RANK, SPAWN_ACTOR, FINE_ACTOR, PREP_BOUNTY }

    private static final List<Chapter> CHAPTERS = List.of(
            new Chapter("Coperta dosarului", "Citește regulile protocolului.", Hook.NONE,
                    new String[]{}),
            new Chapter("Orientarea", "Descoperă meniul și suprafețele de bază.", Hook.NONE,
                    new String[]{
                            "1. Rulează §l/straja§r — se deschide meniul de acțiuni.\n\n→ Vezi butoanele pentru fiecare suprafață disponibilă jucătorului.",
                            "2. Rulează §l/straja status§r.\n\n→ Un card de status: nume, rang (civil), eventuale marcaje.\n\n3. Rulează §l/straja rules§r.\n\n→ Regulamentul se afișează.",
                            "Dacă un pas nu produce rezultatul așteptat, notează comanda + ce s-a întâmplat.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Punctul de trecere", "Testează filtrul de la checkpoint.", Hook.NONE,
                    new String[]{
                            "1. Du-te la {gate} și treci cu inventarul curat.\n\n→ Trecerea e acceptată (PASS); o înregistrare nouă apare în registru.",
                            "2. Pune în inventar un obiect interzis (armă, obiect confiscabil) și treci din nou.\n\n→ Ești respins (DENY); obiectul e confiscat sau returnat la nava de depozitare.",
                            "3. Verifică registrul inspecțiilor.\n\n→ Cele două treceri apar ca PASS și DENY, cu snapshot de inventar.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Amenda", "Primești și achiți o amendă.", Hook.FINE_TESTER,
                    new String[]{
                            "Protocolul tocmai ți-a emis o amendă de " + TESTER_FINE + " monede și ți-a dat un plic de monede pentru plată.",
                            "1. Verifică notificarea și fine-ul: §l/straja debt§r sau la Recepționistă.\n\n→ Amenda apare ca neachitată, cu suma și motivul.",
                            "2. Achită: §l/straja debt pay <numele_tău> " + TESTER_FINE + "§r\n\n→ Monedele pleacă din inventar; amenda devine ACHITATĂ și primești chitanță.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Cariera", "Devii ofițer și intri în tură.", Hook.GRANT_RANK,
                    new String[]{
                            "Protocolul ți-a acordat gradul de Inspector și te-a pus în serviciu (în producție: cerere la Recepționistă + examen).",
                            "1. Rulează §l/straja status§r.\n\n→ Rangul nou și tura activă apar pe card.\n\n2. Rulează §l/straja stop§r.\n\n→ Tura se încheie, cu mesaj de confirmare.",
                            "Capitolul următor te repune automat în tură — ofițerii acționează în serviciu.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Arestul", "Imobilizează și predă suspectul.", Hook.SPAWN_ACTOR,
                    new String[]{
                            "Un suspect fals — «{actor}» — a apărut lângă tine, și ai primit sculele de reținere: funie, cătușe, sac de cap. (Ai pierdut suspectul? /straja protocol actor îl cheamă din nou.)",
                            "1. Leagă {actor} cu funia (click dreapta pe el).\n\n→ Suspectul e legat; funia mușcă doar pe ținte doborâte sau predate — capitolul următor o demonstrează.",
                            "2. Trage-l prin {intake}.\n\n→ La poartă, {actor} e arestat automat: dosar de reținere, inventar confiscat în lăzi, celulă.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Datoria", "Urmărește levierul și plata datoriei.", Hook.FINE_ACTOR,
                    new String[]{
                            "Suspectul a primit o amendă de " + SUSPECT_FINE + " și are monede în inventar — la arestare, statul i le sechestrează pentru datorie.",
                            "1. Predă pe {actor} din nou la {intake} dacă a scăpat.\n\n→ Levierul golește monedele lui mai întâi către amendă; primește o carte-chitanță.",
                            "2. Interoghează: §l/straja debt {actor}§r — vezi soldul și contribuțiile.\n\n3. Contribuie ca „un prieten”: §l/straja debt pay {actor} 50§r (ai primit monede).\n\n→ Plata ta apare în ledger; {actor} e înștiințat printr-o carte.",
                            "4. Eliberează-l din celulă.\n\n→ Dacă datoria trece de prag, eliberarea e blocată — transfer la lagăr sau refuz, cu „Refuz de eliberare” primit.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Vânătoarea", "Pune o recompensă și livrează fugarul.", Hook.PREP_BOUNTY,
                    new String[]{
                            "Suspectul a fost eliberat și marcat predat — e pregătit pentru vânătoare.",
                            "1. Postează: §l/straja bounty post {actor} " + BOUNTY_SUGGESTED + " \"test protocol\"§r\n\n→ Recompensa e activă și apare un BOLO legat (wanted-on-sight).",
                            "2. Leagă pe {actor} cu funia — e „predat” pentru 2 minute.\n\n3. Trage-l prin {intake}.\n\n→ Arestul la vedere îl predă; statul te plătește recompensa (mesaj de încasare).",
                            "4. {actor} datorează cauțiune 2× — oricine o plătește cu §l/straja bail {actor}§r. Neachitată 24h → lagăr de muncă.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Comisia", "Parcurge suprafețele de administrare.", Hook.NONE,
                    new String[]{
                            "Aceste comenzi cer drepturi de administrator — sare peste ce nu poți rula și notează.",
                            "1. §l/straja audit§r — jurnalul acțiunilor: arestul, levierul, plățile apar auditat.\n\n2. §l/straja backup§r — snapshot al datelor Straja.",
                            "3. Revizuiește config-ul: config/straja-server.toml — secțiunile [bounty], [debt], [protocol].\n\n→ Refuzurile admin au și ele remedii afișate.\n\n☑ Gata? §l/straja protocol next"}),
            new Chapter("Raportul", "Sigilează dosarul de testare.", Hook.NONE,
                    new String[]{
                            "Ai parcurs: orientare, checkpoint, amendă, carieră, arest, datorie, vânătoare, administrație.",
                            "Pentru raport, notează pentru fiecare capitol:\n• ce a funcționat\n• ce NU a produs rezultatul așteptat\n• comanda + mesajul exact\n\nTrimite dosarul de observații echipei Straja.\n\n☑ Sigilează: §l/straja protocol next§r — protocolul se încheie."})
    );

    // ------------------------------------------------------------ hooks

    /** Runs the chapter-entry scripted beat once (guarded by hooksDone). */
    private void runHook(PlayerGateway tester, ProtocolStore store,
                         ProtocolStore.Entry entry) {
        Chapter chapter = CHAPTERS.get(entry.chapter);
        int bit = 1 << entry.chapter;
        if ((entry.hooksDone & bit) != 0) return;
        entry.hooksDone |= bit;
        switch (chapter.hook) {
            case FINE_TESTER -> {
                issueProtocolFine(tester, uuid(tester), tester.name(), TESTER_FINE);
                ctx.currency().deposit(tester, FINE_KIT, "protocol_kit_fine");
            }
            case GRANT_RANK -> {
                var state = players.state(tester);
                state.rank = Rank.INSPECTOR.level();
                state.invited = true;
                state.duty = true;
                state.fired = false;
                state.suspended = false;
                state.resigned = false;
                players.save(tester.uuid(), state);
            }
            case SPAWN_ACTOR -> {
                // Restraint kit — the chapter's tools, no admin command needed.
                tester.give(ItemSpec.of("straja:rope", 1));
                tester.give(ItemSpec.of("straja:cuffs", 1));
                tester.give(ItemSpec.of("straja:head_sack", 1));
                spawnActor(tester, store, entry);
            }
            case FINE_ACTOR -> {
                if (!entry.actorUuid.isEmpty()) {
                    PlayerGateway actor = ctx.server().findPlayer(entry.actorUuid);
                    issueProtocolFine(tester, entry.actorUuid,
                            actor == null ? ctx.policies().protocolActorName : actor.name(),
                            SUSPECT_FINE);
                    ctx.currency().deposit(tester, DEBT_KIT, "protocol_kit_debt");
                }
            }
            case PREP_BOUNTY -> prepareBountyChapter(tester, entry);
            case NONE -> {}
        }
        entry.updatedAt = now();
        repo.write(store);
    }

    /** Ch7 setup: suspect must be free and surrender-flagged to be ropable. */
    private void prepareBountyChapter(PlayerGateway tester, ProtocolStore.Entry entry) {
        if (entry.actorUuid.isEmpty()) return;
        // Fresh surrender flag: the scripted "give up" the chapter book narrates.
        var bounties = ctx.bounties().read();
        bounties.surrenders.put(entry.actorUuid,
                now() + (long) ctx.policies().bountySurrenderSeconds * 1000L);
        ctx.bounties().write(bounties);
        // The suspect must be out of a cell to be capturable; keep the
        // release permissive — the tester-facing book covers the case where
        // it still sits in custody (admin release or fresh arrest exercise).
        if (prison != null) {
            PlayerGateway actor = ctx.server().findPlayer(entry.actorUuid);
            if (actor != null) prison.release(tester, actor, "protocol");
        }
    }

    private void issueProtocolFine(PlayerGateway issuer, String targetUuid,
                                   String targetName, int amount) {
        FineStore data = ctx.fines().read();
        Fine fine = new Fine();
        fine.id = data.nextFineId();
        fine.target = targetName;
        fine.targetUuid = targetUuid;
        fine.issuer = "Comisariat (protocol)";
        fine.issuerUuid = uuid(issuer);
        fine.law = "Protocol de testare";
        fine.description = "Amendă emisă de protocolul de testare.";
        fine.amount = amount;
        fine.issuedAt = now();
        data.fines.add(fine);
        ctx.fines().write(data);
    }

    // ------------------------------------------------------------ actor

    private void spawnActor(PlayerGateway tester, ProtocolStore store,
                            ProtocolStore.Entry entry) {
        if (spawner == null) { tell(tester, "straja.protocol.actor_unavailable"); return; }
        UUID spawned = spawner.spawn(tester, ctx.policies().protocolActorName);
        if (spawned != null) {
            entry.actorUuid = spawned.toString();
            repo.write(store);
            tell(tester, "straja.protocol.actor_spawned", ctx.policies().protocolActorName);
        } else {
            tell(tester, "straja.protocol.actor_failed");
        }
    }

    private void dismissActor(String actorUuid) {
        if (spawner == null || actorUuid.isEmpty()) return;
        try { spawner.dismiss(UUID.fromString(actorUuid)); }
        catch (IllegalArgumentException badUuid) { /* corrupt entry — drop it anyway */ }
    }

    /** Dismisses the suspect and strips its leftover records from the stores. */
    private void teardown(PlayerGateway tester, ProtocolStore.Entry entry) {
        if (entry.actorUuid.isEmpty()) return;
        String actorUuid = entry.actorUuid;
        dismissActor(actorUuid);
        entry.actorUuid = "";
        var bounties = ctx.bounties().read();
        bounties.records.removeIf(r -> r != null && actorUuid.equals(r.targetUuid));
        bounties.surrenders.remove(actorUuid);
        ctx.bounties().write(bounties);
        var fines = ctx.fines().read();
        fines.fines.removeIf(f -> f != null && actorUuid.equals(f.targetUuid));
        ctx.fines().write(fines);
        var prisonData = ctx.prison().read();
        prisonData.sentences.removeIf(s -> s != null && actorUuid.equals(s.targetUuid));
        prisonData.waitlist.removeIf(w -> w != null && actorUuid.equals(w.targetUuid));
        prisonData.assignments.values().removeIf(a -> a != null && actorUuid.equals(a.targetUuid));
        ctx.prison().write(prisonData);
        var register = ctx.prisonerRegister().read();
        register.prisoners().values().removeIf(r -> actorUuid.equals(r.detaineeUuid));
        ctx.prisonerRegister().write(register);
    }

    // ------------------------------------------------------------ fixtures

    private static final String SITE_GATE = "protocol_gate";
    private static final String SITE_INTAKE = "protocol_intake";
    private static final String CELL_ID = "protocol_cell";
    private static final String CAMP_ID = "protocol_camp";

    /**
     * Zero-chore provisioning, run once at enrollment: whatever LAW fixture
     * the tester's dimension lacks, the protocol lays out around the tester —
     * a DENY walk-through gate (chapter 2's border crossing), an ARREST
     * intake lane (suspect delivery), a cell, and a labor camp. Pure store
     * records — the crossing pipeline is position-scanned, so no physical
     * structure is needed. A world that already has a working fixture of a
     * kind keeps it; nothing protocol_* is added.
     */
    private void ensureFixtures(PlayerGateway tester) {
        String dim = tester.dimension();
        int ax = (int) Math.floor(tester.x());
        int ay = (int) Math.floor(tester.y());
        int az = (int) Math.floor(tester.z());
        List<String> built = new ArrayList<>();

        LawCheckpointStore sites = ctx.lawCheckpoints().read();
        boolean sitesDirty = false;
        if (sites.checkpoint(SITE_GATE) == null && findSite(sites, dim, false) == null) {
            sites.put(siteRecord(SITE_GATE, "Poarta de test", dim,
                    CheckpointMode.DENY, ax, ay, az, 0));
            sitesDirty = true;
            built.add(SITE_GATE + " §7(poartă civilă, ~4m est)");
        }
        if (sites.checkpoint(SITE_INTAKE) == null && findSite(sites, dim, true) == null) {
            sites.put(siteRecord(SITE_INTAKE, "Poarta de arest", dim,
                    CheckpointMode.ARREST, ax, ay, az, 7));
            sitesDirty = true;
            built.add(SITE_INTAKE + " §7(preluare suspecți, ~4m est, banda nord)");
        }
        if (sitesDirty) ctx.lawCheckpoints().write(sites);

        var prisonData = ctx.prison().read();
        boolean hasCell = prisonData.cells.stream()
                .anyMatch(c -> c != null && dim.equals(c.dimension));
        if (!hasCell && prisonData.cell(CELL_ID) == null) {
            var cell = new Cell();
            cell.id = CELL_ID;
            cell.dimension = dim;
            cell.minX = ax - 9; cell.maxX = ax - 7;
            cell.minY = ay - 1; cell.maxY = ay + 2;
            cell.minZ = az - 3; cell.maxZ = az - 1;
            cell.doorX = ax - 8; cell.doorY = ay; cell.doorZ = az - 2;
            cell.createdAt = now();
            prisonData.cells.add(cell);
            ctx.prison().write(prisonData);
            built.add(CELL_ID + " §7(celulă, ~8m vest)");
        }

        var camps = ctx.laborCamps().read();
        boolean hasCamp = camps.camps().values().stream()
                .anyMatch(c -> c != null && dim.equals(c.dimension));
        if (!hasCamp && camps.camp(CAMP_ID) == null) {
            var camp = new LaborCampRecord();
            camp.id = CAMP_ID;
            camp.name = "Lagăr de test";
            camp.dimension = dim;
            camp.boundary = LawBounds.of(dim, ax - 5, ay - 1, az - 18,
                    ax + 5, ay + 5, az - 12);
            camp.intakeSpawn = new StoragePoint(dim, ax, ay, az - 14);
            camp.releaseSpawn = new StoragePoint(dim, ax + 3, ay, az - 15);
            camp.dormitorySpawn = new StoragePoint(dim, ax - 3, ay, az - 15);
            camps.put(camp);
            ctx.laborCamps().write(camps);
            built.add(CAMP_ID + " §7(lagăr, ~14m sud)");
        }

        if (!built.isEmpty()) {
            tell(tester, "straja.protocol.fixtures", String.join("§7, ", built));
            audit.record("protocol_fixtures", tester.name(), uuid(tester), "", "",
                    "OK", String.join("; ", built));
        }
    }

    /** Two adjacent inspection slabs 4 blocks east of the anchor + a pushback. */
    private static LawCheckpointRecord siteRecord(String id, String name, String dim,
                                                  CheckpointMode mode,
                                                  int ax, int ay, int az, int dz) {
        var site = new LawCheckpointRecord();
        site.id = id;
        site.name = name;
        site.dimension = dim;
        site.mode = mode;
        site.direction = CrossingDirection.BIDIRECTIONAL;
        site.stage1 = LawBounds.of(dim, ax + 3, ay - 1, az + dz - 2,
                ax + 6, ay + 3, az + dz + 2);
        site.stage2 = LawBounds.of(dim, ax + 7, ay - 1, az + dz - 2,
                ax + 9, ay + 3, az + dz + 2);
        site.pushback = PushbackPoint.at(dim, ax, ay, az + dz, -90f);
        site.normalize();
        return site;
    }

    /** First usable site of the requested mode in this dimension, else null. */
    private static LawCheckpointRecord findSite(LawCheckpointStore store,
                                                String dim, boolean arrest) {
        for (var site : store.checkpoints().values()) {
            if (site == null || !dim.equals(site.dimension)) continue;
            if (site.stage1 == null && site.stage2 == null) continue;
            if ((site.mode == CheckpointMode.ARREST) == arrest) return site;
        }
        return null;
    }

    /** Book placeholder: the usable site of the requested mode, with coords. */
    private String siteDesc(PlayerGateway tester, boolean arrest) {
        LawCheckpointStore sites = ctx.lawCheckpoints().read();
        var site = sites.checkpoint(arrest ? SITE_INTAKE : SITE_GATE);
        if (site == null) site = findSite(sites, tester.dimension(), arrest);
        if (site == null || site.stage1 == null) return "checkpoint-ul Straja";
        var b = site.stage1;
        return "§f" + site.name + "§7 (x " + b.minX() + "…" + b.maxX()
                + ", z " + b.minZ() + "…" + b.maxZ() + ")";
    }

    // ------------------------------------------------------------ books

    private void issueCover(PlayerGateway tester) {
        List<String> pages = new ArrayList<>();
        pages.add("PROTOCOL STRAJA\n══════════════\n\nDOSAR DE TESTARE\n\nAcest dosar te ghidează prin fiecare suprafață a modului — un capitol, o carte, un rezultat așteptat la fiecare pas.");
        pages.add("CUM FUNCȚIONEAZĂ\n\n• /straja protocol — starea curentă\n• /straja protocol next — capitolul următor\n• /straja protocol back — recitește capitolul\n• /straja protocol actor — cheamă suspectul\n• /straja protocol stop — abandonează\n• /straja protocol reset — reia de la zero");
        pages.add("PREGĂTIRE\n\nLa start, protocolul construiește singur ce lipsește din dimensiunea ta: poarta civilă, poarta de arest, celula și lagărul — la câțiva metri de tine.\n\nCerință unică: OP (cheats pornit în singleplayer) sau protocol.enabled pe server.\n\nProgresele se păstrează la relog.\n\nSemnat,\nComisariatul Straja");
        tester.giveWrittenBook("Dosar de testare — Straja", "Comisariatul Straja", pages);
    }

    private void issueChapterBook(PlayerGateway tester, int chapterIndex) {
        Chapter chapter = CHAPTERS.get(chapterIndex);
        List<String> pages = new ArrayList<>();
        pages.add("PROTOCOL STRAJA\nCapitolul " + chapterIndex + "/" + CHAPTER_COUNT
                + "\n══════════════\n\n" + chapter.title + "\n\nScop: " + chapter.objective);
        String actorName = ctx.policies().protocolActorName;
        for (String page : chapter.pages) {
            String resolved = page.replace("{actor}", actorName);
            if (resolved.contains("{gate}"))
                resolved = resolved.replace("{gate}", siteDesc(tester, false));
            if (resolved.contains("{intake}"))
                resolved = resolved.replace("{intake}", siteDesc(tester, true));
            pages.add(resolved);
        }
        tester.giveWrittenBook("Protocol — " + chapter.title, "Comisariatul Straja", pages);
    }

    private void finish(PlayerGateway tester, ProtocolStore.Entry entry,
                        ProtocolStore store) {
        entry.finished = true;
        entry.updatedAt = now();
        repo.write(store);
        teardown(tester, entry);
        audit.record("protocol_finish", tester.name(), uuid(tester), "", "", "OK",
                "completed in " + (now() - entry.startedAt) + "ms");
        List<String> pages = new ArrayList<>();
        pages.add("PROTOCOL STRAJA\n══════════════\n\nDOSAR ÎNCHIS\n\nToate cele 9 capitole au fost parcurse. Sigiliul Comisariatului confirmă parcurgerea completă.");
        pages.add("Trimite raportul cu:\n• capitolele care au dat rezultatele așteptate\n• abaterile observate (comandă + mesaj exact)\n• orice carte sau notificare lipsă\n\nMulțumim pentru testare.\n\nComisariatul Straja");
        tester.giveWrittenBook("Dosar închis — Straja", "Comisariatul Straja", pages);
        tell(tester, "straja.protocol.finished");
    }

    // ------------------------------------------------------------ helpers

    /**
     * Test surface, not a cheat: operators (or the singleplayer host with
     * cheats on — {@code isOp} covers both) and commissioners may always run
     * it. The {@code protocol.enabled} flag additionally opens it to non-op
     * testers on a dedicated server without hand-editing ops.json.
     */
    private boolean enabled(PlayerGateway tester) {
        if (ctx.policies().protocolEnabled || tester.isOp()
                || players.isCommissioner(tester)) return true;
        tell(tester, "straja.protocol.disabled");
        return false;
    }

    private void tell(PlayerGateway p, String key, Object... args) {
        p.tellKey(key, args);
    }
}
