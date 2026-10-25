package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.application.port.in.ParoleUseCase;
import com.dwurdy.straja.domain.model.ArmyOperation;
import com.dwurdy.straja.domain.model.ArmyUnit;
import com.dwurdy.straja.domain.model.BountyRecord;
import com.dwurdy.straja.domain.model.DispatchCall;
import com.dwurdy.straja.domain.model.HearingRecord;
import com.dwurdy.straja.domain.model.ManhuntRecord;
import com.dwurdy.straja.domain.model.TransferRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Live-data desk pages: content recomputed from service snapshots on every
 * render — the parole docket and hearing detail, the dispatch board, trades
 * enrolment, the army views. Pure builders over domain records; the session
 * fetches the snapshots so nothing here imports Minecraft.
 */
public final class DeskDynamicPages {
    public static final String PAROLE_STATUS = "parole-status";
    public static final String PAROLE_DOCKET = "parole-docket";
    public static final String PAROLE_HEARING = "parole-hearing";
    public static final String ARMY_STATUS = "army-status";
    public static final String ARMY_HISTORY = "army-history";
    public static final String TRADES = "trades";
    public static final String DISPATCH = "dispatch";
    public static final String V2_OFFER = "v2-offer";
    public static final String MANHUNT = "manhunt";
    public static final String TRANSFER = "transfer";
    public static final String BOUNTY = "bounty";

    private DeskDynamicPages() {}

    /**
     * Maps an action id to the dynamic page it opens inside the desk, or null
     * when the action keeps its normal dispatch (mutations, forms, gives).
     */
    static DeskPageSource.DynamicPage sourceFor(String actionId) {
        if (actionId == null) return null;
        if (actionId.startsWith("parole-review:")) {
            return new DeskPageSource.DynamicPage(
                    PAROLE_HEARING, actionId.substring("parole-review:".length()), 0);
        }
        return switch (actionId) {
            case "parole-status" -> new DeskPageSource.DynamicPage(PAROLE_STATUS, "", 0);
            case "parole-docket" -> new DeskPageSource.DynamicPage(PAROLE_DOCKET, "", 0);
            case "army-status" -> new DeskPageSource.DynamicPage(ARMY_STATUS, "", 0);
            case "army-history" -> new DeskPageSource.DynamicPage(ARMY_HISTORY, "", 0);
            case "trades-list" -> new DeskPageSource.DynamicPage(TRADES, "", 0);
            case "dispatch-board" -> new DeskPageSource.DynamicPage(DISPATCH, "", 0);
            case "manhunt-board" -> new DeskPageSource.DynamicPage(MANHUNT, "", 0);
            case "transfer-board" -> new DeskPageSource.DynamicPage(TRANSFER, "", 0);
            case "bounty-board" -> new DeskPageSource.DynamicPage(BOUNTY, "", 0);
            default -> null;
        };
    }

    // ---------- parole ----------

