# Ghid rapid pentru administratori

De la jar la **„Instalare funcțională — gata pentru jucători."** Pagina asta
urmează exact checklist-ul afișat de `/straja setup` — dacă o verificare e
marcată ✗ în joc, găsești remediul aici.

## 1. Instalare

- **Minecraft** 1.21.1 · **NeoForge** 21.1.x · **Java** 21
- Pune `straja-<versiune>.jar` în `mods/`.
- **Dependență obligatorie:** [Envelope](https://modrinth.com/mod/envelope)
  0.6.2+ (sistemul de poștă/livrări).
- **Opțional:** iteme-monedă pentru economie — implicit monedele din
  Ady's Decorations; alte ID-uri se configurează în `[economy]`.

Configurația serverului este în `world/serverconfig/straja-server.toml`
(fișier de tip SERVER — pornește serverul o dată ca să fie generat).

## 2. Comisarul

În `[identity]` din `straja-server.toml`:

```toml
[identity]
commissionerName = "numele_tau"      # fallback după nume (allowNameFallback)
commissionerUuid = "uuid-ul-tau"     # recomandat în producție
```

Fără `commissionerUuid` fixat, instalările non-local opresc la pornire cu
`DEPLOYMENT GATE FAILED` — fixează UUID-ul (îl afli cu `/straja status` după
prima logare sau din API-uri de tipul mcuuid) și repornește.

## 3. Checklist-ul `/straja setup` (OP 4)

Rulează `/straja setup` în orice moment — afișează starea și **Următorul
pas**. Ordinea verificărilor:

1. **Comisar** — setat prin `[identity]` (pasul 2).
2. **Locații administrative** (9: reportsLectern, commissionerMailbox,
   commissionerOffice, receptionist, secretary, trainer, armorer,
   prisonRelease, infirmary, HQ) — stai unde vrei sediul și rulează
   `/straja setup here` (le scrie pe toate aici), apoi mută-le individual cu
   `/straja set-location <nume>`.
3. **Checkpoint-uri de patrulare** — `/straja setup patrol` așază ruta
   configurată ca inel în jurul tău; rafinează cu
   `/straja set-checkpoint <id>` și `/straja checkpoint add|remove`.
4. **NPC-uri** (Recepționist, Instructor, Secretară, Armurier) —
   `/straja setup npcs` spawnează ce lipsește; mută/leagă NPC-uri existente
   cu bagheta din `/straja setup tools`.
5. **Celule de detenție** — marchează colțurile cu itemul `prison_marker`
   (în kitul `/straja setup tools`).
6. **Economie** — `coinItemIds` în `[economy]` (implicit monede Ady's
   Decorations).
7. **Stații** — lanțul de fallback al stațiilor trebuie să fie valid; erorile
   sunt listate în checklist.

`/straja setup tools` dă kitul de instrumente (bagheta NPC, bagheta de
patrulare, jalonul de măsurare, clonatorul, marcajele de celulă/cameră) —
itemii re-verifică autoritatea Comisarului/op la fiecare folosire.

### 3.1 Birouri de negustor (LAW-005)

Biroul de negustor e un tejghea de cumpărare: jucătorii vând bunurile listate,
marfa intră fizic în cuferele legate (primul se umple, restul curge în
următorul), iar plata se face în monedele din `[economy]` sau în contul de
muncă al deținuților.

```
/straja desk create <id> <npcName|npcUuid>   # creează biroul la poziția ta
/straja desk add-chest <id>                  # apoi click dreapta pe cufere, în ordinea de umplere
/straja desk set-price <id> <mod:item> <unități bază>   # preț 0 = scoate din listă
/straja desk labor <id> on|off               # deținuții primesc credit de muncă, nu monede
/straja desk quartermaster <campId> <id> <preț>  # profil de intendent: preia interdicțiile porții taberei
/straja desk ledger <id> [vânzător]          # registrul de vânzări
/straja desk remove <id> | /straja desk list
```

Jucătorii vând cu `/straja desk sell <id> [item] [cantitate]` stând lângă
birou — leagă comanda la un dialog CustomNPCs (opțiunea „rulează comandă") sau
la un buton de suprafață nativă. Fiecare vânzare e atomică: cuferele pline
refuză fără să atingă inventarul sau banii, iar fiecare tranzacție scrie un
rând imuabil în registrul de vânzări.

### 3.2 Lagăre de muncă (LAW-006)

Un lagăr de muncă e o custodie pe perimetru: deținuții minesz, vând la
intendent, iar vânzările le creditează contul de muncă până la prețul
libertății — atunci eliberarea e automată.

```
/straja camp register <id> <nume> <minX,minY,minZ> <maxX,maxY,maxZ>
/straja camp spawn <id> intake|release|dormitory   # spawn-uri la poziția ta
/straja camp link-exit <id> <checkpointId>          # poarta care respinge deținuții
/straja camp link-desk <id> <deskId>                # intendentul din lagăr
/straja camp set-freedom-price <id> flat "1g 32s"   # sau fines_multiplier <x>
/straja checkpoint arrestdest <site> CAMP:<id>      # arestările site-ului ajung în lagăr
/straja camp transfer <jucător> <id>                # mută un deținut din celulă
/straja camp status [jucător] | /straja camp list | /straja camp unregister <id>
```

Ordine recomandată: înregistrează lagărul → setează spawn-urile → creează
checkpoint-ul de ieșire cu carry-ban pe minereuri → `link-exit` → creează
biroul intendentului și `link-desk` (sau `desk quartermaster` care leagă și
prețuiește din interdicții) → opțional `arrestdest CAMP:` pe checkpoint-urile
de frontieră.

Detalii de comportament: deținutul care trece firul fără escortă devine
FUGITIVE cu BOLO; moartea îl întoarce la dormitor fără să ridice custodia;
poarta de ieșire respinge deținuții și le confiscă mărfurile interzise spre
cuferele de probatoriu; civililor li se plătește în monede chiar la același
birou. Comisarul poate elibera manual oricând — balanța nu e o condiție pentru
`/straja prison release`.

Configurare TOML (`straja-server.toml`): `[economy].tierRatio` (implicit 64),
coinItemIds pentru cele patru monede, `[labor_camp].freedomPriceMode` /
`freedomFlatPrice` / `freedomFineMultiplier` ca fallbackuri per-lagăr.

### Gărzi și urmărire (LAW-007)

Gărzile NPC (facțiunea `[storage].factionId`, implicit 12) atacă din proprie
inițiativă orice jucător **wanted**: BOLO activ (`/straja bolo`), fugitiv
înregistrat (evadare din celulă/lagăr) sau marcă wanted moștenită. Reguli de
angajare: **niciodată** un suspect încătușat aflat în escortă (ofiterul în
raza lese) — lovitura e anulată chiar și între două cicluri de scanare;
niciodată un deținut în custodie sau un jucător în afara survival/adventure.
Arestarea rezolvă marcajele ca `RESOLVED`; eliberarea face la fel. Anularea
manuală a unui BOLO **nu** oprește urmărirea unui fugitiv înregistrat —
statutul de custodie rămâne adevărul autoritar.

### Recompense de stat (#231)

Doar **Inspectorul și Comisarul** pot pune recompense pe jucători:

```
/straja bounty post <jucător> <sumă> [motiv]
/straja bounty cancel <nume|id>
/straja bounty list
```

- Postarea creează automat un **BOLO legat** — ținta e vânată din mers la
  porți (wanted-on-sight). Pe o țintă fără dosar penal motivul e obligatoriu.
- Suma e în unități de bază (bronz = 1), între `[bounty] minAmount` /
  `maxAmount` (implicit 64–65536). TTL implicit 7 zile → `EXPIRED`.
- Orice civil poate captura ținta cu **Frânghia** — dar numai **doborâtă**
  sau **predată** (`/straja surrender`). Ofiterii ocolesc regula prin
  cătușe, nu prin frânghie.
- Captivul tras la o **poartă cu arest** intră în pipeline-ul complet de
  arest → statul plătește recompensa vânătorului → prizonierul primește o
  **cauțiune de 2×** recompensa (`fineMultiplier`), plătibilă în monede
  fizice de către oricine (`/straja bail`). Neachitată în
  `bailWindowHours` (implicit 24h) → **transfer automat în lagăr**
  (`defaultCampId`, sau primul lagăr dacă e gol).
- Anti-abuz: cel care a postat nu-și poate încasa propria recompensă;
  vânătorul offline la captură încasează la următorul login; o singură
  recompensă activă per țintă; plata e idempotentă (bon `bounty:<id>`).

### Datorii de deținut ([debt], DEBT)

Fiecare amendă duce un `paidAmount` și un istoric de contribuții
(`FINE_PAY` | `LEVY` | `CONTRIBUTION` | `BAIL`). Comenzile:

```
/straja debt <jucător>                 # soldul restant, rând cu rând (ofiterii văd orice ledger, civilii doar propriul)
/straja debt pay <jucător> [sumă]      # contribuție terță — fără sumă acoperă tot restul
/straja bail <jucător> [sumă]          # idem, dar servește întâi amenzile bounty_capture
```

```toml
[debt]
enabled = true                 # levy + poarta de eliberare
releaseBlockThreshold = 0      # sold peste prag blochează eliberarea (0 = orice datorie)
onBlocked = "CAMP"             # CAMP → lagărul implicit; CELL → refuz, rămâne în celulă
```

- **Levy (sechestru)** rulează la: emiterea unei amenzi pe un deținut, orice
  încercare de eliberare (înainte de poartă) și înainte de transferul în
  lagăr. Ordinea de tragere: buzunare live → dulap personal → rezervări
  `pendingLockers`; monedele ies valoare-descrescător, cu rest vărsat înapoi
  în cufăr când schimbul exact nu iese. Aplicarea pe amenzi e mereu
  **oldest-first** și scrie contribuții `LEVY`.
- **Poarta de eliberare:** sold > `releaseBlockThreshold` → `CAMP` mută
  deținutul în lagărul implicit (`[bounty].defaultCampId`, altfel primul
  înregistrat; fără niciun lagăr cade pe refuzul CELL); `CELL` refuză
  direct. Eliberarea Comisarului/op e `FORCED_RELEASE` și ocolește poarta.
- **Cărți emise:** „Proces-verbal de sechestru" (levy), „Refuz de eliberare"
  / „Ordin de transfer" (poartă), „Înștiințare de plată" (contribuții).
  Online se dau în mână; offline se pun la `pendingNotices` și se livrează
  la login.
- **Audit:** `levy` (`extracted=`/`applied=`/`remaining=`), `debt_apply`
  (`fineId=`/`applied=`/`source=`), `prison_release` REFUSED cu
  `debt_gate camp=<id> owed=<n> threshold=<n>` (sau `debt_gate cell …`),
  `camp_transfer` cu `source=debt_gate`, `notice_delivery`.

## 4. Verificare finală

```
/straja setup verify
```

Read-only: recheck-uiește checklist-ul, lanțul de stații și consistența
datelor (doctor). Verdictul „**Instalare funcțională — gata pentru
jucători.**" e criteriul de ieșire.

## 5. Primul jucător

Un jucător nou ajunge la Recepție → «Depune cererea» → examen la Instructor.
Tu (Comisarul) vezi cererea în inbox și o aprobi. Detalii complete:
**[manualul de gardă](guard-manual.md)** · **[player quickstart](player-quickstart.md)**.

## 6. Predarea unui build unui tester

Setează `protocol.enabled = true` în `config/straja-server.toml` — doar pe
lumea de test, nu în producție (protocolul acordă rang de ofițer și invocă un
suspect fals). Testerul rulează `/straja protocol start` și este ghidat prin
toate suprafețele: checkpoint, amendă, carieră, arest, datorie, vânătoare,
administrație. Primește cărți scrise cu pași și rezultate așteptate, iar
progresul se păstrează la relog. Opțional: protocolul își spawnează un
suspect fals («Suspectul») pentru capitolele de arest și vânătoare —
dismis automat la final.
