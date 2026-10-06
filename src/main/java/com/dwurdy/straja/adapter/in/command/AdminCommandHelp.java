package com.dwurdy.straja.adapter.in.command;

import static com.dwurdy.straja.adapter.in.StrajaText.refusal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Structured administrator help for the /straja command tree.
 *
 * <p>The command tree is the source of truth for available syntax. Help nodes
 * are attached after registration so every literal and argument node gets the
 * same {@code help} suffix without duplicating command builders.</p>
 */
final class AdminCommandHelp {
    static final int ADMIN_PERMISSION = CommandPermissions.ADMIN;
    static final int SETUP_PERMISSION = CommandPermissions.SETUP;

    private record RootEntry(String syntax, String description, int permission, String section) {}

    private static final List<RootEntry> ROOT_ENTRIES = List.of(
            new RootEntry("/straja status", "Afișează starea și rangul tău Straja.", 0, "INFORMARE"),
            new RootEntry("/straja rules | regulament", "Afișează regulamentul operațional.", 0, "INFORMARE"),
            new RootEntry("/straja stop", "Încheie serviciul când regulile rangului permit asta.", 0, "INFORMARE"),
            new RootEntry("/straja help | ajutor", "Afișează orientarea (jucători) sau acest index (admini).", 0, "INFORMARE"),
            new RootEntry("/straja backup", "Creează un snapshot persistent și bounded al datelor Straja.", ADMIN_PERMISSION, "ADMIN — OP 3"),
            new RootEntry("/straja personnel ...", "Inspectează personalul V2 server-authoritative.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja promotion ...", "Gestionează cereri și dovezi de promovare V2.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja document ...", "Inspectează documente și instrumente V2.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja equipment ledger <player>", "Inspectează obligațiile de echipament V2.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja mobilization ...", "Inspectează și încheie mobilizări V2.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja campaign ...", "Inspectează, pornește și încheie campanii V2.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja settlement list|show|review|retry|void|reconcile ...", "Inspectează și recuperează decontări V2.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja station status|validate", "Verifică stațiile și fallback-ul jurisdicțional.", ADMIN_PERMISSION, "V2 — OP 3/4"),
            new RootEntry("/straja doctor consistency", "Rulează diagnosticele de consistență fără reparații implicite.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja outbox status|pending|retry|dead-letter", "Inspectează și recuperează evenimente outbound fără a expune secrete.", ADMIN_PERMISSION, "V2 — OP 3"),
            new RootEntry("/straja invite <jucător>", "Invită un jucător în procesul de recrutare.", ADMIN_PERMISSION, "RECRUTARE ȘI PERSONAL — OP 3"),
            new RootEntry("/straja recruit | recrute", "Pornește recrutarea pentru jucătorul executor.", ADMIN_PERMISSION, "RECRUTARE ȘI PERSONAL — OP 3"),
            new RootEntry("/straja quiz [răspuns]", "Afișează sau validează chestionarul de admitere.", ADMIN_PERMISSION, "RECRUTARE ȘI PERSONAL — OP 3"),
            new RootEntry("/straja promote|demote|suspend|fire <jucător>", "Administrează rangul și statutul unui membru.", ADMIN_PERMISSION, "RECRUTARE ȘI PERSONAL — OP 3"),
            new RootEntry("/straja reinstate <jucător>", "Reintroduce un membru suspendat sau concediat.", ADMIN_PERMISSION, "RECRUTARE ȘI PERSONAL — OP 3"),
            new RootEntry("/straja faction|specialization ...", "Administrează afilierea și specializările unui membru.", ADMIN_PERMISSION, "RECRUTARE ȘI PERSONAL — OP 3"),

            new RootEntry("/straja start", "Pornește serviciul pentru jucătorul executor.", ADMIN_PERMISSION, "SERVICIU ȘI ECHIPARE — OP 3"),
            new RootEntry("/straja checkpoint <id>", "Activează checkpoint-ul indicat pe traseul curent.", ADMIN_PERMISSION, "SERVICIU ȘI ECHIPARE — OP 3"),
            new RootEntry("/straja special <start|resume|complete> <jucător>", "Gestionează serviciul special al unui jucător.", ADMIN_PERMISSION, "SERVICIU ȘI ECHIPARE — OP 3"),
            new RootEntry("/straja resign | demisie", "Înregistrează demisia și opțiunea de predare a echipamentului.", ADMIN_PERMISSION, "SERVICIU ȘI ECHIPARE — OP 3"),
            new RootEntry("/straja rejoin", "Reia procesul de revenire în Straja.", ADMIN_PERMISSION, "SERVICIU ȘI ECHIPARE — OP 3"),
            new RootEntry("/straja salary | coins | food | kit", "Afișează sau acordă resursele operaționale ale jucătorului.", ADMIN_PERMISSION, "SERVICIU ȘI ECHIPARE — OP 3"),
            new RootEntry("/straja merit [dock ...]", "Afișează registrul de merit sau aplică o deducere autorizată.", ADMIN_PERMISSION, "SERVICIU ȘI ECHIPARE — OP 3"),

            new RootEntry("/straja report|message|request <text>", "Trimite o sesizare, un mesaj sau o cerere în inbox.", ADMIN_PERMISSION, "COMUNICARE — OP 3"),
            new RootEntry("/straja inbox", "Citește inbox-ul administrativ; accesul final este verificat de serviciu.", ADMIN_PERMISSION, "COMUNICARE — OP 3"),
            new RootEntry("/straja mission ...", "Gestionează misiuni, carnete, invitații și șabloane.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),
            new RootEntry("/straja cuffs ...", "Gestionează cereri și stări de contenție.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),
            new RootEntry("/straja prison ...", "Gestionează celule, arestări și eliberări.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),
            new RootEntry("/straja desk ...", "Gestionează birourile de negustor și registrul de vânzări.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),
            new RootEntry("/straja fine ...", "Gestionează amenzi, apeluri, sarcini și mandate.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),
            new RootEntry("/straja complaint ...", "Gestionează plângeri și investigații.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),
            new RootEntry("/straja room ...", "Gestionează camerele și repartizarea lor.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),
            new RootEntry("/straja archive ...", "Lucrează cu dosare, file, registre și documente semnate.", ADMIN_PERMISSION, "ARHIVĂ — OP 3"),
            new RootEntry("/straja identity ...", "Listează, emite, revocă sau marchează controlat legitimații.", ADMIN_PERMISSION, "IDENTITATE — OP 3"),
            new RootEntry("/straja inspector ...", "Administrează licențele de Inspector; register este deschis inspectorilor licențiați.", ADMIN_PERMISSION, "IDENTITATE — OP 3"),
            new RootEntry("/straja transporter ...", "Administrează licențele de Transportator; seal|unseal sunt deschise transportatorilor licențiați.", ADMIN_PERMISSION, "IDENTITATE — OP 3"),
            new RootEntry("/straja artifact ...", "Verifică sau revocă marcile de serie din registrul central.", ADMIN_PERMISSION, "IDENTITATE — OP 3"),
            new RootEntry("/straja book give [player]", "Emite ghidul de patrulare al zilei (detectarea falsurilor).", ADMIN_PERMISSION, "IDENTITATE — OP 3"),
            new RootEntry("/straja emergency ...", "Pornește sau închide procedura de urgență; autoritatea finală rămâne în serviciu.", ADMIN_PERMISSION, "OPERAȚIUNI — OP 3"),

            new RootEntry("/straja checkpoint add|remove ...", "Modifică lista persistentă de checkpoint-uri.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja set-checkpoint <id>", "Salvează checkpoint-ul indicat la poziția curentă.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja set-mission-time <id> <minute>", "Setează durata implicită a unei misiuni.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja set-location <nume>", "Salvează poziția și dimensiunea pentru o locație Straja.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja setup ...", "Arată checklist-ul sau aplică setup-ul ghidat al sediului.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja policy ...", "Citește sau modifică override-urile de politici persistente.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja migrate <worldPath>", "Importă datele vechi KubeJS în store-urile native.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja npc ...", "Administrează registrul și entitățile NPC Straja.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja debug ...", "Rulează diagnostice locale; politica mediului rămâne obligatorie.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4"),
            new RootEntry("/straja test ...", "Rulează suprafața de test virtuală, doar în mediul local permis.", SETUP_PERMISSION, "SETUP ȘI OPERARE — OP 4")
    );

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("personnel", "Inspectează registrul persistent de personal V2."),
            Map.entry("personnel list", "Listează înregistrările de personal V2."),
            Map.entry("personnel show", "Arată starea persistentă V2 a unui jucător."),
            Map.entry("personnel profession", "Atribuie o profesie V2 prin fluxul de personal autorizat."),
            Map.entry("personnel appoint", "Creează o numire V2 cu stație și jurisdicție explicită."),
            Map.entry("promotion", "Gestionează cereri persistente de promovare V2."),
            Map.entry("document", "Gestionează documente și instrumente server-authoritative."),
            Map.entry("equipment", "Inspectează obligațiile de echipament V2."),
            Map.entry("mobilization", "Gestionează mobilizările profesionale V2."),
            Map.entry("campaign", "Gestionează campanii și cote V2."),
            Map.entry("settlement", "Inspectează decontările V2 și starea plăților."),
            Map.entry("settlement retry", "Reintroduce și încearcă plata unei decontări."),
            Map.entry("settlement void", "Anulează o decontare neplătită."),
            Map.entry("settlement reconcile", "Mută plățile întrerupte în starea retryable."),
            Map.entry("station", "Gestionează stațiile și fallback-ul lor."),
            Map.entry("station status", "Arată stația HQ rezolvată."),
            Map.entry("station validate", "Verifică referințele și ciclurile de fallback."),
            Map.entry("doctor", "Rulează verificări read-only ale registrelor V2."),
            Map.entry("doctor consistency", "Detectează referințe rupte, cantități invalide și operații duplicate."),
            Map.entry("outbox", "Inspectează coada persistentă de notificări outbound."),
            Map.entry("outbox status", "Arată notificările outbound încă nesent."),
            Map.entry("outbox pending", "Listează evenimentele outbound care așteaptă livrarea."),
            Map.entry("outbox retry", "Reintroduce un eveniment outbound în coada de retry."),
            Map.entry("outbox dead-letter", "Listează evenimentele outbound epuizate."),
            Map.entry("invite", "Invită jucătorul țintă în fluxul de recrutare."),
            Map.entry("recruit", "Aplică recrutarea pentru jucătorul executor."),
            Map.entry("recrute", "Alias românesc pentru recruit."),
            Map.entry("quiz", "Afișează întrebările sau verifică răspunsul trimis."),
            Map.entry("start", "Pornește serviciul pentru jucătorul executor."),
            Map.entry("stop", "Încheie serviciul și respectă regula de revenire la Secretariat."),
            Map.entry("special", "Gestionează ciclul serviciului special pentru un jucător."),
            Map.entry("special start", "Pornește serviciul special."),
            Map.entry("special resume", "Reia serviciul special întrerupt."),
            Map.entry("special complete", "Finalizează serviciul special."),
            Map.entry("resign", "Înregistrează demisia."),
            Map.entry("demisie", "Alias românesc pentru resign."),
            Map.entry("rejoin", "Pornește revenirea în Straja."),
            Map.entry("salary", "Afișează salariul și starea plăților."),
            Map.entry("coins", "Afișează soldul de monede."),
            Map.entry("food", "Afișează sau acordă rația operațională."),
            Map.entry("kit", "Afișează sau acordă kit-ul operațional."),
            Map.entry("merit", "Afișează registrul de merit."),
            Map.entry("merit dock", "Aplică o deducere de merit unui jucător."),
            Map.entry("promote", "Promovează un membru, după verificarea regulilor din serviciu."),
            Map.entry("demote", "Retrogradează un membru, după verificarea regulilor din serviciu."),
            Map.entry("suspend", "Suspendă un membru."),
            Map.entry("fire", "Concediază un membru."),
            Map.entry("reinstate", "Reintroduce un membru în efectiv."),
            Map.entry("faction", "Setează facțiunea nativă a unui membru."),
            Map.entry("specialization", "Administrează specializările unui membru."),
            Map.entry("specialization add", "Adaugă o specializare."),
            Map.entry("specialization remove", "Elimină o specializare."),
            Map.entry("report", "Trimite o sesizare în inbox."),
            Map.entry("message", "Trimite un mesaj în inbox."),
            Map.entry("request", "Trimite o cerere în inbox."),
            Map.entry("inbox", "Citește ultimele mesaje administrative."),
            Map.entry("mission", "Arată lista de misiuni când nu este indicată o subcomandă."),
            Map.entry("mission list", "Listează misiunile disponibile sau relevante."),
            Map.entry("mission carnet", "Oferă carnetul de misiuni jucătorului."),
            Map.entry("mission create", "Creează o misiune direct pentru un jucător."),
            Map.entry("mission draft", "Gestionează draftul de misiune din carnet."),
            Map.entry("mission templates", "Listează șabloanele disponibile."),
            Map.entry("mission template", "Administrează șabloanele de misiuni."),
            Map.entry("mission give", "Oferă carnetul unui jucător țintă."),
            Map.entry("mission invite", "Invită un jucător într-o misiune."),
            Map.entry("mission resend", "Retrimite pachetul unei misiuni."),
            Map.entry("cuffs", "Arată starea de contenție a jucătorului."),
            Map.entry("cuffs status", "Arată starea cătușelor."),
            Map.entry("cuffs downed", "Arată starea downed."),
            Map.entry("cuffs item", "Oferă obiectul de cătușe."),
            Map.entry("cuffs request", "Trimite sau procesează o cerere de cătușe."),
            Map.entry("cuffs surrender", "Procesează o predare către autoritate."),
            Map.entry("cuffs release", "Eliberează jucătorul țintă din contenție."),
            Map.entry("cuffs emergency", "Forțează eliberarea de urgență, dacă serviciul permite."),
            Map.entry("cuffs sack-remove", "Îndepărtează sacul de pe cap."),
            Map.entry("prison", "Arată starea închisorii."),
            Map.entry("prison status", "Arată starea detențiilor."),
            Map.entry("prison cells", "Listează celulele cunoscute."),
            Map.entry("prison arrest", "Arestă un jucător pentru numărul indicat de zile."),
            Map.entry("prison release", "Eliberează un jucător."),
            Map.entry("prison cell", "Administrează geometria celulelor."),
            Map.entry("desk", "Gestionează birourile de negustor; vânzarea este deschisă jucătorilor."),
            Map.entry("desk create", "Creează un birou legat de un NPC (nume sau UUID)."),
            Map.entry("desk add-chest", "Armează selectarea — click dreapta pe cufere, în ordinea de umplere."),
            Map.entry("desk set-price", "Setează prețul unitar în monede de bază; 0 elimină articolul."),
            Map.entry("desk labor", "Comută creditarea contului de muncă pentru deținuți."),
            Map.entry("desk quartermaster", "Configurează biroul ca intendent de tabără din interdicțiile porții."),
            Map.entry("desk ledger", "Afișează registrul de vânzări al biroului, opțional filtrat."),
            Map.entry("desk sell", "Vinde bunurile transportate la biroul indicat."),
            Map.entry("camp", "Gestionează lagărele de muncă; statusul este deschis deținuților."),
            Map.entry("camp register", "Înregistrează un lagăr cu perimetrul dat (minX,minY,minZ maxX,maxY,maxZ)."),
            Map.entry("camp link-desk", "Leagă biroul intendent de lagăr."),
            Map.entry("camp link-exit", "Leagă checkpoint-ul de ieșire care respinge deținuții."),
            Map.entry("camp set-freedom-price", "Prețul libertății: flat '1g 32s' sau multiplicator de amendă."),
            Map.entry("camp spawn", "Setează spawn-ul intake/release/dormitory la poziția ta."),
            Map.entry("camp transfer", "Transferă un deținut din celulă în custodia lagărului."),
            Map.entry("camp status", "Afișează creditul de muncă și ținta de libertate."),
            Map.entry("fine", "Deschide fluxul registrului de amenzi."),
            Map.entry("fine book", "Oferă registrul de amenzi."),
            Map.entry("fine write", "Scrie un draft de amendă."),
            Map.entry("fine draft", "Afișează draftul de amendă curent."),
            Map.entry("fine issue", "Emite amenda din draft."),
            Map.entry("fine pay", "Plătește o amendă."),
            Map.entry("fine appeal", "Depune un apel pentru o amendă."),
            Map.entry("fine appeals", "Listează apelurile."),
            Map.entry("fine review", "Revizuiește un apel."),
            Map.entry("fine recover", "Recuperează sau reîncearcă plata."),
            Map.entry("fine list", "Listează amenzile."),
            Map.entry("fine tasks", "Listează sarcinile de plată."),
            Map.entry("fine accept", "Acceptă o sarcină."),
            Map.entry("fine complete", "Finalizează o sarcină."),
            Map.entry("fine refuse", "Refuză o sarcină."),
            Map.entry("fine arrest", "Procesează arestarea asociată unei sarcini."),
            Map.entry("fine warrant", "Emite un mandat de audiere."),
            Map.entry("fine cancel", "Anulează o amendă."),
            Map.entry("complaint", "Gestionează plângeri și investigații."),
            Map.entry("room", "Arată și administrează camerele Straja."),
            Map.entry("room status", "Arată starea camerelor."),
            Map.entry("room list", "Listează camerele."),
            Map.entry("room create", "Creează o cameră."),
            Map.entry("room assign", "Repartizează un jucător într-o cameră."),
            Map.entry("room release", "Eliberează o cameră."),
            Map.entry("archive", "Lucrează cu dosare și documente de arhivă."),
            Map.entry("archive role", "Setează rolul arhivistic al unui jucător."),
            Map.entry("archive list", "Listează dosarele."),
            Map.entry("archive folder", "Administrează dosarele."),
            Map.entry("archive folder create", "Creează un dosar nou."),
            Map.entry("archive folder read", "Citește un dosar."),
            Map.entry("archive folder close", "Închide un dosar."),
            Map.entry("archive folder open", "Redeschide un dosar."),
            Map.entry("archive sheet", "Administrează filele și documentele dintr-un dosar."),
            Map.entry("archive sheet new", "Creează o filă nouă."),
            Map.entry("archive sheet edit", "Editează conținutul unei file."),
            Map.entry("archive sheet recipients", "Setează destinatarii unei file."),
            Map.entry("archive sheet submit", "Trimite fila spre procesare."),
            Map.entry("archive sheet read", "Citește o filă."),
            Map.entry("archive sheet list", "Listează filele unui dosar."),
            Map.entry("archive sheet sign", "Semnează o filă, opțional cu motiv."),
            Map.entry("archive sheet revoke", "Revocă o semnătură sau o filă."),
            Map.entry("archive sheet copy", "Copiază o filă către destinatari."),
            Map.entry("archive sheet pack", "Împachetează o filă într-un plic."),
            Map.entry("archive catalog", "Administrează aliasurile de catalog."),
            Map.entry("identity", "Administrează legitimațiile Straja."),
            Map.entry("identity list", "Listează legitimațiile înregistrate."),
            Map.entry("identity issue", "Emite o legitimație autentică pentru un jucător."),
            Map.entry("identity forge", "Emite controlat o legitimație marcată ca falsă, cu indiciu subtil."),
            Map.entry("identity revoke", "Revocă o legitimație după identificator."),
            Map.entry("inspector", "Administrează licențele de Inspector."),
            Map.entry("inspector grant", "Acordă licența de Inspector unui jucător."),
            Map.entry("inspector revoke", "Revocă licența de Inspector a unui jucător."),
            Map.entry("inspector list", "Listează licențele active din registru."),
            Map.entry("inspector status", "Afișează licențele tale active."),
            Map.entry("inspector register", "Înregistrează și marchează obiectul din mână; necesită licență Inspector."),
            Map.entry("transporter", "Administrează licențele de Transportator."),
            Map.entry("transporter grant", "Acordă licența de Transportator unui jucător."),
            Map.entry("transporter revoke", "Revocă licența de Transportator a unui jucător."),
            Map.entry("transporter list", "Listează licențele active din registru."),
            Map.entry("transporter status", "Afișează licențele tale active."),
            Map.entry("transporter seal", "Sigilează lada militară din mână; necesită licență Transportator."),
            Map.entry("transporter unseal", "Rupe sigiliul propriei lăzi; autoritatea poate desigila orice."),
            Map.entry("artifact", "Verifică sau revocă marcile de serie din registrul central."),
            Map.entry("artifact check", "Afișează adevărul din registru pentru o marcă serială."),
            Map.entry("artifact revoke", "Revocă o marcă serială după identificator."),
            Map.entry("license", "Afișează licențele Straja pe care le deții."),
            Map.entry("book", "Emite ghidul de patrulare al zilei cu exemplele curente de fals."),
            Map.entry("book give", "Înmânează ghidul de patrulare al zilei; reemiterea aceleiași ediții e refuzată."),
            Map.entry("emergency", "Gestionează starea de urgență operațională."),
            Map.entry("emergency alert", "Publică o alertă de urgență."),
            Map.entry("emergency clear", "Șterge urgența activă."),
            Map.entry("emergency start", "Pornește ciclul de urgență."),
            Map.entry("emergency end", "Încheie ciclul de urgență."),
            Map.entry("emergency status", "Arată starea de urgență."),
            Map.entry("checkpoint", "Arată sau folosește checkpoint-uri; modificările de setup cer OP 4."),
            Map.entry("checkpoint add", "Adaugă un checkpoint la setup."),
            Map.entry("checkpoint remove", "Elimină un checkpoint din setup."),
            Map.entry("set-checkpoint", "Salvează checkpoint-ul curent sub identificatorul dat."),
            Map.entry("set-mission-time", "Schimbă timpul configurat pentru o misiune."),
            Map.entry("set-location", "Salvează locația curentă sub numele dat."),
            Map.entry("setup", "Arată checklist-ul și acțiunile de configurare ghidată."),
            Map.entry("setup here", "Configurează locațiile sediului la poziția curentă."),
            Map.entry("setup patrol", "Configurează patrula inițială."),
            Map.entry("setup npcs", "Spawnează NPC-urile lipsă în locațiile configurate."),
            Map.entry("setup tools", "Oferă kit-ul de unelte administrative."),
            Map.entry("policy", "Gestionează override-uri de politici."),
            Map.entry("policy list", "Listează politicile disponibile."),
            Map.entry("policy get", "Citește o politică."),
            Map.entry("policy set", "Setează o politică persistentă."),
            Map.entry("policy reset", "Șterge override-ul unei politici."),
            Map.entry("migrate", "Importă un world legacy în store-urile native."),
            Map.entry("npc", "Administrează NPC-urile și registrul lor."),
            Map.entry("npc list", "Listează NPC-urile înregistrate."),
            Map.entry("npc spawn", "Spawnează un NPC la poziția indicată."),
            Map.entry("npc assign", "Atribuie un rol unui NPC."),
            Map.entry("npc set-name", "Schimbă numele unui NPC."),
            Map.entry("npc set-skin", "Schimbă skin-ul unui NPC."),
            Map.entry("npc remove", "Elimină un NPC din registru și din lume."),
            Map.entry("npc expertise", "Setează expertiza inspectorului: junior, veteran sau expert."),
            Map.entry("debug", "Rulează diagnostice locale controlate de politică."),
            Map.entry("test", "Rulează scenarii cu jucători virtuali în mediul local.")
    );

    private static final Map<String, HelpSpec> STRUCTURED_SPECS = Map.ofEntries(
            Map.entry("personnel", new HelpSpec("/straja personnel ...", CommandPermissions.ADMIN,
                    "AUTHORIZE_PERSONNEL / management de personal; numirea Comisarului este separată",
                    "Registrul V2 de personal", "Scrie personnel și auditul aferent",
                    List.of("personnel", "audit"), List.of("DENIED_APPOINTMENT", "DENIED_STALE_STATE"),
                    List.of("/straja personnel list", "/straja personnel show <player>"),
                    "/straja doctor personnel", true, "V2 — OP 3")),
            Map.entry("promotion", new HelpSpec("/straja promotion ...", CommandPermissions.ADMIN,
                    "APPROVE_PROMOTION; aprobare independentă obligatorie",
                    "Cereri și dovezi de promovare", "Scrie promotion și schimbarea de carieră",
                    List.of("promotions", "personnel", "audit"), List.of("DENIED_SELF_APPROVAL", "INSUFFICIENT_EVIDENCE"),
                    List.of("/straja promotion list", "/straja promotion approve <id>"),
                    "/straja promotion show <id>", true, "V2 — OP 3")),
            Map.entry("document", new HelpSpec("/straja document ...", CommandPermissions.ADMIN,
                    "ISSUE_DOCUMENT; emitentul și stația sunt validate server-side",
                    "Documente, instrumente și reprinturi", "Consumă doar o singură dată un instrument; persistă dovada",
                    List.of("documents", "operations", "audit"), List.of("DENIED_AUTHORIZATION", "OPERATION_PAYLOAD_MISMATCH"),
                    List.of("/straja document list", "/straja document reprint <id> <key>"),
                    "/straja doctor operations", true, "V2 — OP 3")),
            Map.entry("equipment", new HelpSpec("/straja equipment ...", CommandPermissions.ADMIN,
                    "ISSUE_DOCUMENT pentru emitere; WAIVE_EQUIPMENT_DEBT pentru derogări",
                    "Registru line-level de echipament și datorii", "Scrie issue, delivery, return și dovada de predare",
                    List.of("equipment", "documents", "operations"), List.of("DELIVERY_EXCEEDS_REQUEST", "DENIED_AUTHORIZATION"),
                    List.of("/straja equipment ledger <player>", "/straja equipment return <player> <item> <qty>"),
                    "/straja doctor equipment", true, "V2 — OP 3")),
            Map.entry("mobilization", new HelpSpec("/straja mobilization ...", CommandPermissions.ADMIN,
                    "MOBILIZE_SPECIALISTS și scope activ pentru operațiuni profesionale",
                    "Ordinele de mobilizare ale specialiștilor", "Muster/end/cancel și decontarea sunt idempotente",
                    List.of("mobilizations", "settlements", "audit"), List.of("DENIED_MOBILIZATION_REQUIRED", "INVALID_STATE"),
                    List.of("/straja mobilization list", "/straja mobilization end <id>"),
                    "/straja settlement show <id>", true, "V2 — OP 3")),
            Map.entry("settlement", new HelpSpec("/straja settlement ...", CommandPermissions.ADMIN,
                    "Acces administrativ; payout-ul folosește doar CurrencyProvider",
                    "Entitlements și plăți exactly-once", "Payout-ul este precedat de persistarea stării IN_PROGRESS",
                    List.of("settlements", "audit"), List.of("FAILED_RETRYABLE", "REVIEW", "VOID"),
                    List.of("/straja settlement retry <id>", "/straja settlement reconcile"),
                    "/straja settlement show <id>", true, "V2 — OP 3")),
            Map.entry("outbox", new HelpSpec("/straja outbox ...", CommandPermissions.ADMIN,
                    "Allowlist de evenimente și payload redacted",
                    "Coada persistentă de notificări outbound", "Trimite asincron; retry și dead-letter fără secret",
                    List.of("outbox"), List.of("RETRY", "DEAD_LETTER"),
                    List.of("/straja outbox status", "/straja outbox test"),
                    "/straja outbox retry <id>", true, "V2 — OP 3/4"))
    );

    private AdminCommandHelp() {}

    static void attach(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild("straja");
        if (root != null) attach(root, List.of("/straja"), "");
    }

    private static void attach(CommandNode<CommandSourceStack> node,
                               List<String> displayPath,
                               String commandKey) {
        if (!"help".equals(node.getName()) && node.getChild("help") == null) {
            String path = String.join(" ", displayPath);
            var help = Commands.literal("help")
                    .executes(ctx -> show(ctx, node, path, commandKey));
            // Root help is the player recovery surface — parseable at
            // permission 0; subtree help stays admin-only at the parse gate.
            if (!commandKey.isEmpty()) {
                help.requires(source -> source.hasPermission(ADMIN_PERMISSION));
            }
            node.addChild(help.build());
        }

        for (CommandNode<CommandSourceStack> child : new ArrayList<>(node.getChildren())) {
            if ("help".equals(child.getName()) || "npc-action".equals(child.getName())) continue;
            String displaySegment = child instanceof ArgumentCommandNode<?, ?>
                    ? child.getUsageText() : child.getName();
            String nextKey = child instanceof ArgumentCommandNode<?, ?>
                    ? commandKey : append(commandKey, child.getName());
            attach(child, append(displayPath, displaySegment), nextKey);
        }
    }

    private static String append(String base, String value) {
        return base.isEmpty() ? value : base + " " + value;
    }

    private static List<String> append(List<String> base, String value) {
        List<String> result = new ArrayList<>(base);
        result.add(value);
        return result;
    }

    private static int show(CommandContext<CommandSourceStack> ctx,
                            CommandNode<CommandSourceStack> node,
                            String displayPath,
                            String commandKey) {
        CommandSourceStack source = ctx.getSource();
        if (commandKey.isEmpty()) {
            return sendRoot(source);
        }
        if (!source.hasPermission(ADMIN_PERMISSION)) {
            source.sendFailure(refusal("straja.cmd.help_op", "straja.remedy.ask_comisar"));
            return 0;
        }

        int permission = permissionLevel(commandKey);
        if (permission > effectivePermission(source)) {
            source.sendFailure(refusal("straja.cmd.command_op", "straja.remedy.ask_comisar", permission));
            return 0;
        }
        HelpSpec spec = STRUCTURED_SPECS.get(commandKey);
        if (spec == null) {
            send(source, List.of(
                    "§lAjutor Straja: " + displayPath,
                    "Descriere: " + description(commandKey),
                    "Acces: " + accessLabel(permission)
            ));
        } else {
            List<String> metadata = new ArrayList<>(List.of(
                    "§lAjutor Straja: " + displayPath,
                    "Scop: " + spec.purpose(),
                    "Acces: " + accessLabel(spec.permissionLevel()),
                    "Autoritate: " + spec.domainAuthorization(),
                    "Efecte: " + spec.sideEffects(),
                    "Persistă: " + String.join(", ", spec.persistentRecords()),
                    "Erori: " + String.join(", ", spec.errorCodes())
            ));
            if (!spec.recoveryCommand().isBlank()) metadata.add("Recuperare: " + spec.recoveryCommand());
            metadata.add("Sensibil: " + (spec.sensitive() ? "da" : "nu"));
            send(source, metadata);
        }

        List<String> children = new ArrayList<>();
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if ("help".equals(child.getName())) continue;
            String childDisplay = child instanceof ArgumentCommandNode<?, ?>
                    ? child.getUsageText() : child.getName();
            String childKey = child instanceof ArgumentCommandNode<?, ?>
                    ? commandKey : append(commandKey, child.getName());
            int childPermission = permissionLevel(childKey);
            if (childPermission > effectivePermission(source)) continue;
            String syntax = displayPath + " " + childDisplay;
            children.add("  " + syntax + " — " + description(childKey));
        }
        if (!children.isEmpty()) {
            send(source, List.of("Subcomenzi:"));
            send(source, children);
        }
        if (node.getChild("help") != null) {
            send(source, List.of("Detalii: " + displayPath + " help"));
        }
        return 1;
    }

    private static void send(CommandSourceStack source, List<String> lines) {
        for (String line : lines) source.sendSystemMessage(Component.literal(line));
    }

    /** Bare /straja, /straja help and /straja ajutor entry point for every audience. */
    static int showRoot(CommandContext<CommandSourceStack> ctx) {
        return sendRoot(ctx.getSource());
    }

    private static int sendRoot(CommandSourceStack source) {
        int permission = effectivePermission(source);
        if (permission >= ADMIN_PERMISSION) {
            send(source, rootHelpLines(permission));
            return 1;
        }
        for (Component line : playerHelp()) source.sendSystemMessage(line);
        return 1;
    }

    static List<String> rootHelpLines(int permission) {
        List<String> lines = new ArrayList<>();
        lines.add("§lStraja — help administrativ");
        lines.add("Detalii: /straja <comandă> help");
        lines.add("Ajutorul este disponibil de la OP 3; setup-ul cere OP 4.");
        String section = "";
        for (RootEntry entry : ROOT_ENTRIES) {
            if (entry.permission() > permission) continue;
            if (!entry.section().equals(section)) {
                section = entry.section();
                lines.add("");
                lines.add("§l" + section);
            }
            lines.add(entry.syntax() + " — " + entry.description());
        }
        return lines;
    }

    private static List<Component> playerHelp() {
        List<Component> lines = new ArrayList<>(List.of(
                Component.translatable("straja.help.player.intro"),
                Component.translatable("straja.help.player.first_step")));
        for (var entry : StrajaCommands.CommandPolicy.playerHelpEntries()) {
            lines.add(Component.literal(entry.command())
                    .withStyle(style -> style
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, entry.command()))
                            .withUnderlined(true))
                    .append(Component.literal(" — "))
                    .append(Component.translatable(entry.descKey())));
        }
        lines.add(Component.translatable("straja.help.player.faq"));
        return lines;
    }

    static int permissionLevel(String command) {
        return CommandPermissions.permissionLevel(command);
    }

    static String description(String command) {
        String known = DESCRIPTIONS.get(command);
        if (known != null) return known;
        if (command == null || command.isBlank()) return "Indexul comenzilor Straja.";
        String last = command.substring(command.lastIndexOf(' ') + 1);
        if (last.startsWith("<")) return "Completează parametrul " + last + ".";
        if (!command.contains(" ")) return "Execută «" + last + "».";
        return "Execută operațiunea «" + last + "» din fluxul "
                + command.substring(0, command.lastIndexOf(' ')) + ".";
    }

    private static int effectivePermission(CommandSourceStack source) {
        return source.hasPermission(SETUP_PERMISSION) ? SETUP_PERMISSION
                : source.hasPermission(ADMIN_PERMISSION) ? ADMIN_PERMISSION : 0;
    }

    private static String accessLabel(int permission) {
        return permission == 0 ? "public"
                : "OP " + permission;
    }
}
