package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.RollSource;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.EscapeStore;
import com.dwurdy.straja.domain.model.ManhuntRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.SetupData;
import com.dwurdy.straja.domain.model.TunnelRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * FUN-9: escape paths — dig, pick, bribe, blend. Every path is a
 * preparation the patrol can interrupt, every detection feeds the FUN-6
 * dispatch board at low severity, and every success exits through the
 * same bounds→fugitive→manhunt funnel. Nothing is instant.
 */
public final class EscapeService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private PrisonService prison;
    private CustodyService custody;
    private DispatchService dispatch;
    private ManhuntService manhunts;
    private RollSource rolls = RollSource.system();

    /** Blending prisoners: uuid → grace expiry. Memory-only by design —
     *  a restart drops the cover and the normal bounds check resumes. */
    private final java.util.Map<String, Long> blended = new java.util.HashMap<>();
    /** Pending bribe offers to player guards: guardUuid → offer. */
    private final java.util.Map<String, BribeOffer> offers = new java.util.HashMap<>();

    private record BribeOffer(String prisonerUuid, String prisonerName,
                              String guardUuid, long expiresAt) {}

    public EscapeService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    public void usePrison(PrisonService prison) { this.prison = prison; }
    public void useCustody(CustodyService custody) { this.custody = custody; }
    public void useDispatch(DispatchService dispatch) { this.dispatch = dispatch; }
    public void useManhunts(ManhuntService manhunts) { this.manhunts = manhunts; }
    public void useRolls(RollSource rolls) { this.rolls = rolls == null ? RollSource.system() : rolls; }

    private EscapeStore store() {
        var data = ctx.escapes().read();
        data.ensure();
        return data;
    }
    private long now() { return ctx.clock().nowMillis(); }
    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }

    // ------------------------------------------------------------ dig

    /**
     * Left-click swing inside a cell: tunnel-eligible blocks count progress.
     * Returns true when the swing was consumed as dig work (the event is
     * cancelled either way — the block never breaks to the swing itself).
     */
    public boolean onDigSwing(PlayerGateway player, String dimension, int x, int y, int z) {
        if (player == null || player.uuid() == null || !ctx.policies().prisonEnabled) return false;
        var block = ctx.world().blockAt(dimension, x, y, z);
        if (block == null || !isTunnelBlock(block.id())) return false;
        var cell = cellAt(dimension, x, y, z);
        if (cell == null) return false;
        var rec = ctx.prisonerRegister().read().prisoner(uuidOf(player));
        if (rec == null || rec.status != PrisonerStatus.IN_CELL) return false;
        var sentence = ctx.prison().read().activeSentenceFor(uuidOf(player));
        if (sentence == null || !cell.id.equals(sentence.cellId)) return false;
        if (custody != null && custody.actionBlocked(player, "dig")) return true;
        if (guardNear(dimension, cell.doorX, cell.doorY, cell.doorZ)) {
            player.refuse("straja.escape.dig_guard", "straja.remedy.wait");
            return true;
        }
        var data = store();
        var t = data.at(dimension, x, y, z);
        if (t != null && t.progress >= t.required) {
            player.tell("§7Gaura e deschisă — strecoară-te.");
            return true;
        }
        if (t == null) {
            t = new TunnelRecord();
            t.id = data.nextTunnelId();
            t.cellId = cell.id;
            t.dimension = dimension;
            t.x = x; t.y = y; t.z = z;
            t.diggerUuid = uuidOf(player);
            t.diggerName = player.name();
            t.required = Math.max(1, ctx.policies().escapeDigClicks);
            t.startedAt = now();
            data.tunnels.add(t);
        }
        if (now() - t.lastDigAt < Math.max(0, ctx.policies().escapeDigCooldownMs)) return true;
        t.lastDigAt = now();
        t.progress++;
        ctx.escapes().write(data);
        if (t.progress >= t.required) {
            ctx.world().removeBlock(dimension, x, y, z);
            player.tell("§aBlocul cedează — tunelul e deschis.");
            notice("tunnel:" + t.id, "Zgomot de săpat la celula " + cell.id,
                    "săpături auzite în blocul de detenție",
                    dimension, x, y, z, "celula " + cell.id,
                    t.diggerUuid, t.diggerName);
        } else {
            player.actionbar("Sapi… " + t.progress + "/" + t.required);
        }
        audit.record(AuditActions.PRISON_BREAK, player.name(), uuidOf(player), cell.id, "",
                AuditResults.SUCCESS, "dig " + t.progress + "/" + t.required);
        return true;
    }

    private boolean isTunnelBlock(String id) {
        var allowed = ctx.policies().escapeTunnelBlocks;
        return allowed != null && id != null && allowed.contains(id);
    }

    private com.dwurdy.straja.domain.model.Cell cellAt(String dimension, int x, int y, int z) {
        for (var cell : ctx.prison().read().cells) {
            if (cell != null && cell.dimension.equals(dimension) && cell.contains(x, y, z))
                return cell;
        }
        return null;
    }

    /**
     * A member placing a block over a dig site fills the tunnel — the prep
     * is destroyed and the digger's find penalty applies if it was found.
     * A prisoner covering their own hole hides the block but the record
     * stays findable on inspection.
     */
    public boolean onBlockPlaced(PlayerGateway player, String dimension, int x, int y, int z) {
        var data = store();
        var t = data.at(dimension, x, y, z);
        if (t == null || t.progress <= 0) return false;
        if (player == null || players.state(player).rank <= 0) return false;
        data.tunnels.remove(t);
        ctx.escapes().write(data);
        player.tell("§7Ai acoperit tunelul de la celula " + t.cellId + ".");
        audit.record(AuditActions.PRISON_CELL_CREATE, player.name(), uuidOf(player),
                t.cellId, t.diggerUuid, AuditResults.SUCCESS, "tunnel_filled");
        return true;
    }

    // ------------------------------------------------------------ bribe

    /** `/straja bribe` — pay a corruptible post or offer a player guard. */
    public synchronized boolean bribe(PlayerGateway prisoner) {
        if (prisoner == null || prisoner.uuid() == null) return false;
        var rec = ctx.prisonerRegister().read().prisoner(uuidOf(prisoner));
        if (rec == null || rec.status != PrisonerStatus.IN_CELL) {
            prisoner.refuse("straja.escape.bribe_not_jailed", "straja.remedy.wait");
            return false;
        }
        if (custody != null && custody.actionBlocked(prisoner, "bribe")) return false;
        if (!unlockable(uuidOf(prisoner))) {
            prisoner.refuse("straja.escape.bribe_no_cell", "straja.remedy.wait");
            return false;
        }
        var post = postAt(prisoner);
        if (post != null) return bribePost(prisoner, post, rec);
        return bribeOffer(prisoner);
    }

    private SetupData.BribePost postAt(PlayerGateway p) {
        var posts = ctx.setup().read().bribePosts;
        if (posts == null) return null;
        for (var post : posts.values()) {
            if (post != null && post.contains(p.dimension(), p.x(), p.y(), p.z())) return post;
        }
        return null;
    }

    /** A declared post always takes the coins and unlocks the payer's cell. */
    private boolean bribePost(PlayerGateway prisoner, SetupData.BribePost post,
                              com.dwurdy.straja.domain.model.PrisonerRegisterRecord rec) {
        int price = Math.max(1, post.price > 0 ? post.price : ctx.policies().bribePostPrice);
        var pay = ctx.currency().withdraw(prisoner, price);
        if (!pay.ok() || pay.removed() < price) {
            prisoner.refuse("straja.escape.bribe_funds", "straja.remedy.debt_release", price);
            return false;
        }
        if (prison != null) prison.bribeUnlock(prisoner.uuid().toString(),
                ctx.policies().bribeDoorOpenSeconds);
        prisoner.tell("§6Postul " + post.id + " a luat " + price + " B — ușa e deschisă o clipă. Du-te.");
        audit.record(AuditActions.PRISON_BREAK, prisoner.name(), uuidOf(prisoner), post.id, "",
                AuditResults.SUCCESS, "bribe_post price=" + price);
        if (witnessNear(prisoner, post.x, post.y, post.z)) {
            notice("bribe:" + post.id + ":" + uuidOf(prisoner), "Mită la postul " + post.id,
                    "un străjer a văzut plata",
                    post.dimension, post.x, post.y, post.z, post.id,
                    uuidOf(prisoner), prisoner.name());
        }
        return true;
    }

    /** Offers a nearby on-duty guard the bribe — a bounded consent prompt. */
    private boolean bribeOffer(PlayerGateway prisoner) {
        PlayerGateway guard = nearestGuard(prisoner, ctx.policies().bribeRadius);
        if (guard == null) {
            prisoner.refuse("straja.escape.bribe_none", "straja.remedy.travel");
            return false;
        }
        String gkey = uuidOf(guard);
        if (offers.containsKey(gkey)) {
            prisoner.refuse("straja.escape.bribe_busy", "straja.remedy.wait");
            return false;
        }
        if (!unlockable(uuidOf(prisoner))) {
            prisoner.refuse("straja.escape.bribe_no_cell", "straja.remedy.wait");
            return false;
        }
        offers.put(gkey, new BribeOffer(uuidOf(prisoner), prisoner.name(), gkey,
                now() + Math.max(5_000, ctx.policies().bribeOfferMs)));
        prisoner.tell("§6Ai oferit mită lui " + guard.name() + " — așteaptă răspunsul.");
        guard.tell("§c" + prisoner.name() + " îți oferă " + Math.max(1, ctx.policies().bribePostPrice)
                + " B să deschizi celula. /straja bribe accept sau decline — "
                + (Math.max(5_000, ctx.policies().bribeOfferMs) / 1000) + "s.");
        return true;
    }

    /** `/straja bribe accept|decline` — the guard answers a pending offer. */
    public synchronized boolean bribeAnswer(PlayerGateway guard, boolean accept) {
        if (guard == null || guard.uuid() == null) return false;
        var offer = offers.remove(guard.uuid().toString());
        if (offer == null || now() > offer.expiresAt) {
            guard.refuse("straja.escape.bribe_expired", "straja.remedy.wait");
            return false;
        }
        PlayerGateway prisoner = onlineByUuid(offer.prisonerUuid);
        int price = Math.max(1, ctx.policies().bribePostPrice);
        if (!accept) {
            if (prisoner != null)
                prisoner.tell("§c" + guard.name() + " a refuzat mita — ești raportat.");
            notice("bribe:" + offer.guardUuid + ":" + offer.prisonerUuid,
                    "Tentativă de mită: " + offer.prisonerName,
                    "străjerul a refuzat și a raportat",
                    guard.dimension(), guard.x(), guard.y(), guard.z(), "penitenciar",
                    offer.prisonerUuid, offer.prisonerName);
            audit.record(AuditActions.PRISON_BREAK, offer.prisonerName, offer.prisonerUuid,
                    guard.name(), offer.guardUuid, AuditResults.REFUSED, "bribe_declined");
            return true;
        }
        if (prisoner == null) {
            guard.refuse("straja.common.target_offline", "straja.remedy.wait");
            return false;
        }
        if (!unlockable(offer.prisonerUuid)) {
            guard.refuse("straja.escape.bribe_no_cell", "straja.remedy.wait");
            return false;
        }
        var pay = ctx.currency().withdraw(prisoner, price);
        if (!pay.ok() || pay.removed() < price) {
            guard.refuse("straja.escape.bribe_funds", "straja.remedy.wait", price);
            if (prisoner != null) prisoner.tell("§cPlata a picat — banca a refuzat retragerea.");
            return false;
        }
        ctx.currency().deposit(guard, price, "bribe:" + offer.prisonerUuid + ":" + offer.guardUuid);
        if (prison != null) prison.bribeUnlock(offer.prisonerUuid,
                ctx.policies().bribeDoorOpenSeconds);
        guard.tell("§6Ai luat " + price + " B — ușa celulei s-a deschis.");
        prisoner.tell("§6Străjerul a luat mita — ușa e deschisă o clipă. Du-te.");
        // The crime is heavier in the audit when a sworn member took it.
        audit.record(AuditActions.PRISON_BREAK, offer.prisonerName, offer.prisonerUuid,
                guard.name(), offer.guardUuid, AuditResults.SUCCESS,
                "bribe_accepted corrupt_guard price=" + price);
        if (witnessNear(guard, guard.x(), guard.y(), guard.z())) {
            notice("bribe:" + offer.guardUuid + ":" + offer.prisonerUuid,
                    "Mită prinsă: " + offer.prisonerName + " ↔ " + guard.name(),
                    "un al doilea străjer a văzut schimbul",
                    guard.dimension(), guard.x(), guard.y(), guard.z(), "penitenciar",
                    offer.prisonerUuid, offer.prisonerName);
        }
        return true;
    }

    /** The bribe only pays out when a real cell door exists to unlock. */
    private boolean unlockable(String prisonerUuid) {
        var sentence = ctx.prison().read().activeSentenceFor(prisonerUuid);
        return sentence != null && !sentence.cellId.isBlank()
                && ctx.prison().read().cell(sentence.cellId) != null;
    }

    private PlayerGateway nearestGuard(PlayerGateway near, double radius) {
        PlayerGateway best = null;
        double bestD = radius * radius;
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p == null || p == near || !players.isOnDutyGuard(p)) continue;
            if (!p.dimension().equals(near.dimension())) continue;
            double dx = p.x() - near.x(), dy = p.y() - near.y(), dz = p.z() - near.z();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = p; }
        }
        return best;
    }

    /** A second on-duty guard watching the exchange makes it witnessed. */
    private boolean witnessNear(PlayerGateway involved, double x, double y, double z) {
        double radius = Math.max(1.0, ctx.policies().manhuntGuardDetectRadius);
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p == null || p == involved || !players.isOnDutyGuard(p)) continue;
            if (!p.dimension().equals(involved.dimension())) continue;
            double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ blend

    /**
     * Bounds-breach hook: a prisoner carrying a guard uniform walks out
     * under cover — the alarm is delayed by the blend grace, and any real
     * guard inside the detect radius blows it. Returns true when the
     * breach is suppressed this tick.
     */
    public boolean suppressBreach(PlayerGateway prisoner,
                                  com.dwurdy.straja.domain.model.Sentence sentence) {
        if (prisoner == null || prisoner.uuid() == null || sentence == null) return false;
        var rec = ctx.prisonerRegister().read().prisoner(uuidOf(prisoner));
        if (rec == null || rec.status != PrisonerStatus.IN_CELL) return false;
        String key = uuidOf(prisoner);
        Long expiry = blended.get(key);
        if (expiry != null) {
            if (now() < expiry) return true;
            blended.remove(key); // stale cover — the breach path flags normally
            return false;
        }
        if (!carriesUniform(prisoner)) return false;
        blended.put(key, now() + Math.max(1, ctx.policies().blendGraceSeconds) * 1_000L);
        prisoner.tell("§6Uniforma te acoperă — patrulele încă trec pe lângă tine. Du-te departe.");
        audit.record(AuditActions.PRISON_BREAK, prisoner.name(), key, sentence.id, "",
                AuditResults.SUCCESS, "blend_walkout");
        return true;
    }

    private boolean carriesUniform(PlayerGateway p) {
        var inv = p.inventory();
        return inv != null && inv.contains("straja:guard_uniform");
    }

    /** Detection sweep + offer expiry. Runs on the prison tick cadence. */
    public synchronized void tick() {
        if (!blended.isEmpty()) {
            var it = blended.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                PlayerGateway p = onlineByUuid(entry.getKey());
                if (p == null) continue; // offline keeps the cover's clock
                if (backInside(entry.getKey(), p)) {
                    // Slipped back into the cell inside the grace window —
                    // the cover dissolves with no warrant.
                    it.remove();
                    p.tell("§7Te-ai strecurat înapoi — patrulele încă trec pe lângă tine.");
                    continue;
                }
                var detected = nearestGuard(p, ctx.policies().manhuntGuardDetectRadius);
                if (detected != null) {
                    it.remove();
                    confiscateUniform(p);
                    if (prison != null) prison.flagFugitive(entry.getKey(), p, "blend_detected");
                    p.tell("§c" + detected.name() + " te-a reperat sub uniformă — ești căutat.");
                    detected.tell("§6Ai reperat un deținut sub uniformă — " + p.name()
                            + " e căutat acum.");
                    openHunt(p, "blend_detected");
                } else if (now() >= entry.getValue()) {
                    it.remove();
                    if (prison != null) prison.flagFugitive(entry.getKey(), p, "blend_out");
                }
            }
        }
        if (!offers.isEmpty()) {
            var it = offers.values().iterator();
            while (it.hasNext()) {
                if (now() > it.next().expiresAt) it.remove();
            }
        }
        var data = store();
        if (data.prune(ctx.policies().escapeRetentionLimit)) ctx.escapes().write(data);
    }

    /** Still-out-of-bounds check — a blend that made it home costs nothing. */
    private boolean backInside(String prisonerUuid, PlayerGateway p) {
        var sentence = ctx.prison().read().activeSentenceFor(prisonerUuid);
        if (sentence == null || sentence.cellId.isBlank()) return false;
        var cell = ctx.prison().read().cell(sentence.cellId);
        return cell != null && cell.dimension.equals(p.dimension())
                && cell.contains(p.x(), p.y(), p.z());
    }

    private void confiscateUniform(PlayerGateway p) {
        var inv = p.inventory();
        if (inv == null) return;
        for (int i = 0; i < inv.slots(); i++) {
            var stack = inv.stackAt(i);
            if (stack != null && "straja:guard_uniform".equals(stack.id()))
                inv.extract(i, stack.count());
        }
    }

    private void openHunt(PlayerGateway runner, String detail) {
        if (manhunts == null || runner == null) return;
        var r = new ManhuntRecord.Runner();
        r.uuid = uuidOf(runner);
        r.name = runner.name();
        r.wasPrisoner = true;
        manhunts.begin(List.of(r), runner.dimension(), runner.x(), runner.y(), runner.z(),
                detail, "escape:" + r.uuid);
    }

    // ------------------------------------------------------------ inspect

    /**
     * `/straja inspect` — an on-duty guard searches the nearest cell:
     * tunnel progress, lock state, and the occupant's contraband (taken).
     */
    public synchronized boolean inspect(PlayerGateway guard) {
        if (guard == null || !players.isOnDutyGuard(guard)) {
            if (guard != null) guard.refuse("straja.incident.work_rank", "straja.remedy.duty");
            return false;
        }
        var cell = nearestCell(guard, Math.max(1.0, ctx.policies().inspectionRadius));
        if (cell == null) {
            guard.refuse("straja.escape.inspect_far", "straja.remedy.travel");
            return false;
        }
        var data = store();
        var tunnels = data.inCell(cell.id);
        boolean found = false;
        for (var t : tunnels) {
            if (t.progress <= 0) continue;
            found = true;
            if (t.discoveredAt == 0) {
                t.discoveredAt = now();
                if (prison != null && !t.diggerUuid.isBlank())
                    prison.extendSentence(t.diggerUuid,
                            Math.max(1, ctx.policies().tunnelFoundPenaltyMinutes) * 60_000L,
                            "tunnel_found");
                notice("tunnel_found:" + t.id, "Tunel găsit la celula " + cell.id,
                        "inspecția lui " + guard.name(),
                        t.dimension, t.x + 0.5, t.y + 0.5, t.z + 0.5, "celula " + cell.id,
                        t.diggerUuid, t.diggerName);
            }
            guard.tell("§cSăpături la " + cell.id + " (x:" + t.x + " y:" + t.y + " z:" + t.z
                    + ") — " + t.progress + "/" + t.required
                    + (t.progress >= t.required ? " DESCHIS" : ""));
        }
        if (cell.jammedUntil > now()) {
            found = true;
            guard.tell("§cBroasca celulei " + cell.id + " a fost forțată și s-a gripat"
                    + (cell.jammedByName.isBlank() ? "" : " — autor: " + cell.jammedByName) + ".");
        }
        // The occupant's contraband leaves with the inspector.
        var assignment = ctx.prison().read().assignments.get(cell.id);
        if (assignment != null && !assignment.targetUuid.isBlank()) {
            var occupant = onlineByUuid(assignment.targetUuid);
            if (occupant != null) {
                int taken = confiscateContraband(guard, occupant);
                if (taken > 0) {
                    found = true;
                    guard.tell("§6Confiscat " + taken + " obiecte de la " + occupant.name() + ".");
                    occupant.tell("§cPercheziție — ți s-au confiscat uneltele de evadare.");
                    notice("contraband:" + cell.id + ":" + assignment.targetUuid,
                            "Contrabandă la celula " + cell.id,
                            "inspecția lui " + guard.name(),
                            guard.dimension(), guard.x(), guard.y(), guard.z(), "celula " + cell.id,
                            assignment.targetUuid, occupant.name());
                }
            }
        }
        if (!found) guard.tell("§7Celula " + cell.id + " e curată — nimic găsit.");
        else ctx.escapes().write(data);
        audit.record(AuditActions.PRISON_CELL_CREATE, guard.name(), uuidOf(guard), cell.id, "",
                AuditResults.SUCCESS, "inspect found=" + found);
        return true;
    }

    private int confiscateContraband(PlayerGateway guard, PlayerGateway occupant) {
        var list = ctx.policies().contrabandItems;
        if (list == null || list.isEmpty()) return 0;
        var inv = occupant.inventory();
        if (inv == null) return 0;
        int taken = 0;
        for (int i = 0; i < inv.slots(); i++) {
            var stack = inv.stackAt(i);
            if (stack == null || !list.contains(stack.id())) continue;
            var removed = inv.extract(i, stack.count());
            taken += removed.count();
        }
        return taken;
    }

    private com.dwurdy.straja.domain.model.Cell nearestCell(PlayerGateway p, double radius) {
        com.dwurdy.straja.domain.model.Cell best = null;
        double bestD = radius * radius;
        for (var cell : ctx.prison().read().cells) {
            if (cell == null || !cell.dimension.equals(p.dimension())) continue;
            double dx = p.x() - cell.doorX, dy = p.y() - cell.doorY, dz = p.z() - cell.doorZ;
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) { bestD = d; best = cell; }
        }
        return best;
    }

    // ------------------------------------------------------------ posts admin

    public boolean postAdd(PlayerGateway admin, String id, int price, double radius, String name) {
        if (admin == null || id == null || id.isBlank()) return false;
        var data = ctx.setup().read();
        if (data.bribePosts == null) data.bribePosts = new java.util.LinkedHashMap<>();
        if (data.bribePosts.containsKey(id)) {
            admin.refuse("straja.zone.exists", "straja.remedy.fix_retry", id);
            return false;
        }
        if (price < 1 || radius < 2 || radius > 64) {
            admin.refuse("straja.zone.radius_invalid", "straja.remedy.fix_retry");
            return false;
        }
        var post = new SetupData.BribePost();
        post.id = id;
        post.name = name == null ? "" : name.trim();
        post.dimension = admin.dimension();
        post.x = admin.x(); post.y = admin.y(); post.z = admin.z();
        post.radius = radius;
        post.price = price;
        data.bribePosts.put(id, post);
        ctx.setup().write(data);
        admin.tell("Post coruptibil " + id + " declarat (" + price + " B, r=" + (int) radius + ").");
        audit.record(AuditActions.CAMP_SPAWN, admin.name(), uuidOf(admin), id, "",
                AuditResults.SUCCESS, "bribe_post price=" + price);
        return true;
    }

    public boolean postRemove(PlayerGateway admin, String id) {
        if (admin == null) return false;
        var data = ctx.setup().read();
        if (data.bribePosts == null || data.bribePosts.remove(id) == null) {
            admin.refuse("straja.zone.unknown", "straja.remedy.fix_retry", id);
            return false;
        }
        ctx.setup().write(data);
        admin.tell("Post coruptibil " + id + " eliminat.");
        return true;
    }

    public void postList(PlayerGateway admin) {
        if (admin == null) return;
        var posts = ctx.setup().read().bribePosts;
        if (posts == null || posts.isEmpty()) {
            admin.tell("Fără posturi coruptibile declarate.");
            return;
        }
        for (var post : posts.values()) {
            if (post == null) continue;
            admin.tell(post.id + " „" + (post.name.isBlank() ? "-" : post.name) + "” "
                    + post.price + " B r=" + (int) post.radius + " @ " + post.dimension
                    + " x:" + (int) post.x + " y:" + (int) post.y + " z:" + (int) post.z);
        }
    }

    // ------------------------------------------------------------ helpers

    private boolean guardNear(String dimension, double x, double y, double z) {
        double radius = Math.max(1.0, ctx.policies().manhuntGuardDetectRadius);
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p == null || !dimension.equals(p.dimension())) continue;
            if (!players.isOnDutyGuard(p)) continue;
            double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
            if (dx * dx + dy * dy + dz * dz <= radius * radius) return true;
        }
        return false;
    }

    /** Low-severity dispatch notice — the FUN-6 board sees the detection. */
    void notice(String sourceId, String title, String detail,
                        String dimension, double x, double y, double z, String place,
                        String subjectUuid, String subjectName) {
        if (dispatch == null) return;
        dispatch.raise(DispatchService.SOURCE_ESCAPE, sourceId, title, detail,
                dimension, x, y, z, place, subjectUuid, subjectName, "", "ROUTINE", "ENLISTED");
    }

    private PlayerGateway onlineByUuid(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p != null && p.uuid() != null && uuid.equalsIgnoreCase(p.uuid().toString()))
                return p;
        }
        return null;
    }

    /** Test seam: pending offers / blend windows are memory-only. */
    public int pendingOffers() { return offers.size(); }
    public boolean isBlending(String uuid) {
        Long expiry = blended.get(uuid);
        return expiry != null && now() < expiry;
    }
}
