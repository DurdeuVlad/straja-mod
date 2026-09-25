package com.dwurdy.straja.adapter.in.npc;

import com.dwurdy.straja.domain.model.GuardState;
import com.dwurdy.straja.domain.model.Rank;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Context-aware FAQ tree shared by the four player-facing Straja NPCs.
 *
 * The tree is deliberately read-only: it explains the current path and never
 * grants authority. Mutating actions remain on the normal role surfaces.
 */
final class NpcFaqSurface {
    private NpcFaqSurface() {}

    record Context(boolean commissioner, int rank, String rankName, String applicationState,
                   boolean invited, boolean duty, boolean suspended, boolean resigned,
                   boolean fired, Map<String, String> stationMessages) {
        static Context from(GuardState state, boolean commissioner, String rankName) {
            return from(state, commissioner, rankName, Map.of(
                    "{station}", "sediul Străjii", "{fallbackStation}", "stația de rezervă",
                    "{receptionist}", "Recepția", "{secretariat}", "Secretariatul",
                    "{armorer}", "Armuriera", "{trainer}", "Instructorul",
                    "{commissionerOffice}", "biroul Comisarului", "{prison}", "închisoarea"));
        }

        static Context from(GuardState state, boolean commissioner, String rankName,
                            Map<String, String> stationMessages) {
            GuardState safe = state == null ? new GuardState() : state;
            return new Context(commissioner, safe.rank,
                    rankName == null || rankName.isBlank() ? Rank.of(safe.rank).displayName() : rankName,
                    safe.applicationState == null ? "NONE" : safe.applicationState,
                    safe.invited, safe.duty, safe.suspended, safe.resigned, safe.fired,
                    stationMessages == null ? Map.of() : Map.copyOf(stationMessages));
        }

        String stationMessage(String key, String fallback) {
            String template = stationMessages.getOrDefault(key, fallback);
            if (template == null) return "";
            String rendered = template;
            for (Map.Entry<String, String> entry : stationMessages.entrySet()) {
                if (entry.getKey().startsWith("{")) rendered = rendered.replace(entry.getKey(), entry.getValue());
            }
            return rendered;
        }

        boolean candidate() {
            return !fired && !suspended && !resigned
                    && (invited || "APPLIED".equals(applicationState));
        }

        boolean memberRecord() {
            return rank >= Rank.STAGIAR.level() || invited || !"NONE".equals(applicationState)
                    || suspended || resigned || fired;
        }

        boolean activeMember() {
            return rank >= Rank.STAGIAR.level() && !suspended && !resigned && !fired;
        }

        String status() {
            if (commissioner) return "Comisar";
            if (fired) return "concediat";
            if (resigned) return "demisionat / în cooldown";
            if (suspended) return "suspendat";
            if (duty) return rankName + ", în serviciu";
            if (rank >= Rank.STAGIAR.level()) return rankName + ", în afara serviciului";
            if (invited) return "invitat la recrutare";
            if ("APPLIED".equals(applicationState)) return "candidat cu cerere depusă";
            return "Civil";
        }
    }

    record Target(String kind, String topic, String question, NpcPlayerSurface.RoleRoute origin) {}

    static NpcPlayerSurface.ChatAction entry(NpcPlayerSurface.RoleRoute origin) {
        return action("Am o întrebare", "root_" + roleKey(origin));
    }

    static Optional<Target> parseTarget(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String[] parts = id.split("_");
        if (parts.length < 2) return Optional.empty();
        NpcPlayerSurface.RoleRoute origin;
        String kind = parts[0];
        String topic = "";
        String question = "";
        String originKey;
        if ("root".equals(kind) && parts.length == 2) {
            originKey = parts[1];
        } else if ("menu".equals(kind) && parts.length == 3) {
            topic = parts[1];
            originKey = parts[2];
        } else if ("answer".equals(kind) && parts.length == 4) {
            topic = parts[1];
            question = parts[2];
            originKey = parts[3];
        } else {
            return Optional.empty();
        }
        origin = roleForKey(originKey);
        if (origin == NpcPlayerSurface.RoleRoute.UNKNOWN) return Optional.empty();
        return Optional.of(new Target(kind, topic, question, origin));
    }

