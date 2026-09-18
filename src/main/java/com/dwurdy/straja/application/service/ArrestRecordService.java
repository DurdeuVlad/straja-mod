package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ArrestRecord;
import com.dwurdy.straja.domain.model.ArrestRecordStatus;
import com.dwurdy.straja.domain.model.Fine;
import com.dwurdy.straja.domain.model.FineTask;
import com.dwurdy.straja.domain.model.Sentence;
import java.util.ArrayList;
import java.util.List;

/** Formal case paperwork generated from the existing sentence/task graph. */
public final class ArrestRecordService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public ArrestRecordService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private long now() { return ctx.clock().nowMillis(); }

    public synchronized ArrestRecord startForSentence(Sentence sentence) {
        if (sentence == null || sentence.id == null || sentence.id.isBlank()) return null;
        var data = ctx.arrestRecords().read();
        if (data.records == null) data.records = new ArrayList<>();
        ArrestRecord existing = findBySentence(data.records, sentence.id);
        if (existing != null) {
            normalize(existing);
            return existing;
        }
        ArrestRecord record = populate(data, sentence);
        data.records.add(record);
        ctx.arrestRecords().write(data);
        audit.record("arrest_record_created", "system", "", record.id,
                record.detaineeUuid, "SUCCESS", "sentence=" + sentence.id);
        return record;
    }

    public synchronized ArrestRecord finalizeForSentence(Sentence sentence, String notes) {
        if (sentence == null || sentence.id == null || sentence.id.isBlank()) return null;
        var data = ctx.arrestRecords().read();
        if (data.records == null) data.records = new ArrayList<>();
        ArrestRecord record = findBySentence(data.records, sentence.id);
        if (record == null) {
            record = populate(data, sentence);
            data.records.add(record);
        } else {
            normalize(record);
        }
        if (record.status == ArrestRecordStatus.FINALIZED) return record;
        record.status = ArrestRecordStatus.FINALIZED;
        record.jailedAt = record.jailedAt == null ? now() : record.jailedAt;
        record.guardNotes = clean(notes);
        ctx.arrestRecords().write(data);
        audit.record("arrest_record_finalized", "system", "", record.id,
                record.detaineeUuid, "SUCCESS", "");
        return record;
    }

    public synchronized boolean finalizeFromJailer(PlayerGateway actor,
                                                    PlayerGateway detainee,
                                                    String sentenceId,
                                                    String notes) {
        if (actor == null || detainee == null
                || (!players.isCommissioner(actor) && !players.isOnDutyGuard(actor))) {
            if (actor != null) actor.tell("Predarea la Temnicer cere un străjer activ.");
            return false;
        }
        Sentence sentence = ctx.prison().read().activeSentenceFor(detainee.uuid().toString());
        if (sentence == null || (sentenceId != null && !sentenceId.isBlank()
                && !sentenceId.equals(sentence.id))) {
            actor.tell("Nu există o sentință activă pentru această predare.");
            return false;
        }
        ArrestRecord record = finalizeForSentence(sentence, notes);
        if (record == null) return false;
        actor.tell("Dosarul de arest " + record.id + " a fost completat automat.");
        return true;
    }

    public synchronized List<ArrestRecord> recordsFor(PlayerGateway viewer) {
        if (viewer == null || (!players.isCommissioner(viewer) && !players.isOnDutyGuard(viewer))) {
            return List.of();
        }
        var data = ctx.arrestRecords().read();
        return data.records == null ? List.of() : List.copyOf(data.records);
    }

    public synchronized List<ArrestRecord> recordsForDetainee(PlayerGateway viewer) {
        if (viewer == null) return List.of();
        String uuid = viewer.uuid() == null ? "" : viewer.uuid().toString();
        var data = ctx.arrestRecords().read();
        if (data.records == null) return List.of();
        return data.records.stream()
                .filter(record -> record != null && uuid.equals(record.detaineeUuid))
                .toList();
    }

    public synchronized ArrestRecord find(String id) {
        var data = ctx.arrestRecords().read();
        if (data.records == null) data.records = new ArrayList<>();
        return data.find(id);
    }

    /** Reconciles links created after the initial arrest paperwork (evidence and reputation). */
    public synchronized void refreshLinksForSubject(String detaineeUuid) {
        if (detaineeUuid == null || detaineeUuid.isBlank()) return;
        var data = ctx.arrestRecords().read();
        if (data.records == null) data.records = new ArrayList<>();
        boolean changed = false;
        for (ArrestRecord record : data.records) {
            if (record == null || !detaineeUuid.equals(record.detaineeUuid)) continue;
            normalize(record);
            int evidenceBefore = record.evidenceIds.size();
            int reputationBefore = record.reputationEventIds.size();
            linkEvidence(record);
            linkReputation(record);
            changed |= evidenceBefore != record.evidenceIds.size()
                    || reputationBefore != record.reputationEventIds.size();
        }
        if (changed) ctx.arrestRecords().write(data);
    }

    private ArrestRecord populate(com.dwurdy.straja.domain.model.ArrestRecordStore data,
                                  Sentence sentence) {
        ArrestRecord record = new ArrestRecord();
        record.id = data.nextArrestId();
        record.detaineeUuid = sentence.targetUuid == null ? "" : sentence.targetUuid;
        record.detaineeName = sentence.target == null ? "" : sentence.target;
        record.arrestingGuardUuid = sentence.arrestedByUuid == null ? "" : sentence.arrestedByUuid;
        record.arrestingGuardName = sentence.arrestedBy == null ? "" : sentence.arrestedBy;
        record.startedAt = sentence.createdAt > 0 ? sentence.createdAt : now();
        record.jailedAt = sentence.status != null && !"WAITING_CELL".equals(sentence.status)
                ? record.startedAt : null;
        record.sentenceDays = sentence.sentenceDays;
        record.sentenceId = sentence.id;
        record.fineId = sentence.fineId == null ? "" : sentence.fineId;
        record.warrantTaskId = sentence.missionId == null ? "" : sentence.missionId;
        record.detentionReason = reasonFor(sentence);
        linkTask(record, sentence);
        linkIncident(record);
        linkEvidence(record);
        linkBolo(record);
        linkComplaint(record);
        linkReputation(record);
        return record;
    }

    private void linkTask(ArrestRecord record, Sentence sentence) {
        var taskStore = ctx.fines().read();
        if (taskStore.tasks == null) return;
        for (FineTask task : taskStore.tasks) {
            if (task == null) continue;
            if (sentence.missionId != null && sentence.missionId.equals(task.id)) {
                record.warrantTaskId = task.id;
                break;
            }
            if (sentence.fineId != null && sentence.fineId.equals(task.fineId)) {
                record.warrantTaskId = task.id;
                break;
            }
        }
        Fine fine = sentence.fineId == null ? null : ctx.fines().read().find(sentence.fineId);
        if (fine != null && record.detentionReason.isBlank()) {
            record.detentionReason = fine.law + ": " + fine.description;
        }
    }

    private void linkIncident(ArrestRecord record) {
        if (record.warrantTaskId.isBlank() && record.fineId.isBlank()) return;
        var data = ctx.incidents().read();
        if (data.incidents == null) return;
        for (var incident : data.incidents) {
            if (incident == null) continue;
            if (record.warrantTaskId.equals(incident.linkedArrestTaskId)
                    || record.fineId.equals(incident.linkedFineId)) {
                record.incidentId = incident.id;
                incident.linkedArrestId = record.id;
                ctx.incidents().write(data);
                return;
            }
        }
    }

    private void linkEvidence(ArrestRecord record) {
        var data = ctx.evidence().read();
        if (data.records == null) return;
        for (var evidence : data.records.values()) {
            if (evidence == null || !record.detaineeUuid.equals(evidence.sourcePlayerUuid)) continue;
            if (evidence.status == com.dwurdy.straja.domain.model.EvidenceStatus.RETURNED
                    || evidence.status == com.dwurdy.straja.domain.model.EvidenceStatus.DESTROYED) continue;
            if (!record.evidenceIds.contains(evidence.id)) record.evidenceIds.add(evidence.id);
            evidence.caseId = record.id;
        }
        if (!record.evidenceIds.isEmpty()) ctx.evidence().write(data);
    }

    private void linkBolo(ArrestRecord record) {
        var data = ctx.bolos().read();
        if (data.records == null) return;
        for (var bolo : data.records) {
            if (bolo == null || !record.detaineeUuid.equals(bolo.subjectUuid)) continue;
            if (record.warrantTaskId.equals(bolo.linkedArrestTaskId)
                    || "ACTIVE".equals(String.valueOf(bolo.status))) {
                record.boloId = bolo.id;
                return;
            }
        }
    }

    private void linkComplaint(ArrestRecord record) {
        var data = ctx.complaints().read();
        if (data.complaints == null) return;
        for (var complaint : data.complaints) {
            if (complaint != null && record.detaineeUuid.equals(complaint.accusedUuid)) {
                record.complaintId = complaint.id;
                return;
            }
        }
    }

    private void linkReputation(ArrestRecord record) {
        var data = ctx.reputation().read();
        if (data.events == null) return;
        for (var event : data.events.values()) {
            if (event != null && record.detaineeUuid.equals(event.playerUuid)) {
                if (!record.reputationEventIds.contains(event.id)) {
                    record.reputationEventIds.add(event.id);
                }
            }
        }
    }

    private String reasonFor(Sentence sentence) {
        if (sentence.fineId != null && !sentence.fineId.isBlank()) {
            Fine fine = ctx.fines().read().find(sentence.fineId);
            if (fine != null) return clean(fine.law + ": " + fine.description);
        }
        if (sentence.missionId != null && !sentence.missionId.isBlank()) {
            FineTask task = ctx.fines().read().findTask(sentence.missionId);
            if (task != null && task.warrantReason != null && !task.warrantReason.isBlank()) {
                return clean(task.warrantReason);
            }
        }
        return "Detenție înregistrată prin procedura Străjii.";
    }

    private static ArrestRecord findBySentence(List<ArrestRecord> records, String sentenceId) {
        for (ArrestRecord record : records) {
            if (record != null && sentenceId.equals(record.sentenceId)) return record;
        }
        return null;
    }

    private static void normalize(ArrestRecord record) {
        if (record.assistingGuardUuids == null) record.assistingGuardUuids = new ArrayList<>();
        if (record.evidenceIds == null) record.evidenceIds = new ArrayList<>();
        if (record.reputationEventIds == null) record.reputationEventIds = new ArrayList<>();
        if (record.status == null) record.status = ArrestRecordStatus.STARTED;
        if (record.detaineeUuid == null) record.detaineeUuid = "";
        if (record.warrantTaskId == null) record.warrantTaskId = "";
        if (record.fineId == null) record.fineId = "";
        if (record.incidentId == null) record.incidentId = "";
        if (record.boloId == null) record.boloId = "";
        if (record.complaintId == null) record.complaintId = "";
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\p{Cntrl}", " ");
        return clean.length() > 500 ? clean.substring(0, 500) : clean;
    }
}
