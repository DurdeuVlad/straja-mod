# NPC dialog trees

NPCs are brokers, not security principals. Every clickable action is a short-
lived, player-bound token and is revalidated by the target use case.

```text
Recepție
├─ admitere / regulament / statut
├─ acte, amenzi, cameră, facțiune nativă
└─ incidente și plângeri

Instructor
├─ examen de admitere
├─ instruire și progres
└─ cererea de promovare V2

Secretariat
├─ misiuni, carnet, incident și BOLO
├─ serviciu și roster
├─ dosar V2 și campanii active
└─ suprafață Comisar, doar când central authorization permite

Arhivă
├─ dosare și probe
└─ acte V2 ale jucătorului

Armurier
├─ oferte legacy compatibile
└─ registrul V2 de echipament
```

Forms carry only bounded input. Identity, status, authorization, document
validity, quota, and currency remain server-side records.
