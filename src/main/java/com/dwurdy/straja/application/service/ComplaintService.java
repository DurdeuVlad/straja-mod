package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.Complaint;
import com.dwurdy.straja.domain.model.ComplaintStore;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayList;
import java.util.List;

/** Citizen complaints: submission, investigation, report, review and rewards. */
public class ComplaintService {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public ComplaintService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private StrajaPolicies p() {
        return ctx.policies();
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    private SetupData.Location location(String key) {
        return ctx.setup().read().location(key);
    }

    private static boolean near(PlayerGateway player, SetupData.Location at, double radius) {
        if (at == null) return false;
        double dx = player.x() - at.x, dy = player.y() - at.y, dz = player.z() - at.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private boolean atLocation(PlayerGateway player, String... keys) {
        for (String key : keys) if (near(player, location(key), 6)) return true;
        return false;
    }

    private boolean canInvestigate(PlayerGateway player) {
        return players.hasCapability(player, Capability.INVESTIGATE_COMPLAINTS);
    }

    private boolean canReview(PlayerGateway player) {
        return players.isCommissioner(player) || players.hasCapability(player, Capability.APPROVE_REWARDS);
    }

    /**
     * Reward keys: UUID when the record is UUID-bound, otherwise a namespaced
     * legacy name key ("name:<canon>") for name-only migrated records. A UUID
     * key can never be claimed by a name-spoofed player.
     */
    private List<String> participantKeys(Complaint complaint) {
        List<String> keys = new ArrayList<>();
        addKey(keys, complaint.leadUuid, complaint.lead);
        for (Complaint.Participant participant : complaint.participants) {
            if ("JOINED".equals(participant.status)) addKey(keys, participant.uuid, participant.name);
        }
        return keys;
    }

    private static void addKey(List<String> keys, String uuid, String name) {
        String key = uuid != null && !uuid.isEmpty() ? uuid
                : (name != null && !name.isEmpty() ? "name:" + PlayerService.canon(name) : "");
        if (!key.isEmpty() && !keys.contains(key)) keys.add(key);
    }

    /** The key under which this player may claim a share, or null. */
    private static String matchingKey(PlayerGateway player, List<String> keys) {
        String uuid = player.uuid() == null ? "" : player.uuid().toString();
        if (!uuid.isEmpty() && keys.contains(uuid)) return uuid;
        String nameKey = "name:" + PlayerService.canon(player.name());
        return keys.contains(nameKey) ? nameKey : null;
    }

    private int rewardSuggestion(Complaint complaint) {
        Integer configured = p().complaintRewardBySeverity.get(complaint.severity);
        return configured != null ? configured : p().complaintRewardBySeverity.getOrDefault(1, 25);
    }

    // ---------------------------------------------------------------- submit

    public boolean submit(PlayerGateway player, String accused, String category, String description) {
        if (!p().complaintsEnabled) {
            player.tell("Registrul de plângeri este dezactivat.");
            return false;
        }
        if (!atLocation(player, "receptionist")) {
            player.tell("Plângerea se depune la recepționistă.");
            return false;
        }
        String text = description == null ? "" : description.trim();
        if (accused == null || accused.isBlank() || category == null || category.isBlank()
                || text.isEmpty() || text.length() > p().complaintMaxDescriptionLength) {
            player.tell("Folosește: /straja complaint submit <acuzat> <categorie> <descriere>.");
            return false;
        }
        ComplaintStore data = ctx.complaints().read();
        String key = player.uuid().toString();
        long active = data.complaints.stream().filter(c -> c.isOpen()
                && PlayerService.identityMatches(player, c.complainantUuid, c.complainant)).count();
        if (active >= p().complaintMaxActivePerComplainant) {
            player.tell("Ai prea multe plângeri deschise. Așteaptă soluționarea lor.");
            return false;
        }
        Complaint complaint = new Complaint();
        complaint.id = data.nextComplaintId();
        complaint.complainant = player.name();
        complaint.complainantUuid = key;
        complaint.accused = accused.substring(0, Math.min(80, accused.length()));
        complaint.category = category.substring(0, Math.min(80, category.length()));
        complaint.description = text;
        complaint.severity = Math.max(1, p().complaintDefaultSeverity);
        complaint.status = "SUBMITTED";
        complaint.createdAt = now();
        data.complaints.add(complaint);
        ctx.complaints().write(data);
        audit.record("complaint_submit", player.name(), key, complaint.accused, null, "SUCCESS", "submitted complaintId=" + complaint.id + " category=" + complaint.category);
        player.tell("Plângerea " + complaint.id + " a fost înregistrată. Vei putea confirma rezultatul la recepționistă.");
        return true;
    }

    public void list(PlayerGateway player) {
        if (!canInvestigate(player) && !canReview(player)) {
            player.tell("Doar Seniorul sau un rang superior poate vedea dosarele.");
            return;
        }
        ComplaintStore data = ctx.complaints().read();
        int shown = 0;
        for (int i = data.complaints.size() - 1; i >= 0 && shown < 30; i--) {
            Complaint c = data.complaints.get(i);
            if (!c.isOpen()) continue;
            player.tell(c.id + " [" + c.status + "] " + c.category + " | " + c.complainant + " → " + c.accused
                    + (c.lead.isEmpty() ? "" : " | lead: " + c.lead));
            shown++;
        }
    }

    // ---------------------------------------------------------------- claim/mobilize

    public boolean claim(PlayerGateway player, String id) {
        if (!canInvestigate(player)) {
            player.tell("Doar Străjerul Senior sau un rang superior poate prelua plângeri.");
            return false;
        }
        ComplaintStore data = ctx.complaints().read();
        Complaint complaint = data.find(id);
        if (complaint == null || !List.of("SUBMITTED", "CLAIMED").contains(complaint.status)) {
            player.tell("Plângerea nu este disponibilă pentru preluare.");
            return false;
        }
        if (!complaint.leadUuid.isEmpty() && !complaint.leadUuid.equals(player.uuid().toString())) {
            player.tell("Plângerea este deja preluată de " + complaint.lead + ".");
            return false;
        }
        complaint.lead = player.name();
        complaint.leadUuid = player.uuid().toString();
        complaint.status = "CLAIMED";
        complaint.claimedAt = now();
        ctx.complaints().write(data);
        audit.record("complaint_claim", player.name(), player.uuid().toString(), complaint.complainant, complaint.complainantUuid, "SUCCESS", "case_lead complaintId=" + complaint.id);
        player.tell("Ai preluat dosarul " + complaint.id + ".");
        return true;
    }

    public boolean mobilize(PlayerGateway player, String id, PlayerGateway target) {
        if (!players.hasCapability(player, Capability.MOBILIZE_PLAYERS)) {
            player.tell("Doar Străjerul Senior sau un rang superior poate mobiliza participanți.");
            return false;
        }
        ComplaintStore data = ctx.complaints().read();
        Complaint complaint = data.find(id);
        if (complaint == null || !PlayerService.identityMatches(player, complaint.leadUuid, complaint.lead)
                || !complaint.isOpen()) {
            player.tell("Dosarul nu îți este atribuit.");
            return false;
        }
        if (target == null || !players.hasCapability(target, Capability.ASSIST_COMPLAINTS)) {
            player.tell("Participantul trebuie să fie o gardă activă.");
            return false;
        }
        if (!players.isCommissioner(player) && players.state(target).rank >= players.state(player).rank) {
            player.tell("Poți mobiliza doar Juniori și Străjeri.");
            return false;
        }
        for (Complaint.Participant participant : complaint.participants) {
            if (PlayerService.identityMatches(target, participant.uuid, participant.name)
                    && !"LEFT".equals(participant.status)) {
                player.tell("Jucătorul este deja pe dosar.");
                return false;
            }
        }
        long joined = complaint.participants.stream().filter(item -> !"LEFT".equals(item.status)).count();
        if (joined >= p().complaintMaxParticipants) {
            player.tell("Dosarul are deja numărul maxim de participanți.");
            return false;
        }
        Complaint.Participant participant = new Complaint.Participant();
        participant.uuid = target.uuid().toString();
        participant.name = target.name();
        participant.rank = players.state(target).rank;
        participant.status = "INVITED";
        participant.invitedAt = now();
        complaint.participants.add(participant);
        ctx.complaints().write(data);
        target.tell("Seniorul " + player.name() + " te-a mobilizat pentru dosarul " + complaint.id
                + ". Prezintă-te la secretară și folosește /straja complaint join " + complaint.id + ".");
        audit.record("complaint_mobilize", player.name(), player.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", "invited complaintId=" + complaint.id);
        player.tell(target.name() + " a fost mobilizat pe dosarul " + complaint.id + ".");
        return true;
    }

    public boolean join(PlayerGateway player, String id) {
        if (!players.hasCapability(player, Capability.ASSIST_COMPLAINTS)) {
            player.tell("Doar o gardă activă poate participa la o investigație.");
            return false;
        }
        if (!atLocation(player, "secretary", "receptionist")) {
            player.tell("Raportarea pentru dosar se face la secretară.");
            return false;
        }
        ComplaintStore data = ctx.complaints().read();
        Complaint complaint = data.find(id);
        Complaint.Participant participant = null;
        if (complaint != null) {
            for (Complaint.Participant p : complaint.participants) {
                if (PlayerService.identityMatches(player, p.uuid, p.name) && "INVITED".equals(p.status)) participant = p;
            }
        }
        if (participant == null) {
            player.tell("Nu ai o mobilizare activă pentru acest dosar.");
            return false;
        }
        participant.status = "JOINED";
        participant.joinedAt = now();
        if ("CLAIMED".equals(complaint.status)) complaint.status = "INVESTIGATING";
        ctx.complaints().write(data);
        audit.record("complaint_join", player.name(), player.uuid().toString(), complaint.complainant, complaint.complainantUuid, "SUCCESS", "reported_at_secretary complaintId=" + complaint.id);
        player.tell("Te-ai raportat pentru dosarul " + complaint.id + ".");
        return true;
    }

    public boolean leave(PlayerGateway player, String id) {
        ComplaintStore data = ctx.complaints().read();
        Complaint complaint = data.find(id);
        Complaint.Participant participant = null;
        if (complaint != null) {
            for (Complaint.Participant p : complaint.participants) {
                if (PlayerService.identityMatches(player, p.uuid, p.name) && "JOINED".equals(p.status)) participant = p;
            }
        }
        if (participant == null) {
            player.tell("Nu ești participant activ pe acest dosar.");
            return false;
        }
        participant.status = "LEFT";
        participant.leftAt = now();
        ctx.complaints().write(data);
        audit.record("complaint_leave", player.name(), player.uuid().toString(), complaint.complainant, complaint.complainantUuid, "SUCCESS", "left_case complaintId=" + complaint.id);
        player.tell("Ai ieșit din dosarul " + complaint.id + ". Retragerea rămâne în istoricul cazului.");
        return true;
    }

    // ---------------------------------------------------------------- report/review

    public boolean report(PlayerGateway player, String id, String report) {
        if (!canInvestigate(player)) {
            player.tell("Doar Seniorul sau un rang superior poate depune raportul de investigație.");
            return false;
        }
        String text = report == null ? "" : report.trim();
        if (text.isEmpty() || text.length() > p().complaintMaxEvidenceLength) {
            player.tell("Raportul trebuie completat și să aibă maximum " + p().complaintMaxEvidenceLength + " caractere.");
            return false;
        }
        ComplaintStore data = ctx.complaints().read();
        Complaint complaint = data.find(id);
        if (complaint == null || !PlayerService.identityMatches(player, complaint.leadUuid, complaint.lead)
                || !List.of("CLAIMED", "INVESTIGATING").contains(complaint.status)) {
            player.tell("Dosarul nu îți este atribuit sau nu mai acceptă raport.");
            return false;
        }
        complaint.report = text;
        complaint.reportAt = now();
        complaint.status = "REPORT_SUBMITTED";
        ctx.complaints().write(data);
        PlayerGateway complainant = ctx.server().findPlayer(complaint.complainant);
        if (complainant != null) complainant.tell("Dosarul " + complaint.id + " are un raport. Mergi la recepționistă și confirmă rezultatul sau retrage plângerea.");
        audit.record("complaint_report", player.name(), player.uuid().toString(), complaint.complainant, complaint.complainantUuid, "SUCCESS", "report_submitted complaintId=" + complaint.id);
        player.tell("Raportul dosarului " + complaint.id + " a fost trimis spre verificare.");
        return true;
    }

    public boolean complainantDecision(PlayerGateway player, String id, String decision, String reason) {
        if (!atLocation(player, "receptionist")) {
            player.tell("Confirmarea plângerii se face la recepționistă.");
            return false;
        }
        ComplaintStore data = ctx.complaints().read();
        Complaint complaint = data.find(id);
        if (complaint == null
                || !PlayerService.identityMatches(player, complaint.complainantUuid, complaint.complainant)) {
            player.tell("Acest dosar nu îți aparține.");
            return false;
        }
        if (!List.of("REPORT_SUBMITTED", "UNDER_REVIEW").contains(complaint.status)) {
            player.tell("Dosarul nu are încă un raport final.");
            return false;
        }
        String action = decision == null ? "" : decision.toLowerCase();
        if (List.of("retrage", "withdraw", "anuleaza", "anulează").contains(action)) {
            complaint.status = "WITHDRAWN";
            complaint.complainantDecision = "WITHDRAWN";
            complaint.withdrawnAt = now();
            complaint.withdrawReason = reason == null ? "" : reason.substring(0, Math.min(240, reason.length()));
            ctx.complaints().write(data);
            audit.record("complaint_withdraw", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "complainant_withdrew complaintId=" + complaint.id);
            player.tell("Plângerea " + complaint.id + " a fost retrasă și trimisă în istoricul secției.");
            return true;
        }
        if (!List.of("satisfy", "satisfacut", "satisfăcut", "satisfied", "accept", "confirm").contains(action)) {
            player.tell("Folosește confirm sau retrage.");
            return false;
        }
        complaint.status = "UNDER_REVIEW";
        complaint.complainantDecision = "SATISFIED";
        complaint.satisfiedAt = now();
        ctx.complaints().write(data);
        audit.record("complaint_satisfied", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "complainant_confirmed complaintId=" + complaint.id);
        player.tell("Ai confirmat că dosarul " + complaint.id + " a fost soluționat.");
        return true;
    }

    // ---------------------------------------------------------------- rewards

    private boolean reserveBudget(PlayerGateway player, int amount, ComplaintStore data) {
        if (players.isCommissioner(player)) return true;
        int limit = Math.max(0, p().complaintMaxRewardPerReviewerPerDay);
        long window = now() / DAY_MS;
        String key = window + ":" + player.uuid();
        int used = data.rewardBudgets.getOrDefault(key, 0);
        if (amount < 0 || used + amount > limit) {
            player.tell("Bugetul zilnic al Locotenentului este insuficient (" + used + "/" + limit + ").");
            return false;
        }
        data.rewardBudgets.put(key, used + amount);
        return true;
    }

    private int deliverReward(ComplaintStore data, Complaint complaint) {
        int total = Math.max(0, complaint.rewardTotal);
        if (total == 0) {
            complaint.rewardStatus = "PAID";
            return 0;
        }
        List<String> keys = participantKeys(complaint);
        if (complaint.rewardShares.isEmpty()) {
            int base = keys.isEmpty() ? 0 : total / keys.size();
            int remainder = total - base * keys.size();
            for (String key : keys) {
                complaint.rewardShares.put(key, base + (remainder > 0 ? 1 : 0));
                if (remainder > 0) remainder--;
            }
        }
        for (String key : keys) {
            if (!"PAID".equals(complaint.rewardClaims.get(key))) complaint.rewardClaims.put(key, "PENDING");
        }
        int delivered = 0;
        for (PlayerGateway recipient : ctx.server().onlinePlayers()) {
            String key = matchingKey(recipient, keys);
            if (key == null || "PAID".equals(complaint.rewardClaims.get(key))) continue;
            int amount = complaint.rewardShares.getOrDefault(key, 0);
            var payout = ctx.currency().deposit(recipient, amount, "complaint:" + complaint.id + ":" + key);
            if (payout.ok()) {
                complaint.rewardClaims.put(key, "PAID");
                delivered += amount;
                recipient.tell("Ai primit " + amount + " monede pentru investigația " + complaint.id + ".");
            }
        }
        complaint.rewardStatus = keys.stream().allMatch(k -> "PAID".equals(complaint.rewardClaims.get(k))) ? "PAID" : "PENDING";
        return delivered;
    }

    /** Re-delivers pending rewards to a player that just came online. */
    public void claimPendingRewards(PlayerGateway player) {
        ComplaintStore data = ctx.complaints().read();
        boolean changed = false;
        for (Complaint complaint : data.complaints) {
            if (!"CLOSED".equals(complaint.status) || !"PENDING".equals(complaint.rewardStatus)) continue;
            String key = matchingKey(player, participantKeys(complaint));
            if (key == null || "PAID".equals(complaint.rewardClaims.get(key))) continue;
            int before = complaint.rewardClaims.size();
            deliverReward(data, complaint);
            changed = changed || complaint.rewardClaims.size() != before || "PAID".equals(complaint.rewardStatus);
        }
        if (changed) ctx.complaints().write(data);
    }

    public boolean review(PlayerGateway player, String id, String decision, Integer reward) {
        if (!canReview(player)) {
            player.tell("Doar Locotenentul sau Comisaru' poate verifica și plăti dosare.");
            return false;
        }
        ComplaintStore data = ctx.complaints().read();
        Complaint complaint = data.find(id);
        if (complaint == null || !List.of("REPORT_SUBMITTED", "UNDER_REVIEW").contains(complaint.status)) {
            player.tell("Dosarul nu este pregătit pentru verificare.");
            return false;
        }
        if (PlayerService.identityMatches(player, complaint.complainantUuid, complaint.complainant)) {
            player.tell("Nu îți poți verifica propriul dosar.");
            return false;
        }
        String action = decision == null ? "" : decision.toLowerCase();
        if (List.of("return", "trimite-inapoi", "corectie").contains(action)) {
            complaint.status = "INVESTIGATING";
            complaint.reviewedBy = player.name();
            complaint.reviewNote = "Raport returnat pentru completări.";
            ctx.complaints().write(data);
            player.tell("Dosarul a fost returnat pentru completări.");
            return true;
        }
        if (List.of("dismiss", "respinge", "nefondat").contains(action)) {
            complaint.status = "CLOSED";
            complaint.resolution = "UNFOUNDED";
            complaint.rewardTotal = 0;
            complaint.rewardStatus = "NONE";
            complaint.reviewedBy = player.name();
            complaint.closedAt = now();
            ctx.complaints().write(data);
            audit.record("complaint_close", player.name(), player.uuid().toString(), complaint.complainant, complaint.complainantUuid, "SUCCESS", "unfounded complaintId=" + complaint.id);
            player.tell("Dosarul " + complaint.id + " a fost închis ca nefondat.");
            return true;
        }
        if (!List.of("approve", "aproba", "rezolva", "resolve").contains(action)) {
            player.tell("Folosește approve, return sau dismiss.");
            return false;
        }
        if (!"SATISFIED".equals(complaint.complainantDecision) && !players.isCommissioner(player)) {
            player.tell("Așteaptă confirmarea petentului sau folosește override-ul Comisarului.");
            return false;
        }
        int configuredReward = reward == null ? rewardSuggestion(complaint) : reward;
        int maximum = Math.max(0, p().complaintMaxReward);
        if (configuredReward < 0 || configuredReward > maximum) {
            player.tell("Reward-ul trebuie să fie un întreg între 0 și " + maximum + ".");
            return false;
        }
        if (!reserveBudget(player, configuredReward, data)) return false;
        complaint.status = "CLOSED";
        complaint.resolution = "RESOLVED";
        complaint.reviewedBy = player.name();
        complaint.reviewedByUuid = player.uuid().toString();
        complaint.closedAt = now();
        complaint.rewardTotal = configuredReward;
        complaint.rewardStatus = configuredReward > 0 ? "PENDING" : "NONE";
        complaint.rewardApprovedAt = now();
        complaint.rewardApprovedBy = player.name();
        complaint.rewardBudgetKey = (now() / DAY_MS) + ":" + player.uuid();
        complaint.rewardBudgetAmount = configuredReward;
        complaint.rewardBudgetStatus = configuredReward > 0 ? "RESERVED" : "NONE";
        deliverReward(data, complaint);
        ctx.complaints().write(data);
        audit.record("complaint_close", player.name(), player.uuid().toString(), complaint.complainant, complaint.complainantUuid, "SUCCESS", "resolved_and_reviewed complaintId=" + complaint.id + " reward=" + configuredReward);
        player.tell("Dosarul " + complaint.id + " a fost închis. Reward calculat: " + configuredReward + " monede; livrare: " + complaint.rewardStatus + ".");
        return true;
    }
}
