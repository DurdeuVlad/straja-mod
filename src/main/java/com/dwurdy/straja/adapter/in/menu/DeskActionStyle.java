package com.dwurdy.straja.adapter.in.menu;

import com.dwurdy.straja.adapter.in.npc.NpcFaqSurface;
import com.dwurdy.straja.adapter.out.theme.GuiTheme;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Action-id → icon/lore defaults for desk-menu buttons, seeded from
 * {@link GuiTheme} role-icon fallbacks. Vanilla item ids keep every button
 * renderable on unmodded clients; unknown ids fall back to paper and generic
 * lore — nothing here throws. Pure — no Minecraft imports.
 */
public final class DeskActionStyle {
    private DeskActionStyle() {}

    /** Exact action-id → icon overrides (statuses, forms, hand-outs). */
    private static final Map<String, String> ITEM_BY_ID = Map.ofEntries(
            Map.entry("rules", "minecraft:book"),
            Map.entry("guard-status", "straja:guard_uniform"),
            Map.entry("v2-personnel-status", "straja:identity_card"),
            Map.entry("v2-promotion-status", "straja:seal_stamp"),
            Map.entry("v2-document-status", "straja:official_document"),
            Map.entry("v2-equipment-status", "straja:baton"),
            Map.entry("v2-campaign-status", "straja:order_book"),
            Map.entry("v2-professional-work", "straja:carbon_paper"),
            Map.entry("application-submit", "straja:official_document"),
            Map.entry("recruit", "minecraft:writable_book"),
            Map.entry("quiz-answer", "minecraft:writable_book"),
            Map.entry("training-progress", "straja:training_manual"),
            Map.entry("training-promote", "straja:seal_stamp"),
            Map.entry("training-manual", "straja:training_manual"),
            Map.entry("trades-list", "minecraft:book"),
            Map.entry("trades-promote", "straja:seal_stamp"),
            Map.entry("faction-declare", "straja:carbon_paper"),
            Map.entry("incident-report", "straja:alarm_whistle"),
            Map.entry("incident-list", "straja:alarm_whistle"),
            Map.entry("dispatch-board", "straja:alarm_whistle"),
            Map.entry("duty-roster", "minecraft:paper"),
            Map.entry("bolo-list", "straja:fine_notice"),
            Map.entry("bolo-create", "straja:carbon_paper"),
            Map.entry("reputation-self", "minecraft:paper"),
            Map.entry("reputation-view", "straja:carbon_paper"),
            Map.entry("reputation-correction", "straja:carbon_paper"),
            Map.entry("arrest-record", "straja:archive_folder"),
            Map.entry("arrest-record-admin", "straja:archive_folder"),
            Map.entry("arrest-handoff", "straja:cuffs"),
            Map.entry("evidence-list", "straja:evidence_bag"),
            Map.entry("evidence-case-view", "straja:evidence_bag"),
            Map.entry("duty-start", "minecraft:arrow"),
            Map.entry("duty-stop", "minecraft:arrow"),
            Map.entry("work-start", "minecraft:arrow"),
            Map.entry("duty-salary", "minecraft:gold_ingot"),
            Map.entry("duty-coins", "minecraft:gold_ingot"),
            Map.entry("duty-food", "minecraft:bread"),
            Map.entry("duty-kit", "straja:guard_uniform"),
            Map.entry("resignation-start", "minecraft:barrier"),
            Map.entry("resignation-confirm", "minecraft:barrier"),
            Map.entry("resignation-cancel", "minecraft:arrow"),
            Map.entry("rejoin", "minecraft:arrow"),
            Map.entry("mission-list", "straja:mission_carnet"),
            Map.entry("mission-carnet", "straja:mission_carnet"),
            Map.entry("mission-draft-status", "straja:order_book"),
            Map.entry("mission-draft-sign", "straja:order_book"),
            Map.entry("mission-draft-package", "straja:order_book"),
            Map.entry("mission-draft-write", "straja:carbon_paper"),
            Map.entry("mission-draft-scope", "straja:carbon_paper"),
            Map.entry("mission-template-list", "straja:order_book"),
            Map.entry("mission-budget-adjust", "straja:carbon_paper"),
            Map.entry("cuffs-status", "straja:cuffs"),
            Map.entry("prison-status", "straja:prison_marker"),
            Map.entry("downed-status", "minecraft:clock"),
            Map.entry("custody-remove-head-sack", "straja:head_sack"),
            Map.entry("custody-wake-downed", "minecraft:totem_of_undying"),
            Map.entry("cuffs-item", "straja:cuffs"),
            Map.entry("fine-list", "straja:fine_book"),
            Map.entry("fine-task-list", "straja:fine_notice"),
            Map.entry("fine-draft-status", "straja:fine_book"),
            Map.entry("fine-appeal-list", "straja:fine_book"),
            Map.entry("fine-draft-write", "straja:carbon_paper"),
            Map.entry("fine-warrant", "straja:carbon_paper"),
            Map.entry("delegation-ledger", "straja:order_book"),
            Map.entry("identity-request", "straja:identity_card"),
            Map.entry("identity-list", "straja:identity_card"),
            Map.entry("complaint-list", "straja:archive_folder"),
            Map.entry("complaint-submit", "straja:carbon_paper"),
            Map.entry("room-status", "straja:room_marker"),
            Map.entry("room-release", "straja:room_marker"),
            Map.entry("archive-list", "straja:archive_folder"),
            Map.entry("archive-folder-create", "straja:carbon_paper"),
            Map.entry("report-submit", "straja:carbon_paper"),
            Map.entry("report-status", "minecraft:paper"),
            Map.entry("report-review-list", "straja:archive_folder"),
            Map.entry("petition-request", "straja:carbon_paper"),
            Map.entry("audience-request", "straja:carbon_paper"),
            Map.entry("audience-status", "minecraft:paper"),
            Map.entry("audience-review-list", "straja:archive_folder"),
            Map.entry("admin-personnel", "straja:order_book"),
            Map.entry("admin-roster", "straja:order_book"),
            Map.entry("admin-authorize", "straja:carbon_paper"),
            Map.entry("admin-policies", "straja:order_book"),
            Map.entry("admin-policy-set", "straja:carbon_paper"),
            Map.entry("admin-emergency-status", "straja:alarm_whistle"),
            Map.entry("admin-emergency-alert", "straja:alarm_whistle"),
            Map.entry("admin-emergency-start", "straja:alarm_whistle"),
            Map.entry("admin-emergency-end", "straja:alarm_whistle"),
            Map.entry("tool-cell-confirm", "straja:npc_wand"),
            Map.entry("tool-survey-all", "straja:survey_rod"),
            Map.entry("inspect-documents", "minecraft:spyglass"),
            Map.entry("armory-status", "straja:baton"));