    /**
     * The prisoner's own view: the ledger checks as one tile each, plus the
     * rows the jailer desk would show underneath (file, advocate, settle,
     * check-in) so the status page is actionable in place.
     */
    static DeskPage paroleStatus(List<ParoleUseCase.Check> checks,
                                 ParoleUseCase.ParoleSurface surface) {
        var items = new ArrayList<DeskButton>();
        if (checks != null) {
            for (ParoleUseCase.Check check : checks) {
                if (check == null) continue;
                var lore = new ArrayList<String>();
                if (check.detail() != null && !check.detail().isBlank()) {
                    lore.add(check.detail());
                }
                lore.add(check.ok() ? "Îndeplinit." : "Neîndeplinit încă.");
                items.add(button(new DeskIcon(
                        check.ok() ? "minecraft:lime_dye" : "minecraft:red_dye",
                        checkLabel(check.key()) + (check.ok() ? " ✓" : " ✗"),
                        List.copyOf(lore)), null));
            }
        }
        if (surface != null) {
            if (surface.prisoner() && surface.eligible()
                    && surface.pendingId().isEmpty() && surface.conditionalId().isEmpty()) {
                items.add(button(DeskActionStyle.icon(
                        "Cere eliberarea condiționată", "parole-file"), "parole-file"));
                items.add(button(DeskActionStyle.icon(
                        "Contează sentința", "parole-appeal"), "parole-appeal"));
            }
            if (!surface.pendingId().isEmpty()) {
                items.add(button(new DeskIcon("straja:seal_stamp",
                        "Dosar " + surface.pendingId() + " — " + surface.pendingKind(),
                        List.of("Depus, în așteptarea ședinței.")), null));
            }
            if (!surface.pendingId().isEmpty()
                    && !"AGREED".equals(surface.advocateStatus())) {
                items.add(button(DeskActionStyle.icon(
                        "Desemnează un avocat", "parole-advocate"), "parole-advocate"));
            }
            if (!surface.conditionalId().isEmpty()) {
                items.add(button(DeskActionStyle.icon(
                        "Plătește cauțiunea (" + surface.bailAmount() + " credite)",
                        "parole-settle"), "parole-settle"));
            }
            if (surface.parolee()) {
                items.add(button(DeskActionStyle.icon(
                        "Raportează-te (check-in)", "parole-checkin"), "parole-checkin"));
            }
        }
        return DeskLayout.grid("Eliberare condiționată — statut",
                new DeskIcon("straja:official_document", "Statutul tău",
                        List.of("Condițiile contabilității pentru eliberare.")),
                items, 0, true);
    }

    /** The officer's ordered docket — one tile per pending case. */
    static DeskPage paroleDocket(List<HearingRecord> docket, int page) {
        var items = new ArrayList<DeskButton>();
        if (docket != null) {
            for (HearingRecord h : docket) {
                if (h == null) continue;
                var lore = new ArrayList<String>();
                lore.add(h.kind + " · " + h.status);
                if (h.reason != null && !h.reason.isBlank()) {
                    lore.addAll(DeskLayout.wrapLore("Motiv: " + h.reason, DeskLayout.LORE_WRAP));
                }
                if (h.objections > 0) lore.add("Obiecții: " + h.objections);
                items.add(button(new DeskIcon("straja:seal_stamp",
                        h.id + " · " + bounded(h.prisonerName, 24),
                        List.copyOf(lore)), "parole-review:" + h.id));
            }
        }
        if (items.isEmpty()) {
            items.add(button(new DeskIcon("minecraft:paper",
                    "Niciun dosar în așteptare",
                    List.of("Dozarul e gol — liniște pe front.")), null));
        }
        return DeskLayout.grid("Dosare de eliberare",
                new DeskIcon("straja:order_book", "Dozarul zilei",
                        List.of("Dosarele așteaptă ședința.", "Deschide un dosar pentru detalii.")),
                items, page, true);
    }

