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

## Mai departe

Pentru detalii complete despre carieră, misiuni, cătușe, pușcărie, arhivă și
restul sistemelor, vezi **[manualul complet](guard-manual.md)**.