    static NpcPlayerSurface.RoleSurface root(NpcPlayerSurface.RoleRoute origin, Context context) {
        List<NpcPlayerSurface.ChatAction> actions = new ArrayList<>();
        addMenu(actions, "Despre Strajă", "about", origin);
        addMenu(actions, "Înscriere și examen", "entry", origin);
        addMenu(actions, "Gradul și drepturile mele", "rank", origin);
        addMenu(actions, "Reguli, plângeri și amenzi", "rules", origin);
        addMenu(actions, "Unde găsesc fiecare serviciu?", "locations", origin);
        if (context.memberRecord()) addMenu(actions, "Statutul meu", "status", origin);
        if (context.activeMember()) {
            addMenu(actions, "Serviciu și patrule", "duty", origin);
            addMenu(actions, "Misiuni, rapoarte și audiențe", "missions", origin);
            addMenu(actions, "Monede și echipament", "economy", origin);
        }
        if (context.commissioner()) addMenu(actions, "Întrebări pentru Comisar", "admin", origin);
        addMenu(actions, "Ce pot face aici?", "here", origin);
        return new NpcPlayerSurface.RoleSurface(origin, "Întrebări frecvente",
                "Alege o întrebare. Răspunsurile respectă statutul și gradul tău: "
                        + context.status() + ".", List.copyOf(actions));
    }

    static NpcPlayerSurface.RoleSurface menu(NpcPlayerSurface.RoleRoute origin,
                                             Context context, String topic) {
        if (!topicAvailable(topic, context)) return root(origin, context);
        List<NpcPlayerSurface.ChatAction> actions = new ArrayList<>();
        switch (topic == null ? "" : topic) {
            case "about" -> {
                answer(actions, topic, "what", "Ce este Straja?", origin);
                answer(actions, topic, "ranks", "Ce grade există?", origin);
                answer(actions, topic, "faction", "Pot rămâne în facțiunea mea?", origin);
            }
            case "entry" -> {
                answer(actions, topic, "join", "Cum intru în Strajă?", origin);
                answer(actions, topic, "application", "Ce se întâmplă cu cererea mea?", origin);
                answer(actions, topic, "exam", "Cum funcționează examenul?", origin);
                answer(actions, topic, "cooldown", "Ce se întâmplă dacă pic examenul?", origin);
                answer(actions, topic, "afterpass", "Ce primesc după promovare?", origin);
            }
            case "rank" -> {
                answer(actions, topic, "current", "Care este statutul și gradul meu?", origin);
                answer(actions, topic, "powers", "Ce pot face la gradul meu?", origin);
                answer(actions, topic, "promotion", "Cum promovez?", origin);
            }
            case "duty" -> {
                answer(actions, topic, "start", "Cum încep serviciul?", origin);
                answer(actions, topic, "checkpoint", "Cum funcționează checkpoint-urile?", origin);
                answer(actions, topic, "stop", "Cum închei serviciul?", origin);
                answer(actions, topic, "report", "De ce trebuie să depun raport?", origin);
            }
            case "missions" -> {
                answer(actions, topic, "missions", "Cum funcționează misiunile?", origin);
                answer(actions, topic, "reports", "Cum depun raportul de activitate?", origin);
                answer(actions, topic, "audience", "Cum cer audiență la Comisar?", origin);
            }
            case "economy" -> {
                answer(actions, topic, "salary", "Cum se calculează salariul?", origin);
                answer(actions, topic, "coins", "Unde primesc monedele?", origin);
                answer(actions, topic, "equipment", "Cum primesc și schimb echipamentul?", origin);
                answer(actions, topic, "armory", "De ce nu pot cumpăra un obiect?", origin);
            }
            case "rules" -> {
                answer(actions, topic, "complaint", "Cum depun o plângere?", origin);
                answer(actions, topic, "fine", "Cum plătesc sau contest o amendă?", origin);
                answer(actions, topic, "discipline", "Ce se întâmplă dacă încalc regulile?", origin);
            }
            case "locations" -> {
                answer(actions, topic, "ground", "Unde sunt Recepția și Instructorul?", origin);
                answer(actions, topic, "first", "Unde este Secretariatul și biroul Comisarului?", origin);
                answer(actions, topic, "second", "Unde sunt camerele și Armuriera?", origin);
                answer(actions, topic, "basement", "Unde este închisoarea?", origin);
            }
            case "status" -> {
                answer(actions, topic, "current", "Ce statut am acum?", origin);
                answer(actions, topic, "blocked", "De ce nu pot începe serviciul?", origin);
                answer(actions, topic, "return", "Cum revin după demisie sau suspendare?", origin);
            }
            case "admin" -> {
                answer(actions, topic, "personnel", "Ce poate face Comisarul?", origin);
                answer(actions, topic, "emergency", "Cum funcționează starea de urgență?", origin);
                answer(actions, topic, "audit", "Ce operațiuni sunt auditate?", origin);
            }
            case "here" -> answer(actions, topic, "role", "Ce face acest NPC?", origin);
            default -> { return root(origin, context); }
        }
        actions.add(action("Înapoi la întrebări", "root_" + roleKey(origin)));
        return new NpcPlayerSurface.RoleSurface(origin, title(topic),
                "Alege întrebarea care te interesează.", List.copyOf(actions));
    }

