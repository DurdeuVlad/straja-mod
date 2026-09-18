package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.Incident;
import com.dwurdy.straja.domain.model.IncidentPriority;
import com.dwurdy.straja.domain.model.IncidentResolution;
import com.dwurdy.straja.domain.model.IncidentStatus;
import com.dwurdy.straja.domain.model.IncidentStore;
import com.dwurdy.straja.domain.model.IncidentType;
import com.dwurdy.straja.domain.model.Rank;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Operational dispatch lifecycle. Missions intentionally remain separate. */
public final class IncidentService {
    private static final long SECOND = 1_000L;
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public IncidentService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private long now() { return ctx.clock().nowMillis(); }

    private IncidentStore store() {
        IncidentStore store = ctx.incidents().read();
        if (store.incidents == null) store.incidents = new ArrayList<>();
        if (store.lastWhistleAt == null) store.lastWhistleAt = new java.util.LinkedHashMap<>();
        if (store.lastCitizenReportAt == null) {
            store.lastCitizenReportAt = new java.util.LinkedHashMap<>();
        }
        for (Incident incident : store.incidents) {
            if (incident == null) continue;
            if (incident.supportingGuardUuids == null) incident.supportingGuardUuids = new ArrayList<>();
            if (incident.supportingGuardNames == null) incident.supportingGuardNames = new ArrayList<>();
            if (incident.placeLabel == null) incident.placeLabel = "";
            if (incident.leadGuardUuid == null) incident.leadGuardUuid = "";
            if (incident.leadGuardName == null) incident.leadGuardName = "";
        }
        return store;
    }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    private boolean enabled(PlayerGateway actor) {
        if (ctx.policies().incidentsEnabled) return true;
        if (actor != null) actor.tell("Sistemul de incidente este dezactivat.");
        return false;
    }

    public synchronized Incident createCitizenReport(PlayerGateway citizen,
                                                       String category,
                                                       String description) {
        if (citizen == null || !enabled(citizen)) return null;
        IncidentStore data = store();
        String key = uuid(citizen);
        long last = data.lastCitizenReportAt.getOrDefault(key, 0L);
        if (last > 0 && now() - last < ctx.policies().incidentCitizenReportCooldownSeconds * SECOND) {
            citizen.tell("Ai raportat deja un incident recent. Așteaptă înainte de a trimite altul.");
            audit.record("incident_create", citizen.name(), key, "", "", "REFUSED", "cooldown");
            return null;
        }
        long active = data.incidents.stream()
                .filter(i -> i != null && key.equals(i.createdByUuid)
                        && i.type == IncidentType.CITIZEN_REPORT
                        && isActive(i.status))
                .count();
        if (active >= ctx.policies().incidentMaxActiveCitizenReports) {
            citizen.tell("Ai atins limita de incidente active raportate de tine.");
            audit.record("incident_create", citizen.name(), key, "", "", "REFUSED", "active_limit");
            return null;
        }
        Incident incident = create(data, citizen, IncidentType.CITIZEN_REPORT,
                IncidentPriority.ROUTINE, category, description, citizen, "RECEPTIONIST");
        if (incident == null) return null;
        data.lastCitizenReportAt.put(key, now());
        ctx.incidents().write(data);
        citizen.tell("Incidentul " + incident.id + " a fost înregistrat. Straja a fost informată.");
        return incident;
    }

    public synchronized Incident useWhistle(PlayerGateway guard) {
        if (guard == null || !enabled(guard) || !ctx.policies().whistleEnabled) return null;
        if (!players.isOnDutyGuard(guard)) {
            guard.tell("Fluierul poate fi folosit doar în timpul serviciului.");
            audit.record("whistle", guard.name(), uuid(guard), "", "", "REFUSED", "not_on_duty");
            return null;
        }
        IncidentStore data = store();
        String key = uuid(guard);
        long last = data.lastWhistleAt.getOrDefault(key, 0L);
        long cooldown = ctx.policies().whistleCooldownSeconds * SECOND;
        if (last > 0 && now() - last < cooldown) {
            guard.tell("Fluierul se reîncarcă. Mai așteaptă "
                    + Math.max(1, (cooldown - (now() - last)) / SECOND) + " secunde.");
            audit.record("whistle", guard.name(), key, "", "", "REFUSED", "cooldown");
            return null;
        }
        Incident existing = data.incidents.stream()
                .filter(i -> i != null && isActive(i.status)
                        && i.type == IncidentType.GUARD_ASSISTANCE
                        && key.equals(i.createdByUuid))
                .findFirst().orElse(null);
        if (existing != null) {
            refreshLocation(existing, guard);
            existing.priority = IncidentPriority.URGENT;
            existing.expiresAt = now() + ctx.policies().incidentDefaultExpirationSeconds * SECOND;
            data.lastWhistleAt.put(key, now());
            ctx.incidents().write(data);
            notifyUrgent(existing);
            audit.record("whistle", guard.name(), key, existing.id, "", "SUCCESS", "refreshed");
            guard.tell("Cererea " + existing.id + " a fost reîmprospătată.");
            return existing;
        }
        Incident incident = create(data, guard, IncidentType.GUARD_ASSISTANCE,
                IncidentPriority.URGENT, "Ajutor Străjer",
                "Solicitare urgentă de asistență.", null, "ALARM_WHISTLE");
        if (incident == null) return null;
        data.lastWhistleAt.put(key, now());
        ctx.incidents().write(data);
        notifyUrgent(incident);
        ctx.world().playSoundAt(guard.dimension(), guard.x(), guard.y(), guard.z(),
                ctx.policies().whistleSoundRadius, "minecraft:block.note_block.bell");
        audit.record("whistle", guard.name(), key, incident.id, "", "SUCCESS", "created");
        guard.tell("Ai solicitat ajutor: " + incident.id + ".");
        return incident;
    }

