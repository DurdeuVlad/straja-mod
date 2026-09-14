package com.dwurdy.straja.application.port.in;

import com.dwurdy.straja.application.port.out.PlayerGateway;
import java.util.List;

/**
 * Physical admin-tool surface (CustomNPCs wand/cloner style): one item per
 * verb, used by an authorized holder (Comisar or op) clicking the world.
 * Commands stay canonical — every action below converges on an existing
 * application-service call; the tool adds no capability a command lacks.
 * The item is a pointer, never a credential: authority is re-checked per use
 * and a stolen tool in a normal player's hands fails closed with a tell.
 * Pending selections live server-side in the per-holder tool store, never in
 * item data, and clear on logout.
 */
public interface AdminToolsUseCase {

    /** One clickable chat row: display label plus the action id the adapter binds to a one-use token. */
    record MenuAction(String label, String actionId) {}

    /** A clickable chat menu; null return means the click was denied (tell already sent). */
    record Menu(String title, List<MenuAction> actions) {}

    /** A captured NPC template handed to the adapter so it can spawn the copy. */
    record CloneTemplate(String role, String displayName, String skin) {}

    /** Comisar or op — the tool holder gate. No tell. */
    boolean isToolHolder(PlayerGateway player);

    /** AT-001: gives the full admin tool kit to an authorized holder. */
    void giveToolKit(PlayerGateway player);

    /** Logout hook: drops every pending selection/route/template of the holder. */
    void clearState(PlayerGateway player);

    // ---------------------------------------------------------------- AT-002 NPC Wand

    /** Click on an entity: the wand menu for a registered Straja NPC, null + tell otherwise. */
    Menu npcWandMenu(PlayerGateway player, String entityUuid);

    void npcAssign(PlayerGateway player, String entityUuid, String role);

    void npcRename(PlayerGateway player, String entityUuid, String name);

    void npcSetSkin(PlayerGateway player, String entityUuid, String skin);

    /** Confirmed removal of a registered NPC. */
    void npcRemove(PlayerGateway player, String entityUuid);

    void npcShowRecord(PlayerGateway player, String entityUuid);

    /** Stale-click guard: true while the entity still has a registry record. */
    boolean npcStillRegistered(String entityUuid);

    // ---------------------------------------------------------------- AT-003 Patrol Wand

    /** Click a block: appends the waypoint, or removes it when already recorded. */
    void patrolClick(PlayerGateway player, String dimension, int x, int y, int z);

    /** Sneak + click air: writes the recorded route through the checkpoint setup path. */
    void patrolFinish(PlayerGateway player);

    /** Plain click on air: progress readout, no state change. */
    void patrolStatus(PlayerGateway player);

    // ---------------------------------------------------------------- AT-004 Survey Rod

    /** Click a block: stores the stamp target and returns the location-key menu. */
    Menu surveyMenu(PlayerGateway player, String dimension, int x, int y, int z);

    void surveyStamp(PlayerGateway player, String locationKey);

    /** Stamps every missing location at the pending target (the "setup here" behavior). */
    void surveyStampAllMissing(PlayerGateway player);

    /** Stale-click guard: a stamp button is valid only while a target is pending. */
    boolean surveyPending(PlayerGateway player);

    // ---------------------------------------------------------------- AT-005 Boundary Marker

    /**
     * Click a block: first click stores corner A, second click stores corner B.
     * Returns true once both corners exist — the adapter then offers the
     * confirm token. A cross-dimension second click resets the selection.
     */
    boolean cellClick(PlayerGateway player, String dimension, int x, int y, int z);

    /** Confirm token: registers the cell through the existing creation path. */
    void cellConfirm(PlayerGateway player);

    /** Stale-click guard: confirm is valid only while both corners are stored. */
    boolean cellSelectionReady(PlayerGateway player);

    // ---------------------------------------------------------------- AT-006 NPC Cloner

    /** Click a Straja NPC: captures role/name/skin into the holder's slot. */
    void cloneCapture(PlayerGateway player, String entityUuid);

    /**
     * Click a block face: returns the captured template for the adapter to
     * spawn at the given position, or null (tell sent) when the slot is empty.
     */
    CloneTemplate cloneSpawnAt(PlayerGateway player, String dimension, int x, int y, int z);

    /** Registers the freshly spawned entity under the captured template. */
    void registerClone(PlayerGateway player, String entityUuid);

    /** Sneak + click air: clears the captured template. */
    void cloneClear(PlayerGateway player);
}
