package com.dwurdy.straja.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Authoritative prisoner register entry (LAW-001). Unlike {@link Sentence}
 * (day-based punishments), this is the physical-custody ledger: where the
 * body is, what was seized, what the prisoner owes, and where belongings go.
 */
public class PrisonerRegisterRecord {
    public String detaineeUuid = "";
    public String detaineeName = "";
    public PrisonerStatus status = PrisonerStatus.IN_CELL;
    public String detentionReason = "";
    public int sentenceDays;
    /** Outstanding fines in base currency units (bronze = 1). */
    public int outstandingFines;
    /** Total times this detainee has been booked. */
    public int arrestCount;
    /** Penal labor balance in base units; buys freedom at the freedom price. */
    public int laborAccount;
    /** Permanent inventory snapshot taken at the exact moment of arrest. */
    public List<SnapshotItem> arrestSnapshot = new ArrayList<>();
    /** Confiscated-contraband summary kept beside the snapshot. */
    public List<String> confiscatedSummary = new ArrayList<>();
    /** True when seizure completed without leftovers. */
    public boolean confiscatedFully;
    /** Assigned jail cell id (empty when serving in a camp). */
    public String assignedCellId = "";
    /** Assigned labor camp id (empty when in a cell). */
    public String assignedCampId = "";
    /** Personal locker chest positions (usually a pair). */
    public List<StoragePoint> personalLocker = new ArrayList<>();
    /** Checkpoint/site name where the arrest happened (release point). */
    public String arrestSite = "";
    /** Epoch ms of the last booking. */
    public long bookedAt;
    /** Times the prisoner left custody without an official release. */
    public int escapeCount;
    /** Epoch ms of the last official release (0 while in custody). */
    public long releasedAt;
    /** Game mode the player had at booking; restored on release/escape. */
    public String priorGameMode = "survival";

    public PrisonerRegisterRecord() {}

    public PrisonerRegisterRecord(String detaineeUuid, String detaineeName, String detentionReason) {
        this.detaineeUuid = detaineeUuid;
        this.detaineeName = detaineeName;
        this.detentionReason = detentionReason;
    }
}