    /**
     * One hearing: the case facts as info tiles and the officer's verbs as
     * action rows. Decision rows stay visible while the objection window is
     * open — the service names the wait if clicked early.
     */
    static DeskPage paroleHearing(HearingRecord h, long nowMs) {
        var items = new ArrayList<DeskButton>();
        if (h == null) {
            return DeskLayout.info("Dosar dispărut",
                    new DeskIcon("minecraft:barrier", "Dosar închis",
                            List.of("Dosarul nu mai există", "sau a fost deja decis.")),
                    true);
        }
        items.add(button(new DeskIcon("straja:seal_stamp",
                h.id + " · " + h.kind + " · " + h.status,
                List.of("Deținut: " + bounded(h.prisonerName, 30))), null));
        if (h.reason != null && !h.reason.isBlank()) {
            items.add(button(new DeskIcon("minecraft:paper", "Motiv",
                    DeskLayout.wrapLore(h.reason, DeskLayout.LORE_WRAP)), null));
        }
        if ("HEARING".equals(h.status)) {
            long left = Math.max(0, (h.objectionUntil - nowMs) / 60_000L);
            items.add(button(new DeskIcon("minecraft:clock",
                    "Fereastră obiecții: " + left + " min",
                    List.of("Obiecții primite: " + h.objections,
                            left > 0 ? "Decizia se deblochează la capătul ferestrei."
                                    : "Fereastra s-a închis — decizia e liberă.")), null));
        }
        if (h.advocateName != null && !h.advocateName.isBlank()) {
            items.add(button(new DeskIcon("straja:identity_card",
                    "Avocat: " + bounded(h.advocateName, 24),
                    List.of("Statut: " + h.advocateStatus)), null));
        }
        if ("FILED".equals(h.status)) {
            items.add(button(DeskActionStyle.icon(
                    "Deschide ședința", "parole-open:" + h.id), "parole-open:" + h.id));
        } else if ("HEARING".equals(h.status)) {
            items.add(button(DeskActionStyle.icon(
                    "Eliberează", "parole-decide-paroled:" + h.id),
                    "parole-decide-paroled:" + h.id));
            items.add(button(DeskActionStyle.icon(
                    "Eliberare cu cauțiune", "parole-decide-conditional:" + h.id),
                    "parole-decide-conditional:" + h.id));
            items.add(button(DeskActionStyle.icon(
                    "Respinge", "parole-decide-denied:" + h.id),
                    "parole-decide-denied:" + h.id));
        }
        return DeskLayout.grid("Dosar " + h.id,
                new DeskIcon("straja:seal_stamp", "Ședința",
                        List.of("Faptele dosarului și decizia.")),
                items, 0, true);
    }

    // ---------- army ----------

    /** The unit card: roster, readiness and the next operation countdown. */
    static DeskPage armyStatus(ArmyUnit unit, ArmyOperation nextOp,
                             String commanderName, long nowMs) {
        var items = new ArrayList<DeskButton>();
        if (unit == null) {
            return DeskLayout.info("Armata stației",
                    new DeskIcon("straja:baton", "Nicio unitate",
                            List.of("Încă nu s-a organizat o unitate", "militară la această stație.")),
                    true);
        }
        items.add(button(new DeskIcon("straja:baton", unit.name,
                List.of("Comandant: " + bounded(commanderName, 24),
                        "Membri: " + unit.ensureMembers().size(),
                        "Pregătire: " + unit.readiness + "/100")), null));
        items.add(button(new DeskIcon("straja:order_book", "Operații",
                List.of("Reușite: " + unit.operationsCompleted,
                        "Ratate: " + unit.operationsFailed)), null));
        if (nextOp == null) {
            items.add(button(new DeskIcon("minecraft:paper", "Nicio operație programată",
                    List.of("Următoarea se planifică singură —", "revino în scurt timp.")), null));
        } else if (nextOp.status == ArmyOperation.Status.ACTIVE) {
            items.add(button(new DeskIcon("straja:alarm_whistle",
                    "În desfășurare: " + nextOp.kind.label,
                    List.of("Prezenți: " + nextOp.ensureParticipants().size()
                            + "/" + nextOp.ensureSignups().size(),
                            "Prezintă-te la comandant!")), null));
        } else {
            long minutes = Math.max(0, (nextOp.scheduledStart - nowMs) / 60_000L);
            items.add(button(new DeskIcon("minecraft:clock",
                    "Programată: " + nextOp.kind.label,
                    List.of("Start în " + minutes + " min",
                            "Înscriși: " + nextOp.ensureSignups().size())), null));
        }
        return DeskLayout.grid("Armata — " + unit.name,
                new DeskIcon("straja:baton", "Statutul unității",
                        List.of("Unitatea stației și operația curentă.")),
                items, 0, true);
    }

