package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.RoleplayExpansionUseCase;
import com.dwurdy.straja.application.port.out.InventoryView;
import com.dwurdy.straja.application.port.out.ItemView;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.CustodyStatus;
import com.dwurdy.straja.domain.model.EvidenceCustodyEvent;
import com.dwurdy.straja.domain.model.EvidenceRecord;
import com.dwurdy.straja.domain.model.EvidenceStatus;
import com.dwurdy.straja.domain.model.EvidenceStore;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.PlayerCondition;
import com.dwurdy.straja.domain.model.RestraintStatus;
import com.dwurdy.straja.domain.model.ArrestRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-side search snapshots, one-stack confiscation, and custody history. */
public final class EvidenceService {
    private static final long SEARCH_SESSION_TTL_MS = 60_000L;
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final Map<String, SearchSession> sessions = new LinkedHashMap<>();

    public EvidenceService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private EvidenceStore store() {
        EvidenceStore store = ctx.evidence().read();
        if (store.records == null) store.records = new LinkedHashMap<>();
        for (EvidenceRecord record : store.records.values()) {
            if (record == null) continue;
            if (record.itemData == null) record.itemData = new LinkedHashMap<>();
            if (record.custodyHistory == null) record.custodyHistory = new ArrayList<>();
        }
        return store;
    }

