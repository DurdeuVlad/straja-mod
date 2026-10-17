package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * FUN-10: durable job bookkeeping — per-prisoner cooldowns and completed
 * turn-ins. Idempotency anchor: every settle writes a settled run record
 * keyed by a caller-supplied token; a retried turn-in finds the token and
 * refuses instead of double-paying. Gson-deserialized — normalize.
 */
public class JobStore {
    public List<JobRun> runs = new ArrayList<>();
    /** prisonerUuid -> jobId -> earliest next allowed epoch ms. */
    public List<Cooldown> cooldowns = new ArrayList<>();
    /** Recent prisoner-to-prisoner trades — the seizure/trade ledger. */
    public List<TradeEntry> trades = new ArrayList<>();

    public void normalize() {
        if (runs == null) runs = new ArrayList<>();
        if (cooldowns == null) cooldowns = new ArrayList<>();
        if (trades == null) trades = new ArrayList<>();
    }

    public boolean hasRun(String token) {
        if (token == null || token.isBlank()) return false;
        for (JobRun r : runs) {
            if (r != null && token.equals(r.token)) return true;
        }
        return false;
    }

    public long cooldownAt(String uuid, String jobId) {
        if (uuid == null || jobId == null) return 0;
        for (Cooldown c : cooldowns) {
            if (c != null && uuid.equalsIgnoreCase(c.uuid)
                    && jobId.equalsIgnoreCase(c.jobId)) return c.nextAt;
        }
        return 0;
    }

    public void setCooldown(String uuid, String jobId, long nextAt) {
        if (uuid == null || jobId == null) return;
        for (Cooldown c : cooldowns) {
            if (c != null && uuid.equalsIgnoreCase(c.uuid)
                    && jobId.equalsIgnoreCase(c.jobId)) {
                c.nextAt = nextAt;
                return;
            }
        }
        Cooldown c = new Cooldown();
        c.uuid = uuid;
        c.jobId = jobId;
        c.nextAt = nextAt;
        cooldowns.add(c);
    }

    public int completedCount(String uuid) {
        if (uuid == null) return 0;
        int n = 0;
        for (JobRun r : runs) {
            if (r != null && uuid.equalsIgnoreCase(r.uuid)) n++;
        }
        return n;
    }

    /** Keep the durable ledger bounded — oldest entries drop first. */
    public void prune(int keepRuns, int keepTrades) {
        normalize();
        if (keepRuns >= 0 && runs.size() > keepRuns) {
            runs = new ArrayList<>(runs.subList(runs.size() - keepRuns, runs.size()));
        }
        if (keepTrades >= 0 && trades.size() > keepTrades) {
            trades = new ArrayList<>(trades.subList(trades.size() - keepTrades, trades.size()));
        }
    }

    public static class JobRun {
        public String token = "";
        public String uuid = "";
        public String jobId = "";
        public String postId = "";
        public int paid;
        public long at;
    }

    public static class Cooldown {
        public String uuid = "";
        public String jobId = "";
        public long nextAt;
    }

    /** A settled market trade — goods for labor credit or a favor IOU. */
    public static class TradeEntry {
        public String id = "";
        public String sellerUuid = "";
        public String sellerName = "";
        public String buyerUuid = "";
        public String buyerName = "";
        public String itemId = "";
        public int count;
        public int price;
        public boolean favor;
        public long at;
    }
}
