package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistent Straja mission (order). Field names mirror the reference
 * KubeJS schema so migration preserves the semantics one-to-one.
 */
public class Mission {
    public String id = "";
    public String issuer = "";
    public String issuerUuid = "";
    public int issuerRank;
    public String target = "";
    public String targetUuid = "";
    public List<Identity> assignees = new ArrayList<>();
    public List<Identity> invited = new ArrayList<>();
    public List<Identity> declined = new ArrayList<>();
    public int minimumRank = 1;
    public int maxAssignees = 1;
    public String objective = "";
    public int minutes;
    public int reward;
    /** NONE | PENDING | PAID | PARTIAL | PAYMENT_IN_PROGRESS | PAYMENT_FAILED | PAYMENT_REVIEW */
    public String rewardStatus = "NONE";
    public String rewardPayoutId = "";
    public int rewardPool;
    public int copyIndex = 1;
    public String issuerBudgetKey = "";
    public int issuerBudgetAmount;
    /** NONE | RESERVED | RELEASED */
    public String issuerBudgetStatus = "NONE";
    public Long issuerBudgetReleasedAt;
    public long startAt;
    public String startLabel = "acum";
    public long createdAt;
    public long dueAt;
    /** ISSUED | ACCEPTED | REPORTED | COMPLETED | DECLINED | FAILED | EXPIRED | DELIVERY_FAILED | CANCELLED_ROLE_CHANGE */
    public String status = "ISSUED";
    public Long acceptedAt;
    public Long reportedAt;
    public Long completedAt;
    public Long failedAt;
    public Long expiredAt;
    public Long cancelledAt;
    public String failureReason = "";
    public String cancellationReason = "";
    public String report = "";
    public String signedBy = "";
    public Long signedAt;
    public Long packagedAt;
    /** PENDING | ENVELOPE | ENVELOPE_PACKAGE | CHAT_FALLBACK | FAILED | DELIVERY_FAILED */
    public String delivery = "PENDING";
    public String deliveryError = "";
    public String draftKey = "";
    public Map<String, RewardClaim> rewardClaims = new LinkedHashMap<>();
    public Long rewardPaidAt;
    public Long rewardRecoveryRequestedAt;
    public String rewardError = "";
    public int rewardDeliveredDenominations;
    /** Origin marker for missions created by other subsystems (fines, warrants). */
    public String origin = "SECRETARY";

    public static class Identity {
        public String uuid = "";
        public String name = "";
        public long at;

        public Identity() {}

        public Identity(String uuid, String name, long at) {
            this.uuid = uuid;
            this.name = name;
            this.at = at;
        }
    }

    public static class RewardClaim {
        public String name = "";
        public String uuid = "";
        public int amount;
        /** NONE | PENDING | PAID | PAYMENT_IN_PROGRESS | PAYMENT_FAILED | PAYMENT_REVIEW */
        public String status = "PENDING";
        public String payoutId = "";
        public Long paidAt;
        public Long recoveredAt;
    }

    public boolean isOpen() {
        return "ISSUED".equals(status) || "ACCEPTED".equals(status) || "REPORTED".equals(status);
    }
}