    public synchronized void purgeExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue() == null
                || entry.getValue().expiresAt() < now());
    }

    public synchronized RoleplayExpansionUseCase.SearchView beginSearch(
            PlayerGateway guard, PlayerGateway target) {
        purgeExpiredSessions();
        if (!eligibleGuard(guard) || target == null || !target.isOnline()) return null;
        String failure = eligibilityFailure(guard, target);
        if (failure != null) {
            guard.tell(failure);
            audit.record("search_started", guard.name(), uuid(guard),
                    target.name(), uuid(target), "REFUSED", failure);
            return null;
        }
        String token = ctx.ids().token();
        List<RoleplayExpansionUseCase.SearchSlot> slots = new ArrayList<>();
        InventoryView inventory = target.inventory();
        for (int slot = 0; slot < inventory.slots(); slot++) {
            ItemView item = inventory.stackAt(slot);
            if (!item.isEmpty()) slots.add(new RoleplayExpansionUseCase.SearchSlot(slot, item));
        }
        sessions.put(token, new SearchSession(guard.uuid(), target.uuid(),
                guard.dimension(), target.dimension(), now(), slots));
        audit.record("search_started", guard.name(), uuid(guard),
                target.name(), uuid(target), "SUCCESS", "read_only_snapshot");
        guard.tell("Percheziție deschisă pentru " + target.name()
                + ". Inventarul este doar pentru citire.");
        return new RoleplayExpansionUseCase.SearchView(token, target.name(), List.copyOf(slots));
    }

    public synchronized boolean confiscate(PlayerGateway guard, String token, int slot,
                                           int amount, String reason, String incidentId) {
        SearchSession session = sessions.remove(token);
        if (session == null || !session.guardUuid.equals(guard == null ? null : guard.uuid())
                || session.expiresAt() < now()) {
            if (guard != null) guard.tell("Percheziția nu mai este validă. Deschide-o din nou.");
            return false;
        }
        PlayerGateway target = ctx.server().findPlayer(session.targetUuid);
        if (target == null || eligibilityFailure(guard, target) != null) {
            if (guard != null) guard.tell("Ținta s-a îndepărtat sau nu mai poate fi percheziționată.");
            audit.record("item_confiscated", name(guard), uuid(guard),
                    session.targetUuid.toString(), "", "REFUSED", "context_changed");
            return false;
        }
        RoleplayExpansionUseCase.SearchSlot selected = session.slot(slot);
        if (selected == null || amount != selected.item().count() || amount <= 0) {
            guard.tell("Obiectul sau cantitatea s-au schimbat; confiscarea a fost refuzată.");
            return false;
        }
        ItemView current = target.inventory().stackAt(slot);
        if (!sameStack(selected.item(), current)) {
            guard.tell("Obiectul s-a schimbat; confiscarea a fost refuzată.");
            audit.record("item_confiscated", guard.name(), uuid(guard),
                    target.name(), uuid(target), "REFUSED", "stale_slot");
            return false;
        }
        ItemView taken = target.inventory().extract(slot, amount);
        if (!sameStack(selected.item(), taken)) {
            guard.tell("Stiva nu a putut fi preluată integral; nimic nu a fost înregistrat ca probă.");
            return false;
        }
        EvidenceStore data = store();
        EvidenceRecord evidence = new EvidenceRecord();
        evidence.id = data.nextEvidenceId();
        evidence.sourcePlayerUuid = uuid(target);
        evidence.sourcePlayerName = target.name();
        evidence.seizingGuardUuid = uuid(guard);
        evidence.seizingGuardName = guard.name();
        evidence.itemId = taken.id();
        evidence.itemName = taken.id();
        evidence.amount = taken.count();
        evidence.maxStackSize = taken.maxStackSize();
        evidence.itemData = new LinkedHashMap<>(taken.customData());
        evidence.seizedAt = now();
        evidence.reason = clean(reason, ctx.policies().evidenceMaxReasonLength);
        evidence.incidentId = cleanId(incidentId);
        evidence.status = EvidenceStatus.IN_GUARD_CUSTODY;
        evidence.currentCustodianUuid = uuid(guard);
        evidence.currentCustodianName = guard.name();
        evidence.currentLocation = guard.dimension();
        move(evidence, null, EvidenceStatus.IN_GUARD_CUSTODY, "", uuid(guard),
                guard, "confiscated");
        data.records.put(evidence.id, evidence);
        ctx.evidence().write(data);
        linkCase(evidence);

        ItemSpec bag = ItemSpec.of("straja:evidence_bag", 1)
                .named("Pungă de probe " + evidence.id)
                .withData("EvidenceId", evidence.id);
        guard.giveVerified(bag);
        ItemSpec receipt = ItemSpec.of("straja:confiscation_receipt", 1)
                .named("Dovadă de confiscare " + evidence.id)
                .withData("EvidenceId", evidence.id);
        target.giveVerified(receipt);
        String msg = "Confiscat: " + taken.id() + " x" + taken.count()
                + " | probă " + evidence.id + " | străjer " + guard.name();
        target.tell(msg);
        guard.tell(msg);
        audit.record("item_confiscated", guard.name(), uuid(guard),
                target.name(), uuid(target), "SUCCESS", evidence.id);
        return true;
    }

    public synchronized boolean deposit(PlayerGateway actor, String evidenceId) {
        EvidenceStore data = store();
        EvidenceRecord evidence = data.records.get(evidenceId);
        if (evidence == null || evidence.status != EvidenceStatus.IN_GUARD_CUSTODY) {
            if (actor != null) actor.tell("Proba nu mai este în custodia unui străjer.");
            return false;
        }
        if (!authorizedArchive(actor)) {
            actor.tell("Doar personalul autorizat poate depune probe.");
            return false;
        }
        move(evidence, evidence.status, EvidenceStatus.DEPOSITED,
                evidence.currentCustodianUuid, "archive", actor, "archivist_deposit");
        evidence.currentCustodianName = actor.name();
        evidence.currentLocation = "archive";
        ctx.evidence().write(data);
        audit.record("evidence_deposit", actor.name(), uuid(actor),
                evidence.id, evidence.sourcePlayerUuid, "SUCCESS", "");
        actor.tell("Proba " + evidence.id + " a fost depusă în Arhivă.");
        return true;
    }

    public synchronized boolean returnToOwner(PlayerGateway actor, String evidenceId) {
        EvidenceStore data = store();
        EvidenceRecord evidence = data.records.get(evidenceId);
        if (evidence == null || evidence.status == EvidenceStatus.RETURNED
                || evidence.status == EvidenceStatus.DESTROYED) {
            if (actor != null) actor.tell("Proba nu poate fi restituită.");
            return false;
        }
        if (!authorizedArchive(actor)) {
            actor.tell("Doar personalul autorizat poate restitui probe.");
            return false;
        }
        PlayerGateway owner = ctx.server().findPlayer(evidence.sourcePlayerUuid);
        if (owner == null || !owner.isOnline()) {
            actor.tell("Proprietarul probei nu este online.");
            return false;
        }
        ItemSpec original = new ItemSpec(evidence.itemId, evidence.amount,
                Map.copyOf(evidence.itemData), null);
        if (!owner.giveVerified(original)) {
            actor.tell("Inventarul proprietarului este plin; proba rămâne în Arhivă.");
            return false;
        }
        move(evidence, evidence.status, EvidenceStatus.RETURNED,
                evidence.currentCustodianUuid, uuid(owner), actor, "returned_to_owner");
        evidence.currentCustodianName = owner.name();
        evidence.currentLocation = "player";
        ctx.evidence().write(data);
        audit.record("evidence_return", actor.name(), uuid(actor),
                evidence.id, owner.name(), "SUCCESS", "");
        return true;
    }

    public synchronized boolean transfer(PlayerGateway actor, String evidenceId,
                                         PlayerGateway recipient, String reason) {
        EvidenceStore data = store();
        EvidenceRecord evidence = data.records.get(evidenceId);
        if (evidence == null || evidence.status == EvidenceStatus.RETURNED
                || evidence.status == EvidenceStatus.DESTROYED) {
            if (actor != null) actor.tell("Proba nu mai poate fi transferată.");
            return false;
        }
        if (!authorizedArchive(actor) || !authorizedCustodian(recipient)) {
            if (actor != null) actor.tell("Transferul cere un arhivist/Comisar și un custode autorizat.");
            return false;
        }
        EvidenceStatus next = players.isOnDutyGuard(recipient)
                ? EvidenceStatus.IN_GUARD_CUSTODY : EvidenceStatus.DEPOSITED;
        String safeReason = clean(reason, ctx.policies().evidenceMaxReasonLength);
        move(evidence, evidence.status, next, evidence.currentCustodianUuid,
                uuid(recipient), actor, safeReason);
        evidence.currentCustodianUuid = uuid(recipient);
        evidence.currentCustodianName = recipient.name();
        evidence.currentLocation = players.isOnDutyGuard(recipient) ? recipient.dimension() : "archive";
        ctx.evidence().write(data);
        audit.record("evidence_transfer", actor.name(), uuid(actor), evidence.id,
                uuid(recipient), "SUCCESS", safeReason);
        actor.tell("Proba " + evidence.id + " a fost transferată lui " + recipient.name() + ".");
        return true;
    }

    public synchronized boolean destroy(PlayerGateway actor, String evidenceId, String reason) {
        EvidenceStore data = store();
        EvidenceRecord evidence = data.records.get(evidenceId);
        if (evidence == null || evidence.status == EvidenceStatus.RETURNED
                || evidence.status == EvidenceStatus.DESTROYED) {
            if (actor != null) actor.tell("Proba nu mai poate fi distrusă.");
            return false;
        }
        if (actor == null || !players.isCommissioner(actor)) {
            if (actor != null) actor.tell("Doar Comisaru' poate aproba distrugerea unei probe.");
            return false;
        }
        String safeReason = clean(reason, ctx.policies().evidenceMaxReasonLength);
        move(evidence, evidence.status, EvidenceStatus.DESTROYED,
                evidence.currentCustodianUuid, "", actor,
                safeReason);
        evidence.currentCustodianUuid = "";
        evidence.currentCustodianName = "";
        evidence.currentLocation = "destroyed";
        ctx.evidence().write(data);
        audit.record("evidence_destroy", actor.name(), uuid(actor), evidence.id,
                evidence.sourcePlayerUuid, "SUCCESS", safeReason);
        actor.tell("Proba " + evidence.id + " a fost marcată ca distrusă.");
        return true;
    }

    public synchronized List<EvidenceRecord> recordsForCase(PlayerGateway viewer, String caseId) {
        if (!authorizedArchive(viewer)) return List.of();
        String safe = cleanId(caseId);
        return store().records.values().stream()
                .filter(e -> e != null && safe.equals(e.caseId))
                .toList();
    }

    public synchronized List<EvidenceRecord> recordsFor(PlayerGateway viewer) {
        if (!authorizedArchive(viewer)) return List.of();
        return List.copyOf(store().records.values().stream().filter(java.util.Objects::nonNull).toList());
    }

    public synchronized EvidenceRecord find(String evidenceId) {
        return store().records.get(evidenceId);
    }

    public boolean canSearch(PlayerGateway guard, PlayerGateway target) {
        return eligibleGuard(guard) && target != null && eligibilityFailure(guard, target) == null;
    }

    private boolean eligibleGuard(PlayerGateway guard) {
        return guard != null && ctx.policies().evidenceEnabled
                && players.isOnDutyGuard(guard)
                && (players.isCommissioner(guard)
                    || players.hasCapability(guard, Capability.USE_CUFFS))
                && guard.health() > 0;
    }

    private String eligibilityFailure(PlayerGateway guard, PlayerGateway target) {
        if (!eligibleGuard(guard)) return "Doar un Străjer activ și operațional poate percheziționa.";
        if (target == null || !target.isOnline()) return "Ținta nu este disponibilă.";
        if (!guard.dimension().equals(target.dimension())) return "Ținta este în altă dimensiune.";
        double dx = guard.x() - target.x();
        double dy = guard.y() - target.y();
        double dz = guard.z() - target.z();
        double range = ctx.policies().searchRangeBlocks;
        if (dx * dx + dy * dy + dz * dz > range * range) return "Ținta este prea departe.";
        CustodyState state = ctx.custody().read().states.get(uuid(target));
        if (state == null || !searchable(state)) {
            return "Nu există un context valid de percheziție.";
        }
        return null;
    }

    private static boolean searchable(CustodyState state) {
        return state.restraint == RestraintStatus.CUFFED
                || state.custody == CustodyStatus.ARRESTED
                || state.custody == CustodyStatus.JAILED
                || state.condition == PlayerCondition.DOWNED
                || state.condition == PlayerCondition.UNCONSCIOUS_CUSTODY;
    }

    private boolean authorizedArchive(PlayerGateway actor) {
        if (actor == null) return false;
        if (players.isCommissioner(actor)) return true;
        var archive = ctx.archive().read();
        return archive.archivists != null && archive.archivists.containsKey(uuid(actor));
    }

    private boolean authorizedCustodian(PlayerGateway actor) {
        if (actor == null || !actor.isOnline()) return false;
        if (players.isCommissioner(actor) || players.isOnDutyGuard(actor)) return true;
        var archive = ctx.archive().read();
        return archive.archivists != null && archive.archivists.containsKey(uuid(actor));
    }

    private void move(EvidenceRecord evidence, EvidenceStatus previous,
                      EvidenceStatus next, String previousCustodian,
                      String newCustodian, PlayerGateway actor, String reason) {
        EvidenceCustodyEvent event = new EvidenceCustodyEvent();
        event.at = now();
        event.evidenceId = evidence.id;
        event.previousStatus = previous;
        event.newStatus = next;
        event.previousCustodianUuid = previousCustodian == null ? "" : previousCustodian;
        event.newCustodianUuid = newCustodian == null ? "" : newCustodian;
        event.actorUuid = uuid(actor);
        event.actorName = name(actor);
        event.reason = reason;
        evidence.custodyHistory.add(event);
    }

    private static boolean sameStack(ItemView left, ItemView right) {
        return left != null && right != null && !left.isEmpty() && !right.isEmpty()
                && left.id().equals(right.id()) && left.count() == right.count()
                && java.util.Objects.equals(left.customData(), right.customData());
    }

    /** Links a newly seized item into the existing formal case when one exists. */
    private void linkCase(EvidenceRecord evidence) {
        var data = ctx.arrestRecords().read();
        if (data.records == null) return;
        ArrestRecord candidate = null;
        for (ArrestRecord record : data.records) {
            if (record == null || !evidence.sourcePlayerUuid.equals(record.detaineeUuid)) continue;
            if (!evidence.incidentId.isBlank() && evidence.incidentId.equals(record.incidentId)) {
                candidate = record;
                break;
            }
            if (candidate == null
                    && record.status != com.dwurdy.straja.domain.model.ArrestRecordStatus.FINALIZED) {
                candidate = record;
            }
        }
        if (candidate == null) return;
        evidence.caseId = candidate.id;
        if (candidate.evidenceIds == null) candidate.evidenceIds = new ArrayList<>();
        if (!candidate.evidenceIds.contains(evidence.id)) candidate.evidenceIds.add(evidence.id);
        ctx.arrestRecords().write(data);

        // The evidence record was persisted before this link was discovered.
        // Persist the updated caseId as well, otherwise the case and evidence
        // stores disagree after the next read (and after a server restart).
        var evidenceData = ctx.evidence().read();
        var persistedEvidence = evidenceData.records == null ? null
                : evidenceData.records.get(evidence.id);
        if (persistedEvidence != null) {
            persistedEvidence.caseId = candidate.id;
            ctx.evidence().write(evidenceData);
        }
    }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    private static String name(PlayerGateway player) {
        return player == null ? "" : player.name();
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\p{Cntrl}", " ");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String cleanId(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.matches("[A-Za-z0-9_-]{1,80}") ? clean : "";
    }

    private record SearchSession(UUID guardUuid, UUID targetUuid,
                                 String guardDimension, String targetDimension,
                                 long createdAt,
                                 List<RoleplayExpansionUseCase.SearchSlot> slots) {
        long expiresAt() { return createdAt + SEARCH_SESSION_TTL_MS; }
        RoleplayExpansionUseCase.SearchSlot slot(int index) {
            return slots.stream().filter(s -> s.slot() == index).findFirst().orElse(null);
        }
    }
}