    /** Past operations, one tile each — the unit's public record. */
    static DeskPage armyHistory(List<ArmyOperation> ops, int page) {
        var items = new ArrayList<DeskButton>();
        if (ops != null) {
            for (ArmyOperation op : ops) {
                if (op == null) continue;
                boolean won = op.status == ArmyOperation.Status.COMPLETED;
                var lore = new ArrayList<String>();
                if (op.resultNote != null && !op.resultNote.isBlank()) {
                    lore.addAll(DeskLayout.wrapLore(op.resultNote, DeskLayout.LORE_WRAP));
                }
                if (op.awardedBlocks > 0) lore.add("+" + op.awardedBlocks + " blocuri");
                items.add(button(new DeskIcon(
                        won ? "minecraft:lime_dye" : "minecraft:red_dye",
                        op.kind.label + " — " + (won ? "reușită" : "ratată"),
                        List.copyOf(lore)), null));
            }
        }
        if (items.isEmpty()) {
            items.add(button(new DeskIcon("minecraft:paper", "Istoric gol",
                    List.of("Prima operație e în desfășurare", "sau în planificare.")), null));
        }
        return DeskLayout.grid("Armata — istoric",
                new DeskIcon("straja:order_book", "Ultimele operații",
                        List.of("Registrul public al unității.")),
                items, page, true);
    }

    // ---------- trades / dispatch / v2 ----------

    /**
     * The guild enrolment grid — one tile per registered profession, each
     * opening the join-request form; a pending request renders as an info tile.
     */
    static DeskPage trades(List<String> professions, String pendingRequestId) {
        var items = new ArrayList<DeskButton>();
        if (professions != null) {
            for (String profession : professions) {
                if (profession == null || profession.isBlank()) continue;
                items.add(button(DeskActionStyle.icon(
                        "Meseria " + profession.toLowerCase(java.util.Locale.ROOT),
                        "trades-enroll:" + profession), "trades-enroll:" + profession));
            }
        }
        if (pendingRequestId != null && !pendingRequestId.isBlank()) {
            items.add(button(new DeskIcon("minecraft:clock",
                    "Cerere în așteptare: " + pendingRequestId,
                    List.of("Comisarul o semnează la birou.")), null));
        }
        if (items.isEmpty()) {
            items.add(button(new DeskIcon("minecraft:paper",
                    "Nicio meserie activă",
                    List.of("Generatorii de meserii sunt opriți.")), null));
        }
        return DeskLayout.grid("Meserii",
                new DeskIcon("minecraft:book", "Gilda meseriilor",
                        List.of("Alege o meserie — formularul se deschide.")),
                items, 0, true);
    }

    /**
     * The dispatch board: one info tile per call, and for OPEN calls the
     * accept/decline pair — the same one-click verbs the chat board emits.
     */
    static DeskPage dispatch(List<DispatchCall> calls) {
        var items = new ArrayList<DeskButton>();
        if (calls != null) {
            for (DispatchCall call : calls) {
                if (call == null) continue;
                boolean open = "OPEN".equals(call.status);
                var lore = new ArrayList<String>();
                lore.add("Severitate: " + call.severity);
                lore.add(open ? "Deschis (" + call.band + ")"
                        : "Preluat de " + call.acceptedByName);
                if (call.subjectName != null && !call.subjectName.isBlank()) {
                    lore.add("Subiect: " + call.subjectName);
                }
                if (call.placeLabel != null && !call.placeLabel.isBlank()) {
                    lore.add("Loc: " + call.placeLabel);
                }
                items.add(button(new DeskIcon("straja:alarm_whistle",
                        call.id + " — " + bounded(call.title, 26),
                        List.copyOf(lore)), null));
                if (open) {
                    items.add(button(DeskActionStyle.icon("Preia " + call.id,
                            "dispatch-accept:" + call.id), "dispatch-accept:" + call.id));
                    items.add(button(DeskActionStyle.icon("Refuză " + call.id,
                            "dispatch-decline:" + call.id), "dispatch-decline:" + call.id));
                }
            }
        }
        if (items.isEmpty()) {
            items.add(button(new DeskIcon("minecraft:paper", "Tabloul e gol",
                    List.of("Liniște pe teren.")), null));
        }
        return DeskLayout.grid("Dispecerat",
                new DeskIcon("straja:alarm_whistle", "Apeluri",
                        List.of("Apelurile deschise așteaptă o echipă.")),
                items, 0, true);
    }

