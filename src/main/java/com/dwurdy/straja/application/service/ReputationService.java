package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.domain.model.CustodyState;
import com.dwurdy.straja.domain.model.CustodyStatus;
import com.dwurdy.straja.domain.model.PlayerCondition;
import com.dwurdy.straja.domain.model.ReputationEvent;
import com.dwurdy.straja.domain.model.ReputationState;
import com.dwurdy.straja.domain.model.ReputationStore;
import com.dwurdy.straja.domain.model.RestraintStatus;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persistent civic history, deliberately separate from rank and merit. */
public final class ReputationService {
    private static final long SECOND = 1_000L;
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final IncidentService incidents;
    private final BoloService bolos;

    public ReputationService(StrajaContext ctx, PlayerService players, AuditService audit,
                             IncidentService incidents, BoloService bolos) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.incidents = incidents;
        this.bolos = bolos;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private ReputationStore store() {
        ReputationStore data = ctx.reputation().read();
        if (data.states == null) data.states = new java.util.LinkedHashMap<>();
        if (data.events == null) data.events = new java.util.LinkedHashMap<>();
        if (data.idempotency == null) data.idempotency = new java.util.LinkedHashMap<>();
        if (data.recentHostileDamageAt == null) data.recentHostileDamageAt = new java.util.LinkedHashMap<>();
        return data;
    }

    public synchronized ReputationState state(PlayerGateway player) {
        if (player == null) return null;
        ReputationStore data = store();
        ReputationState state = data.states.computeIfAbsent(uuid(player), key -> {
            ReputationState created = new ReputationState();
            created.playerUuid = key;
            created.playerName = player.name();
            return created;
        });
        if (state.prisonTaskRehabilitation == null) {
            state.prisonTaskRehabilitation = new java.util.LinkedHashMap<>();
        }
        state.playerName = player.name();
        state.band = band(state.score);
        ctx.reputation().write(data);
        return state;
    }

    public synchronized ReputationEvent apply(PlayerGateway actor, PlayerGateway subject,
                                               String sourceType, String sourceId, int delta,
                                               String reason, boolean capAtNeutral) {
        if (subject == null || !ctx.policies().reputationEnabled) return null;
        return applyToUuid(actor, uuid(subject), subject.name(), sourceType, sourceId,
                delta, reason, capAtNeutral);
    }

    private ReputationEvent applyToUuid(PlayerGateway actor, String subjectUuid,
                                        String subjectName, String sourceType, String sourceId,
                                        int delta, String reason, boolean capAtNeutral) {
        if (subjectUuid == null || subjectUuid.isBlank() || !ctx.policies().reputationEnabled) return null;
        String normalizedSourceId = sourceId == null ? "" : sourceId;
        // Death delivery can be reclassified after recovery. Keep its
        // idempotency independent from the descriptive event type while
        // retaining that type in the stored audit event.
        String key = normalizedSourceId.startsWith("DEATH:")
                ? normalizedSourceId
                : (sourceType == null ? "" : sourceType) + ":" + normalizedSourceId;
        ReputationStore data = store();
        String existingId = data.idempotency.get(key);
        if (existingId != null) return data.events.get(existingId);
        ReputationState state = data.states.computeIfAbsent(subjectUuid, ignored -> {
            ReputationState created = new ReputationState();
            created.playerUuid = subjectUuid;
            return created;
        });
        if (state.prisonTaskRehabilitation == null) {
            state.prisonTaskRehabilitation = new java.util.LinkedHashMap<>();
        }
        int before = state.score;
        int proposed = before + delta;
        boolean capped = false;
        if (capAtNeutral && delta > 0 && proposed > 0) {
            proposed = 0;
            capped = true;
        }
        int after = Math.max(ctx.policies().reputationMinScore,
                Math.min(ctx.policies().reputationMaxScore, proposed));
        ReputationEvent event = new ReputationEvent();
        event.id = data.nextEventId();
        event.idempotencyKey = key;
        event.playerUuid = subjectUuid;
        event.playerName = subjectName == null ? "" : subjectName;
        event.sourceType = sourceType == null ? "" : sourceType;
        event.sourceRecordId = sourceId == null ? "" : sourceId;
        event.delta = after - before;
        event.scoreBefore = before;
        event.scoreAfter = after;
        event.at = now();
        event.actorUuid = uuid(actor);
        event.actorName = actor == null ? "Sistem" : actor.name();
        event.reason = clean(reason, 240);
        event.cappedAtNeutral = capped;
        state.score = after;
        state.band = band(after);
        state.updatedAt = now();
        data.events.put(event.id, event);
        data.idempotency.put(key, event.id);
        ctx.reputation().write(data);
        audit.record("reputation_delta", event.actorName, event.actorUuid,
                event.id, event.playerUuid, "SUCCESS",
                event.sourceType + ":" + event.sourceRecordId + " delta=" + event.delta);
        return event;
    }

