package com.dwurdy.straja.adapter.in.item;

import com.dwurdy.straja.application.port.out.ItemView;

import java.util.regex.Pattern;

/** Minecraft-free routing contract for the registered paper roleplay items. */
public final class PhysicalItemSurface {
    private static final Pattern RECORD_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    public enum Action {
        NONE,
        MISSION_CARNET,
        ARCHIVE_FOLDER,
        ARCHIVE_DOCUMENT,
        ARCHIVE_TOOL,
        FINE_BOOK,
        FINE_NOTICE,
        TRAINING_MANUAL,
        ALARM_WHISTLE,
        EVIDENCE_BAG,
        CONFISCATION_RECEIPT,
        IDENTITY_CARD
    }

    private PhysicalItemSurface() {}

    public static Action action(ItemView item) {
        if (item == null || item.isEmpty()) return Action.NONE;
        return switch (item.id()) {
            case "straja:order_book", "straja:mission_carnet" -> Action.MISSION_CARNET;
            case "straja:archive_folder" -> Action.ARCHIVE_FOLDER;
            case "straja:archive_document" -> Action.ARCHIVE_DOCUMENT;
            case "straja:carbon_paper", "straja:archive_stamp",
                    "straja:official_envelope" -> Action.ARCHIVE_TOOL;
            case "straja:fine_book" -> Action.FINE_BOOK;
            case "straja:fine_notice" -> Action.FINE_NOTICE;
            case "straja:training_manual" -> Action.TRAINING_MANUAL;
            case "straja:alarm_whistle" -> Action.ALARM_WHISTLE;
            case "straja:evidence_bag" -> Action.EVIDENCE_BAG;
            case "straja:confiscation_receipt" -> Action.CONFISCATION_RECEIPT;
            case "straja:identity_card" -> Action.IDENTITY_CARD;
            default -> Action.NONE;
        };
    }

    public static String validRecordId(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return RECORD_ID.matcher(trimmed).matches() ? trimmed : null;
    }

    public static String firstValidRecordId(String... values) {
        if (values == null) return null;
        for (String value : values) {
            String valid = validRecordId(value);
            if (valid != null) return valid;
        }
        return null;
    }
}