    static boolean topicAvailable(String topic, Context context) {
        if (context == null) return false;
        return switch (topic == null ? "" : topic) {
            case "about", "entry", "rank", "rules", "locations", "here" -> true;
            case "status" -> context.memberRecord();
            case "duty", "missions", "economy" -> context.activeMember();
            case "admin" -> context.commissioner();
            default -> false;
        };
    }

    /** Checks the complete FAQ target, including the question allowlist. */
    static boolean targetAvailable(Target target, Context context) {
        if (target == null || context == null || target.origin() == NpcPlayerSurface.RoleRoute.UNKNOWN) {
            return false;
        }
        if ("root".equals(target.kind())) return true;
        if (!topicAvailable(target.topic(), context)) return false;
        if ("menu".equals(target.kind())) return true;
        if (!"answer".equals(target.kind())) return false;
        String expected = "faq:answer_" + target.topic() + "_" + target.question()
                + "_" + roleKey(target.origin());
        return menu(target.origin(), context, target.topic()).actions().stream()
                .anyMatch(action -> expected.equals(action.actionId()));
    }

    static String answer(NpcPlayerSurface.RoleRoute origin, String topic, String question,
                         Context context) {
        String key = (topic == null ? "" : topic) + ":" + (question == null ? "" : question);
        return switch (key) {
            case "about:what" ->
                    "Straja protejează Castelul, face patrule, răspunde la incidente și ține evidența oficială a activității.";
            case "about:ranks" ->
                    "Parcursul este Civil → Stagiar → Străjer → Sergent → Inspector. Comisarul este o funcție de autoritate, nu o treaptă obișnuită.";
            case "about:faction" ->
                    "Da. Facțiunea nativă rămâne înregistrată, iar în timpul serviciului acționezi temporar ca membru al Străjii.";
            case "entry:join" -> entryAnswer(context);
            case "entry:application" -> applicationAnswer(context);
            case "entry:exam" ->
                    "Examenul are întrebări succesive. Instructorul este și Recrutorul: aici depui răspunsurile, iar promovarea te autorizează ca Stagiar.";
            case "entry:cooldown" ->
                    "Un răspuns greșit activează cooldown-ul configurat de server. După expirare poți relua examenul de la întrebarea curentă.";
            case "entry:afterpass" ->
                    "După promovare devii Stagiar, primești acces la instruire și poți începe traseul de serviciu conform regulilor serverului.";
            case "rank:current" -> "Statutul tău curent este: " + context.status() + ".";
            case "rank:powers" -> rankAnswer(context);
            case "rank:promotion" ->
                    "Promovarea se face la Instructor după modulele cerute și punctele de serviciu. Treptele superioare pot necesita decizia Comisarului.";
            case "duty:start" ->
                    "Serviciul începe la Secretariat. Dacă ai statut activ, apasă acțiunea de pornire; altfel vei vedea motivul exact al refuzului.";
            case "duty:checkpoint" ->
                    "Patrula urmează checkpoint-urile în ordine. Activează fiecare punct când ajungi la el; traseul și timpul sunt validate de server.";
            case "duty:stop" ->
                    "Serviciul normal se încheie la Secretariat. Gradele eligibile pentru tură liberă pot încheia serviciul de oriunde; patrula normală nu se oprește de la distanță.";
            case "duty:report" ->
                    "Raportul de activitate păstrează evidența muncii, misiunilor, incidentelor și notelor pentru Comisar.";
            case "missions:missions" ->
                    "Secretariatul afișează misiunile disponibile. De acolo poți intra, accepta, raporta, finaliza sau abandona o misiune, după statut.";
            case "missions:reports" ->
                    "Raportul săptămânal se depune la Secretariat. Comisarul îl poate accepta, returna pentru completări sau te poate chema la audiență.";
            case "missions:audience" ->
                    "Un membru poate cere o audiență la Comisar prin Secretariat. Cererea rămâne deschisă până când este rezolvată sau respinsă.";
            case "economy:salary" ->
                    "Salariul se acumulează din timpul de serviciu validat. Valoarea este configurabilă; implicit crește odată cu gradul.";
            case "economy:coins" ->
                    "Monedele și soldul salarial se verifică și se ridică la Secretariat, după ce plata devine disponibilă.";
            case "economy:equipment" ->
                    context.stationMessage("faq.economy.equipment",
                            "Kitul de serviciu se ridică prin {secretariat}, iar schimbul se face la {armorer}, în stația {station}.");
            case "economy:armory" ->
                    "Armuriera afișează doar articolele permise gradului și soldului tău. Unele folosesc monede, altele puncte de rechiziție.";
            case "rules:complaint" ->
                    "Plângerile se depun la Recepție și sunt prelucrate prin Secretariat, conform drepturilor membrilor desemnați.";
            case "rules:fine" ->
                    "O amendă poate fi plătită sau contestată la Recepție. Refuzul plății urmează procedura disciplinară configurată.";
            case "rules:discipline" ->
                    "Încălcările pot produce amendă, suspendare sau alte măsuri. Fiecare modificare relevantă este înregistrată în audit.";
            case "locations:ground" ->
                    context.stationMessage("faq.locations.ground",
                            "{receptionist} și {trainer} sunt puncte distincte ale stației {station}; cere direcția curentă personalului local.");
            case "locations:first" ->
                    context.stationMessage("faq.locations.first",
                            "{secretariat} și {commissionerOffice} sunt în stația {station}; locația exactă este cea configurată pentru această stație.");
            case "locations:second" ->
                    context.stationMessage("faq.locations.second",
                            "Camerele Străjerilor și {armorer} sunt în stația {station}; întreabă personalul local pentru traseul curent.");
            case "locations:basement" ->
                    context.stationMessage("faq.locations.basement",
                            "{prison} este în stația {station}; accesul și operațiunile de custodie depind de statut și autorizație.");
            case "status:current" -> "Statutul tău este: " + context.status() + ".";
            case "status:blocked" -> blockedAnswer(context);
            case "status:return" ->
                    "Revenirea după demisie, suspendare sau concediere nu este automată. Urmează instrucțiunile afișate la Secretariat sau vorbește cu Comisarul.";
            case "admin:personnel" ->
                    "Comisarul poate administra personalul, autoriza membri, aproba schimbări de grad și aplica măsuri disciplinare.";
            case "admin:emergency" ->
                    "Starea de urgență este activată de Comisar și modifică temporar operațiunile de patrulare și recompensele.";
            case "admin:audit" ->
                    "Recrutarea, promovările, sancțiunile, plățile, misiunile și schimbările administrative importante sunt auditate.";
            case "here:role" -> roleAnswer(origin, context);
            default -> "Întrebarea nu are încă un răspuns configurat.";
        };
    }

