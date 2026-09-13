package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.ArchiveRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.ArchiveStore;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayList;
import java.util.List;

/**
 * Paper archive: folders, sheets, signatures, numbered carbon copies and
 * official-envelope sends. Port of the KubeJS archive system.
 */
public class ArchiveService implements ArchiveRoleplayUseCase {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;

    public ArchiveService(StrajaContext ctx, PlayerService players, AuditService audit) {
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

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private boolean canArchive(PlayerGateway player) {
        if (!p().archiveEnabled) return false;
        if (p().archiveCommissionerAlways && players.isCommissioner(player)) return true;
        return ctx.archive().read().archivists.containsKey(player.uuid().toString());
    }

    private boolean canSign(PlayerGateway player) {
        return players.isCommissioner(player) || players.state(player).rank >= 4;
    }

    private boolean isFolderOwner(PlayerGateway player, ArchiveStore.Folder folder) {
        return PlayerService.identityMatches(player, folder.ownerUuid, folder.owner);
    }

    private boolean canReadFolder(PlayerGateway player, ArchiveStore.Folder folder) {
        if (canArchive(player) || canSign(player) || isFolderOwner(player, folder)) return true;
        for (var reader : folder.readers) {
            if (reader != null && PlayerService.identityMatches(player, reader.uuid, reader.name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Persisted read authority for a sheet: folder access, an explicit sheet
     * recipient, or a DELIVERED copy/envelope issued for this original. Item
     * metadata is never consulted.
     */
    private boolean canReadSheet(PlayerGateway player, ArchiveStore store, ArchiveStore.Sheet sheet) {
        if (sheet == null) return false;
        var folder = store.folders.get(sheet.folderId);
        if (folder != null && canReadFolder(player, folder)) return true;
        for (var recipient : sheet.recipients) {
            if (recipient != null
                    && PlayerService.identityMatches(player, recipient.uuid, recipient.name)) {
                return true;
            }
        }
        for (var copy : store.copies) {
            if (copy == null || !sheet.id.equals(copy.originalId)
                    || !"DELIVERED".equals(copy.status)) continue;
            if (PlayerService.identityMatches(player, copy.recipientUuid, copy.recipientName)) {
                return true;
            }
        }
        return false;
    }

    private boolean canEditFolder(PlayerGateway player, ArchiveStore.Folder folder) {
        return "OPEN".equals(folder.status) && canArchive(player);
    }

    // ------------------------------------------------------------ projection

    @Override
    public List<AvailableAction> availableActions(PlayerGateway player) {
        if (player == null) return List.of();
        var actions = new ArrayList<AvailableAction>();
        actions.add(new AvailableAction(Action.LIST, ""));
        ArchiveStore store = ctx.archive().read();
        boolean archivist = canArchive(player);
        boolean signer = canSign(player);
        boolean authority = archivist || signer;
        if (archivist) actions.add(new AvailableAction(Action.CREATE_FOLDER, ""));
        for (var folder : store.folders.values()) {
            if (folder == null || folder.id == null || folder.id.isBlank()
                    || !canReadFolder(player, folder)) continue;
            actions.add(new AvailableAction(Action.READ_FOLDER, folder.id));
            if (archivist) {
                actions.add(new AvailableAction(Action.ISSUE_FOLDER, folder.id));
                if ("OPEN".equals(folder.status)) {
                    actions.add(new AvailableAction(Action.NEW_SHEET, folder.id));
                }
            }
        }
        boolean stamp = player.inventory().countOf("straja:archive_stamp") >= 1;
        boolean carbon = player.inventory().countOf("straja:carbon_paper") >= 1;
        boolean envelope = player.inventory().countOf("straja:official_envelope") >= 1;
        for (var sheet : store.sheets.values()) {
            if (sheet == null || sheet.id == null || sheet.id.isBlank()) continue;
            if (!canReadSheet(player, store, sheet)) continue;
            actions.add(new AvailableAction(Action.READ_SHEET, sheet.id));
            var folder = store.folders.get(sheet.folderId);
            if ("DRAFT".equals(sheet.status) && folder != null
                    && canEditFolder(player, folder)) {
                actions.add(new AvailableAction(Action.EDIT_SHEET, sheet.id));
                actions.add(new AvailableAction(Action.SET_RECIPIENTS, sheet.id));
                if (sheet.content != null && !sheet.content.isBlank()) {
                    actions.add(new AvailableAction(Action.SUBMIT_SHEET, sheet.id));
                }
            }
            if ("PENDING_SIGNATURE".equals(sheet.status) && signer && stamp) {
                actions.add(new AvailableAction(Action.SIGN_SHEET, sheet.id));
            }
            if ("SIGNED".equals(sheet.status) && !sheet.revoked && authority) {
                if (carbon && sheet.originId == null) {
                    actions.add(new AvailableAction(Action.COPY_SHEET, sheet.id));
                }
                if (envelope) actions.add(new AvailableAction(Action.PACK_ENVELOPE, sheet.id));
                actions.add(new AvailableAction(Action.ISSUE_DOCUMENT, sheet.id));
                actions.add(new AvailableAction(Action.REVOKE_SHEET, sheet.id));
            }
        }
        return List.copyOf(actions);
    }

    @Override
    public Limits limits() {
        return new Limits(
                Math.min(2000, Math.max(1, p().archiveMaxTitleLength)),
                Math.min(2000, Math.max(1, p().archiveMaxContentLength)),
                Math.min(2000, Math.max(1, p().archiveMaxRecipients * 41)),
                240, 80);
    }

    // ---------------------------------------------------------------- roles

    public boolean grantArchivist(PlayerGateway actor, PlayerGateway target, boolean enabled) {
        if (!players.isCommissioner(actor)) {
            actor.tell("Doar Comisaru' poate autoriza Arhivista.");
            return false;
        }
        if (target == null) {
            actor.tell("Ținta trebuie să fie online.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        if (enabled) store.archivists.put(target.uuid().toString(), target.name());
        else store.archivists.remove(target.uuid().toString());
        ctx.archive().write(store);
        audit.record("archive_role", actor.name(), actor.uuid().toString(), target.name(), target.uuid().toString(), "SUCCESS", enabled ? "granted" : "revoked");
        actor.tell("Arhivista " + target.name() + (enabled ? " autorizată." : " dezactivată."));
        return true;
    }

    // ---------------------------------------------------------------- folders

    @Override
    public boolean createFolder(PlayerGateway player, String title, String department) {
        if (!canArchive(player)) {
            player.tell("Doar Arhivista autorizată sau Comisaru' poate crea dosare.");
            return false;
        }
        if (title == null || title.isBlank() || title.length() > p().archiveMaxTitleLength) {
            player.tell("Titlul dosarului trebuie completat (max " + p().archiveMaxTitleLength + " caractere).");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        long owned = store.folders.values().stream().filter(f -> player.uuid().toString().equals(f.ownerUuid)).count();
        if (owned >= p().archiveMaxFoldersPerOwner) {
            player.tell("Ai atins limita de dosare pentru acest proprietar.");
            return false;
        }
        ArchiveStore.Folder folder = new ArchiveStore.Folder();
        folder.id = "D-" + (store.nextFolderNumber++);
        folder.title = title.trim();
        folder.department = department == null || department.isBlank() ? "Straja" : department.trim().substring(0, Math.min(80, department.trim().length()));
        folder.owner = player.name();
        folder.ownerUuid = player.uuid().toString();
        folder.status = "OPEN";
        folder.createdAt = now();
        store.folders.put(folder.id, folder);
        ctx.archive().write(store);
        audit.record("archive_folder_create", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "created folderId=" + folder.id);
        player.tell("Dosar creat: " + folder.id + " — " + folder.title + " [" + folder.status + "]");
        return true;
    }

    public boolean folderStatus(PlayerGateway player, String id, String status) {
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Folder folder = store.folders.get(id);
        if (folder == null || !canReadFolder(player, folder)) {
            player.tell("Dosarul nu există sau nu ai acces.");
            return false;
        }
        if (!canArchive(player) && !isFolderOwner(player, folder)) {
            player.tell("Nu poți modifica acest dosar.");
            return false;
        }
        folder.status = "CLOSED".equalsIgnoreCase(status) ? "CLOSED" : "OPEN";
        ctx.archive().write(store);
        player.tell("Dosarul " + folder.id + " este acum " + folder.status + ".");
        return true;
    }

    @Override
    public void listFolders(PlayerGateway player) {
        ArchiveStore store = ctx.archive().read();
        int shown = 0;
        for (ArchiveStore.Folder folder : store.folders.values()) {
            if (!canReadFolder(player, folder)) continue;
            player.tell(folder.id + " — " + folder.title + " [" + folder.status + "] " + folder.department + " | foi: " + folder.sheetIds.size());
            if (++shown >= 30) break;
        }
        if (shown == 0) player.tell("Nu există dosare accesibile.");
    }

    @Override
    public void readFolder(PlayerGateway player, String id) {
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Folder folder = store.folders.get(id);
        if (folder == null || !canReadFolder(player, folder)) {
            player.tell("Dosarul nu există sau nu ai acces.");
            return;
        }
        player.tell(folder.id + " — " + folder.title + " [" + folder.status + "] dept " + folder.department + " | proprietar " + folder.owner);
        for (String sheetId : folder.sheetIds) {
            ArchiveStore.Sheet sheet = store.sheets.get(sheetId);
            if (sheet != null) player.tell("  " + sheet.id + " — " + sheet.title + " [" + sheet.status + "] rev " + sheet.revision);
        }
    }

    /**
     * Issues a bound copy of the folder item: the physical item is delivered
     * first, then the recipient is persisted as a reader. A failed delivery
     * leaves no grant behind.
     */
    @Override
    public boolean issueFolder(PlayerGateway player, String id, String targetName) {
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Folder folder = id == null ? null : store.folders.get(id);
        if (folder == null || !(canArchive(player) || canReadFolder(player, folder))) {
            player.tell("Dosarul nu există sau nu ai acces.");
            return false;
        }
        PlayerGateway target = targetName == null ? null : ctx.server().findPlayer(targetName);
        if (target == null || target.uuid() == null
                || target.uuid().equals(player.uuid())) {
            player.tell("Ținta trebuie să fie un alt jucător online.");
            return false;
        }
        var spec = ItemSpec.of("straja:archive_folder", 1)
                .withData("ArchiveFolderId", folder.id)
                .named("Dosar — " + folder.title);
        if (!target.inventory().canReceive(List.of(spec))) {
            player.tell("Destinatarul nu are loc pentru dosar.");
            return false;
        }
        if (!target.giveVerified(spec)) {
            player.tell("Dosarul nu a putut fi livrat; niciun acces nu a fost acordat.");
            return false;
        }
        if (folder.readers.stream().noneMatch(r -> r != null
                && PlayerService.identityMatches(target, r.uuid, r.name))) {
            var reader = new ArchiveStore.Recipient();
            reader.name = target.name();
            reader.key = norm(target.name());
            reader.uuid = target.uuid().toString();
            folder.readers.add(reader);
            ctx.archive().write(store);
        }
        audit.record("archive_folder_issue", player.name(), player.uuid().toString(),
                target.name(), target.uuid().toString(), "SUCCESS",
                "folderId=" + folder.id);
        player.tell("Dosarul " + folder.id + " a fost emis pentru " + target.name() + ".");
        target.tell("Ai primit dosarul " + folder.id + " — " + folder.title + ".");
        return true;
    }

    // ---------------------------------------------------------------- sheets

    @Override
    public boolean newSheet(PlayerGateway player, String folderId, String type, String title) {
        if (!canArchive(player)) {
            player.tell("Doar Arhivista autorizată sau Comisaru' poate crea foi.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Folder folder = store.folders.get(folderId);
        if (folder == null || !canEditFolder(player, folder)) {
            player.tell("Dosarul nu există, nu este deschis sau nu ai drept de scriere.");
            return false;
        }
        if (title == null || title.isBlank() || title.length() > p().archiveMaxTitleLength) {
            player.tell("Titlul foii trebuie completat (max " + p().archiveMaxTitleLength + " caractere).");
            return false;
        }
        long count = store.sheets.values().stream().filter(s -> folder.id.equals(s.folderId) && s.originId == null).count();
        if (count >= p().archiveMaxSheetsPerFolder) {
            player.tell("Dosarul a atins limita de foi.");
            return false;
        }
        ArchiveStore.Sheet sheet = new ArchiveStore.Sheet();
        sheet.id = "A-" + (store.nextSheetNumber++);
        sheet.folderId = folder.id;
        sheet.type = (type == null || type.isBlank() ? "NOTĂ" : type.toUpperCase()).substring(0, Math.min(32, type == null || type.isBlank() ? 4 : type.length()));
        sheet.title = title.trim();
        sheet.author = player.name();
        sheet.authorUuid = player.uuid().toString();
        sheet.authorKey = norm(player.name());
        sheet.status = "DRAFT";
        sheet.createdAt = now();
        sheet.updatedAt = now();
        store.sheets.put(sheet.id, sheet);
        folder.sheetIds.add(sheet.id);
        ctx.archive().write(store);
        audit.record("archive_sheet_create", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "draft_created sheetId=" + sheet.id + " folderId=" + folder.id);
        player.tell("Foaie creată: " + sheet.id + " — " + sheet.title + " [DRAFT]");
        return true;
    }

    @Override
    public boolean editSheet(PlayerGateway player, String id, String content) {
        if (!canArchive(player)) {
            player.tell("Doar Arhivista autorizată poate edita foaia.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        ArchiveStore.Folder folder = sheet == null ? null : store.folders.get(sheet.folderId);
        if (sheet == null || folder == null || !canEditFolder(player, folder)) {
            player.tell("Foaia nu există, dosarul este închis sau nu ai drept de scriere.");
            return false;
        }
        if (!"DRAFT".equals(sheet.status)) {
            player.tell("Foaia nu mai este editabilă după trimiterea la semnătură.");
            return false;
        }
        if (content != null && content.length() > p().archiveMaxContentLength) {
            player.tell("Conținutul depășește limita de " + p().archiveMaxContentLength + " caractere.");
            return false;
        }
        sheet.content = content == null ? "" : content;
        sheet.updatedAt = now();
        ctx.archive().write(store);
        audit.record("archive_sheet_write", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "draft_updated sheetId=" + sheet.id);
        player.tell("Draft salvat: " + sheet.id + ".");
        return true;
    }

    @Override
    public boolean setRecipients(PlayerGateway player, String id, String rawRecipients) {
        if (!canArchive(player)) {
            player.tell("Doar Arhivista autorizată poate modifica destinatarii.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        ArchiveStore.Folder folder = sheet == null ? null : store.folders.get(sheet.folderId);
        if (sheet == null || folder == null || !canEditFolder(player, folder) || !"DRAFT".equals(sheet.status)) {
            player.tell("Destinatarii pot fi schimbați doar pe un draft dintr-un dosar deschis.");
            return false;
        }
        List<ArchiveStore.Recipient> unique = new ArrayList<>();
        for (String token : (rawRecipients == null ? "" : rawRecipients).split(",")) {
            String name = token.trim();
            if (name.isEmpty()) continue;
            name = name.substring(0, Math.min(40, name.length()));
            PlayerGateway online = ctx.server().findPlayer(name);
            String uuid = online == null || online.uuid() == null ? "" : online.uuid().toString();
            String resolvedName = online == null ? name : online.name();
            final String pinnedUuid = uuid;
            boolean duplicate = unique.stream().anyMatch(r ->
                    (!pinnedUuid.isEmpty() && pinnedUuid.equals(r.uuid))
                            || norm(r.name).equals(norm(resolvedName)));
            if (duplicate) continue;
            var recipient = new ArchiveStore.Recipient();
            recipient.name = resolvedName.substring(0, Math.min(40, resolvedName.length()));
            recipient.key = norm(resolvedName);
            recipient.uuid = pinnedUuid;
            unique.add(recipient);
        }
        if (unique.size() > p().archiveMaxRecipients) {
            player.tell("Prea mulți destinatari.");
            return false;
        }
        sheet.recipients = unique;
        sheet.updatedAt = now();
        ctx.archive().write(store);
        player.tell("Destinatari salvați: " + (unique.isEmpty() ? "niciunul" : String.join(", ", unique.stream().map(r -> r.name).toList())) + ".");
        return true;
    }

    @Override
    public boolean submitSheet(PlayerGateway player, String id) {
        if (!canArchive(player)) {
            player.tell("Doar Arhivista autorizată poate trimite foi la semnătură.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        ArchiveStore.Folder folder = sheet == null ? null : store.folders.get(sheet.folderId);
        if (sheet == null || folder == null || !canEditFolder(player, folder) || !"DRAFT".equals(sheet.status)) {
            player.tell("Doar un draft dintr-un dosar deschis poate fi trimis.");
            return false;
        }
        if (sheet.content == null || sheet.content.isEmpty()) {
            player.tell("Scrie conținutul înainte de semnare.");
            return false;
        }
        sheet.status = "PENDING_SIGNATURE";
        sheet.submittedAt = now();
        sheet.updatedAt = now();
        ctx.archive().write(store);
        audit.record("archive_sheet_submit", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "pending_signature sheetId=" + sheet.id);
        player.tell("Foaia " + sheet.id + " așteaptă semnătura Comisarului sau Inspectorului.");
        return true;
    }

    @Override
    public void readSheet(PlayerGateway player, String id) {
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        if (sheet == null || !canReadSheet(player, store, sheet)) {
            player.tell("Foaia nu există sau nu ai acces.");
            return;
        }
        player.tell(sheet.id + " — " + sheet.title + " [" + sheet.status + "] tip " + sheet.type + " revizia " + sheet.revision);
        player.tell("Autor: " + sheet.author + " | Destinatari: " + String.join(", ",
                sheet.recipients.stream().filter(r -> r != null).map(r -> r.name).toList()));
        player.tell(sheet.content == null || sheet.content.isEmpty() ? "(draft gol)" : sheet.content);
    }

    public void listSheets(PlayerGateway player, String folderId) {
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Folder folder = store.folders.get(folderId);
        if (folder == null || !canReadFolder(player, folder)) {
            player.tell("Dosarul nu există sau nu ai acces.");
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String sheetId : folder.sheetIds) {
            ArchiveStore.Sheet sheet = store.sheets.get(sheetId);
            if (sheet != null) lines.add(sheet.id + " [" + sheet.status + "] " + sheet.title);
        }
        player.tell(lines.isEmpty() ? "Dosarul nu are foi." : String.join(" | ", lines));
    }

    @Override
    public boolean signSheet(PlayerGateway player, String id, String reason) {
        if (!canSign(player)) {
            player.tell("Doar un Inspector sau Comisaru' poate semna acte.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        ArchiveStore.Folder folder = sheet == null ? null : store.folders.get(sheet.folderId);
        if (sheet == null || folder == null || !canReadFolder(player, folder) || !"PENDING_SIGNATURE".equals(sheet.status)) {
            player.tell("Foaia nu este pregătită pentru semnătură sau nu ai acces.");
            return false;
        }
        if (player.inventory().countOf("straja:archive_stamp") < 1) {
            player.tell("Ai nevoie de Ștampila Arhivei pentru a semna actul.");
            return false;
        }
        if (reason != null && reason.length() > 240) {
            player.tell("Motivul semnăturii depășește 240 de caractere.");
            return false;
        }
        sheet.status = "SIGNED";
        sheet.signedAt = now();
        var signer = new ArchiveStore.Signer();
        signer.key = norm(player.name());
        signer.uuid = player.uuid().toString();
        signer.name = player.name();
        sheet.signedBy = signer;
        sheet.signatureReason = reason == null || reason.isBlank() ? "verificat" : reason.trim();
        sheet.updatedAt = now();
        ctx.archive().write(store);
        audit.record("archive_sheet_sign", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "signed sheetId=" + sheet.id + " signer=" + player.name());
        player.tell("Act semnat și blocat: " + sheet.id + ". Corecțiile se fac printr-o foaie nouă.");
        return true;
    }

    @Override
    public boolean revokeSheet(PlayerGateway player, String id) {
        if (!canSign(player) && !canArchive(player)) {
            player.tell("Doar Inspectorul, Comisaru' sau Arhivista pot revoca un act.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        if (sheet == null) {
            player.tell("Foaia nu există.");
            return false;
        }
        sheet.status = "REVOKED";
        sheet.revoked = true;
        sheet.updatedAt = now();
        ctx.archive().write(store);
        audit.record("archive_sheet_revoke", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS", "revoked sheetId=" + sheet.id);
        player.tell("Actul " + sheet.id + " a fost revocat.");
        return true;
    }

    // ---------------------------------------------------------------- copies

    @Override
    public boolean copySheet(PlayerGateway player, String id, Integer rawCount, String rawTargets) {
        if (!canArchive(player) && !canSign(player)) {
            player.tell("Doar Arhivista autorizată, Inspectorul sau Comisaru' pot genera copii.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        ArchiveStore.Folder folder = sheet == null ? null : store.folders.get(sheet.folderId);
        if (sheet == null || folder == null || !canReadFolder(player, folder) || !"SIGNED".equals(sheet.status) || sheet.originId != null) {
            player.tell("Numai originalul unui act semnat poate produce copii.");
            return false;
        }
        List<String> targets = new ArrayList<>();
        if (rawTargets != null && !rawTargets.isBlank()) {
            for (String token : rawTargets.split(",")) if (!token.trim().isEmpty()) targets.add(token.trim());
        } else {
            for (var recipient : sheet.recipients) targets.add(recipient.name);
        }
        int requested = rawCount != null ? rawCount : targets.size();
        if (requested < 1 || requested > p().archiveMaxCopiesPerOperation) {
            player.tell("Numărul de copii este invalid.");
            return false;
        }
        if (targets.isEmpty()) {
            player.tell("Indică cel puțin un destinatar sau adaugă destinatari pe foaie.");
            return false;
        }
        while (targets.size() < requested) targets.add(targets.get(targets.size() % Math.max(1, targets.size())));
        if (targets.size() > requested) targets = new ArrayList<>(targets.subList(0, requested));
        String carbon = "straja:carbon_paper";
        if (player.inventory().countOf(carbon) < requested) {
            player.tell("Nu ai suficiente Foi Indigo: ai nevoie de " + requested + ".");
            return false;
        }
        // Capacity preflight for online targets — abort before consuming carbon.
        List<String> unavailable = new ArrayList<>();
        for (String target : targets) {
            PlayerGateway online = ctx.server().findPlayer(target);
            if (online == null) continue;
            var stack = copyStack(sheet);
            if (!online.inventory().canReceive(List.of(stack))) unavailable.add(target);
        }
        if (!unavailable.isEmpty()) {
            player.tell("Operația oprită: inventar plin pentru " + String.join(", ", unavailable) + ". Nicio Indigo nu a fost consumată.");
            return false;
        }
        ArchiveStore.Operation operation = new ArchiveStore.Operation();
        operation.id = "OP-" + now() + "-" + store.operations.size();
        operation.kind = "COPY";
        operation.status = "PREPARED";
        operation.actor = player.uuid().toString();
        operation.sheetId = sheet.id;
        operation.count = requested;
        operation.createdAt = now();
        store.operations.add(operation);
        if (!extractAll(player, carbon, requested)) {
            operation.status = "ABORTED";
            ctx.archive().write(store);
            player.tell("Operația nu a putut consuma Foi Indigo; nimic nu a fost livrat.");
            return false;
        }
        operation.status = "CONSUMED";
        int nextNumber = (int) store.copies.stream().filter(c -> sheet.id.equals(c.originalId)).count() + 1;
        for (String targetName : targets) {
            ArchiveStore.Copy copy = new ArchiveStore.Copy();
            copy.id = "CP-" + (store.nextCopyNumber++);
            copy.originalId = sheet.id;
            copy.folderId = folder.id;
            copy.copyNumber = nextNumber++;
            copy.totalCopies = requested;
            copy.recipientName = targetName;
            copy.recipientKey = norm(targetName);
            copy.status = "PENDING";
            copy.createdAt = now();
            PlayerGateway online = ctx.server().findPlayer(targetName);
            if (online != null) {
                copy.recipientUuid = online.uuid().toString();
                if (online.giveVerified(copyStack(sheet))) {
                    copy.status = "DELIVERED";
                    copy.deliveredAt = now();
                } else {
                    copy.deliveryError = "delivery_failed";
                }
            }
            store.copies.add(copy);
        }
        long pending = store.copies.stream().filter(c -> sheet.id.equals(c.originalId)
                && c.createdAt >= operation.createdAt && "PENDING".equals(c.status)).count();
        operation.status = pending > 0 ? "COMPLETED_WITH_PENDING" : "COMPLETED";
        operation.completedAt = now();
        ctx.archive().write(store);
        audit.record("archive_sheet_copy", player.name(), player.uuid().toString(), player.name(), player.uuid().toString(), "SUCCESS",
                (pending > 0 ? "copies_created_with_pending" : "copies_created") + " sheetId=" + sheet.id + " count=" + requested + " pending=" + pending);
        player.tell("Au fost generate " + requested + " copii numerotate ale " + sheet.id + (pending > 0 ? "; unele rămân pending pentru retry." : "."));
        return true;
    }

    private ItemSpec copyStack(ArchiveStore.Sheet sheet) {
        return ItemSpec.of("straja:archive_document", 1)
                .withData("ArchiveKind", "COPY")
                .withData("ArchiveCopyOf", sheet.id)
                .withData("ArchiveTitle", sheet.title)
                .named("Copie " + sheet.id + " — " + sheet.title);
    }

    private boolean extractAll(PlayerGateway player, String itemId, int amount) {
        int remaining = amount;
        var inventory = player.inventory();
        for (int slot = 0; slot < inventory.slots() && remaining > 0; slot++) {
            var stack = inventory.stackAt(slot);
            if (stack == null || !itemId.equals(stack.id())) continue;
            remaining -= inventory.extract(slot, remaining).count();
        }
        return remaining <= 0;
    }

    // ---------------------------------------------------------------- envelopes

    @Override
    public boolean packEnvelope(PlayerGateway player, String id, String targetName) {
        if (!canArchive(player) && !canSign(player)) {
            player.tell("Doar Arhivista autorizată, Inspectorul sau Comisaru' pot împacheta acte.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = store.sheets.get(id);
        ArchiveStore.Folder folder = sheet == null ? null : store.folders.get(sheet.folderId);
        if (sheet == null || folder == null || !canReadFolder(player, folder) || !"SIGNED".equals(sheet.status)) {
            player.tell("Doar un act semnat poate fi împachetat.");
            return false;
        }
        if (targetName == null || targetName.isBlank()) {
            player.tell("Indică destinatarul.");
            return false;
        }
        String envelopeId = "straja:official_envelope";
        if (player.inventory().countOf(envelopeId) < 1) {
            player.tell("Ai nevoie de un Plic Oficial.");
            return false;
        }
        PlayerGateway target = ctx.server().findPlayer(targetName);
        ArchiveStore.Copy copy = new ArchiveStore.Copy();
        copy.id = "CP-" + (store.nextCopyNumber++);
        copy.originalId = sheet.id;
        copy.folderId = folder.id;
        copy.recipientName = targetName;
        copy.recipientKey = norm(targetName);
        copy.recipientUuid = target == null ? "" : target.uuid().toString();
        copy.issuerName = player.name();
        copy.issuerKey = player.uuid().toString();
        copy.kind = "ENVELOPE";
        copy.sourceState = "PREPARED";
        copy.status = "PENDING";
        copy.createdAt = now();
        var stack = ItemSpec.of(envelopeId, 1)
                .withData("ArchiveKind", "ENVELOPE")
                .withData("ArchiveDocumentId", sheet.id)
                .withData("ArchiveRecipient", targetName)
                .withData("ArchiveCopyId", copy.id)
                .named("Plic Oficial — " + sheet.title);
        if (target != null && !target.inventory().canReceive(List.of(stack))) {
            player.tell("Destinatarul nu are loc pentru plic.");
            return false;
        }
        store.copies.add(copy);
        ctx.archive().write(store);
        audit.record("archive_envelope_pack", player.name(), player.uuid().toString(), targetName, null, "PENDING", "prepared_before_source_consume sheetId=" + sheet.id + " copyId=" + copy.id);
        copy.sourceState = "CONSUMING";
        copy.sourceConsumeStartedAt = now();
        ctx.archive().write(store);
        if (!extractAll(player, envelopeId, 1)) {
            copy.status = "FAILED";
            copy.sourceState = "NOT_CONSUMED";
            copy.deliveryError = "source_consume_failed";
            ctx.archive().write(store);
            audit.record("archive_envelope_pack", player.name(), player.uuid().toString(), targetName, null, "FAILED", "source_consume_failed sheetId=" + sheet.id);
            player.tell("Plicul nu a putut fi consumat; operația a fost anulată.");
            return false;
        }
        copy.sourceState = "CONSUMED";
        copy.sourceConsumedAt = now();
        ctx.archive().write(store);
        if (target != null) {
            if (target.giveVerified(stack)) {
                copy.status = "DELIVERED";
                copy.deliveredAt = now();
            } else {
                copy.deliveryError = "delivery_failed";
            }
        }
        ctx.archive().write(store);
        audit.record("archive_envelope_pack", player.name(), player.uuid().toString(), targetName, null, "SUCCESS",
                ("DELIVERED".equals(copy.status) ? "official_envelope" : "official_envelope_pending") + " sheetId=" + sheet.id + " copyId=" + copy.id);
        player.tell("DELIVERED".equals(copy.status)
                ? "Plicul oficial a fost pregătit pentru " + targetName + "."
                : "Plicul oficial a fost pregătit; livrarea rămâne pending pentru retry.");
        return true;
    }

    /**
     * Issues the signed document item to a named online player. The persisted
     * recipient grant and the DELIVERED record — not item metadata — carry the
     * read authority; a repeat issue for the same sheet/target delivers nothing.
     */
    @Override
    public boolean issueDocument(PlayerGateway player, String id, String targetName) {
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Sheet sheet = id == null ? null : store.sheets.get(id);
        if (sheet == null || !"SIGNED".equals(sheet.status) || sheet.revoked
                || sheet.originId != null
                || !(canArchive(player) || canSign(player))
                || !canReadSheet(player, store, sheet)) {
            player.tell("Actul nu există, nu este semnat sau nu ai acces.");
            return false;
        }
        PlayerGateway target = targetName == null ? null : ctx.server().findPlayer(targetName);
        if (target == null || target.uuid() == null
                || target.uuid().equals(player.uuid())) {
            player.tell("Ținta trebuie să fie un alt jucător online.");
            return false;
        }
        String targetUuid = target.uuid().toString();
        for (var copy : store.copies) {
            if (copy != null && "DOCUMENT".equals(copy.kind) && "DELIVERED".equals(copy.status)
                    && sheet.id.equals(copy.originalId)
                    && PlayerService.identityMatches(target, copy.recipientUuid, copy.recipientName)) {
                player.tell("Documentul " + sheet.id + " i-a fost deja emis lui " + target.name() + ".");
                return false;
            }
        }
        var spec = ItemSpec.of("straja:archive_document", 1)
                .withData("ArchiveDocumentId", sheet.id)
                .named("Act — " + sheet.title);
        if (!target.inventory().canReceive(List.of(spec))) {
            player.tell("Destinatarul nu are loc pentru document.");
            return false;
        }
        if (!target.giveVerified(spec)) {
            player.tell("Documentul nu a putut fi livrat; niciun acces nu a fost acordat.");
            return false;
        }
        if (sheet.recipients.stream().noneMatch(r -> r != null
                && PlayerService.identityMatches(target, r.uuid, r.name))) {
            var recipient = new ArchiveStore.Recipient();
            recipient.name = target.name();
            recipient.key = norm(target.name());
            recipient.uuid = targetUuid;
            sheet.recipients.add(recipient);
        }
        var copy = new ArchiveStore.Copy();
        copy.id = "CP-" + (store.nextCopyNumber++);
        copy.originalId = sheet.id;
        copy.folderId = sheet.folderId;
        copy.recipientName = target.name();
        copy.recipientKey = norm(target.name());
        copy.recipientUuid = targetUuid;
        copy.issuerName = player.name();
        copy.issuerKey = player.uuid() == null ? "" : player.uuid().toString();
        copy.kind = "DOCUMENT";
        copy.status = "DELIVERED";
        copy.createdAt = now();
        copy.deliveredAt = now();
        store.copies.add(copy);
        ctx.archive().write(store);
        audit.record("archive_document_issue", player.name(), player.uuid().toString(),
                target.name(), targetUuid, "SUCCESS",
                "sheetId=" + sheet.id + " copyId=" + copy.id);
        player.tell("Actul " + sheet.id + " a fost emis pentru " + target.name() + ".");
        target.tell("Ai primit actul oficial " + sheet.id + " — " + sheet.title + ".");
        return true;
    }

    /**
     * Retries pending copy deliveries for a player that came online. Malformed
     * records are discarded and audited; a revoked or missing source sheet can
     * never deliver.
     */
    @Override
    public void deliverPending(PlayerGateway player) {
        ArchiveStore store = ctx.archive().read();
        boolean changed = false;
        var iterator = store.copies.iterator();
        while (iterator.hasNext()) {
            ArchiveStore.Copy copy = iterator.next();
            boolean malformed = copy == null || copy.originalId == null || copy.originalId.isBlank()
                    || (copy.recipientUuid == null || copy.recipientUuid.isEmpty())
                            && (copy.recipientKey == null || copy.recipientKey.isEmpty());
            if (malformed) {
                iterator.remove();
                changed = true;
                audit.record("archive_delivery", player.name(), player.uuid().toString(),
                        "", "", "SUCCESS", "malformed_copy_discarded");
                continue;
            }
            if (!"PENDING".equals(copy.status)) continue;
            // UUID-pinned recipients match by UUID only; name-only records
            // (offline at creation, or legacy) fall back to the name key.
            boolean recipient = copy.recipientUuid != null && !copy.recipientUuid.isEmpty()
                    ? copy.recipientUuid.equalsIgnoreCase(player.uuid().toString())
                    : norm(player.name()).equals(copy.recipientKey);
            if (!recipient) continue;
            ArchiveStore.Sheet sheet = store.sheets.get(copy.originalId);
            if (sheet == null || sheet.revoked) {
                copy.status = "FAILED";
                copy.deliveryError = sheet == null ? "missing_sheet" : "revoked";
                changed = true;
                audit.record("archive_delivery", player.name(), player.uuid().toString(),
                        copy.recipientName, copy.recipientUuid, "SUCCESS",
                        "copy_discarded reason=" + copy.deliveryError + " copyId=" + copy.id);
                continue;
            }
            ItemSpec stack = "ENVELOPE".equals(copy.kind)
                    ? ItemSpec.of("straja:official_envelope", 1)
                            .withData("ArchiveKind", "ENVELOPE").withData("ArchiveDocumentId", sheet.id)
                            .withData("ArchiveRecipient", copy.recipientName).withData("ArchiveCopyId", copy.id)
                            .named("Plic Oficial — " + sheet.title)
                    : copyStack(sheet);
            if (player.giveVerified(stack)) {
                copy.status = "DELIVERED";
                copy.deliveredAt = now();
                changed = true;
                player.tell("Ai primit documentul arhivat " + sheet.id + " — " + sheet.title + ".");
            } else {
                copy.deliveryError = "delivery_failed";
                changed = true;
                audit.record("archive_delivery", player.name(), player.uuid().toString(),
                        copy.recipientName, copy.recipientUuid, "FAILED",
                        "delivery_failed copyId=" + copy.id);
            }
        }
        if (changed) ctx.archive().write(store);
    }

    // ---------------------------------------------------------------- catalog

    public boolean catalogAdd(PlayerGateway player, String folderId, String alias) {
        if (!canArchive(player)) {
            player.tell("Doar Arhivista autorizată poate cataloga dosare.");
            return false;
        }
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Folder folder = store.folders.get(folderId);
        if (folder == null || !canReadFolder(player, folder)) {
            player.tell("Dosarul nu există sau nu ai acces.");
            return false;
        }
        if (folder.catalogIds.size() >= p().archiveMaxCatalogEntriesPerFolder) {
            player.tell("Dosarul a atins limita de înregistrări de catalog.");
            return false;
        }
        var entry = new ArchiveStore.CatalogEntry();
        entry.id = "K-" + (store.nextCatalogNumber++);
        entry.folderId = folder.id;
        entry.alias = alias == null ? "" : alias.substring(0, Math.min(80, alias.length()));
        entry.fingerprint = folder.id + "|" + folder.title + "|" + folder.sheetIds.size();
        entry.addedAt = now();
        store.catalog.put(entry.id, entry);
        folder.catalogIds.add(entry.id);
        ctx.archive().write(store);
        player.tell("Înregistrare de catalog " + entry.id + " pentru " + folder.id + ".");
        return true;
    }

    public void catalogList(PlayerGateway player, String folderId) {
        ArchiveStore store = ctx.archive().read();
        ArchiveStore.Folder folder = store.folders.get(folderId);
        if (folder == null || !canReadFolder(player, folder)) {
            player.tell("Dosarul nu există sau nu ai acces.");
            return;
        }
        if (folder.catalogIds.isEmpty()) {
            player.tell("Dosarul nu are înregistrări de catalog.");
            return;
        }
        for (String id : folder.catalogIds) {
            var entry = store.catalog.get(id);
            if (entry != null) player.tell(entry.id + " — " + entry.alias + " | " + entry.fingerprint);
        }
    }
}
