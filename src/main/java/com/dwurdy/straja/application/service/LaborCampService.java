package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.Capability;
import com.dwurdy.straja.domain.model.FreedomPriceMode;
import com.dwurdy.straja.domain.model.LaborCampRecord;
import com.dwurdy.straja.domain.model.LaborCampStore;
import com.dwurdy.straja.domain.model.LawBounds;
import com.dwurdy.straja.domain.model.PrisonerRegisterRecord;
import com.dwurdy.straja.domain.model.PrisonerStatus;
import com.dwurdy.straja.domain.model.StoragePoint;
import com.dwurdy.straja.domain.model.StrajaPolicies;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LAW-006 labor camps: perimeter registration, quartermaster/exit links,
 * spawn points and freedom pricing. Custody enforcement (bounds, respawn,
 * auto-release, transfers) lives in {@link PrisonService} — this service is
 * the camp's configuration and coin-math surface the custody side queries.
 */
public final class LaborCampService {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public LaborCampService(StrajaContext ctx, PlayerService players, AuditService audit) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
    }

    private LaborCampStore store() { return ctx.laborCamps().read(); }

    // ----------------------------------------------------------- queries

    /** Read-only camp lookup — null-tolerant for stale register links. */
    public LaborCampRecord camp(String id) {
        if (id == null || id.isBlank()) return null;
        return store().camp(id.trim());
    }

    /** The camp whose exit checkpoint is {@code siteId}; null when none. */
    public LaborCampRecord campForExit(String siteId) {
        if (siteId == null || siteId.isBlank()) return null;
        for (var entry : store().camps().entrySet()) {
            LaborCampRecord camp = entry.getValue();
            if (camp != null && siteId.equals(camp.exitCheckpointId)) return camp;
        }
        return null;
    }

    /** True when the record is currently under camp custody. */
    public boolean isCampPrisoner(PrisonerRegisterRecord rec) {
        return rec != null && rec.status == PrisonerStatus.IN_CAMP
                && rec.assignedCampId != null && !rec.assignedCampId.isBlank();
    }

    /**
     * The camp's freedom price for this prisoner in base units. FLAT uses the
     * per-camp price (or the TOML default when unset); FINES_MULTIPLIER is
     * derived live from outstanding fines so external fine payments lower the
     * target without a config write.
     */
    public long freedomPrice(LaborCampRecord camp, PrisonerRegisterRecord rec) {
        var p = ctx.policies();
        if (camp != null && camp.freedomMode == FreedomPriceMode.FINES_MULTIPLIER) {
            double mult = camp.freedomFineMultiplier > 0
                    ? camp.freedomFineMultiplier : p.laborFreedomFineMultiplier;
            long fines = rec == null ? 0 : Math.max(0, rec.outstandingFines);
            double price = fines * mult;
            // A prisoner with no fines (or an unreachable price) would make the
            // buy-out valve dead — fall back to the flat price so release is
            // always earnable, and never exceed the int-capped account.
            if (price <= 0 || price > Integer.MAX_VALUE) {
                return flatPrice(camp, p);
            }
            return Math.round(price);
        }
        return flatPrice(camp, p);
    }

    private static long flatPrice(LaborCampRecord camp,
                                  com.dwurdy.straja.domain.model.StrajaPolicies p) {
        if (camp != null && camp.freedomFlatPrice > 0) return camp.freedomFlatPrice;
        return Math.max(0, p.laborFreedomFlatPrice);
    }

    // ------------------------------------------------------- coin format

    /**
     * Ascending tier base-unit values resolved from policy (tierRatio ladder
     * unless {@code coinItemIds} carries explicit values). Never empty —
     * malformed policy falls back to the 1:64 ladder.
     */
    private int[] tiers() {
        var p = ctx.policies();
        List<Integer> explicit = new ArrayList<>();
        if (p.coinItemIds != null) explicit.addAll(p.coinItemIds.keySet());
        explicit.sort(Integer::compareTo);
        if (explicit.size() >= 4 && explicit.get(0) == 1) {
            return new int[]{explicit.get(0), explicit.get(1), explicit.get(2), explicit.get(3)};
        }
        return StrajaPolicies.coinTierValues(Math.max(2, p.coinTierRatio), 0, 0, 0);
    }

    /**
     * Exact lossless breakdown like {@code "1g 32s 5br 12b"} — highest tiers
     * first, zero tiers omitted, zero renders {@code "0b"}. The configured
     * tier ratio (or explicit values) defines the denominations.
     */
    public String formatCoins(long units) {
        if (units <= 0) return "0b";
        int[] t = tiers();
        String[] suffix = {"b", "br", "s", "g"};
        StringBuilder out = new StringBuilder();
        long rem = units;
        for (int i = t.length - 1; i >= 0; i--) {
            long n = rem / t[i];
            rem %= t[i];
            if (n > 0) {
                if (out.length() > 0) out.append(' ');
                out.append(n).append(suffix[Math.min(i, suffix.length - 1)]);
            }
        }
        return out.length() == 0 ? "0b" : out.toString();
    }

    /**
     * Parses coin amounts into base units: bare integers are raw base units,
     * {@code 1g 32s 5br 12b} mixes tier suffixes. Returns -1 when malformed.
     */
    public long parseCoins(String text) {
        if (text == null || text.isBlank()) return -1;
        int[] t = tiers();
        long total = 0;
        for (String tok : text.trim().split("\\s+")) {
            long mult = -1;
            String digits = tok;
            String low = tok.toLowerCase(java.util.Locale.ROOT);
            // Longest suffix first so "br" beats "b".
            if (low.endsWith("br")) { mult = t[1]; digits = tok.substring(0, tok.length() - 2); }
            else if (low.endsWith("b")) { mult = t[0]; digits = tok.substring(0, tok.length() - 1); }
            else if (low.endsWith("s")) { mult = t[2]; digits = tok.substring(0, tok.length() - 1); }
            else if (low.endsWith("g")) { mult = t[3]; digits = tok.substring(0, tok.length() - 1); }
            if (digits.isEmpty() || !digits.matches("\\d+")) return -1;
            long amount;
            try {
                amount = Long.parseLong(digits);
            } catch (NumberFormatException tooLong) {
                return -1;
            }
            if (mult > 0 && amount > Integer.MAX_VALUE / mult) return -1;
            total += mult < 0 ? amount : amount * mult;
            if (total > Integer.MAX_VALUE) return -1;
        }
        return total;
    }

    // ------------------------------------------------------------ admin

    /** {@code camp register <id> <name> <minX,minY,minZ> <maxX,maxY,maxZ>}. */
    public boolean register(PlayerGateway actor, String id, String name,
                            String minCsv, String maxCsv) {
        if (!players.isCommissioner(actor)) {
            actor.refuse("straja.camp.rank", "straja.remedy.ask_comisar");
            return false;
        }
        if (id == null || !id.matches("[a-z0-9_-]{1,32}")) {
            actor.refuse("straja.camp.bad_id", "straja.remedy.fix_retry");
            return false;
        }
        var store = store();
        if (store.camp(id) != null) {
            actor.refuse("straja.camp.exists", "straja.remedy.fix_retry", id);
            return false;
        }
        LawBounds bounds = parseBounds(actor.dimension(), minCsv, maxCsv);
        if (bounds == null) {
            actor.refuse("straja.camp.bad_bounds", "straja.remedy.fix_retry");
            return false;
        }
        var camp = new LaborCampRecord();
        camp.id = id;
        camp.name = name == null ? "" : name;
        camp.dimension = actor.dimension();
        camp.boundary = bounds;
        store.put(camp);
        ctx.laborCamps().write(store);
        actor.tellKey("straja.camp.registered", id, camp.name);
        audit.record("camp_register", actor.name(), uuidOf(actor), id, "",
                "SUCCESS", "bounds=" + boundsKey(bounds));
        return true;
    }

    /** {@code camp link-desk <camp> <desk>} — the quartermaster inside the camp. */
    public boolean linkDesk(PlayerGateway actor, String campId, String deskId) {
        var camp = requireCamp(actor, campId);
        if (camp == null) return false;
        var desk = ctx.merchantDesks().read().desk(deskId == null ? "" : deskId);
        if (desk == null) {
            actor.refuse("straja.camp.no_desk", "straja.remedy.fix_retry", deskId);
            return false;
        }
        camp.quartermasterDeskId = desk.id;
        commit(camp);
        actor.tellKey("straja.camp.linked_desk", camp.id, desk.id);
        audit.record("camp_link_desk", actor.name(), uuidOf(actor), camp.id, "",
                "SUCCESS", "desk=" + desk.id);
        return true;
    }

    /** {@code camp link-exit <camp> <site>} — repels prisoners, confiscates ore. */
    public boolean linkExit(PlayerGateway actor, String campId, String siteId) {
        var camp = requireCamp(actor, campId);
        if (camp == null) return false;
        var site = ctx.lawCheckpoints().read().checkpoint(siteId == null ? "" : siteId);
        if (site == null) {
            actor.refuse("straja.camp.no_site", "straja.remedy.fix_retry", siteId);
            return false;
        }
        camp.exitCheckpointId = site.id;
        commit(camp);
        actor.tellKey("straja.camp.linked_exit", camp.id, site.id);
        audit.record("camp_link_exit", actor.name(), uuidOf(actor), camp.id, "",
                "SUCCESS", "site=" + site.id);
        return true;
    }

    /**
     * {@code camp set-freedom-price <camp> <flat|fines_multiplier> [value]} —
     * flat accepts coin syntax ({@code 1g 32s}) or raw base units; multiplier
     * takes a double. Blank value restores the TOML default.
     */
    public boolean setFreedomPrice(PlayerGateway actor, String campId,
                                   String mode, String value) {
        var camp = requireCamp(actor, campId);
        if (camp == null) return false;
        String m = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (value == null || value.isBlank()) {
            // Reset to TOML defaults for the chosen (or current) mode.
            if ("flat".equals(m)) camp.freedomMode = FreedomPriceMode.FLAT;
            else if ("fines_multiplier".equals(m) || "multiplier".equals(m))
                camp.freedomMode = FreedomPriceMode.FINES_MULTIPLIER;
            else { actor.refuse("straja.camp.bad_mode", "straja.remedy.fix_retry"); return false; }
            camp.freedomFlatPrice = 0;
            camp.freedomFineMultiplier = 0;
            commit(camp);
            actor.tellKey("straja.camp.price_reset", camp.id);
            return true;
        }
        if ("flat".equals(m) || "fixed".equals(m)) {
            long units = parseCoins(value);
            if (units <= 0) {
                actor.refuse("straja.camp.bad_price", "straja.remedy.fix_retry", value);
                return false;
            }
            camp.freedomMode = FreedomPriceMode.FLAT;
            camp.freedomFlatPrice = (int) units;
        } else if ("fines_multiplier".equals(m) || "multiplier".equals(m)) {
            double mult;
            try { mult = Double.parseDouble(value); }
            catch (NumberFormatException ex) {
                actor.refuse("straja.camp.bad_price", "straja.remedy.fix_retry", value);
                return false;
            }
            if (mult <= 0 || mult > 1000) {
                actor.refuse("straja.camp.bad_price", "straja.remedy.fix_retry", value);
                return false;
            }
            camp.freedomMode = FreedomPriceMode.FINES_MULTIPLIER;
            camp.freedomFineMultiplier = mult;
        } else {
            actor.refuse("straja.camp.bad_mode", "straja.remedy.fix_retry");
            return false;
        }
        commit(camp);
        actor.tellKey("straja.camp.price_set", camp.id, mode,
                camp.freedomMode == FreedomPriceMode.FLAT
                        ? formatCoins(camp.freedomFlatPrice)
                        : String.valueOf(camp.freedomFineMultiplier));
        audit.record("camp_freedom_price", actor.name(), uuidOf(actor), camp.id, "",
                "SUCCESS", "mode=" + camp.freedomMode + " value=" + value);
        return true;
    }

    /** {@code camp spawn <camp> <intake|release|dormitory>} — actor's position. */
    public boolean setSpawn(PlayerGateway actor, String campId, String kind) {
        var camp = requireCamp(actor, campId);
        if (camp == null) return false;
        // A spawn in the wrong dimension teleports prisoners outside the wire
        // and the perimeter check flags them fugitive for doing nothing.
        if (camp.boundary != null
                && !camp.boundary.dimension().equals(actor.dimension())) {
            actor.refuse("straja.camp.bad_dimension", "straja.remedy.stand_in_camp",
                    camp.id);
            return false;
        }
        var point = new StoragePoint(actor.dimension(),
                (int) Math.floor(actor.x()), (int) Math.floor(actor.y()),
                (int) Math.floor(actor.z()));
        switch (kind == null ? "" : kind.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "intake" -> camp.intakeSpawn = point;
            case "release" -> camp.releaseSpawn = point;
            case "dormitory", "dorm" -> camp.dormitorySpawn = point;
            default -> {
                actor.refuse("straja.camp.bad_spawn_kind", "straja.remedy.fix_retry");
                return false;
            }
        }
        commit(camp);
        actor.tellKey("straja.camp.spawn_set", camp.id, kind, point.key());
        audit.record("camp_spawn", actor.name(), uuidOf(actor), camp.id, "",
                "SUCCESS", kind + "=" + point.key());
        return true;
    }

    /** {@code camp unregister <camp>} — refuses while prisoners are assigned. */
    public boolean unregister(PlayerGateway actor, String campId) {
        var camp = requireCamp(actor, campId);
        if (camp == null) return false;
        var reg = ctx.prisonerRegister().read();
        for (var rec : reg.prisoners().values()) {
            // Any unreleased assignment still belongs to the camp — a fugitive
            // or escorted prisoner must not strand the link.
            if (rec != null && camp.id.equals(rec.assignedCampId)
                    && !rec.status.isReleased()) {
                actor.refuse("straja.camp.occupied", "straja.remedy.release_prisoners",
                        rec.detaineeName);
                return false;
            }
        }
        var store = store();
        store.remove(camp.id);
        ctx.laborCamps().write(store);
        actor.tellKey("straja.camp.unregistered", camp.id);
        audit.record("camp_unregister", actor.name(), uuidOf(actor), camp.id, "", "SUCCESS", "");
        return true;
    }

    // ------------------------------------------------------- status/list

    /** Self-status for a camp prisoner; staff may query a target by name. */
    public void status(PlayerGateway viewer, String targetName) {
        PrisonerRegisterRecord rec;
        var reg = ctx.prisonerRegister().read();
        if (targetName == null || targetName.isBlank()) {
            rec = reg.prisoner(viewer.uuid() == null ? "" : viewer.uuid().toString());
            if (rec == null || !isCampPrisoner(rec)) {
                viewer.refuse("straja.camp.not_prisoner", "straja.remedy.jailer");
                return;
            }
        } else {
            if (!players.hasCapability(viewer, Capability.EXECUTE_ARRESTS)
                    && !players.isCommissioner(viewer)) {
                viewer.refuse("straja.camp.rank", "straja.remedy.jailer");
                return;
            }
            rec = reg.prisonerByName(targetName);
            if (rec == null) rec = reg.prisoner(targetName);
            if (rec == null) {
                viewer.refuse("straja.camp.no_record", "straja.remedy.fix_retry", targetName);
                return;
            }
        }
        var camp = camp(rec.assignedCampId);
        long target = freedomPrice(camp, rec);
        long credit = Math.max(0, rec.laborAccount);
        long remaining = Math.max(0, target - credit);
        viewer.tellKey("straja.camp.status_head", rec.detaineeName,
                camp == null ? rec.assignedCampId : camp.name,
                rec.status.name());
        viewer.tellKey("straja.camp.status_labor", formatCoins(credit),
                formatCoins(target), formatCoins(remaining));
    }

    /** Admin listing of registered camps. */
    public void listCamps(PlayerGateway actor) {
        if (!players.isCommissioner(actor)) {
            actor.refuse("straja.camp.rank", "straja.remedy.ask_comisar");
            return;
        }
        var camps = store().camps();
        if (camps.isEmpty()) {
            actor.tellKey("straja.camp.none");
            return;
        }
        for (var camp : camps.values()) {
            if (camp == null) continue;
            actor.tellKey("straja.camp.entry", camp.id, camp.name,
                    boundsKey(camp.boundary),
                    camp.quartermasterDeskId.isBlank() ? "-" : camp.quartermasterDeskId,
                    camp.exitCheckpointId.isBlank() ? "-" : camp.exitCheckpointId);
        }
    }

    /**
     * Quartermaster-sale feedback (called by {@code MerchantDeskService}
     * after crediting a camp prisoner's labor account): actionbar-style
     * progress line — credited, total, freedom target.
     */
    public void notifyLaborSale(PlayerGateway seller, PrisonerRegisterRecord rec,
                                long creditedUnits) {
        var camp = camp(rec.assignedCampId);
        seller.tellKey("straja.camp.sale_credited", formatCoins(creditedUnits),
                formatCoins(Math.max(0, rec.laborAccount)),
                formatCoins(freedomPrice(camp, rec)));
    }

    // ---------------------------------------------------------- helpers

    private LaborCampRecord requireCamp(PlayerGateway actor, String campId) {
        if (!players.isCommissioner(actor)) {
            actor.refuse("straja.camp.rank", "straja.remedy.ask_comisar");
            return null;
        }
        var camp = camp(campId);
        if (camp == null) {
            actor.refuse("straja.camp.unknown", "straja.remedy.fix_retry", campId);
        }
        return camp;
    }

    private void commit(LaborCampRecord camp) {
        var store = store();
        store.put(camp);
        ctx.laborCamps().write(store);
    }

    private static LawBounds parseBounds(String dimension, String minCsv, String maxCsv) {
        int[] a = parseCsv(minCsv), b = parseCsv(maxCsv);
        if (a == null || b == null) return null;
        return LawBounds.of(dimension, a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    private static int[] parseCsv(String csv) {
        if (csv == null) return null;
        String[] parts = csv.split(",");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String boundsKey(LawBounds b) {
        if (b == null) return "-";
        return b.dimension() + " " + b.minX() + "," + b.minY() + "," + b.minZ()
                + " -> " + b.maxX() + "," + b.maxY() + "," + b.maxZ();
    }

    private static String uuidOf(PlayerGateway p) {
        return p.uuid() == null ? "" : p.uuid().toString();
    }
}