    private static String entryAnswer(Context context) {
        if (context.activeMember()) return "Ești deja membru activ: " + context.rankName + ". Pentru progres mergi la Instructor.";
        if (context.candidate()) return "Cererea sau invitația ta este activă. Mergi la Instructor pentru examenul de admitere.";
        if (context.suspended || context.resigned || context.fired) {
            return "Statutul tău actual nu permite o nouă cerere. Verifică ramura «Statutul meu» și discută cu Comisarul.";
        }
        return "Ca Civil, depui cererea la Recepție. După înregistrare mergi la Instructor, care este și Recrutorul.";
    }

    private static String applicationAnswer(Context context) {
        if ("APPLIED".equals(context.applicationState)) {
            return "Cererea este înregistrată. Următorul pas este examenul la Instructor.";
        }
        if (context.invited) return "Ai o invitație activă și poți merge direct la Instructor pentru examen.";
        if (context.activeMember()) return "Cererea a fost depășită: ești deja " + context.rankName + ".";
        return "Nu există o cerere activă. Depune-o la Recepție pentru a începe recrutarea.";
    }

    private static String rankAnswer(Context context) {
        if (context.commissioner) return "Ca Comisar ai autoritate administrativă și poți gestiona personalul și politicile serverului.";
        return switch (Rank.of(context.rank)) {
            case CIVIL -> "Ca Civil poți citi regulile și depune cererea, dar nu poți începe serviciul.";
            case STAGIAR -> "Ca Stagiar urmezi instruirea și poți începe serviciul dacă îndeplinești condițiile configurate.";
            case GUARD -> "Ca Străjer poți face patrule și operațiuni de teren în limitele capacităților tale.";
            case SERGENT -> "Ca Sergent primești atribuții extinse de coordonare și investigație, după regulile serverului.";
            case INSPECTOR -> "Ca Inspector ai atribuții superioare de administrare operațională și revizuire.";
        };
    }