    public synchronized Incident createManual(PlayerGateway actor, IncidentType type,
                                               IncidentPriority priority, String title,
                                               String description, PlayerGateway subject) {
        if (actor == null || !enabled(actor)
                || (!players.isCommissioner(actor)
                    && !players.hasCapability(actor, Capability.INVESTIGATE_COMPLAINTS))) {
            if (actor != null) actor.tell("Nu ai autoritatea de a crea incidente.");
            return null;
        }
        IncidentStore data = store();
        Incident incident = create(data, actor, type, priority, title, description,
                subject, "AUTHORIZED_MANUAL");
        if (incident != null) ctx.incidents().write(data);
        return incident;
    }

    /** Existing jailer assault missions and the operational alert share one record. */
    public synchronized Incident createJailerAssault(PlayerGateway attacker, String jailer,
                                                      String outcome) {
        if (attacker == null || !enabled(attacker)) return null;
        IncidentStore data = store();
        String key = uuid(attacker) + ":" + (jailer == null ? "" : jailer);
        Incident existing = data.incidents.stream()
                .filter(i -> i != null && i.type == IncidentType.JAILER_ASSAULT
                        && isActive(i.status) && key.equals(i.provenance))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        Incident incident = create(data, attacker, IncidentType.JAILER_ASSAULT,
                IncidentPriority.URGENT, "Atac asupra Temnicerului",
                "Temnicerul a fost " + (outcome == null ? "atacat" : outcome.toLowerCase(Locale.ROOT)) + ".",
                attacker, "JAILER_ASSAULT");
        if (incident != null) {
            incident.provenance = key;
            ctx.incidents().write(data);
        }
        return incident;
    }

    /** System-generated Comisar review flag; never grants enforcement authority. */
    public synchronized Incident createDisciplinaryFlag(PlayerGateway guard,
                                                         PlayerGateway victim,
                                                         String description) {
        if (guard == null || !enabled(guard)) return null;
        IncidentStore data = store();
        String provenance = "DISCIPLINE:" + uuid(guard) + ":" + uuid(victim);
        Incident existing = data.incidents.stream()
                .filter(i -> i != null && provenance.equals(i.provenance)
                        && isActive(i.status))
                .findFirst().orElse(null);
        if (existing != null) return existing;
        Incident incident = create(data, guard, IncidentType.MANUAL,
                IncidentPriority.IMPORTANT, "Sesizare disciplinară",
                description, victim, "SYSTEM_DISCIPLINE");
        if (incident != null) {
            incident.provenance = provenance;
            ctx.incidents().write(data);
        }
        return incident;
    }

    private Incident create(IncidentStore data, PlayerGateway creator, IncidentType type,
                            IncidentPriority priority, String title, String description,
                            PlayerGateway subject, String source) {
        String cleanTitle = clean(title, 80);
        String cleanDescription = clean(description, ctx.policies().incidentMaxDescriptionLength);
        if (cleanTitle.isBlank() || cleanDescription.isBlank()) {
            if (creator != null) creator.tell("Incidentul trebuie să aibă titlu și descriere.");
            if (creator != null) audit.record("incident_create", creator.name(), uuid(creator),
                    "", "", "REFUSED", "invalid_text");
            return null;
        }
        Incident incident = new Incident();
        incident.id = data.nextIncidentId();
        incident.type = type == null ? IncidentType.MANUAL : type;
        incident.priority = priority == null ? IncidentPriority.ROUTINE : priority;
        incident.title = cleanTitle;
        incident.description = cleanDescription;
        incident.createdAt = now();
        incident.createdByUuid = uuid(creator);
        incident.createdByName = creator == null ? "Sistem" : creator.name();
        incident.source = source == null ? "UNKNOWN" : source;
        incident.expiresAt = now() + ctx.policies().incidentDefaultExpirationSeconds * SECOND;
        if (creator != null) refreshLocation(incident, creator);
        if (subject != null) {
            incident.subjectUuid = uuid(subject);
            incident.subjectName = subject.name();
        }
        data.incidents.add(incident);
        audit.record("incident_create", incident.createdByName, incident.createdByUuid,
                incident.id, "", "SUCCESS", incident.source);
        return incident;
    }