    /** Parameterized (op:recordId) prefixes → icon, longest-prefix wins by order. */
    private static final List<Map.Entry<String, String>> ITEM_BY_PREFIX = List.of(
            Map.entry("fine-pay:", "minecraft:gold_ingot"),
            Map.entry("fine-refuse:", "minecraft:barrier"),
            Map.entry("fine-", "straja:fine_book"),
            Map.entry("complaint-confirm:", "minecraft:lime_dye"),
            Map.entry("complaint-", "straja:archive_folder"),
            Map.entry("custody-accept:", "minecraft:lime_dye"),
            Map.entry("custody-refuse:", "minecraft:barrier"),
            Map.entry("custody-release:", "straja:cuff_key"),
            Map.entry("custody-", "straja:cuffs"),
            Map.entry("mission-", "straja:mission_carnet"),
            Map.entry("v2-mission-claim:", "straja:mission_carnet"),
            Map.entry("duty-checkpoint:", "straja:patrol_wand"),
            Map.entry("trades-enroll:", "minecraft:writable_book"),
            Map.entry("join-read:", "straja:archive_folder"),
            Map.entry("join-review:", "straja:seal_stamp"),
            Map.entry("archive-sheet-read:", "straja:archive_document"),
            Map.entry("archive-folder-read:", "straja:archive_folder"),
            Map.entry("archive-folder-issue:", "straja:archive_folder"),
            Map.entry("archive-sheet-new:", "straja:carbon_paper"),
            Map.entry("archive-sheet-edit:", "straja:carbon_paper"),
            Map.entry("archive-recipients:", "straja:official_envelope"),
            Map.entry("archive-sheet-submit:", "straja:seal_stamp"),
            Map.entry("archive-sheet-sign:", "straja:archive_stamp"),
            Map.entry("archive-sheet-copy:", "straja:carbon_paper"),
            Map.entry("archive-sheet-envelope:", "straja:official_envelope"),
            Map.entry("archive-sheet-issue:", "straja:official_document"),
            Map.entry("archive-sheet-revoke:", "minecraft:barrier"),
            Map.entry("archive-", "straja:archive_document"),
            Map.entry("report-review:", "straja:seal_stamp"),
            Map.entry("petition-read:", "straja:archive_folder"),
            Map.entry("petition-review:", "straja:seal_stamp"),
            Map.entry("audience-review:", "straja:seal_stamp"),
            Map.entry("incident-", "straja:alarm_whistle"),
            Map.entry("dispatch-", "straja:alarm_whistle"),
            Map.entry("bolo-cancel:", "minecraft:barrier"),
            Map.entry("evidence-return:", "straja:confiscation_receipt"),
            Map.entry("evidence-destroy:", "minecraft:barrier"),
            Map.entry("evidence-", "straja:evidence_bag"),
            Map.entry("admin-", "straja:seal_stamp"),
            Map.entry("tool-", "straja:npc_wand"),
            Map.entry("armory-buy:", "minecraft:gold_ingot"),
            Map.entry("armory-reserve:", "straja:iou_note"),
            Map.entry("delegation-", "straja:order_book"));