    /** A freshly published professional offer — one claim tile. */
    static DeskPage v2Offer(String missionId, String objective) {
        var items = new ArrayList<DeskButton>();
        items.add(button(new DeskIcon("straja:mission_carnet", "Ofertă " + missionId,
                DeskLayout.wrapLore(objective == null ? "" : objective, DeskLayout.LORE_WRAP)),
                null));
        items.add(button(DeskActionStyle.icon("Acceptă", "v2-mission-claim:" + missionId),
                "v2-mission-claim:" + missionId));
        return DeskLayout.grid("Ofertă profesională",
                new DeskIcon("straja:mission_carnet", "Comanda zilei",
                        List.of("Publicată pentru tine — o poți revendica acum.")),
                items, 0, true);
    }

    // ---------- custody-shift boards ----------

    /**
     * Active hunts: one info tile per pursuit plus, for senior officers, the
     * call-off verb — rank re-verifies inside the service on the click.
     */
    static DeskPage manhuntBoard(List<ManhuntRecord> hunts, boolean senior, long nowMs) {
        var items = new ArrayList<DeskButton>();
        if (hunts != null) {
            for (ManhuntRecord h : hunts) {
                if (h == null) continue;
                long left = Math.max(0, h.coolAt - nowMs) / 60_000L;
                var lore = new ArrayList<String>();
                lore.add(h.runners.size() + " fugitiv" + (h.runners.size() == 1 ? "" : "i")
                        + " — ~" + left + " min până la mandat");
                if (h.originLabel != null && !h.originLabel.isBlank()) {
                    lore.addAll(DeskLayout.wrapLore("Origine: " + h.originLabel,
                            DeskLayout.LORE_WRAP));
                }
                var names = new StringBuilder();
                for (ManhuntRecord.Runner r : h.runners) {
                    if (r == null) continue;
                    if (names.length() > 0) names.append(", ");
                    names.append(r.name);
                    if (names.length() > 30) break;
                }
                if (names.length() > 0) lore.add("Ținte: " + bounded(names.toString(), 34));
                items.add(button(new DeskIcon("straja:alarm_whistle",
                        h.id + " — vânătoare activă",
                        List.copyOf(lore)), null));
                if (senior) {
                    items.add(button(DeskActionStyle.icon(
                            "Oprește vânătoarea " + h.id,
                            "manhunt-calloff:" + h.id), "manhunt-calloff:" + h.id));
                }
            }
        }
        if (items.isEmpty()) {
            items.add(button(new DeskIcon("minecraft:paper", "Nicio vânătoare",
                    List.of("Liniște — niciun dosar activ.")), null));
        }
        return DeskLayout.grid("Panou vânători",
                new DeskIcon("straja:alarm_whistle", "Vânători active",
                        List.of("Urmăririle în desfășurare.", "Un ofițer senior le poate opri.")),
                items, 0, true);
    }

