# Ghid rapid pentru jucători

Ai intrat pe server și nu știi nimic despre Straja? Perfect — pagina asta te
duce de la zero până la **cererea de admitere depusă**. Totul se face în joc,
fără să întrebi pe nimeni.

## Ce este Straja

Straja este poliția și armata garnizoanei — recrutează membri, patrulează,
rezolvă incidente și apără tărâmul. Interacționezi cu ea prin **NPC-uri Straja**
(birouri cu interfață proprie — dai click dreapta și se deschide un panou cu
acțiuni), **obiecte fizice** și **formulare native**. Meniurile clickabile din
chat rămân rezerva — dacă biroul nu se poate deschide (sau serverul a oprit
`npcMenus.enabled`), primești aceleași acțiuni ca rânduri clickabile.

## Comenzile tale (mereu disponibile, fără drepturi speciale)

| Comandă | Ce face |
|---|---|
| `/straja` | orientare pentru jucători — punctul de pornire dacă te-ai pierdut |
| `/straja help` sau `/straja ajutor` | același meniu de orientare |
| `/straja status` | starea și rangul tău în Strajă |
| `/straja rules` sau `/straja regulament` | regulamentul serverului |
| `/straja bounty list` | recompensele active — cine e vânat și pentru cât |
| `/straja surrender` | te predai unui vânător din apropiere (oprește urmărirea) |
| `/straja bail <jucător>` | plătești cauțiunea cuiva din recompensă — oricine poate plăti pentru oricine |
| `/straja debt <jucător>` | vezi datoriile restante — ofițerii văd pe ale oricui, tu pe ale tale |
| `/straja debt pay <jucător> [sumă]` | achiți din datoriile cuiva — oricine poate plăti pentru oricine |
| `/straja stop` | încheie serviciul (doar după ce ești membru și ești în tură) |

## Primul pas: cererea de admitere

1. **Mergi la Recepție** (Recepționista). Click dreapta pe NPC deschide
   biroul — o grilă de acțiuni cu iconițe și nume; fiecare acțiune își spune
   ce face când treci cu mouse-ul peste ea.
   (Dacă biroul nu se deschide, același meniu apare în chat.)
2. Apasă **«Depune cererea»** — completezi formularul și-l trimiți.
3. Cererea ta intră la Comisar. După acceptare (sau dacă ești invitat
   direct), **prezintă-te la Instructor pentru examen** — 6 întrebări despre
   regulament, în ordine fixă; treci cu minimum 5 corecte. Greșești sub 5 →
   iei pauză și reiei tot examenul.
4. Treci examenul → devii **Stagiar** și începi cariera: serviciu la
   Secretară, patrule, avansare.

## Cariera: două intrări, un corp de ofițeri

- **Soldat (normă parțială):** Stagiar → Străjer — intri prin examen.
- **Meseriaș (normă parțială):** Zilier → Meseriaș → Maistru — la Recepție apeși
  **«Meseriași — înscriere»**, alegi meseria și depui **cerere de muncă** cu
  motiv; Comisarul o aprobă sau respinge tot la Recepție.
- **Ofițer (normă întreagă):** Locotenent → Căpitan → Colonel — din Străjer
  sau Maistru, prin cerere de avansare.

**Orice avansare** se depune la **Secretară** («Cere avansarea», cu motiv
scris). Comisarul citește cererea și o aprobă sau respinge — blocurile de
serviciu și modulele de instruire sunt orientative, nu praguri. Ofițerii
împart aceleași drepturi indiferent de grad; doar Comisarul numește și
decide cereri.

## Armata: unitatea și operațiile

Straja e și armata garnizoanei. La **Comandant** (stă în curtea cazărmii,
la ancora HQ) orice membru de pe traseul militar — trupă sau ofițer — își
găsește unitatea:

1. **Înscrie-te în unitate** — un click la biroul Comandantului. Unitatea
   stației tale se organizează singură la prima înscriere, iar primul
   ofițer înscris preia comanda automat.
2. **Înrolează-te la operație** — biroul îți arată următoarea operație
   programată (instrucție, patrulare sau exercițiu) cu numărătoarea
   inversă; un click și ești pe listă.
3. **Prezintă-te la muster** — când operația pornește, același birou îți
   oferă «Prezintă-te». Poți veni și fără înscriere — musterul numără
   cine a venit, nu cine a promis.
4. **Restul e automat.** Operația se încheie singură la finalul ferestrei:
   fiecare participant prezent primește **blocuri de serviciu** (aceeași
   monedă de avansare ca la misiuni), iar **pregătirea unității** crește
   cu prezența. Eșecul doare doar când cineva a promis și n-a venit —
   o operație automată pe care n-o calculează nimeni se anulează în
   liniște, fără penalizare.
   Fără ofițer disponibil, unitatea își planifică singură următoarea operație.
5. **Verifică oricând** «Stare unitate» (comandant, efectiv, pregătire,
   operația curentă) și «Istoric operații» la același birou.
6. **«Părăsește unitatea»** cere confirmare DA/NU — plecarea îți anulează
   și înscrierile la operații.

Ofițerii unității au în plus trei acțiuni de planificare la același birou
(instrucție / patrulare / exercițiu), iar dacă comandantul pleacă, comanda
trece automat la următorul ofițer al unității. Civilii și meseriașii văd
doar informațiile publice ale biroului.

