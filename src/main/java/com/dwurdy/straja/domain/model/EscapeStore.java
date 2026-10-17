package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/** FUN-9: durable escape-prep state — tunnel dig progress per block. */
public class EscapeStore {
    public List<TunnelRecord> tunnels = new ArrayList<>();
    private int nextTunnel = 1;

    public void ensure() {
        if (tunnels == null) tunnels = new ArrayList<>();
        if (nextTunnel < 1) nextTunnel = 1;
    }

    public String nextTunnelId() {
        return "tun-" + (nextTunnel++);
    }

    public TunnelRecord at(String dimension, int x, int y, int z) {
        ensure();
        for (TunnelRecord t : tunnels) {
            if (t != null && t.dimension.equals(dimension)
                    && t.x == x && t.y == y && t.z == z) return t;
        }
        return null;
    }

    public List<TunnelRecord> inCell(String cellId) {
        List<TunnelRecord> out = new ArrayList<>();
        if (cellId == null) return out;
        ensure();
        for (TunnelRecord t : tunnels) {
            if (t != null && cellId.equals(t.cellId)) out.add(t);
        }
        return out;
    }

    /** Drops oldest discovered first, then the stalest abandoned digs. */
    public boolean prune(int retentionLimit) {
        ensure();
        if (retentionLimit <= 0 || tunnels.size() <= retentionLimit) return false;
        boolean removed = tunnels.removeAll(tunnels.stream()
                .filter(t -> t != null && t.discoveredAt > 0)
                .sorted((a, b) -> Long.compare(a.discoveredAt, b.discoveredAt))
                .limit(tunnels.size() - retentionLimit)
                .toList());
        int excess = tunnels.size() - retentionLimit;
        if (excess > 0) {
            removed |= tunnels.removeAll(tunnels.stream()
                    .sorted((a, b) -> Long.compare(a.lastDigAt, b.lastDigAt))
                    .limit(excess)
                    .toList());
        }
        return removed;
    }
}
