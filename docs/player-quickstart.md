# Ghid rapid pentru jucători

Ai intrat pe server și nu știi nimic despre Straja? Perfect — pagina asta te
duce de la zero până la **cererea de admitere depusă**. Totul se face în joc,
fără să întrebi pe nimeni.

## Ce este Straja

Straja este forța de ordine a serverului — recrutează membri, patrulează și
rezolvă incidente. Interacționezi cu ea prin **NPC-uri Straja** (meniuri
clickabile în chat), **obiecte fizice** și **formulare native**.

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
| `/straja debt <jucător>` | vezi datoriile restante — ofiterii văd pe ale oricui, tu pe ale tale |
| `/straja debt pay <jucător> [sumă]` | achiți din datoriile cuiva — oricine poate plăti pentru oricine |
| `/straja stop` | încheie serviciul (doar după ce ești membru și ești în tură) |

## Primul pas: cererea de admitere

1. **Mergi la Recepție** (Recepționista). Fiecare NPC Straja afișează un meniu
   în chat; acțiunea pe care o cauți e prima din listă.
2. Apasă **«Depune cererea»** — completezi formularul și-l trimiți.
3. Cererea ta intră la Comisar. După acceptare (sau dacă ești invitat
   direct), **prezintă-te la Instructor pentru examen** — un scurt quiz
   despre regulament.
4. Treci examenul → devii **Stagiar** și începi cariera: serviciu la
   Secretară, patrule, avansare.

## Dacă ești trimis la lagărul de muncă

Un deținut în lagăr minesz și vinde minereul la intendent — încasările nu vin
în mână, ci în **contul de muncă**. Când contul atinge prețul libertății
(`flat` sau un multiplicator al amenzilor tale), ești eliberat automat și îți
recuperezi lucrurile. `/straja camp status` îți arată progresul. Nu ieși din
perimetru fără escortă — devii fugitiv căutat.

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
- **Oricine poate plăti pentru tine.** `/straja debt <nume>` arată soldul;
  `/straja debt pay <nume> [sumă]` virează monedele plătitorului pe
  amenzile tale — fără sumă se acoperă tot restul. Cauțiunea e tot o
  contribuție: `/straja bail <nume>` plătește întâi amenda de captură, apoi
  restul datoriilor. Fiecare plată îți aduce o „Înștiințare de plată" cu
  cine a plătit, pe ce amendă și cât a rămas.
- **Cărțile te așteaptă.** Dacă ești offline când curge orice pas, cărțile
  se pun la coadă și le primești la următorul login.
- Comisarul/op poate oricând elibera administrativ, ocolind datoria.

## Recompense: vânat sau vânător

Când Inspectorul sau Comisarul pune o recompensă pe un jucător, acel jucător
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
te ghidează pas cu pas prin fiecare suprafață a modului. Rulează
**`/straja protocol start`**: primești dosarul și, capitol cu capitol,
cărți scrise cu pași exacți și rezultate așteptate — checkpoint, amendă,
carieră, arestul unui suspect fals invocat de protocol, datorie, vânătoare,
administrație. Progresul se păstrează la relog.

- `/straja protocol` — status și capitolul curent
- `/straja protocol next` / `back` — avansează / recitește capitolul
- `/straja protocol actor` — cheamă sau demite suspectul de test
- `/straja protocol reset` — reia de la zero · `stop` — abandonează

La final, protocolul sigilează dosarul și îți cere raportul: ce a funcționat,
ce nu, comanda și mesajul exact pentru fiecare abatere.

## Mai departe

Pentru detalii complete despre carieră, misiuni, cătușe, pușcărie, arhivă și
restul sistemelor, vezi **[manualul complet](guard-manual.md)**.