    public synchronized void recordHostileDamage(PlayerGateway attacker, PlayerGateway victim) {
        if (attacker == null || victim == null || attacker.uuid() == null || victim.uuid() == null) return;
        ReputationStore data = store();
        long current = now();
        data.recentHostileDamageAt.put(uuid(attacker) + ":" + uuid(victim), current);
        long retention = Math.max(ctx.policies().lawfulHostilityWindowSeconds * 4L * SECOND,
                5 * 60_000L);
        data.recentHostileDamageAt.entrySet().removeIf(entry -> entry.getValue() == null
                || entry.getValue() < current - retention);
        ctx.reputation().write(data);
    }

    public synchronized void recordFinalDeath(PlayerGateway killer, PlayerGateway victim) {
        if (killer == null || victim == null) return;
        long current = now();
        String deathPrefix = "DEATH:" + uuid(killer) + ":" + uuid(victim) + ":";
        // NeoForge can surface more than one death-shaped callback for the
        // same final death. The source key below is persisted, while this
        // short pair window also protects against callbacks that arrive a few
        // milliseconds apart and would otherwise receive different timestamps.
        boolean duplicate = store().events.values().stream()
                .anyMatch(event -> event != null
                        && event.sourceRecordId != null
                        && event.sourceRecordId.startsWith(deathPrefix)
                        && Math.abs(current - event.at) <= 1_000L);
        if (duplicate) return;
        // The attribution is one domain event, even when its classification is
        // different on a duplicate delivery (for example after recovery has
        // already cleared the victim's custody state).  A source key that
        // includes only the victim, or the classification, can double-charge
        // the killer or suppress a later genuine death.
        String deathId = deathPrefix + current;
        boolean killerGuard = players.isOnDutyGuard(killer);
        CustodyState victimState = ctx.custody().read().states.get(uuid(victim));
        boolean restrained = victimState != null && (victimState.restraint == RestraintStatus.CUFFED
                || victimState.condition == PlayerCondition.DOWNED
                || victimState.condition == PlayerCondition.UNCONSCIOUS_CUSTODY
                || victimState.custody == CustodyStatus.ARRESTED
                || victimState.custody == CustodyStatus.JAILED);
        if (killerGuard && restrained) {
            apply(killer, killer, "GUARD_EXECUTION", deathId,
                    ctx.policies().reputationGuardExecutionDelta,
                    "Uciderea unei persoane aflate în custodie", false);
            incidents.createDisciplinaryFlag(killer, victim,
                    "Execuție în custodie; necesită analiză Comisar.");
            audit.record("guard_lethal_force_flag", killer.name(), uuid(killer),
                    victim.name(), uuid(victim), "SUCCESS", "restrained_target");
            return;
        }
        boolean lawful = killerGuard && !restrained
                && bolos.hasAuthoritativeTask(victim)
                && recentlyHostile(killer, victim);
        int delta;
        String source;
        if (lawful) {
            delta = 0;
            source = "LAWFUL_LETHAL_FORCE";
        } else if (restrained) {
            delta = ctx.policies().reputationRestrainedKillDelta;
            source = "KILL_RESTRAINED";
        } else if (players.isOnDutyGuard(victim)) {
            delta = ctx.policies().reputationKillOnDutyGuardDelta;
            source = "KILL_ON_DUTY_GUARD";
        } else {
            delta = ctx.policies().reputationOrdinaryKillDelta;
            source = "PLAYER_KILL";
        }
        apply(killer, killer, source, deathId, delta,
                lawful ? "Forță letală în context ostil și autoritate activă"
                        : "Moartea finală a unui jucător", false);
        audit.record("lethal_force", killer.name(), uuid(killer),
                victim.name(), uuid(victim), "SUCCESS", lawful ? "lawful_context" : source);
    }

