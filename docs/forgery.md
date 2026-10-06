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

Un obiect care poartă deja `ArtifactSerial` nu poate fi re-bătut — rezultatul
nu apare deloc în nicovală. Zarul se aruncă DOAR în momentul în care jucătorul
ia rezultatul; previzualizarea nu dezvăluie nimic.

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

Pe traseul de faurire și inspectorii licențiați produc copii — dar copia lor
oglindește datele exemplarului (nu e "fals", e o copie autorizată, încă
vizibil distinctă de original prin registrul din spate).

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
marcă a produs, când. Fiecare lovitură emite și `artifact_forge` în audit.

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

## Ce NU face M2 (vine în #248 / M3)

Scanere la porți, inspectori NPC, cărțile de patrulă și pipeline-ul
ofensă→BOLO→arest. M2 produce falsurile și adevărul din registru; M3 le
prinde.
