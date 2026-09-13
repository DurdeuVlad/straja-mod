package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * Inbound port for the paper archive journey: folders, sheets, signatures,
 * numbered copies and official deliveries. Adapters carry only record IDs —
 * identity and item authority are revalidated inside the service.
 */
public interface ArchiveRoleplayUseCase {
    enum Action {
        LIST, CREATE_FOLDER, READ_FOLDER, ISSUE_FOLDER, NEW_SHEET, READ_SHEET,
        EDIT_SHEET, SET_RECIPIENTS, SUBMIT_SHEET, SIGN_SHEET, COPY_SHEET,
        PACK_ENVELOPE, ISSUE_DOCUMENT, REVOKE_SHEET
    }

    record AvailableAction(Action action, String recordId) {}

    record Limits(int title, int content, int recipients, int signatureReason, int target) {}

    List<AvailableAction> availableActions(PlayerGateway player);

    Limits limits();

    void listFolders(PlayerGateway player);

    void readFolder(PlayerGateway player, String id);

    void readSheet(PlayerGateway player, String id);

    boolean createFolder(PlayerGateway player, String title, String department);

    boolean issueFolder(PlayerGateway player, String id, String targetName);

    boolean newSheet(PlayerGateway player, String folderId, String type, String title);

    boolean editSheet(PlayerGateway player, String id, String content);

    boolean setRecipients(PlayerGateway player, String id, String recipients);

    boolean submitSheet(PlayerGateway player, String id);

    boolean signSheet(PlayerGateway player, String id, String reason);

    boolean copySheet(PlayerGateway player, String id, Integer count, String targets);

    boolean packEnvelope(PlayerGateway player, String id, String targetName);

    boolean issueDocument(PlayerGateway player, String id, String targetName);

    boolean revokeSheet(PlayerGateway player, String id);

    void deliverPending(PlayerGateway player);
}
