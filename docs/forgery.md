# Falsificarea — Piața Neagră a Marcilor (#247)

Motorul de fals "Papers, Please": orice jucător poate încerca să bată o marcă
autentică, dar fără licența de Inspector rezultatul trece prin piramida de
calitate — și fiecare fals poartă defectul lui pe marca fizică.

## Cele două stații de lucru

### Nicovala — arme și artefacte reglementate

`seal_stamp` (slot dreapta) + obiect din `artifactRegistry.regulatedItemIds`
(slot stânga):

- **Inspector licențiat** → marcă autentică `#RC-<n>` + înregistrare PENDING
  în registru (maturare 24h). Cost: `forgery.anvilXpLicensed` niveluri XP.
- **Oricine altcineva** → se lansează piramida. Cost:
  `forgery.anvilXpUnlicensed` niveluri XP + 1 `seal_stamp` consumat per lovitură.

Fiecare lovitură produce **exact 1** obiect marcat (o stivă de intrare nu se
multiplică), iar costul efectiv este minim 1 nivel — nicovala vanilla refuză
ridicarea rezultatului la cost zero. Un obiect care poartă deja
`ArtifactSerial` nu poate fi re-bătut — rezultatul nu apare deloc în
nicovală. Zarul se aruncă DOAR în momentul în care jucătorul ia rezultatul;
previzualizarea nu dezvăluie nimic.

### Masa de faurire — documente și cărți

Rețeta copiatorului (trei rețete: card, document, instrument):

| Slot | Conținut |
|---|---|
| Template | **Exemplarul** — un document real, cu date de emitere (`IdentityCardId`, `DocumentId`, `InstrumentId` sau `ArtifactSerial`) |
| Bază | Stoc gol — `archive_document` pentru cărți, `official_envelope` pentru documente/instrumente |
| Adițiune | `carbon_paper` — consumat per încercare |

Exemplarul **nu se consumă** — o referință autentică inspiră oricâte copii.
Regula exemplarului este fizică: nu poți falsifica o clasă de document pe
care n-ai ținut-o niciodată în mână. Se dezactivează cu
`forgery.exemplarRequired = false` (doar pentru scenarii admin).

Rezultatul asamblat poartă un marcaj *în așteptare*; zarul se rezolvă în
momentul în care obiectul ajunge real în inventar — clic, shift-clic sau
orice altă cale de livrare — iar exemplarul se întoarce intact din copia de
rezervă inclusă în marcaj. O mișcare greșită a mouse-ului nu arde referința.

Pe traseul de faurire și inspectorii licențiați produc copii — dar copia lor
poartă datele de emitere ale exemplarului (nu e "fals", e o copie autorizată,
încă vizibil distinctă de original prin registrul din spate).

## Piramida calității (locked)

```
      ▲
   N1 3%   Aproape perfect — fură o serie reală alocată; perfectă la format,
           trădată doar de nepotrivirea obiect↔înregistrare la cross-check
   N2 7%   Fin — format perfect, serie puțin peste alocare (tipar ușor deschis)
   N3 15%  Acceptabil — serie cu format valid dar număr fantastic, absent
           din orice registru
   N4 30%  Grosolan — erori vizibile de format (`#RC-15_`, `#RC--15`, `#rc15`)
   N5 45%  Caraghios — marci absurde (`#RUSTY-GUN-99`); mașinile le prind
           din mers
```

~75% din falsuri mor la mașini (N4+N5), ~22% cer un ochi antrenat, iar cei 3%
din vârf sunt hârtie aproape reală — doar registrul le arde.

Greutățile sunt configurabile: `forgery.tierWeights = [3,7,15,30,45]`.
Orice valoare nevalidă revine la piramidă — configurația proastă nu poate
produce falsuri de calitate superioară.

## Adevărul fizic vs. adevărul registru

Un obiect falsificat **minte în NBT**: `ArtifactSerial` poartă seria
pretinsă (furată sau inventată), `ArtifactMark` poartă marca vizibilă
(defectă după nivel), iar `ArtifactForgery` reține nivelul intern.

Registrul ține adevărul separat: fiecare încercare scrie o **înregistrare
umbră** `FRG-n` cu `status=FORGED` — niciodată legală, niciodată în spațiul
seriilor autentice, dar păstrată ca pistă de audit: cine a falsificat, ce
marcă a produs, când. Fiecare serie pretinsă este indexată: un claim care
nu rezolvă spre o înregistrare autentică dar a fost prezentat de umbre este
`KNOWN_FORGED`, nu `ABSENT`. Fiecare lovitură emite și `artifact_forge` în
audit.

La cross-check (`checkClaim`): `AUTHENTIC` = claimul și înregistrarea se
potrivesc; `CONFLICT` = înregistrarea există dar descrie alt obiect **sau
alt deținător** — exact nepotrivirea care arde falsurile N1 (seria e reală,
proprietarul nu); `KNOWN_FORGED` = doar umbre au prezentat seria;
`ABSENT` = nimeni n-a pretins-o vreodată.

## Staging pentru administratori

`/straja identity forge <player> [N1..N5]` — creează un buletin contrafăcut
cu indiciu calibrat pe nivel (`card.forgeryTier` păstrat pentru bookkeeping).
Fără argument → comportamentul legacy cu indicii rotative.

## Configurație

```toml
[forgery]
enabled = true
tierWeights = [3, 7, 15, 30, 45]
anvilXpLicensed = 3
anvilXpUnlicensed = 5
exemplarRequired = true
```

Notă: `artifactRegistry.serialPrefix` nu poate fi `FRG-` — prefixul este
rezervat înregistrărilor umbră, iar configurația care l-ar folosi este
respinsă la încărcare.

## Detectarea — scannerul de poartă (#248)

Poarta (checkpoint) citește **doar marca fizică** — un scanner, nu un
arhivist:

- **fără marcă** pe obiect reglementat → `UNREGISTERED` → confiscare + flag
- **marcă absurdă** (`#GUNS4U-13`) → `CRUDE` → **arest la linia de arest**
- **marcă malformată** (`#RC-15_`, `#rc-15`, `#RC--15`) → `FLAGGED` →
  confiscare + BOLO
