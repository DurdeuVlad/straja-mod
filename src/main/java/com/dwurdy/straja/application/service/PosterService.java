package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.PosterBoardGateway;
import com.dwurdy.straja.domain.model.AuditActions;
import com.dwurdy.straja.domain.model.AuditResults;
import com.dwurdy.straja.domain.model.SetupData;

import java.util.HashMap;
import java.util.Map;

/**
 * POSTER-1: the public face board. Each declared spot cycles the active
 * bounty list — the swap happens only while nobody is watching, so the
 * face never flickers mid-look. A resolved hunt comes down immediately:
 * the board should never advertise a criminal the state no longer wants.
 * The service is headless — the board port owns entity mechanics.
 */
public final class PosterService {

    private static final String SYSTEM_ISSUER = "straja:system";

    private final StrajaContext ctx;
    private final BountyService bounties;
    private final AuditService audit;
    private PosterBoardGateway board = PosterBoardGateway.NONE;

    private final Map<String, SpotState> states = new HashMap<>();

    private static final class SpotState {
        int index = -1;
        String currentBountyId = "";
        boolean rendered;
        long nextSwapAt;
    }

    public PosterService(StrajaContext ctx, BountyService bounties, AuditService audit) {
        this.ctx = ctx;
        this.bounties = bounties;
        this.audit = audit;
    }

    public void useBoard(PosterBoardGateway gateway) {
        this.board = gateway == null ? PosterBoardGateway.NONE : gateway;
    }

    public synchronized void tick() {
        if (!ctx.policies().posterEnabled) return;
        // The event caller paces this to once per second; an internal
        // wall-clock throttle would stretch past a full tick boundary
        // under lag and let resolved hunts linger an extra cycle.
        long now = now();
        var data = ctx.setup().read();
        var spots = data.posterSpots == null
                ? Map.<String, SetupData.PosterSpot>of() : data.posterSpots;
        if (spots.isEmpty()) return;
        var active = bounties.activeBounties();
        double holdR = Math.max(1, ctx.policies().posterHoldRadius);
        long cycleMs = Math.max(1, ctx.policies().posterCycleSeconds) * 1_000L;
        for (var spot : spots.values()) {
            if (spot == null || spot.id == null || spot.id.isBlank()) continue;
            var st = states.computeIfAbsent(spot.id, k -> new SpotState());

            // A resolved hunt comes down immediately — never advertise a
            // criminal the state stopped wanting.
            if (!st.currentBountyId.isEmpty() && active.stream()
                    .noneMatch(b -> b != null && st.currentBountyId.equals(b.id))) {
                board.renderIdle(spot.id, spot.dimension, spot.x, spot.y, spot.z);
                st.currentBountyId = "";
                st.rendered = false;
                st.index = -1;
            }

            int watchers = board.watchersNear(spot.dimension, spot.x, spot.y,
                    spot.z, holdR);
            // An observed poster holds its face — the swap waits for the
            // room to empty. The hold resets the cadence, not just defers.
            if (watchers > 0 && st.rendered) {
                st.nextSwapAt = now + cycleMs;
                continue;
            }
            // The cadence spaces out visible face-swaps only — a blank or
            // idle board paints its first face the moment the feed has one.
            if (st.rendered && now < st.nextSwapAt) {
                continue;
            }
            st.nextSwapAt = now + cycleMs;

            if (active.isEmpty()) {
                if (st.rendered) {
                    board.renderIdle(spot.id, spot.dimension, spot.x, spot.y, spot.z);
                    st.rendered = false;
                    st.currentBountyId = "";
                    st.index = -1;
                }
                continue;
            }
            if (++st.index >= active.size() || st.index < 0) st.index = 0;
            var bounty = active.get(st.index);
            if (bounty == null) { st.index = -1; continue; }
            board.renderFace(spot.id, spot.dimension, spot.x, spot.y, spot.z,
                    bounty.targetUuid, bounty.targetName, bounty.amount);
            st.currentBountyId = bounty.id;
            st.rendered = true;
        }
    }

    /** `/straja poster add <id>` — stand where the poster should hang. */
    public boolean posterAdd(PlayerGateway admin, String id) {
        if (admin == null || id == null || id.isBlank()) return false;
        var data = ctx.setup().read();
        if (data.posterSpots == null) data.posterSpots = new java.util.LinkedHashMap<>();
        if (data.posterSpots.containsKey(id)) {
            admin.refuse("straja.zone.exists", "straja.remedy.fix_retry", id);
            return false;
        }
        var spot = new SetupData.PosterSpot();
        spot.id = id;
        spot.dimension = admin.dimension();
        spot.x = admin.x();
        spot.y = admin.y() + 1.6;
        spot.z = admin.z();
        data.posterSpots.put(id, spot);
        ctx.setup().write(data);
        admin.tell("Panou de căutați " + id + " declarat — fața ciclhează singură.");
        audit.record(AuditActions.CAMP_SPAWN, admin.name(), uuidOf(admin), id, "",
                AuditResults.SUCCESS, "poster_spot");
        return true;
    }

    /** `/straja poster remove <id>` — tears down the spot and its entities. */
    public boolean posterRemove(PlayerGateway admin, String id) {
        if (admin == null) return false;
        var data = ctx.setup().read();
        states.remove(id);
        SetupData.PosterSpot spot = data.posterSpots == null
                ? null : data.posterSpots.remove(id);
        if (spot == null) {
            admin.refuse("straja.zone.unknown", "straja.remedy.fix_retry", id);
            return false;
        }
        ctx.setup().write(data);
        states.remove(id);
        board.remove(id, spot.dimension, spot.x, spot.y, spot.z);
        admin.tell("Panoul de căutați " + id + " eliminat.");
        return true;
    }

    public void posterList(PlayerGateway admin) {
        if (admin == null) return;
        var data = ctx.setup().read();
        if (data.posterSpots == null || data.posterSpots.isEmpty()) {
            admin.tellKey("straja.poster.list_empty");
            return;
        }
        for (var spot : data.posterSpots.values()) {
            admin.tell(" - " + spot.id + " @ " + spot.dimension + " "
                    + (int) spot.x + "," + (int) spot.y + "," + (int) spot.z);
        }
    }

    private long now() { return ctx.clock().nowMillis(); }

    private static String uuidOf(PlayerGateway p) {
        return p == null || p.uuid() == null ? "" : p.uuid().toString();
    }
}