    public synchronized ReputationEvent recordJailerAssault(PlayerGateway attacker,
                                                              String jailerId,
                                                              boolean killed) {
        if (attacker == null || jailerId == null || jailerId.isBlank()) return null;
        return apply(attacker, attacker, killed ? "JAILER_KILL" : "JAILER_ASSAULT",
                jailerId, killed ? ctx.policies().reputationJailerKillDelta
                        : ctx.policies().reputationJailerAssaultDelta,
                killed ? "Uciderea Temnicerului" : "Atac asupra Temnicerului", false);
    }

    /** Applies a single negative event for a confirmed custody/sentence escape. */
    public synchronized ReputationEvent recordCustodyEscape(PlayerGateway actor,
                                                              PlayerGateway escapee,
                                                              String transitionId) {
        if (escapee == null || escapee.uuid() == null) return null;
        boolean prisonEscape = ctx.prison().read().activeSentenceFor(uuid(escapee)) != null;
        return apply(actor, escapee, prisonEscape ? "PRISON_ESCAPE" : "CUSTODY_ESCAPE",
                stableId(transitionId, escapee),
                prisonEscape ? ctx.policies().reputationPrisonEscapeDelta
                        : ctx.policies().reputationCustodyEscapeDelta,
                prisonEscape ? "Evadare dintr-o sentință activă"
                        : "Tăiere/eliberare neautorizată din custodie", false);
    }

    public synchronized ReputationEvent recordFineRefusal(PlayerGateway subject,
                                                            String taskId) {
        return apply(subject, subject, "FINE_REFUSAL", taskId,
                ctx.policies().reputationFineRefusalDelta,
                "Refuz explicit al unei amenzi executorii", false);
    }

    public synchronized ReputationEvent completeSentence(PlayerGateway player, String sentenceId) {
        if (sentenceId == null || sentenceId.isBlank()) return null;
        return apply(null, player, "SENTENCE_SERVED", sentenceId,
                ctx.policies().reputationSentenceCompletionDelta,
                "Sentință executată; reabilitare până la neutral", true);
    }

    public synchronized ReputationEvent completeSentence(java.util.UUID playerUuid,
                                                         String playerName, String sentenceId) {
        if (playerUuid == null || sentenceId == null || sentenceId.isBlank()) return null;
        return applyToUuid(null, playerUuid.toString(), playerName, "SENTENCE_SERVED",
                sentenceId, ctx.policies().reputationSentenceCompletionDelta,
                "Sentință executată; reabilitare până la neutral", true);
    }

    /** Positive prison-task credit is capped per authoritative sentence. */
    public synchronized ReputationEvent completePrisonTask(PlayerGateway player,
                                                            String sentenceId,
                                                            String taskId) {
        if (player == null || sentenceId == null || sentenceId.isBlank()
                || taskId == null || taskId.isBlank()) return null;
        String sourceId = sentenceId + ":" + taskId;
        ReputationStore data = store();
        String key = "PRISON_TASK:" + sourceId;
        String existingId = data.idempotency.get(key);
        if (existingId != null) return data.events.get(existingId);
        ReputationState state = state(player);
        int earned = state.prisonTaskRehabilitation.getOrDefault(sentenceId, 0);
        int remaining = Math.max(0, ctx.policies().reputationPrisonTaskCap - earned);
        int requested = Math.min(Math.max(0, ctx.policies().reputationPrisonTaskDelta), remaining);
        ReputationEvent event = apply(player, player, "PRISON_TASK", sourceId,
                requested, "Sarcină de penitenciar finalizată; reabilitare până la neutral", true);
        data = store();
        ReputationState stored = data.states.get(uuid(player));
        if (stored != null) {
            if (stored.prisonTaskRehabilitation == null) {
                stored.prisonTaskRehabilitation = new java.util.LinkedHashMap<>();
            }
            stored.prisonTaskRehabilitation.put(sentenceId,
                    earned + (event == null ? 0 : Math.max(0, event.delta)));
            ctx.reputation().write(data);
        }
        return event;
    }

