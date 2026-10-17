package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Durable store for transfer rides. Gson-deserialized — normalize before use. */
public class TransferStore {
    public List<TransferRecord> transfers = new ArrayList<>();
    public int nextNumber = 1;

    public void ensure() {
        if (transfers == null) transfers = new ArrayList<>();
        transfers.removeIf(r -> r == null || r.id == null || r.id.isBlank());
        for (TransferRecord r : transfers) {
            if (r.escorts == null) r.escorts = new ArrayList<>();
            if (r.status == null || r.status.isBlank()) r.status = "ORDERED";
        }
        if (nextNumber < 1) nextNumber = 1;
    }

    public String nextId() {
        return "TR-" + (nextNumber++);
    }

    public TransferRecord find(String id) {
        if (id == null) return null;
        for (TransferRecord r : transfers) {
            if (r != null && id.equals(r.id)) return r;
        }
        return null;
    }

    /** Live transfer for a prisoner — one active ride per prisoner. */
    public TransferRecord activeFor(String prisonerUuid) {
        if (prisonerUuid == null || prisonerUuid.isBlank()) return null;
        for (TransferRecord r : transfers) {
            if (r != null && r.isLive() && prisonerUuid.equalsIgnoreCase(r.prisonerUuid)) return r;
        }
        return null;
    }

    /** Drops terminal records beyond the retention bound, oldest first. */
    public boolean pruneTerminal(int retention) {
        int terminal = 0;
        for (TransferRecord r : transfers) {
            if (r != null && !r.isLive()) terminal++;
        }
        int excess = terminal - Math.max(1, retention);
        if (excess <= 0) return false;
        Iterator<TransferRecord> it = transfers.iterator();
        while (it.hasNext() && excess > 0) {
            TransferRecord r = it.next();
            if (r != null && !r.isLive()) { it.remove(); excess--; }
        }
        return true;
    }
}