    /** Ids that open a native form — the click closes the desk, not mutates. */
    private static final Set<String> FORM_ACTIONS = Set.of(
            "quiz-answer", "faction-declare", "incident-report", "bolo-create",
            "evidence-case-view", "arrest-handoff", "reputation-view",
            "reputation-correction", "mission-draft-write", "mission-draft-scope",
            "mission-budget-adjust", "fine-draft-write", "fine-warrant",
            "complaint-submit", "archive-folder-create", "report-submit",
            "petition-request", "audience-request", "admin-authorize",
            "admin-policy-set", "admin-emergency-alert", "admin-emergency-start");

    /** Parameterized ops that open a native form (never confirm-gated). */
    private static final Set<String> FORM_PREFIXES = Set.of(
            "mission-invite:", "mission-report:", "mission-fail:", "trades-enroll:",
            "join-review:", "complaint-report:", "complaint-review:",
            "complaint-withdraw:", "fine-appeal:", "fine-appeal-review:",
            "archive-folder-issue:", "archive-sheet-new:", "archive-sheet-edit:",
            "archive-recipients:", "archive-sheet-sign:", "archive-sheet-copy:",
            "archive-sheet-envelope:", "archive-sheet-issue:", "report-review:",
            "petition-review:", "audience-review:", "incident-resolve:",
            "evidence-transfer:", "evidence-destroy:", "tool-npc-rename:",
            "tool-npc-skin:");

    /** Ids whose result is a chat dump (status, lists, ledger text). */
    private static final Set<String> CHAT_ACTIONS = Set.of(
            "rules", "guard-status", "v2-personnel-status", "v2-promotion-status",
            "v2-document-status", "v2-equipment-status", "v2-campaign-status",
            "v2-professional-work", "trades-list", "trades-promote", "recruit",
            "training-progress", "training-promote", "incident-list",
            "dispatch-board", "duty-roster", "bolo-list", "reputation-self",
            "arrest-record", "arrest-record-admin", "evidence-list",
            "cuffs-status", "prison-status", "downed-status", "fine-list",
            "identity-list", "fine-task-list", "fine-draft-status",
            "fine-appeal-list", "delegation-ledger", "complaint-list",
            "room-status", "archive-list", "report-status", "report-review-list",
            "audience-status", "audience-review-list", "admin-personnel",
            "admin-roster", "admin-policies", "admin-emergency-status",
            "armory-status", "mission-list", "mission-draft-status",
            "mission-template-list", "inspect-documents");