    public synchronized ReputationEvent completeFinePayment(PlayerGateway player, String fineId) {
        return apply(null, player, "FINE_PAID", fineId,
                ctx.policies().reputationFinePaymentDelta,
                "Amendă achitată; reabilitare până la neutral", true);
    }

    public synchronized ReputationEvent completeFinePayment(java.util.UUID playerUuid,
                                                             String playerName, String fineId) {
        if (playerUuid == null || fineId == null || fineId.isBlank()) return null;
        return applyToUuid(null, playerUuid.toString(), playerName, "FINE_PAID",
                fineId, ctx.policies().reputationFinePaymentDelta,
                "Amendă achitată; reabilitare până la neutral", true);
    }

    public synchronized ReputationEvent commend(PlayerGateway actor, PlayerGateway subject,
                                                int delta, String reason) {
        if (actor == null || !players.isCommissioner(actor) || reason == null || reason.isBlank()) {
            if (actor != null) actor.tell("Comanda trebuie să fie Comisar și să aibă motiv.");
            return null;
        }
        int bounded = Math.max(-ctx.policies().reputationMaxScore,
                Math.min(ctx.policies().reputationMaxScore, delta));
        return apply(actor, subject, "COMMISSAR_CORRECTION",
                ctx.ids().token(), bounded, reason, false);
    }

    public synchronized ReputationEvent reverseSource(PlayerGateway actor, String sourceType,
                                                       String sourceId) {
        ReputationStore data = store();
        if (actor == null || !players.isCommissioner(actor)) {
            if (actor != null) actor.tell("Doar Comisaru' poate inversa un eveniment de reputație.");
            return null;
        }
        String requestedSourceType = sourceType == null ? "" : sourceType;
        String requestedSourceId = sourceId == null ? "" : sourceId;
        ReputationEvent original = data.events.values().stream()
                .filter(e -> e != null && !e.voided
                        && requestedSourceType.equals(e.sourceType)
                        && requestedSourceId.equals(e.sourceRecordId))
                .findFirst().orElse(null);
        if (original == null) return null;
        ReputationEvent reversal = applyToUuid(actor, original.playerUuid, original.playerName,
                "REVERSAL", original.id, -original.delta,
                "Reversarea evenimentului " + original.id, false);
        if (reversal != null) {
            data = store();
            ReputationEvent stored = data.events.get(original.id);
            if (stored != null) stored.voided = true;
            ctx.reputation().write(data);
            audit.record("reputation_reversal", actor == null ? "Sistem" : actor.name(),
                    uuid(actor), original.id, original.playerUuid, "SUCCESS", reversal.id);
        }
        return reversal;
    }

    public synchronized boolean recruitmentAllowed(PlayerGateway player) {
        ReputationState state = state(player);
        if (state == null || !ctx.policies().reputationEnabled) return true;
        return state.score >= ctx.policies().recruitmentMinReputation;
    }

    public synchronized List<ReputationEvent> history(PlayerGateway viewer, PlayerGateway subject) {
        if (viewer == null || subject == null) return List.of();
        if (!players.isCommissioner(viewer)) return List.of();
        return store().events.values().stream()
                .filter(e -> e != null && uuid(subject).equals(e.playerUuid)).toList();
    }

    public String bandFor(int score) { return band(score); }

    private boolean recentlyHostile(PlayerGateway attacker, PlayerGateway victim) {
        String key = uuid(attacker) + ":" + uuid(victim);
        long cutoff = now() - ctx.policies().lawfulHostilityWindowSeconds * SECOND;
        return store().recentHostileDamageAt.getOrDefault(key, 0L) >= cutoff;
    }

    private static String band(int score) {
        if (score <= -300) return "INFAM";
        if (score <= -100) return "RAU_VAZUT";
        if (score <= 99) return "NEUTRU";
        if (score <= 299) return "RESPECTAT";
        return "EXEMPLAR";
    }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\p{Cntrl}", " ");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    private static String stableId(String value, PlayerGateway subject) {
        if (value != null && !value.isBlank()) return value.trim();
        return subject.uuid() + ":" + subject.name();
    }
}