    private void refreshLocation(Incident incident, PlayerGateway player) {
        incident.dimension = player.dimension();
        incident.x = player.x();
        incident.y = player.y();
        incident.z = player.z();
        incident.placeLabel = placeLabel(player);
    }

    private String placeLabel(PlayerGateway player) {
        var setup = ctx.setup().read();
        for (var entry : setup.locations.entrySet()) {
            var location = entry.getValue();
            if (location == null || !player.dimension().equals(location.dimension)) continue;
            double dx = player.x() - location.x;
            double dy = player.y() - location.y;
            double dz = player.z() - location.z;
            if (dx * dx + dy * dy + dz * dz <= 12 * 12) {
                return switch (entry.getKey()) {
                    case "secretary" -> "Secretariat";
                    case "receptionist" -> "Recepție";
                    case "trainer" -> "Instructor";
                    case "armorer" -> "Armerie";
                    case "hq" -> "Cartierul general";
                    default -> entry.getKey();
                };
            }
        }
        return "";
    }

    private void notifyUrgent(Incident incident) {
        String lead = incident.createdByName;
        String location = incident.placeLabel.isBlank()
                ? String.format(Locale.ROOT, "(x: %.0f, y: %.0f, z: %.0f)",
                        incident.x, incident.y, incident.z)
                : incident.placeLabel;
        for (PlayerGateway guard : ctx.server().onlinePlayers()) {
            if (players.isOnDutyGuard(guard)) {
                guard.tell("[URGENT] " + incident.id + " — " + incident.title
                        + " — " + location + " (" + lead + ")");
            }
        }
    }

    public synchronized List<Incident> active() {
        expire();
        return store().incidents.stream()
                .filter(i -> i != null && isActive(i.status))
                .sorted(Comparator.comparing((Incident i) -> i.priority == IncidentPriority.URGENT ? 0
                        : i.priority == IncidentPriority.IMPORTANT ? 1 : 2)
                        .thenComparingLong(i -> i.createdAt))
                .toList();
    }

    public synchronized boolean accept(PlayerGateway guard, String incidentId) {
        if (!guardReady(guard)) return false;
        IncidentStore data = store();
        Incident incident = data.find(incidentId);
        if (incident == null || !isActive(incident.status)) {
            guard.tell("Incidentul nu mai este activ.");
            return false;
        }
        if (!incident.leadGuardUuid.isBlank()) {
            guard.tell("Incidentul este deja preluat de " + incident.leadGuardName + ".");
            return false;
        }
        incident.leadGuardUuid = uuid(guard);
        incident.leadGuardName = guard.name();
        incident.acceptedAt = now();
        incident.status = IncidentStatus.ASSIGNED;
        ctx.incidents().write(data);
        audit.record("incident_accept", guard.name(), uuid(guard), incident.id, "", "SUCCESS", "");
        guard.tell("Ai preluat " + incident.id + ".");
        return true;
    }

    public synchronized boolean join(PlayerGateway guard, String incidentId) {
        if (!guardReady(guard)) return false;
        IncidentStore data = store();
        Incident incident = data.find(incidentId);
        if (incident == null || !isActive(incident.status)) {
            guard.tell("Incidentul nu mai este activ.");
            return false;
        }
        String key = uuid(guard);
        if (key.equals(incident.leadGuardUuid)
                || incident.supportingGuardUuids.contains(key)) return true;
        if (incident.supportingGuardUuids.size() >= ctx.policies().incidentMaxSupportingGuards) {
            guard.tell("Incidentul are deja numărul maxim de sprijinitori.");
            return false;
        }
        incident.supportingGuardUuids.add(key);
        incident.supportingGuardNames.add(guard.name());
        if (incident.status == IncidentStatus.OPEN) incident.status = IncidentStatus.ASSIGNED;
        ctx.incidents().write(data);
        audit.record("incident_join", guard.name(), key, incident.id, "", "SUCCESS", "");
        guard.tell("Te-ai alăturat incidentului " + incident.id + ".");
        return true;
    }

