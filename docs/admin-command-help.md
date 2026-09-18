# Help administrativ Straja

Suprafața `/straja` este pentru administratori și consolă/RCON. Jucătorii
obișnuiți folosesc NPC-urile, obiectele și acțiunile clickable; ruta internă
`/straja npc-action <token>` nu este listată în help.

## Niveluri de acces

- **OP 3** — comenzi administrative obișnuite: recrutare, personal, serviciu,
  misiuni, contenție, închisoare, amenzi, plângeri, camere, arhivă,
  legitimații, urgențe, backup și help.
- **OP 4** — setup și operare sensibilă: modificarea checkpoint-urilor,
  locații, politici,
  migrare, administrare NPC, debug și test.
- **Public** — `status`, `rules`, `regulament` și `stop`. Ajutorul rămâne
  protejat la OP 3, inclusiv pentru comenzile publice.

## Cum se folosește

```text
/straja help
/straja archive help
/straja archive folder help
/straja identity forge help
/straja setup help
```

Fiecare nod al arborelui are sufixul `help`, inclusiv după argumente:

```text
/straja identity issue help
/straja identity issue Jucator help
```

Help-ul arată descrierea nodului, nivelul cerut și subcomenzile permise pentru
executorul curent. La OP 3, rutele OP 4 sunt ascunse din index și din listele
de subcomenzi; ele nu sunt doar marcate ca inaccesibile după execuție.

## Categorii

| Categorie | Exemple |
| --- | --- |
| Informare | `status`, `rules`, `regulament`, `stop` |
| Personal | `invite`, `recruit`, `quiz`, `promote`, `suspend`, `reinstate` |
| Serviciu | `start`, `special`, `resign`, `salary`, `merit` |
| Operațiuni | `mission`, `cuffs`, `prison`, `fine`, `complaint`, `room`, `emergency` |
| Arhivă și identitate | `archive`, `identity` |
| Setup OP 4 | `checkpoint add/remove`, `setup`, `policy`, `set-location`, `migrate` |
| Operare OP 4 | `npc`, `debug`, `test` |

Descrierile din acest document și cele afișate în joc trebuie menținute în
aceeași limbă și cu aceleași niveluri. Politica numerică este centralizată în
`CommandPermissions`; renderer-ul help-ului doar proiectează arborele Brigadier.
