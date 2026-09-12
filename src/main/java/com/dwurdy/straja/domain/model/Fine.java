package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A fine, its appeal, and the payment-attempt boundary record. Mirrors the
 * KubeJS civic schema so migration can map records one-to-one.
 */
public class Fine {
    public String id = "";               // F<n>
    public String target = "";
    public String targetUuid = "";
    public String issuer = "";
    public String issuerUuid = "";
    public String law = "";
    public String description = "";
    public int amount;
    /** ISSUED|DELIVERY_FAILED|PAYMENT_REVIEW|PAID|APPEAL_PENDING|ESCALATED|
     *  ARREST_PENDING|IN_SENTENCE|GRACE_AFTER_SENTENCE|WAIVED|CANCELLED */
    public String status = "ISSUED";
    public long issuedAt;
    public int onlineDays;
    public long onlineGraceMs;
    public long onlineElapsedMs;
    public long onlineLastTickAt;
    public int recoveryCycle = 1;
    public String taskId;
    public Long paidAt;
    public Long escalatedAt;
    public Long waivedAt;
    public Long graceStartedAt;
    public String sentenceId;
    public Long appealSubmittedAt;
    public Long appealLastTickAt;
    public Long paymentReviewResolvedAt;
    public Appeal appeal;
    public PaymentAttempt paymentAttempt;

    public static class Appeal {
        /** PENDING | UPHELD | REDUCED | VOID | AUTO_WAIVED */
        public String status = "PENDING";
        public String id = "";           // FA-F<n>
        public long filedAt;
        public long deadlineAt;
        public String reason = "";
        public String filedBy = "";
        public String filedByUuid = "";
        public String previousStatus = "";
        public int previousAmount;
        public Long reviewedAt;
        public String reviewedBy;
        public String reviewedByUuid;
        public String decision;
        public String decisionReason = "";
        public Integer reducedAmount;
    }

    /** Boundary record for the physical coin withdrawal. */
    public static class PaymentAttempt {
        public int amount;
        public List<PlanEntry> plan = new ArrayList<>();
        public String player = "";
        public String playerUuid = "";
        public long startedAt;
        public String previousStatus = "";
        public int removedValue;
        public int removedEntries;
        public String error = "";
        public boolean sideEffectUnknown;
    }

    public static class PlanEntry {
        public int value;
        public String id = "";
        public int count;
    }
}