    public synchronized boolean leave(PlayerGateway guard, String incidentId) {
        if (guard == null) return false;
        IncidentStore data = store();
        Incident incident = data.find(incidentId);
        if (incident == null || !isActive(incident.status)) {
            if (guard != null) guard.tell("Incidentul nu mai este activ.");
            return false;
        }
        String key = uuid(guard);
        if (key.equals(incident.leadGuardUuid)) {
            incident.leadGuardUuid = "";
            incident.leadGuardName = "";
            incident.acceptedAt = null;
            if (incident.supportingGuardUuids.isEmpty()) incident.status = IncidentStatus.OPEN;
        } else {
            int index = incident.supportingGuardUuids.indexOf(key);
            if (index < 0) return false;
            incident.supportingGuardUuids.remove(index);
            if (index < incident.supportingGuardNames.size()) incident.supportingGuardNames.remove(index);
        }
        ctx.incidents().write(data);
        audit.record("incident_leave", guard.name(), key, incident.id, "", "SUCCESS", "");
        return true;
    }

    public synchronized boolean resolve(PlayerGateway guard, String incidentId,
                                        String resolution, String notes) {
        if (guard == null) return false;
        IncidentStore data = store();
        Incident incident = data.find(incidentId);
        if (incident == null || !isActive(incident.status)) {
            if (guard != null) guard.tell("Incidentul nu mai este activ.");
            return false;
        }
        String key = uuid(guard);
        boolean lead = key.equals(incident.leadGuardUuid);
        if (!lead && !players.isCommissioner(guard)
                && !players.hasCapability(guard, Capability.INVESTIGATE_COMPLAINTS)) {
            guard.tell("Doar Străjerul principal sau un superior poate încheia incidentul.");
            return false;
        }
        IncidentResolution code;
        try {
            code = IncidentResolution.valueOf((resolution == null ? "" : resolution.trim())
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            guard.tell("Cod de rezolvare necunoscut.");
            return false;
        }
        incident.status = IncidentStatus.RESOLVED;
        incident.resolution = code;
        incident.resolutionNotes = clean(notes, ctx.policies().incidentMaxDescriptionLength);
        incident.resolvedAt = now();
        ctx.incidents().write(data);
        audit.record("incident_resolve", guard.name(), key, incident.id, "", "SUCCESS",
                code.name());
        guard.tell("Incidentul " + incident.id + " a fost încheiat.");
        return true;
    }

    public synchronized void removeAssignments(PlayerGateway guard, String reason) {
        if (guard == null) return;
        IncidentStore data = store();
        String key = uuid(guard);
        boolean changed = false;
        for (Incident incident : data.incidents) {
            if (incident == null || !isActive(incident.status)) continue;
            if (key.equals(incident.leadGuardUuid)) {
                incident.leadGuardUuid = "";
                incident.leadGuardName = "";
                incident.acceptedAt = null;
                incident.status = incident.supportingGuardUuids.isEmpty()
                        ? IncidentStatus.OPEN : IncidentStatus.ASSIGNED;
                changed = true;
            }
            int before = incident.supportingGuardUuids.size();
            for (int i = incident.supportingGuardUuids.size() - 1; i >= 0; i--) {
                if (key.equals(incident.supportingGuardUuids.get(i))) {
                    incident.supportingGuardUuids.remove(i);
                    if (i < incident.supportingGuardNames.size()) incident.supportingGuardNames.remove(i);
                }
            }
            changed |= before != incident.supportingGuardUuids.size();
        }
        if (changed) {
            ctx.incidents().write(data);
            audit.record("incident_assignment_removed", "system", "", key, "", "SUCCESS",
                    reason == null ? "" : reason);
        }
    }

    public synchronized void expire() {
        IncidentStore data = store();
        boolean changed = false;
        for (Incident incident : data.incidents) {
            if (incident != null && isActive(incident.status)
                    && incident.expiresAt > 0 && incident.expiresAt <= now()) {
                incident.status = IncidentStatus.EXPIRED;
                incident.resolvedAt = now();
                incident.resolution = IncidentResolution.OTHER;
                changed = true;
                audit.record("incident_expire", "system", "", incident.id, "", "SUCCESS", "");
            }
        }
        if (changed) ctx.incidents().write(data);
    }

    public static boolean isActive(IncidentStatus status) {
        return status == IncidentStatus.OPEN || status == IncidentStatus.ASSIGNED;
    }

    private boolean guardReady(PlayerGateway guard) {
        if (guard == null || !players.isOnDutyGuard(guard)) {
            if (guard != null) guard.tell("Trebuie să fii Străjer activ pentru a lucra la incidente.");
            return false;
        }
        return true;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\p{Cntrl}", " ");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }
}
