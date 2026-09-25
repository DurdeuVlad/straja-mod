package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

public class Settlement {
    public String settlementId = "";
    public String settlementKey = "";
    public String playerUuid = "";
    public SettlementCategory category = SettlementCategory.MISSION_REWARD;
    public long amount;
    public List<String> evidenceIds = new ArrayList<>();
    public String periodId = "";
    public String workUnitId = "";
    public SettlementStatus status = SettlementStatus.PENDING;
    public List<PayoutAttempt> payoutAttempts = new ArrayList<>();
    public long createdAt;
    public Long paidAt;
    public long version;

    public enum SettlementCategory { JOB, WEEKLY_STIPEND, MOBILIZATION, LEGACY_SALARY, MISSION_REWARD }
    public enum SettlementStatus { PENDING, RESERVED, IN_PROGRESS, PAID, FAILED_RETRYABLE, REVIEW, VOID }

    public static class PayoutAttempt {
        public String attemptId = "";
        public long startedAt;
        public String status = "";
        public String receiptReference = "";
        public String error = "";
    }
}