    /**
     * Live transfer rides: route and escorts as info; ORDERED rides offer
     * join to every on-duty guard and pickup to their escorts; seniors get
     * the cancel verb.
     */
    static DeskPage transferBoard(List<TransferRecord> rides, String viewerUuid,
                                  boolean senior, long nowMs) {
        var items = new ArrayList<DeskButton>();
        if (rides != null) {
            for (TransferRecord t : rides) {
                if (t == null) continue;
                var lore = new ArrayList<String>();
                lore.add(t.prisonerName + " → " + t.destLabel);
                lore.add("Statut: " + t.status
                        + " — escorte " + t.escorts.size());
                if ("ORDERED".equals(t.status)) {
                    long left = Math.max(0, t.escortDeadlineAt - nowMs) / 60_000L;
                    lore.add("Preluare în ~" + left + " min sau se întoarce.");
                }
                items.add(button(new DeskIcon("straja:cuffs",
                        t.id + " — transfer",
                        List.copyOf(lore)), null));
                if ("ORDERED".equals(t.status)) {
                    items.add(button(DeskActionStyle.icon(
                            "Alătură-te escortei " + t.id,
                            "transfer-join:" + t.id), "transfer-join:" + t.id));
                    if (viewerUuid != null && t.escort(viewerUuid) != null) {
                        items.add(button(DeskActionStyle.icon(
                                "Ia lanțul " + t.id,
                                "transfer-pickup:" + t.id), "transfer-pickup:" + t.id));
                    }
                }
                if (senior) {
                    items.add(button(DeskActionStyle.icon(
                            "Anulează transferul " + t.id,
                            "transfer-cancel:" + t.id), "transfer-cancel:" + t.id));
                }
            }
        }
        if (items.isEmpty()) {
            items.add(button(new DeskIcon("minecraft:paper", "Niciun transfer",
                    List.of("Liniște — nicio cursă în desfășurare.")), null));
        }
        return DeskLayout.grid("Panou transferuri",
                new DeskIcon("straja:cuffs", "Curse în desfășurare",
                        List.of("Deținuți mutați între unități.", "Escortele îi preiau de aici.")),
                items, 0, true);
    }

    /**
     * The public bounty board: every ACTIVE bounty as an info tile; seniors
     * get the cancel verb; posting rides a separate form row on the desk.
     */
    static DeskPage bountyBoard(List<BountyRecord> records, boolean senior, long nowMs) {
        var items = new ArrayList<DeskButton>();
        if (records != null) {
            for (BountyRecord r : records) {
                if (r == null) continue;
                long leftH = Math.max(1, (r.expiresAt - nowMs) / 3_600_000L);
                var lore = new ArrayList<String>();
                lore.add(r.amount + " unități — expiră în " + leftH + "h");
                if (r.reason != null && !r.reason.isBlank()) {
                    lore.addAll(DeskLayout.wrapLore("Motiv: " + r.reason,
                            DeskLayout.LORE_WRAP));
                }
                lore.add("Oferită de " + r.postedBy);
                items.add(button(new DeskIcon("straja:fine_notice",
                        r.id + " — " + bounded(r.targetName, 24),
                        List.copyOf(lore)), null));
                if (senior) {
                    items.add(button(DeskActionStyle.icon(
                            "Retrage recompensa " + r.id,
                            "bounty-cancel:" + r.id), "bounty-cancel:" + r.id));
                }
            }
        }
        if (items.isEmpty()) {
            items.add(button(new DeskIcon("minecraft:paper", "Nicio recompensă",
                    List.of("Tabloul e gol — nimeni căutat", "pe recompensă acum.")), null));
        }
        return DeskLayout.grid("Panou recompense",
                new DeskIcon("straja:fine_notice", "Recompense active",
                        List.of("Capturile se plătesc la predare.", "Orice jucător poate captura.")),
                items, 0, true);
    }

    // ---------- helpers ----------

    private static DeskButton button(DeskIcon icon, String actionId) {
        // Slot is assigned by DeskLayout.grid — the wire contract only sees
        // the placed index, so the builder keeps slot 0 as a placeholder.
        return new DeskButton(0, icon, actionId);
    }

    static String checkLabel(String key) {
        return switch (key == null ? "" : key) {
            case "custody" -> "custodie";
            case "standing" -> "reputație";
            case "served" -> "timp ispășit";
            case "debts" -> "datorii";
            case "clean" -> "cazier curat";
            case "cooldown" -> "așteptare refuz";
            default -> "dosar liber";
        };
    }

    private static String bounded(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
