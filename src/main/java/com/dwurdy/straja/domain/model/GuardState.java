package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-player persistent Straja state. Field names and semantics mirror the
 * reference KubeJS straja_state payload so migrations are 1:1.
 */
public class GuardState {
    public int rank = 0;
    public boolean invited = false;
    public int quizIndex = 0;
    public boolean quizPassed = false;
    public Long quizCooldownAt = null;
    public List<String> quizOrder = new ArrayList<>();
    public Map<String, Boolean> trainingPassed = new HashMap<>();
    public String trainingQuizId = null;
    public Long trainingQuizCooldownAt = null;
    public boolean suspended = false;
    public boolean fired = false;
    public String nativeFaction = null;        // self-declared origin faction; Straja applies while on duty
    public boolean duty = false;
    public String mode = "OFF_DUTY";           // NORMAL | SPECIAL | FREE | OFF_DUTY
    public String patrolState = "OFF";         // ACTIVE | WAITING | SUSPENDED | OFF
    public List<String> route = new ArrayList<>();
    public int patrolIndex = 0;
    public Long waitingUntil = null;
    public Long deadlineAt = null;
    public Map<String, Integer> missionMinutes = new LinkedHashMap<>();
    public Long dutyStartedAt = null;
    public Long lastAccrualAt = null;
    public Long lastDutyActivityAt = null;
    public Double lastDutyActivityX = null;
    public Double lastDutyActivityY = null;
    public Double lastDutyActivityZ = null;
    public boolean salaryActivityPaused = false;
    public long dutyRemainderMs = 0;
    public long dutyMinutes = 0;
    public int dutyBlocksCurrent = 0;
    public long serviceBlocks = 0;
    public Long salaryWindowKey = null;
    public int salaryBlocksWindow = 0;
    public int unpaidSalary = 0;
    public int equipmentDebt = 0;
    public String salaryPayoutId = null;
    public String salaryPaymentStatus = "NONE"; // NONE | IN_PROGRESS | PENDING | REVIEW | PAID
    public String salaryPaymentError = null;
    public int salaryDeliveredDenominations = 0;
    public ServiceEquipment serviceEquipment = null;
    public int kitClaimedRank = 0;
    public boolean regearPending = false;
    public String specialAuthorizedBy = null;
    public String lastEndReason = null;
    public boolean resigned = false;
    public Long resignedAt = null;
    public Long rejoinAvailableAt = null;
    public Integer formerRank = null;
    public boolean resignationPending = false;
    public Long resignationDeadlineAt = null;
    public Integer resignationRank = null;
    public Long foodReadyAt = null;
    public long arrestRewardDay = -1;          // epochDay of arrestRewardDayTotal
    public int arrestRewardDayTotal = 0;
    public String lifecycle = "CIVIL";
    public String runtimeBootId = null;

    /** Issued service equipment that must be returned at end of duty. */
    public static class ServiceEquipment {
        public long issuedAt;
        public int rank;
        public List<Item> items = new ArrayList<>();

        public static class Item {
            public String key;
            public String id;
            public int count;
            public int replacementCost;
            public String label;
            public String serial;
            /** True once the stack was actually handed to the player. Defaults
             *  true so leases written before this flag existed still reclaim. */
            public boolean delivered = true;

            public Item() {}

            public Item(String key, String id, int count, int replacementCost, String label) {
                this.key = key;
                this.id = id;
                this.count = count;
                this.replacementCost = replacementCost;
                this.label = label;
            }
        }
    }

    public Lifecycle lifecycle() {
        if (fired) return Lifecycle.FIRED;
        if (resigned) return Lifecycle.RESIGNED_COOLDOWN;
        if (suspended) return Lifecycle.SUSPENDED;
        if (resignationPending) return Lifecycle.RESIGNATION_NOTICE;
        if (rank >= Rank.JUNIOR.level()) return duty ? Lifecycle.ACTIVE_ON_DUTY : Lifecycle.ACTIVE_OFF_DUTY;
        if (invited) return Lifecycle.INVITED;
        return Lifecycle.CIVIL;
    }

    public void refreshLifecycle() {
        this.lifecycle = lifecycle().name();
    }
}