## Dacă ești trimis la lagărul de muncă

Un deținut în lagăr minează și vinde minereul la intendent — încasările nu vin
în mână, ci în **contul de muncă**. Când contul atinge prețul libertății
(`flat` sau un multiplicator al amenzilor tale), ești eliberat automat și îți
recuperezi lucrurile. Biroul deținerii (Paznicul) îți arată progresul ca
acțiune «Statutul taberei de muncă» — `/straja camp status` rămâne ca rezervă.
Nu ieși din perimetru fără escortă — devii fugitiv căutat.

## Datorii în custodie (amenzi neplătite)

O amendă neachitată nu dispare la arest — devine **datorie de deținut**:

- **Sechestru automat (levy).** Un deținut cu datorii nu poate ține monede:
  la fiecare amendă nouă primită în custodie, la orice încercare de eliberare
  și înainte de fiecare transfer în lagăr, statul sechestrează monedele din
  buzunare, apoi pe cele din **dulapul tău personal** (inclusiv rezervările
  puse deoparte cât ai fost offline) și le virează pe amenzi **de la cea mai
  veche la cea mai nouă**. Primești o carte „Proces-verbal de sechestru" cu
  suma luată și unde a ajuns — monedele sechestrate nu se mai restituie.
- **Eliberarea e blocată peste prag.** Dacă datoria rămasă depășește pragul
  de eliberare (implicit orice sold pozitiv), cererea de eliberare e
  refuzată și — în configurația implicită — ești **mutat în lagărul de
  muncă**, unde contul de muncă merge spre prețul libertății. Primești un
  „Ordin de transfer"; la blocare în celulă primești „Refuz de eliberare".
- **Oricine poate plăti pentru tine.** La biroul deținerii ai rândurile
  «Datoriile tale», «Plătește din datorie» și «Plătește cauțiunea» —
  fiecare cere confirmare DA/NU înainte să miște bani. Comenzile rămân ca
  rezervă: `/straja debt <nume>` arată soldul;
  `/straja debt pay <nume> [sumă]` virează monedele plătitorului pe
  amenzile tale — fără sumă se acoperă tot restul. Cauțiunea e tot o
  contribuție: `/straja bail <nume>` plătește întâi amenda de captură, apoi
  restul datoriilor. Fiecare plată îți aduce o „Înștiințare de plată" cu
  cine a plătit, pe ce amendă și cât a rămas.
- **Cărțile te așteaptă.** Dacă ești offline când curge orice pas, cărțile
  se pun la coadă și le primești la următorul login.
- Comisarul/op poate oricând elibera administrativ, ocolind datoria.

## Recompense: vânat sau vânător

Când un ofițer sau Comisarul pune o recompensă pe un jucător, acel jucător
devine **vânat** — porțile cu arest îl prind din mers și orice jucător îl
poate captura.

**Ca vânător:** ai nevoie de **Frânghie** (și opțional **Sac** pentru cap).
Frânghia prinde doar dacă ținta e **doborâtă** sau **predată** — nu poți lega
pe cineva în plină luptă. După ce e legată, ținta rămâne atașată de tine:
târăște-o la orice punct de control cu arest, iar poarta îl bagă direct în
arestul complet. La predare, statul îți plătește recompensa — prizonierul
primește o amendă de **2× recompensa** drept cauțiune.

**Ca vânat:** dacă fugi prea departe de vânător, frânghia cedează și scapi.
Sau te predai cu `/straja surrender` când vânătorul e aproape — economisește
lupta, consecințele sunt aceleași. La arest poți fi achitat cu
`/straja bail <numele tău>` (tu sau orice prieten cu monedele exacte);
neachitat în termen → **lagărul de muncă**.

## Dacă ceva nu merge

- Orice refuz îți spune **de ce** și **unde să mergi** — mesajele roșii se
  termină întotdeauna cu o recomandare concretă (de ex. «→ Vezi
  Recepționista.»).
- Fiecare NPC Straja are în meniu opțiunea **«Am o întrebare»** — un FAQ
  adaptat stării tale actuale.
- `/straja` oricând — îți reamintește primul pas.

## Ești tester? Protocolul ghidat

Pe build-urile de test există **`/straja protocol`** — un dosar oficial care
te ghidează pas cu pas prin fiecare suprafață a modului. Trebuie să ai drepturi
de operator (OP pe server, sau cheats pornit în singleplayer) — **fără editări
de config**. Rulează **`/straja protocol start`**: protocolul pregătește singur
ce lipsește (poartă de test, celulă, lagăr) în jurul tău, apoi primești dosarul
și, capitol cu capitol, cărți scrise cu pași exacți și rezultate așteptate —
checkpoint, amendă, carieră, arestul unui suspect fals invocat de protocol,
datorie, vânătoare, administrație. Progresul se păstrează la relog.

- `/straja protocol` — status și capitolul curent
- `/straja protocol next` / `back` — avansează / recitește capitolul
- `/straja protocol actor` — cheamă sau demite suspectul de test
- `/straja protocol reset` — reia de la zero · `stop` — abandonează

La final, protocolul sigilează dosarul și îți cere raportul: ce a funcționat,
ce nu, comanda și mesajul exact pentru fiecare abatere.

## Mai departe

Pentru detalii complete despre carieră, misiuni, cătușe, pușcărie, arhivă și
restul sistemelor, vezi **[manualul complet](guard-manual.md)**.
