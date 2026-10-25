package com.dwurdy.straja.adapter.in.menu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Secretary-desk section taxonomy (Issue 4). The desk's flat grid is too dense
 * — forty-plus dynamic families at once — so the Secretary's root page renders
 * a drawer button per non-empty section instead, each opening a
 * {@code desk:section:<key>} page that lists only that family's actions.
 * Everything the taxonomy does not claim stays {@link #GENERAL} and keeps
 * rendering on the root page (guard-status, the FAQ entry, the carnet…).
 * Pure — no Minecraft imports.
 */
public final class DeskSections {
    private DeskSections() {}

    /** Serviciu și tură — duty verbs, checkpoints, claims, resignation. */
    public static final String DUTY = "duty";
    /** Misiuni și ordine — mission ops, drafts, templates, V2 claims. */
    public static final String MISSIONS = "missions";
    /** Registre și dosare — complaints, fines, reports, audiences, petitions. */
    public static final String RECORDS = "records";
    /** Panou operațional — incidents, the dispatch board, BOLOs. */
    public static final String BOARD = "board";
    /** Comisar — personnel admin, policies, emergency and the delegation console. */
    public static final String COMISAR = "comisar";
    /** Everything unclassified — stays on the root page beside the drawers. */
    public static final String GENERAL = "general";

    /** Drawer order on the Secretary root page; {@link #GENERAL} never drawers. */
    public static final List<String> ORDER = List.of(DUTY, MISSIONS, RECORDS, BOARD, COMISAR);

    private static final String NAV_PREFIX = "desk:section:";

    /**
     * Maps an action id to its desk section. Family prefixes follow the
     * surface vocabulary: {@code duty-*}/work/resignation → {@link #DUTY},
     * {@code mission-*} + {@code v2-mission-claim:*} → {@link #MISSIONS},
     * {@code complaint-|fine-|report-|audience-|petition-|join-*} →
     * {@link #RECORDS}, {@code incident-|dispatch-|bolo-*} → {@link #BOARD},
     * {@code admin-|delegation-*} + the commissioner extras → {@link #COMISAR}.
     * Two deliberate overrides: {@code mission-carnet} is a take-away item,
     * not a workflow, so it stays {@link #GENERAL} on the desk top; anything
     * unrecognized — including {@code faq:*}, {@code main:*} and
     * {@code more:*} nav ids — is {@link #GENERAL} too.
     */
    public static String sectionFor(String actionId) {
        if (actionId == null || actionId.isBlank()) return GENERAL;
        if ("mission-carnet".equals(actionId)) return GENERAL;
        if (actionId.startsWith("duty-")
                || "work-start".equals(actionId)
                || "absence-declare".equals(actionId)
                || actionId.startsWith("resignation-")
                || "rejoin".equals(actionId)) return DUTY;
        if (actionId.startsWith("v2-mission-claim")
                || actionId.startsWith("mission-")) return MISSIONS;
        if (actionId.startsWith("complaint-") || actionId.startsWith("fine-")
                || actionId.startsWith("report-") || actionId.startsWith("audience-")
                || actionId.startsWith("petition-") || actionId.startsWith("join-")) return RECORDS;
        if (actionId.startsWith("incident-") || actionId.startsWith("dispatch-")
                || actionId.startsWith("bolo-")
                || actionId.startsWith("manhunt-") || actionId.startsWith("transfer-")
                || actionId.startsWith("bounty-")
                || "cell-search".equals(actionId)
                || "inspect-player".equals(actionId)) return BOARD;
        if (actionId.startsWith("admin-") || actionId.startsWith("delegation-")
                || "reputation-view".equals(actionId)
                || "reputation-correction".equals(actionId)
                || "arrest-record-admin".equals(actionId)
                || "evidence-list".equals(actionId)) return COMISAR;
        // Parole officer work — the docket and every hearing-scoped verb —
        // lands in the Comisar drawer. The prisoner's own rows (status,
        // filing, advocacy, bail, check-in) stay on the desk top: they are
        // the citizen's justice path, not officer tooling.
        if ("parole-docket".equals(actionId)
                || actionId.startsWith("parole-review")
                || actionId.startsWith("parole-open")
                || actionId.startsWith("parole-decide-")) return COMISAR;
        return GENERAL;
    }

    /** Internal nav id for a section drawer — routed in-menu, never dispatched. */
    public static String navId(String section) {
        return NAV_PREFIX + section;
    }

    /**
     * The section a {@code desk:section:<key>} nav id targets, or null for
     * anything else — including the other {@code desk:*} ids and keys that
     * are not real drawers (a bogus key can never dispatch by construction,
     * it simply renders nothing).
     */
    public static String sectionOfNav(String actionId) {
        if (actionId == null || !actionId.startsWith(NAV_PREFIX)) return null;
        String key = actionId.substring(NAV_PREFIX.length());
        return ORDER.contains(key) ? key : null;
    }

    /**
     * Drawer buttons for every section the action set fills, in
     * {@link #ORDER} — a section with zero actions renders no drawer at all,
     * which is exactly how {@link #COMISAR} stays hidden from players without
     * authority (their surface emits no admin/delegation actions).
     */
    public static List<DeskButton> drawerButtons(List<String> actionIds) {
        var counts = new LinkedHashMap<String, Integer>();
        ORDER.forEach(section -> counts.put(section, 0));
        if (actionIds != null) {
            for (String id : actionIds) {
                String section = sectionFor(id);
                if (counts.containsKey(section)) {
                    counts.merge(section, 1, Integer::sum);
                }
            }
        }
        var buttons = new ArrayList<DeskButton>();
        for (var entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count == 0) continue;
            buttons.add(new DeskButton(button(entry.getKey(), count), navId(entry.getKey())));
        }
        return List.copyOf(buttons);
    }

    /** The drawer's root-page button: icon, Romanian name and what is inside. */
    public static DeskIcon button(String section, int count) {
        return new DeskIcon(itemFor(section), title(section),
                List.of(description(section), countLine(count)));
    }

    /** The section page's header item — same legend as the drawer button. */
    public static DeskIcon header(String section, int count) {
        return button(section, count);
    }

    /** Romanian drawer title, used for the button name and the page title. */
    public static String title(String section) {
        return switch (section == null ? "" : section) {
            case DUTY -> "Serviciu și tură";
            case MISSIONS -> "Misiuni și ordine";
            case RECORDS -> "Registre și dosare";
            case BOARD -> "Panou operațional";
            case COMISAR -> "Comisar";
            default -> "Secretariat";
        };
    }

    private static String description(String section) {
        return switch (section == null ? "" : section) {
            case DUTY -> "Tura, checkpoint-urile, salariul, hrana, kitul și demisia.";
            case MISSIONS -> "Ordine de misiune, înscrieri, rapoarte și recompense.";
            case RECORDS -> "Plângeri, amenzi, rapoarte, audiențe, petiții și cereri de muncă.";
            case BOARD -> "Incidente, dispecerat și BOLO-uri.";
            case COMISAR -> "Personal, politici, delegări și urgență — doar cu autoritate.";
            default -> "Acțiuni generale.";
        };
    }

    private static String itemFor(String section) {
        return switch (section == null ? "" : section) {
            case DUTY -> "straja:patrol_wand";
            case MISSIONS -> "straja:mission_carnet";
            case RECORDS -> "straja:archive_folder";
            case BOARD -> "straja:alarm_whistle";
            case COMISAR -> "straja:seal_stamp";
            default -> DeskIcon.FALLBACK_ITEM;
        };
    }

    private static String countLine(int count) {
        return count == 1 ? "1 acțiune." : count + " acțiuni.";
    }

    /**
     * One live duty-status line for the header item, derived from the duty
     * actions the surface already emitted ({@code dutyActions} is a flat
     * projection of the service's {@code DutyView}, so the emitted ids carry
     * the same state — no service call needed in the menu layer). Empty when
     * the player has no duty affordances at all (e.g. a civilian).
     */
    public static String dutyHint(List<String> actionIds) {
        if (actionIds == null) return "";
        String checkpoint = null;
        boolean onDuty = false, canStart = false, resigning = false, resigned = false;
        for (String id : actionIds) {
            if (id == null) continue;
            if (id.startsWith("duty-checkpoint:")) {
                checkpoint = id.substring("duty-checkpoint:".length());
            } else if ("duty-stop".equals(id)) {
                onDuty = true;
            } else if ("duty-start".equals(id)) {
                canStart = true;
            } else if ("resignation-confirm".equals(id) || "resignation-cancel".equals(id)) {
                resigning = true;
            } else if ("rejoin".equals(id)) {
                resigned = true;
            }
        }
        if (onDuty) {
            return checkpoint == null || checkpoint.isBlank()
                    ? "În tură."
                    : "În tură — checkpoint «" + checkpoint + "» te așteaptă.";
        }
        if (resigning) return "Demisie în curs — confirmă sau anulează la timp.";
        if (canStart) return "Liber — poți începe tura aici.";
        if (resigned) return "Demisionat — te poți reîntoarce în Strajă.";
        return "";
    }
}