    /** Parameterized ops whose result is a chat dump. */
    private static final Set<String> CHAT_PREFIXES = Set.of(
            "admin-dossier:", "petition-read:", "join-read:",
            "archive-folder-read:", "archive-sheet-read:");

    /** Ids that hand the player a physical item. */
    private static final Set<String> GIVE_ACTIONS = Set.of(
            "identity-request", "mission-carnet", "training-manual", "cuffs-item",
            "duty-kit", "duty-food", "duty-coins", "duty-salary");
    private static final Set<String> GIVE_PREFIXES = Set.of(
            "armory-buy:", "armory-reserve:", "mission-template-issue:");

    /** Immediate money-spending or record-destroying mutations gated by a DA/NU page. */
    private static final Set<String> CONFIRM_ACTIONS = Set.of("room-release");
    private static final Set<String> CONFIRM_PREFIXES = Set.of(
            "fine-pay:", "fine-refuse:", "complaint-confirm:",
            "custody-accept:", "custody-refuse:", "custody-release:",
            "armory-buy:", "armory-reserve:",
            "admin-promote:", "admin-demote:", "admin-suspend:",
            "admin-fire:", "admin-reinstate:", "fine-task-arrest:",
            "bolo-cancel:", "evidence-return:", "evidence-deposit:",
            "archive-sheet-submit:", "archive-sheet-revoke:");

    /** The icon stack for one surface action; label passes through as the name. */
    public static DeskIcon icon(String label, String actionId) {
        return new DeskIcon(itemFor(actionId), label, lore(actionId));
    }

    /** Registry item id for an action id — paper for anything unmapped. */
    public static String itemFor(String actionId) {
        if (actionId == null || actionId.isBlank()) return DeskIcon.FALLBACK_ITEM;
        String exact = ITEM_BY_ID.get(actionId);
        if (exact != null) return exact;
        for (var entry : ITEM_BY_PREFIX) {
            if (actionId.startsWith(entry.getKey())) return entry.getValue();
        }
        if (actionId.startsWith("faq:")) return "minecraft:writable_book";
        if (actionId.startsWith("main:") || actionId.startsWith("more:")) {
            return "minecraft:arrow";
        }
        return DeskIcon.FALLBACK_ITEM;
    }

    /** 1–3 Romanian lines telling the player what the click does next. */
    public static List<String> lore(String actionId) {
        if (actionId == null || actionId.isBlank()) {
            return List.of("Se execută imediat.");
        }
        if (needsConfirm(actionId)) {
            var lines = new java.util.ArrayList<String>();
            lines.add("Cere confirmare.");
            lines.addAll(consequence(actionId));
            return List.copyOf(lines);
        }
        if (actionId.startsWith("faq:")) return faqLore(actionId);
        if (isForm(actionId)) return List.of("Deschide formularul.");
        if (isGive(actionId)) return List.of("Primești obiectul.", "Se execută imediat.");
        if (isChat(actionId)) return List.of("Afișează rezultatul în chat.");
        return List.of("Se execută imediat.");
    }

