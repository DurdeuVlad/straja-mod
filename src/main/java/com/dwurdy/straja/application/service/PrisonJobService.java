package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.JobDef;
import com.dwurdy.straja.domain.model.JobStore;
import com.dwurdy.straja.domain.model.SetupData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * FUN-10: the small economy inside a prison/camp — posted quota jobs,
 * prisoner-to-prisoner market trades, favor IOU notes, a durable
 * <i>standing</i> ledger parole reads, and inspection mini-calls that keep
 * on-duty pressure on the contraband market.
 *
 * <p>Job settlement is idempotent: a turn-in is a {@link JobStore.JobRun}
 * keyed by an idempotency token; a retried delivery finds the recorded run
 * and refuses instead of double-paying. Market trades settle atomically —
 * goods move and labor credit moves in the same breath or not at all.
 */
public final class PrisonJobService {
    /** In-memory trade offers — seller uuid -> pending offer. */
    private static final class PendingTrade {
        String buyerUuid = "";
        String buyerName = "";
        String itemId = "";
        String snbt = "";
        int count;
        int price;
        boolean favor;
        long expiresAt;
    }

    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private DispatchService dispatch;
    private final Map<String, PendingTrade> offers = new HashMap<>();
    /** The currently open inspection call, if any — one at a time. */
    private String openInspectionSourceId = "";
    private long nextInspectionCallAt;