- **format plauzibil** (`#RC-<cifre>`) → **trece** — mașina nu vede N1–N3,
  exact cum e gândită piramida

Obiectele marcate pe sloturi nereglementate sunt ele înseși probe — marca
nu există decât prin registru sau prin făurărie.

Flag-urile pun în mișcare pipeline-ul complet: confiscare țintită pe
slotul exact — inclusiv gridul de crafting 2×2, itemul de pe cursor și
sloturile Curios — (evidence bag + chain), înregistrarea ofensei
`document_forgery`/`artifact_forgery`, intrarea de audit `forgery_detected`
(detector + verdict + item + seria reclamată), și un BOLO de sistem
`ARREST_AUTHORIZED` care vânează purtătorul. Flag-ul e idempotent — o
a doua lovire nu stivuiește BOLO-uri (dar falsul încă ajunge pe motivul
mandatului existent). Scanarea rulează pe toate suprafețele de control:
stage-1, stage-2, deny, îmbarcare, sosirea pe poarta legată, sensul
interzis și ieșirea escortată din lagăr. Poarta se poate opri prin
`artifactScanAtGates` / `forgery.scanAtGates` (override de policy).

Două excepții la regula „fără marcă = neînregistrat”: lada militară
sigilată (sigiliul Transportatorului **este** înregistrarea ei — dar doar
pe `straja:sealed_military_crate`, nu pe orice item cu un tag SealBy
aruncat peste) și rândurile `>deep` din stocări străine care nu își
expun datele (lipsa probei ≠ proba lipsei — scannerul nu confiscă ce
nu poate citi).

## Inspectorul NPC

Rol nou `inspector` — staționat ca orice NPC (`/straja npc assign` +
`bind-*`), cu plafon de expertiză propriu:

```text
/straja npc expertise <npc> <junior|veteran|expert>
```

Jucătorul apasă **„Prezint documentele la control”** în interfața NPC-ului;
inspectorul nararează indiciul văzut la fiecare obiect prins, confiscă,
înregistrează ofensa și ridică BOLO-ul. Curatul primește „Totul e în regulă”.

**Paritatea ofițerilor:** `/straja inspect <player>` — aceeași logică, dar
plafonul vine din rangul ofițerului jucător (permis 0, serviciul refuză
civilii și ofițerii care nu sunt în serviciu). Inspecția e hands-on:
ofițerul trebuie să stea lângă călător (~8 blocuri), iar Comisarul citește
la nivel EXPERT indiferent de starea de serviciu.

| Cine | Plafon | Ce prinde |
|------|--------|-----------|
| NPC junior / Străjer în serviciu | JUNIOR | claim-uri absurde de serie + falsuri cunoscute |
| NPC veteran / Străjer cu quiz trecut (sau Sergent+) | VETERAN | + serii „aproape emise” (următoarele 9 deasupra alocării, inclusiv chiar următorul număr) |
| NPC expert / Comisar | EXPERT | + conflicte de registru (serie reală, obiect/deținător greșit) |

## Ghidul de patrulă

```text
/straja book give [player]
```

Emite cartea zilei: tiparul autentic `#<PREFIX>-NNN`, semnele sigure de
fals, și **3 exemplare de defect rotativ pe zi** (determinist — aceeași zi
arată aceleași exemple peste tot). Cartea e ștampilată `StrajaPatrolDay`;
edițiile vechi rămân lizibile dar datate — nu se actualizează niciodată.
Reemiterea aceleiași ediții e refuzată. Ghidul descrie indicii vizibile,
niciodată nume de trepte sau ponderi.