    /** True when the action is an immediate mutation that needs a DA/NU page. */
    public static boolean needsConfirm(String actionId) {
        if (actionId == null) return false;
        if (CONFIRM_ACTIONS.contains(actionId)) return true;
        for (String prefix : CONFIRM_PREFIXES) {
            if (actionId.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Consequence lines shown on the confirm page's subject item. */
    public static List<String> consequence(String actionId) {
        if (actionId == null) return List.of("Acțiunea se execută imediat.");
        if (actionId.startsWith("fine-pay:")) {
            return List.of("Amenda este achitată imediat", "din monedele tale.");
        }
        if (actionId.startsWith("fine-refuse:")) {
            return List.of("Refuzul este înregistrat și", "pornește procedura disciplinară.");
        }
        if (actionId.startsWith("complaint-confirm:")) {
            return List.of("Plângerea se închide", "ca soluționată.");
        }
        if ("room-release".equals(actionId)) {
            return List.of("Camera se eliberează imediat;", "cererea nu poate fi anulată.");
        }
        if (actionId.startsWith("custody-accept:")) {
            return List.of("Accepți cererea — vei fi", "încătușat imediat.");
        }
        if (actionId.startsWith("custody-refuse:")) {
            return List.of("Refuzi cererea; la o predare", "refuzul te doboară.");
        }
        if (actionId.startsWith("custody-release:")) {
            return List.of("Persoana este eliberată imediat", "din custodie.");
        }
        if (actionId.startsWith("armory-buy:")) {
            return List.of("Cumperi echipamentul; monedele", "se iau imediat din inventar.");
        }
        if (actionId.startsWith("armory-reserve:")) {
            return List.of("Rezervi echipamentul; punctele", "de rechiziție se iau imediat.");
        }
        if (actionId.startsWith("admin-")) {
            return List.of("Schimbarea se aplică imediat", "și se scrie în jurnalul de audit.");
        }
        if (actionId.startsWith("fine-task-arrest:")) {
            return List.of("Pornește procedura de arestare", "pentru această sarcină.");
        }
        if (actionId.startsWith("bolo-cancel:")) {
            return List.of("BOLO-ul se retrage imediat.");
        }
        if (actionId.startsWith("evidence-return:")) {
            return List.of("Piesa se înapoiază imediat", "proprietarului.");
        }
        if (actionId.startsWith("evidence-deposit:")) {
            return List.of("Proba intră în depozitul Arhivei", "și iese din custodia ta.");
        }
        if (actionId.startsWith("archive-sheet-submit:")) {
            return List.of("Foaia pleacă la semnat și", "nu se mai poate edita.");
        }
        if (actionId.startsWith("archive-sheet-revoke:")) {
            return List.of("Actul semnat este revocat;", "nu mai poate fi folosit.");
        }
        return List.of("Acțiunea se execută imediat", "și nu se poate anula.");
    }

    /** Header item: the role icon (GuiTheme fallback chain) + wrapped guidance. */
    public static DeskIcon header(String roleId, String title, String guidance) {
        String itemId = GuiTheme.roleIconItemId("straja:" + (roleId == null ? "" : roleId));
        if (itemId == null) itemId = DeskIcon.FALLBACK_ITEM;
        var lore = new java.util.ArrayList<>(DeskLayout.wrapLore(guidance, DeskLayout.LORE_WRAP));
        if (lore.isEmpty()) lore.add("Alege o acțiune.");
        return new DeskIcon(itemId, title, lore);
    }

    private static boolean isForm(String actionId) {
        if (FORM_ACTIONS.contains(actionId)) return true;
        for (String prefix : FORM_PREFIXES) {
            if (actionId.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isChat(String actionId) {
        if (CHAT_ACTIONS.contains(actionId)) return true;
        for (String prefix : CHAT_PREFIXES) {
            if (actionId.startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isGive(String actionId) {
        if (GIVE_ACTIONS.contains(actionId)) return true;
        for (String prefix : GIVE_PREFIXES) {
            if (actionId.startsWith(prefix)) return true;
        }
        return false;
    }

    private static List<String> faqLore(String actionId) {
        var target = NpcFaqSurface.parseTarget(actionId.substring("faq:".length()));
        if (target.isEmpty()) return List.of("Întrebări frecvente.");
        return switch (target.get().kind()) {
            case "menu" -> List.of("Deschide tema.");
            case "answer" -> List.of("Afișează răspunsul.");
            default -> List.of("Întrebări frecvente.");
        };
    }
}