    public PrisonJobService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    public void useDispatch(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    private JobStore store() {
        JobStore s = ctx.jobs().read();
        s.normalize();
        return s;
    }

    // ------------------------------------------------------------ job defs

    public List<JobDef> jobDefs() {
        List<JobDef> defs = new ArrayList<>();
        for (String raw : ctx.policies().jobDefs) {
            JobDef d = JobDef.parse(raw);
            if (d != null) defs.add(d);
        }
        return defs;
    }

    private JobDef jobDef(String jobId) {
        if (jobId == null) return null;
        for (JobDef d : jobDefs()) {
            if (d.id().equalsIgnoreCase(jobId)) return d;
        }
        return null;
    }

    private JobDef defForPost(SetupData.JobPost post) {
        JobDef d = jobDef(post.jobId);
        return d == null ? null : d;
    }

    // ------------------------------------------------------------ job posts

    /** Admin: declare a work point at the admin's position. */
    public void postAdd(PlayerGateway admin, String id, String jobId, double radius, String name) {
        if (id == null || id.isBlank() || jobDef(jobId) == null) {
            admin.refuse("straja.jobpost.unknown_job", "straja.remedy.jobpost_job", jobId);
            return;
        }
        var setup = ctx.setup().read();
        if (setup.jobPosts == null) setup.jobPosts = new java.util.LinkedHashMap<>();
        var post = new SetupData.JobPost();
        post.id = id;
        post.name = name == null ? "" : name;
        post.jobId = jobId;
        post.dimension = admin.dimension();
        post.x = admin.x();
        post.y = admin.y();
        post.z = admin.z();
        post.radius = radius;
        setup.jobPosts.put(id, post);
        ctx.setup().write(setup);
        admin.tellKey("straja.jobpost.added", id, jobId);
        audit.record(AuditActions.CAMP_SPAWN, admin.name(), uuidOf(admin),
                id, "", AuditResults.SUCCESS, "jobpost job=" + jobId);
    }

    public void postRemove(PlayerGateway admin, String id) {
        var setup = ctx.setup().read();
        if (setup.jobPosts == null || setup.jobPosts.remove(id) == null) {
            admin.refuse("straja.jobpost.unknown", "straja.remedy.fix_retry", id);
            return;
        }
        ctx.setup().write(setup);
        admin.tellKey("straja.jobpost.removed", id);
    }

    public void postList(PlayerGateway admin) {
        var setup = ctx.setup().read();
        if (setup.jobPosts == null || setup.jobPosts.isEmpty()) {
            admin.tellKey("straja.jobpost.none");
            return;
        }
        for (var p : setup.jobPosts.values()) {
            JobDef d = jobDef(p.jobId);
            admin.tell("§e" + p.id + " §7" + p.jobId
                    + (d == null ? "" : " — " + d.describe())
                    + " @(" + (int) p.x + "," + (int) p.y + "," + (int) p.z + ")");
        }
    }

    /** The post a position sits inside, or null. */
    private SetupData.JobPost postAt(String dimension, double x, double y, double z) {
        var setup = ctx.setup().read();
        if (setup.jobPosts == null) return null;
        for (var p : setup.jobPosts.values()) {
            if (p != null && p.contains(dimension, x, y, z)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------ job turn-in

    /**
     * Right-click surface on a declared work point: read the quota when the
     * goods aren't in the bag; settle once when they are. Returns true when
     * a post claimed the click.
     */
    public boolean onPostClick(PlayerGateway player, String dimension,
                               double x, double y, double z) {
        var post = postAt(dimension, x, y, z);
        if (post == null) return false;
        JobDef job = defForPost(post);
        if (job == null) return true;
        var rec = registerRecord(player);
        if (rec == null) return true; // work points only pay people in custody
        var store = store();
        long now = now();
        long nextAt = store.cooldownAt(uuidOf(player), job.id());
        if (nextAt > now) {
            player.tellKey("straja.job.cooldown", job.label(),
                    (int) Math.ceil((nextAt - now) / 1000.0));
            return true;
        }
        if (!hasAll(player, job)) {
            player.tellKey("straja.job.needs", job.label(), job.describe(), job.pay());
            return true;
        }
        settle(player, post, job, store, now);
        return true;
    }

    /** {@code /straja job} — the prisoner's own job board view. */
    public void jobStatus(PlayerGateway player) {
        var rec = registerRecord(player);
        if (rec == null) {
            player.refuse("straja.job.only_prisoners", "straja.remedy.wait");
            return;
        }
        var store = store();
        long now = now();
        boolean any = false;
        var setup = ctx.setup().read();
        if (setup.jobPosts != null) {
            for (var p : setup.jobPosts.values()) {
                if (p == null) continue;
                JobDef d = jobDef(p.jobId);
                if (d == null) continue;
                long nextAt = store.cooldownAt(uuidOf(player), d.id());
                player.tell("§e" + p.id + " §f" + d.label() + " §7— "
                        + d.describe() + " → " + d.pay() + "u"
                        + (nextAt > now
                                ? " §8(" + (int) Math.ceil((nextAt - now) / 1000.0) + "s)"
                                : ""));
                any = true;
            }
        }
        if (!any) player.tellKey("straja.job.none");
    }

    private boolean hasAll(PlayerGateway player, JobDef job) {
        var inv = player.inventory();
        for (JobDef.Quota q : job.quotas()) {
            if (inv.countOf(q.itemId()) < q.count()) return false;
        }
        return true;
    }

    /**
     * Idempotent settle: a run record keyed by the idempotency token is
     * written with the payout; a retried delivery finds the token first.
     */
    private void settle(PlayerGateway player, SetupData.JobPost post,
                        JobDef job, JobStore store, long now) {
        String uuid = uuidOf(player);
        String token = "job-" + uuid + "-" + job.id() + "-" + now;
        if (store.hasRun(token)) return;
        // Extract quota goods — all or nothing: count first, then pull.
        if (!hasAll(player, job)) return;
        var inv = player.inventory();
        for (JobDef.Quota q : job.quotas()) {
            int need = q.count();
            for (int i = 0; i < inv.slots() && need > 0; i++) {
                var stack = inv.stackAt(i);
                if (stack != null && q.itemId().equals(stack.id()) && stack.count() > 0) {
                    var out = inv.extract(i, Math.min(need, stack.count()));
                    need -= out == null ? 0 : out.count();
                }
            }
        }
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec != null) {
            rec.laborAccount = (int) Math.min((long) rec.laborAccount + job.pay(),
                    Integer.MAX_VALUE);
            rec.standing += job.standing();
            ctx.prisonerRegister().write(reg);
        }
        var run = new JobStore.JobRun();
        run.token = token;
        run.uuid = uuid;
        run.jobId = job.id();
        run.postId = post.id;
        run.paid = job.pay();
        run.at = now;
        store.runs.add(run);
        store.setCooldown(uuid, job.id(), now + job.cooldownSeconds() * 1000L);
        store.prune(ctx.policies().jobRetentionLimit, ctx.policies().jobRetentionLimit);
        ctx.jobs().write(store);
        player.tellKey("straja.job.paid", job.label(), job.pay());
        audit.record(AuditActions.CAMP_TRANSFER, player.name(), uuid,
                job.id(), "", AuditResults.SUCCESS,
                "job pay=" + job.pay() + " standing+" + job.standing());
    }

    // ------------------------------------------------------------ standing

    /** Visible standing read for the prisoner status line. */
    public int standingOf(String uuid) {
        var rec = ctx.prisonerRegister().read().prisoner(uuid);
        return rec == null ? 0 : rec.standing;
    }

    /** Infractions land here — tunnel finds, traced lock jams. */
    public void penalizeStanding(String uuid, int amount, String reason) {
        if (uuid == null || amount <= 0) return;
        var reg = ctx.prisonerRegister().read();
        var rec = reg.prisoner(uuid);
        if (rec == null) return;
        rec.standing -= amount;
        ctx.prisonerRegister().write(reg);
        audit.record(AuditActions.CAMP_TRANSFER, "system", "", rec.detaineeName,
                uuid, AuditResults.SUCCESS, "standing-" + amount + " " + reason);
    }

    // ------------------------------------------------------------ market trades

    /**
     * {@code /straja trade <prisoner> <price>} — offer the stack in hand to
     * a nearby prisoner. {@code price=0} is a favor: the buyer hands the
     * seller a physical IOU note instead of labor credit.
     */
    public void tradeOffer(PlayerGateway seller, String buyerName, int price) {
        if (registerRecord(seller) == null) {
            seller.refuse("straja.trade.only_prisoners", "straja.remedy.wait");
            return;
        }
        if (price < 0) {
            seller.refuse("straja.trade.bad_price", "straja.remedy.fix_retry");
            return;
        }
        var held = seller.mainHand();
        if (held == null || held.isEmpty()) {
            seller.refuse("straja.trade.empty_hand", "straja.remedy.trade_hand");
            return;
        }
        var buyer = findPrisoner(buyerName);
        if (buyer == null || buyer.equals(seller.uuid() == null ? null : seller.uuid().toString())) {
            seller.refuse("straja.trade.no_buyer", "straja.remedy.trade_buyer", buyerName);
            return;
        }
        var offer = new PendingTrade();
        offer.buyerUuid = buyer;
        offer.buyerName = buyerName;
        offer.itemId = held.id();
        offer.count = held.count();
        offer.price = price;
        offer.favor = price == 0;
        offer.expiresAt = now() + Math.max(1, ctx.policies().tradeOfferMs);
        offers.put(seller.uuid().toString(), offer);
        seller.tellKey("straja.trade.offered", buyerName, held.count() + "×" + held.id(),
                price);
        var bg = findOnline(buyer);
        if (bg != null) {
            bg.tellKey("straja.trade.offer", seller.name(),
                    held.count() + "×" + held.id(), price);
        }
    }

    /** {@code /straja trade accept|decline} — the buyer answers. */
    public void tradeAnswer(PlayerGateway buyer, boolean accept) {
        String buuid = uuidOf(buyer);
        PendingTrade found = null;
        String sellerUuid = null;
        Iterator<Map.Entry<String, PendingTrade>> it = offers.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            var o = e.getValue();
            if (o == null || now() > o.expiresAt) {
                it.remove();
                continue;
            }
            if (buuid != null && buuid.equalsIgnoreCase(o.buyerUuid)) {
                found = o;
                sellerUuid = e.getKey();
                it.remove();
                break;
            }
        }
        if (found == null) {
            buyer.refuse("straja.trade.no_offer", "straja.remedy.wait");
            return;
        }
        var seller = findOnline(sellerUuid);
        if (!accept) {
            buyer.tellKey("straja.trade.declined");
            if (seller != null) seller.tellKey("straja.trade.declined_by", buyer.name());
            return;
        }
        settleTrade(sellerUuid, buyer, found);
    }

    /**
     * Atomic settle: the goods leave the seller's hand and the credit leaves
     * the buyer's account in the same write. Any shortfall aborts the whole
     * trade — value never duplicates.
     */
    private void settleTrade(String sellerUuid, PlayerGateway buyer, PendingTrade offer) {
        var seller = findOnline(sellerUuid);
        if (seller == null) {
            buyer.refuse("straja.trade.seller_gone", "straja.remedy.wait");
            return;
        }
        var held = seller.mainHand();
        if (held == null || held.isEmpty()
                || !offer.itemId.equals(held.id()) || held.count() < offer.count) {
            buyer.refuse("straja.trade.gone", "straja.remedy.wait");
            if (seller != null) seller.tellKey("straja.trade.failed", buyer.name());
            return;
        }
        var reg = ctx.prisonerRegister().read();
        var bRec = reg.prisoner(uuidOf(buyer));
        var sRec = reg.prisoner(sellerUuid);
        if (bRec == null || sRec == null) {
            buyer.refuse("straja.trade.no_buyer", "straja.remedy.wait",
                    seller == null ? "" : seller.name());
            return;
        }
        if (offer.price > bRec.laborAccount) {
            buyer.refuse("straja.trade.cannot_afford", "straja.remedy.trade_price", offer.price);
            return;
        }
        var buyerInv = buyer.inventory();
        if (buyerInv == null || !buyerInv.canReceive(List.of(
                new com.dwurdy.straja.domain.model.ItemSpec(offer.itemId,
                        offer.count, held.customData(), null)))) {
            buyer.refuse("straja.trade.no_room", "straja.remedy.trade_room");
            return;
        }
        // Move the goods first — extract commits nothing until accepted.
        String snbt = seller.inventory().snbtAt(seller.selectedSlot());
        var out = seller.inventory().extract(seller.selectedSlot(), offer.count);
        int moved = out == null ? 0 : out.count();
        if (moved < offer.count) {
            if (moved > 0) seller.giveStack(offer.itemId, moved, snbt);
            buyer.refuse("straja.trade.gone", "straja.remedy.wait");
            return;
        }
        buyer.giveStack(offer.itemId, offer.count, snbt);
        if (offer.price > 0) {
            bRec.laborAccount -= offer.price;
            sRec.laborAccount = (int) Math.min((long) sRec.laborAccount + offer.price,
                    Integer.MAX_VALUE);
            ctx.prisonerRegister().write(reg);
        } else {
            // A favor trade: the buyer's word is physical — an IOU note the
            // seller can hold, lose, or get stolen.
            issueIou(buyer, seller, "favor");
        }
        var entry = new JobStore.TradeEntry();
        entry.id = "trade-" + now();
        entry.sellerUuid = sellerUuid;
        entry.sellerName = seller.name();
        entry.buyerUuid = uuidOf(buyer);
        entry.buyerName = buyer.name();
        entry.itemId = offer.itemId;
        entry.count = offer.count;
        entry.price = offer.price;
        entry.favor = offer.favor;
        entry.at = now();
        var store = store();
        store.trades.add(entry);
        store.prune(ctx.policies().jobRetentionLimit, ctx.policies().jobRetentionLimit);
        ctx.jobs().write(store);
        seller.tellKey("straja.trade.done_seller", buyer.name(),
                offer.count + "×" + offer.itemId, offer.price);
        buyer.tellKey("straja.trade.done_buyer", seller.name(),
                offer.count + "×" + offer.itemId, offer.price);
        audit.record(AuditActions.CAMP_TRANSFER, seller.name(), sellerUuid,
                buyer.name(), uuidOf(buyer), AuditResults.SUCCESS,
                "trade " + offer.count + "x" + offer.itemId
                        + (offer.favor ? " favor" : " for=" + offer.price));
    }

    /** {@code /straja iou <creditor> <text>} — sign a favor note into being. */
    public void iou(PlayerGateway debtor, String creditorName, String text) {
        if (registerRecord(debtor) == null) {
            debtor.refuse("straja.iou.only_prisoners", "straja.remedy.wait");
            return;
        }
        var creditor = findPrisoner(creditorName);
        if (creditor == null) {
            debtor.refuse("straja.iou.no_creditor", "straja.remedy.trade_buyer", creditorName);
            return;
        }
        var cg = findOnline(creditor);
        if (cg == null) {
            debtor.refuse("straja.iou.offline", "straja.remedy.wait");
            return;
        }
        issueIou(debtor, cg, text == null ? "favor" : text);
        debtor.tellKey("straja.iou.issued", creditorName);
        cg.tellKey("straja.iou.received", debtor.name(), text);
        audit.record(AuditActions.CAMP_TRANSFER, debtor.name(), uuidOf(debtor),
                creditorName, creditor, AuditResults.SUCCESS, "iou " + text);
    }

    private void issueIou(PlayerGateway debtor, PlayerGateway creditor, String text) {
        // 1.21 component format: custom_data carries the debtor + note; the
        // visible name comes from the item lang entry.
        String snbt = "{id:\"straja:iou_note\",count:1,components:{"
                + "\"minecraft:custom_data\":{debtor:\"" + safeSnbt(debtor.name())
                + "\",note:\"" + safeSnbt(text) + "\"}}}";
        creditor.giveStack("straja:iou_note", 1, snbt);
    }

    private static String safeSnbt(String s) {
        return s == null ? "" : s.replace("\\", "").replace("\"", "'")
                .replace("{", "(").replace("}", ")");
    }

    // ------------------------------------------------------------ inspection calls

    /**
     * Periodic quota pressure: on a configured cadence a routine dispatch
     * call points at an occupied cell — the guard who runs
     * {@code /straja search} there closes it.
     */
    public void tick() {
        long now = now();
        offers.entrySet().removeIf(e -> e.getValue() == null
                || now > e.getValue().expiresAt);
        if (dispatch == null) return;
        int interval = ctx.policies().inspectionCallIntervalMinutes;
        if (interval <= 0) return;
        if (now < nextInspectionCallAt) return;
        nextInspectionCallAt = now + interval * 60_000L;
        if (!openInspectionSourceId.isEmpty() && !inspectionCallLive()) {
            // The board closed it without a search — release the slot so the
            // next sweep can post fresh quota work.
            openInspectionSourceId = "";
        }
        if (openInspectionSourceId.isEmpty()) {
            var cellId = pickInspectionTarget();
            if (cellId == null) return;
            var cell = ctx.prison().read().cell(cellId);
            if (cell == null) return;
            openInspectionSourceId = "inspect:" + cellId;
            var call = dispatch.raise(DispatchService.SOURCE_ESCAPE,
                    openInspectionSourceId, "straja.inspect.call", cellId,
                    cell.dimension, cell.doorX, cell.doorY, cell.doorZ,
                    cellId, null, null, "", "ROUTINE", "ENLISTED");
            if (call == null) openInspectionSourceId = "";
        }
    }

    private boolean inspectionCallLive() {
        for (var c : ctx.dispatch().read().calls) {
            if (c != null && openInspectionSourceId.equals(c.sourceId)
                    && ("OPEN".equals(c.status) || "ACCEPTED".equals(c.status))) {
                return true;
            }
        }
        return false;
    }

    /** {@code /straja search} near a cell with an open call resolves it. */
    public void completeInspection(PlayerGateway inspector, String cellId) {
        if (cellId == null || openInspectionSourceId.isEmpty()
                || dispatch == null) return;
        if (!openInspectionSourceId.equals("inspect:" + cellId)) return;
        dispatch.closeForSource(DispatchService.SOURCE_ESCAPE,
                openInspectionSourceId, "inspected");
        openInspectionSourceId = "";
        audit.record(AuditActions.CAMP_TRANSFER, inspector.name(), uuidOf(inspector),
                cellId, "", AuditResults.SUCCESS, "inspection_call");
    }

    private String pickInspectionTarget() {
        var data = ctx.prison().read();
        if (data.cells == null || data.cells.isEmpty()) return null;
        for (var c : data.cells) {
            if (c == null) continue;
            // An occupied bunk makes the call real.
            var a = data.assignments.get(c.id);
            if (a != null) return c.id;
        }
        return data.cells.get(0).id;
    }

    // ------------------------------------------------------------ helpers

    private com.dwurdy.straja.domain.model.PrisonerRegisterRecord registerRecord(
            PlayerGateway p) {
        if (p == null || p.uuid() == null) return null;
        var rec = ctx.prisonerRegister().read().prisoner(p.uuid().toString());
        if (rec == null || rec.status.isReleased()) return null;
        return rec;
    }

    /** An online prisoner's uuid by display name, case-insensitive. */
    private String findPrisoner(String name) {
        if (name == null || name.isBlank()) return null;
        var reg = ctx.prisonerRegister().read();
        for (var r : reg.prisoners().values()) {
            if (r != null && !r.status.isReleased()
                    && r.detaineeName != null
                    && r.detaineeName.equalsIgnoreCase(name.trim())
                    && isOnline(r.detaineeUuid)) {
                return r.detaineeUuid;
            }
        }
        return null;
    }

    private boolean isOnline(String uuid) {
        return findOnline(uuid) != null;
    }

    private PlayerGateway findOnline(String uuid) {
        if (uuid == null || uuid.isBlank()) return null;
        for (PlayerGateway p : ctx.server().onlinePlayers()) {
            if (p != null && p.uuid() != null
                    && uuid.equalsIgnoreCase(p.uuid().toString())) return p;
        }
        return null;
    }

    private String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }

    private long now() {
        return ctx.clock() == null ? System.currentTimeMillis() : ctx.clock().nowMillis();
    }
}
