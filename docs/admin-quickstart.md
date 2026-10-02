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
