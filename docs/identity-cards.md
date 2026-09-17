# Buletinul Straja

Buletinul este un item fizic cu `stacksTo(1)`, dar itemul nu este sursa de adevăr. El poartă doar ID-ul cardului (`ID-n`). Registrul serverului păstrează titularul după UUID, emitentul, perioada de valabilitate și revocarea. Redenumirea sau copierea itemului nu schimbă aceste date.

## Fluxul normal

La NPC-ul Recepție:

- `Emite buletinul meu` emite cardul doar dacă jucătorul este online și se află la cel mult 6 blocuri de locația configurată `receptionist`.
- `Verifică buletinele` listează cardurile proprii. Titularul își poate verifica buletinul ținând itemul în mână și folosindu-l.

Un Comisar sau operator poate folosi:

```text
/straja identity list
/straja identity issue <player>
/straja identity revoke <ID-n> <motiv>
```

Comisarul, operatorii și Străjerii aflați la datorie pot verifica orice card. Un card expirat nu este acceptat ca valid, iar unul revocat rămâne revocat și motivul este păstrat în audit.

## Configurație

În config-ul serverului:

```toml
[identityCards]
enabled = true
validityDays = 30
```

Valabilitatea este calculată la emitere. Dacă inventarul titularului este plin, emiterea eșuează fără să creeze o înregistrare și fără să consume numărul următor.