    private static String blockedAnswer(Context context) {
        if (context.fired) return "Nu poți începe serviciul deoarece ești concediat din Strajă.";
        if (context.resigned) return "Nu poți începe serviciul în perioada de cooldown după demisie.";
        if (context.suspended) return "Nu poți începe serviciul cât timp ești suspendat.";
        if (!context.memberRecord()) return "Nu ai încă statut de membru. Depune cererea la Recepție.";
        return "Serviciul poate fi blocat de lipsa unui checkpoint, raport restant sau altă condiție configurată.";
    }

    private static String roleAnswer(NpcPlayerSurface.RoleRoute origin, Context context) {
        String role = switch (origin == null ? NpcPlayerSurface.RoleRoute.UNKNOWN : origin) {
            case RECEPTIONIST -> "Recepția înregistrează cereri și te îndrumă spre regulamente";
            case TRAINER, RECRUITER -> "Instructorul face recrutarea și instruirea — el este și Recrutorul";
            case SECRETARY -> "Secretariatul gestionează serviciul, misiunile, rapoartele, audiențele și copierea cărților ținute în mâna principală";
            case ARMORER -> "Armuriera gestionează echipamentul și rezervele autorizate";
            case JAILER -> "Custodia gestionează închisoarea și eliberările autorizate";
            case ARCHIVIST -> "Arhiva gestionează dosarele și documentele oficiale";
            case UNKNOWN -> "Acest punct nu are încă un rol configurat";
        };
        return role + ". Statutul tău afișat este " + context.status() + ".";
    }

    private static void addMenu(List<NpcPlayerSurface.ChatAction> actions, String label,
                                String topic, NpcPlayerSurface.RoleRoute origin) {
        actions.add(action(label, "menu_" + topic + "_" + roleKey(origin)));
    }

    private static void answer(List<NpcPlayerSurface.ChatAction> actions, String topic,
                               String question, String label, NpcPlayerSurface.RoleRoute origin) {
        actions.add(action(label, "answer_" + topic + "_" + question + "_" + roleKey(origin)));
    }

    private static NpcPlayerSurface.ChatAction action(String label, String target) {
        return new NpcPlayerSurface.ChatAction(label,
                NpcPlayerSurface.parameterizedActionId("faq", target).orElseThrow());
    }

    private static String title(String topic) {
        return switch (topic == null ? "" : topic) {
            case "about" -> "FAQ — Despre Strajă";
            case "entry" -> "FAQ — Înscriere și examen";
            case "rank" -> "FAQ — Grad și drepturi";
            case "duty" -> "FAQ — Serviciu și patrule";
            case "missions" -> "FAQ — Misiuni și rapoarte";
            case "economy" -> "FAQ — Monede și echipament";
            case "rules" -> "FAQ — Reguli și disciplină";
            case "locations" -> "FAQ — Locații";
            case "status" -> "FAQ — Statut personal";
            case "admin" -> "FAQ — Comisar";
            case "here" -> "FAQ — Acest NPC";
            default -> "Întrebări frecvente";
        };
    }

    private static String roleKey(NpcPlayerSurface.RoleRoute origin) {
        return switch (origin == null ? NpcPlayerSurface.RoleRoute.UNKNOWN : origin) {
            case RECEPTIONIST -> "receptionist";
            case TRAINER, RECRUITER -> "trainer";
            case SECRETARY -> "secretary";
            case ARMORER -> "armorer";
            case JAILER -> "jailer";
            case ARCHIVIST -> "archivist";
            case UNKNOWN -> "unknown";
        };
    }

    private static NpcPlayerSurface.RoleRoute roleForKey(String key) {
        return switch (key == null ? "" : key.toLowerCase(Locale.ROOT)) {
            case "receptionist" -> NpcPlayerSurface.RoleRoute.RECEPTIONIST;
            case "trainer" -> NpcPlayerSurface.RoleRoute.TRAINER;
            case "secretary" -> NpcPlayerSurface.RoleRoute.SECRETARY;
            case "armorer" -> NpcPlayerSurface.RoleRoute.ARMORER;
            case "jailer" -> NpcPlayerSurface.RoleRoute.JAILER;
            case "archivist" -> NpcPlayerSurface.RoleRoute.ARCHIVIST;
            default -> NpcPlayerSurface.RoleRoute.UNKNOWN;
        };
    }
}
