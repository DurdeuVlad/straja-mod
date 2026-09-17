package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.IdentityCardRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.IdentityCard;
import com.dwurdy.straja.domain.model.IdentityCardStatus;
import com.dwurdy.straja.domain.model.IdentityCardStore;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

/** Persistent, UUID-bound identity cards with a physical item projection. */
public class IdentityCardService implements IdentityCardRoleplayUseCase {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_REASON_LENGTH = 240;
    private static final Pattern CARD_ID = Pattern.compile("ID-[0-9]{1,10}");

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public IdentityCardService(StrajaContext ctx, PlayerService players, AuditService audit) {
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

    private static boolean near(PlayerGateway player, SetupData.Location at, double radius) {
        if (player == null || at == null) return false;
        if (at.dimension != null && !at.dimension.equals(player.dimension())) return false;
        double dx = player.x() - at.x;
        double dy = player.y() - at.y;
        double dz = player.z() - at.z;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    private boolean atReception(PlayerGateway player) {
        return near(player, ctx.setup().read().location("receptionist"), 6);
    }

    private static String uuid(PlayerGateway player) {
        return player == null || player.uuid() == null ? "" : player.uuid().toString();
    }

    private static boolean sameHolder(IdentityCard card, PlayerGateway player) {
        return card != null && player != null && !uuid(player).isEmpty()
                && uuid(player).equalsIgnoreCase(card.holderUuid);
    }

    private static String safeName(PlayerGateway player) {
        String name = player == null || player.name() == null ? "" : player.name().trim();
        return name.length() > 40 ? name.substring(0, 40) : name;
    }

    private static String validCardId(String value) {
        if (value == null) return null;
        String id = value.trim();
        return CARD_ID.matcher(id).matches() ? id : null;
    }

    private static IdentityCardStore normalized(IdentityCardStore store) {
        if (store == null) store = new IdentityCardStore();
        if (store.cards == null) store.cards = new LinkedHashMap<>();
        if (store.nextCardNumber < 1) store.nextCardNumber = 1;
        return store;
    }

    private IdentityCard currentFor(IdentityCardStore store, PlayerGateway player) {
        long timestamp = now();
        for (IdentityCard card : store.cards.values()) {
            if (sameHolder(card, player) && card.validAt(timestamp)) return card;
        }
        return null;
    }

    private boolean administrator(PlayerGateway player) {
        return player != null && (players.isCommissioner(player) || player.isOp());
    }

    @Override
    public boolean request(PlayerGateway applicant) {
        if (applicant == null) return false;
        if (!p().identityCardsEnabled) {
            applicant.tell("Sistemul de buletine este dezactivat.");
            return false;
        }
        if (!applicant.isOnline()) return false;
        if (!atReception(applicant)) {
            applicant.tell("Buletinul se emite la Recepție.");
            return false;
        }
        return issueInternal(applicant, applicant, "Recepție", "");
    }

    @Override
    public boolean issue(PlayerGateway issuer, PlayerGateway target) {
        if (issuer == null) return false;
        if (!p().identityCardsEnabled) {
            issuer.tell("Sistemul de buletine este dezactivat.");
            return false;
        }
        if (!administrator(issuer)) {
            issuer.tell("Doar Comisaru' sau un operator poate emite buletine direct.");
            return false;
        }
        if (target == null || !target.isOnline() || target.uuid() == null) {
            issuer.tell("Titularul trebuie să fie conectat.");
            return false;
        }
        return issueInternal(target, issuer, safeName(issuer), uuid(issuer));
    }

    private boolean issueInternal(PlayerGateway target, PlayerGateway auditActor,
                                  String issuerName, String issuerUuid) {
        if (target.uuid() == null || safeName(target).isBlank()) {
            auditActor.tell("Titularul nu are o identitate utilizabilă.");
            return false;
        }
        IdentityCardStore store = normalized(ctx.identityCards().read());
        IdentityCard existing = currentFor(store, target);
        if (existing != null) {
            auditActor.tell("Jucătorul are deja buletinul activ " + existing.id + ".");
            return false;
        }

        int number = store.nextCardNumber;
        String id = "ID-" + number;
        while (store.cards.containsKey(id)) {
            if (number == Integer.MAX_VALUE) {
                auditActor.tell("Registrul de buletine este epuizat.");
                return false;
            }
            number++;
            id = "ID-" + number;
        }

        var itemData = new LinkedHashMap<String, String>();
        itemData.put("IdentityCardId", id);
        itemData.put("IdentityCardHolder", target.uuid().toString());
        var item = new ItemSpec("straja:identity_card", 1, itemData,
                "Buletin — " + safeName(target));
        if (!target.inventory().canReceive(List.of(item)) || !target.giveVerified(item)) {
            auditActor.tell("Buletinul nu a putut fi livrat; nu s-a creat niciun registru.");
            return false;
        }

        long issuedAt = now();
        IdentityCard card = new IdentityCard();
        card.id = id;
        card.holderUuid = target.uuid().toString();
        card.holderName = safeName(target);
        card.issuerUuid = issuerUuid == null ? "" : issuerUuid;
        card.issuerName = issuerName == null ? "" : issuerName;
        card.issuedAt = issuedAt;
        card.expiresAt = issuedAt + Math.max(1, p().identityCardValidityDays) * DAY_MS;
        store.cards.put(id, card);
        store.nextCardNumber = number == Integer.MAX_VALUE ? number : number + 1;
        ctx.identityCards().write(store);

        audit.record("identity_card_issue", auditActor.name(), uuid(auditActor),
                target.name(), uuid(target), "SUCCESS", "cardId=" + id);
        target.tell("Ai primit buletinul " + id + ". Este valabil până la " + card.expiresAt + ".");
        if (!uuid(auditActor).equalsIgnoreCase(uuid(target))) {
            auditActor.tell("Buletinul " + id + " a fost emis pentru " + target.name() + ".");
        }
        return true;
    }

    @Override
    public void read(PlayerGateway viewer, String cardId) {
        if (viewer == null) return;
        IdentityCard card = view(viewer, cardId);
        if (card == null) {
            viewer.tell("Buletinul nu există sau nu ai dreptul să-l verifici.");
            return;
        }
        viewer.tell("Buletin " + card.id + " — titular: " + card.holderName
                + "; stare: " + status(card) + "; expiră: " + card.expiresAt + ".");
        if (IdentityCardStatus.REVOKED.name().equals(card.status)) {
            viewer.tell("Revocat de " + card.revokedByName + ": " + card.revocationReason);
        }
    }

    /** Returns a card only when the viewer is its holder or an authority. */
    public IdentityCard view(PlayerGateway viewer, String cardId) {
        String id = validCardId(cardId);
        if (viewer == null || id == null) return null;
        IdentityCardStore store = normalized(ctx.identityCards().read());
        IdentityCard card = store.cards.get(id);
        boolean authority = administrator(viewer) || players.isOnDutyGuard(viewer);
        return card != null && (authority || sameHolder(card, viewer)) ? card : null;
    }

    private String status(IdentityCard card) {
        if (card == null) return "NEVALID";
        if (IdentityCardStatus.REVOKED.name().equals(card.status)) return "REVOCAT";
        return card.validAt(now()) ? "VALID" : "EXPIRAT";
    }

    @Override
    public void list(PlayerGateway viewer) {
        if (viewer == null) return;
        IdentityCardStore store = normalized(ctx.identityCards().read());
        boolean authority = administrator(viewer);
        List<IdentityCard> cards = new ArrayList<>();
        for (IdentityCard card : store.cards.values()) {
            if (card == null) continue;
            if (authority || sameHolder(card, viewer)) cards.add(card);
        }
        if (cards.isEmpty()) {
            viewer.tell(authority ? "Registrul de buletine este gol." : "Nu ai buletine înregistrate.");
            return;
        }
        viewer.tell(authority ? "Registrul de buletine (" + cards.size() + "):" : "Buletinele tale:");
        for (IdentityCard card : cards) {
            viewer.tell("  " + card.id + " — " + card.holderName + " — " + status(card));
        }
    }

    @Override
    public boolean revoke(PlayerGateway actor, String cardId, String reason) {
        if (actor == null) return false;
        if (!administrator(actor)) {
            actor.tell("Doar Comisaru' sau un operator poate revoca buletine.");
            return false;
        }
        String id = validCardId(cardId);
        String note = reason == null ? "" : reason.trim();
        if (id == null || note.isBlank() || note.length() > MAX_REASON_LENGTH) {
            actor.tell("ID-ul sau motivul revocării nu este valid.");
            return false;
        }
        IdentityCardStore store = normalized(ctx.identityCards().read());
        IdentityCard card = store.cards.get(id);
        if (card == null) {
            actor.tell("Buletinul nu există.");
            return false;
        }
        if (IdentityCardStatus.REVOKED.name().equals(card.status)) {
            actor.tell("Buletinul " + id + " era deja revocat.");
            return true;
        }
        card.status = IdentityCardStatus.REVOKED.name();
        card.revokedAt = now();
        card.revokedByUuid = uuid(actor);
        card.revokedByName = safeName(actor);
        card.revocationReason = note;
        ctx.identityCards().write(store);
        audit.record("identity_card_revoke", actor.name(), uuid(actor),
                card.holderName, card.holderUuid, "SUCCESS", "cardId=" + id + " reason=" + note);
        actor.tell("Buletinul " + id + " a fost revocat.");
        return true;
    }
}
