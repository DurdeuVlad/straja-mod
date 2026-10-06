# Registrul Central al Garnizoanei

Registrul de artefacte este sursa de adevăr pentru obiectele reglementate:
arme, documente oficiale și alte bunuri care poartă o marcă de serie Straja.
La înregistrare, itemul fizic primește în datele lui marca (`ArtifactSerial`,
`ArtifactMark` = `#RC-<n>`), iar registrul serverului păstrează titularul după
UUID, emitentul, momentul înregistrării și revocarea. Redenumirea sau copierea
itemului nu schimbă aceste date — legătura reală este seria din datele itemului
versus înregistrarea din registru.

## Marcarea și maturarea

Un artefact nou înregistrat primește o marcă serială alocată de registru în
formatul `<prefix><număr>` (prefixul implicit `RC-`, deci `RC-1`, `RC-2`, …).
Înregistrarea nu înseamnă legalitate imediată: obiectul stă în `pending` timp
de `pendingMaturationHours` (implicit 24 de ore reale). Până la maturare,
verificările de legalitate îl tratează ca încă nelegal — legalitatea costă o
zi, iar asta este presiunea care face piața neagră să existe.

După maturare artefactul devine `active`. Revocarea îl face permanent nelegal,
dar înregistrarea rămâne în registru împreună cu motivul.

## Licențe

Două licențe guvernează suprafețele autorizate:

- **Inspector** — poate înregistra și marca artefacte autentice cu
  `/straja inspector register`. Lovitura de nicovală ca verb fizic sosește în
  milestone-ul M2.
- **Transporter** — poate sigila lăzi militare. Un transportator licențiat cu o
  ladă sigilată este culoarul legal prin punctele de control (integrarea cu
  scanerele sosește în M3).

Licențele se administrează de autoritate (OP 3 — Comisar sau operatori):

```text
/straja inspector grant|revoke <jucător>
/straja transporter grant|revoke <jucător>
/straja inspector list | /straja transporter list
```

Orice jucător își poate verifica propriile licențe cu `/straja license`,
`/straja inspector status` sau `/straja transporter status`.

## Înregistrarea și verificarea artefactelor

```text
/straja inspector register [jucător]  — un inspector licențiat (sau o
                                        autoritate, pentru staging) înregistrează
                                        itemul din mâna lui și îi imprimă marca
                                        serială; fără argument se înregistrează
                                        pentru sine, cu argument îl certifică
                                        pentru jucătorul dat
/straja artifact check <marcă>        — adevărul din registru pentru o marcă
                                        (autoritate, OP 3)
/straja artifact revoke <marcă>       — revocare (autoritate)
```

`register` respinge un obiect care poartă deja o marcă — o armă nu poate
colecționa serii. Dacă marcarea fizică eșuează, înregistrarea este revocată
automat: registrul nu pretinde niciodată o marcă pe care niciun item nu o
poartă. Suprafețele de inspecție pentru titulari și ofițeri la datorie (citirea
mărcii de pe item direct) sosesc în M3; în M1 `check` este unealta autorității.
Această verificare item-vs-registru este detectorul de minciuni pe care se
sprijină întreaga economie a falsurilor: obiectul pretinde un lucru, registrul
știe altul.

## Sigilarea lăzilor

```text
/straja transporter seal    — sigilează lada militară ținută în mână
/straja transporter unseal  — desigilează propria ladă (autoritatea poate orice)
```

Sigiliul este dată pe item (`SealBy`, `SealName`, `SealId`, `SealAt`), nu un
item separat — lada sigilată rămâne aceeași ladă. Desigilarea unei lăzi sigilate
de altcineva este refuzată transportatorilor; autoritățile pot.

## Configurație

În config-ul serverului:

```toml
[artifactRegistry]
    enabled = true
    pendingMaturationHours = 24
    serialPrefix = "RC-"
    regulatedItemIds = []   # id-urile de item considerate arme reglementate
```

`regulatedItemIds` decide clasificarea `weapon` vs `document` vs `other` la
înregistrare; documentele oficiale Straja sunt recunoscute automat.

## Audit și drumul spre falsuri

Fiecare acordare sau revocare de licență, înregistrare, revocare și sigilare
lasă o înregistrare de audit. Acest registru este fundația sistemului de
falsuri „Papers, Please" din #245: falsurile din M2 poartă marci malformate sau
serii care nu apar în acest registru, iar detectarea din M3 compară marca fizică
cu adevărul serverului.
