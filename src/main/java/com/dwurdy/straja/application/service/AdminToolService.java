package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.AdminToolsUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.domain.model.AdminToolStore;
import com.dwurdy.straja.domain.model.ItemSpec;
import com.dwurdy.straja.domain.model.NpcRegistry;
import com.dwurdy.straja.domain.model.SetupChecklist;
import com.dwurdy.straja.domain.model.SetupData;
import java.util.ArrayList;
import java.util.List;

/**
 * Physical admin tools (AT-001..006): one item per verb for an authorized
 * holder (Comisar or op). Every entry point re-checks authority and pending
 * state; the item itself is never trusted. Mutations delegate to the same
 * service calls the commands use — the tool adds no new capability.
 */
public class AdminToolService implements AdminToolsUseCase {

    private static final List<String> KIT = List.of(
            "straja:npc_wand", "straja:patrol_wand", "straja:survey_rod",
            "straja:npc_cloner", "straja:prison_marker", "straja:room_marker");

    private final StrajaContext ctx;
    private final PlayerService players;
    private final NpcAdminService npcs;
    private final GuardService guards;
    private final PrisonService prison;

    public AdminToolService(StrajaContext ctx, PlayerService players, NpcAdminService npcs,
                            GuardService guards, PrisonService prison) {
        this.ctx = ctx;
        this.players = players;
        this.npcs = npcs;
        this.guards = guards;
        this.prison = prison;
    }

    // ---------------------------------------------------------------- gate & state

    @Override
    public boolean isToolHolder(PlayerGateway player) {
        return player != null && (players.isCommissioner(player) || player.isOp());
    }

    private boolean gate(PlayerGateway player) {
        if (isToolHolder(player)) return true;
        if (player != null) {
            player.tell("Instrumentele administrative pot fi folosite doar de Comisar sau operatori.");
        }
        return false;
    }

    private static String key(PlayerGateway player) {
        return player.uuid() == null ? "" : player.uuid().toString();
    }

    private AdminToolStore.HolderState state(AdminToolStore store, PlayerGateway player) {
        return store.holders.computeIfAbsent(key(player), k -> new AdminToolStore.HolderState());
    }

    @Override
    public void giveToolKit(PlayerGateway player) {
        if (!gate(player)) return;
        for (String id : KIT) {
            player.give(ItemSpec.of(id, 1));
        }
        player.tell("Kit de instrumente administrative primit: bagheta NPC, bagheta de patrulare, "
                + "jalonul de măsurare, clonatorul și marcajele de celulă/cameră.");
    }

    @Override
    public void clearState(PlayerGateway player) {
        AdminToolStore store = ctx.adminTools().read();
        if (store.holders.remove(key(player)) != null) {
            ctx.adminTools().write(store);
        }
    }

    // ---------------------------------------------------------------- AT-002 NPC Wand

    @Override
    public Menu npcWandMenu(PlayerGateway player, String entityUuid) {
        if (!gate(player)) return null;
        NpcRegistry.Record record = recordOf(entityUuid);
        if (record == null) {
            player.tell("Bagheta funcționează doar pe un NPC Straja înregistrat.");
            return null;
        }
        var actions = new ArrayList<MenuAction>();
        for (String role : NpcAdminService.ROLE_ORDER) {
            actions.add(new MenuAction("Rol: " + role,
                    "tool-npc-assign:" + entityUuid + "-" + role));
        }
        actions.add(new MenuAction("Redenumește", "tool-npc-rename:" + entityUuid));
        actions.add(new MenuAction("Skin nou", "tool-npc-skin:" + entityUuid));
        actions.add(new MenuAction("Elimină NPC", "tool-npc-remove:" + entityUuid));
        actions.add(new MenuAction("Fișa registrului", "tool-npc-record:" + entityUuid));
        String name = record.displayName == null || record.displayName.isEmpty()
                ? entityUuid : record.displayName;
        return new Menu("[Straja] NPC " + name + " (rol: "
                + (record.role == null ? "—" : record.role) + "):", List.copyOf(actions));
    }

    @Override
    public void npcAssign(PlayerGateway player, String entityUuid, String role) {
        if (!gate(player) || !requireRecord(player, entityUuid)) return;
        if (npcs.assignRole(entityUuid, role).ok()) {
            player.tell("NPC " + entityUuid + " are acum rolul " + role + ".");
        } else {
            player.tell("Rol necunoscut: " + role + ". Valide: " + NpcAdminService.KNOWN_ROLES);
        }
    }

    @Override
    public void npcRename(PlayerGateway player, String entityUuid, String name) {
        if (!gate(player) || !requireRecord(player, entityUuid)) return;
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            player.tell("Numele trebuie să aibă între 1 și 80 de caractere.");
            return;
        }
        npcs.setName(entityUuid, trimmed);
        player.tell("Numele a fost salvat: " + trimmed);
    }

    @Override
    public void npcSetSkin(PlayerGateway player, String entityUuid, String skin) {
        if (!gate(player) || !requireRecord(player, entityUuid)) return;
        String trimmed = skin == null ? "" : skin.trim();
        if (trimmed.isEmpty() || trimmed.length() > 80) {
            player.tell("Skin-ul trebuie să aibă între 1 și 80 de caractere.");
            return;
        }
        npcs.setSkin(entityUuid, trimmed);
        player.tell("Skin salvat: " + trimmed);
    }

    @Override
    public void npcRemove(PlayerGateway player, String entityUuid) {
        if (!gate(player) || !requireRecord(player, entityUuid)) return;
        npcs.remove(entityUuid);
        player.tell("NPC " + entityUuid + " a fost eliminat din registru.");
    }

    @Override
    public void npcShowRecord(PlayerGateway player, String entityUuid) {
        if (!gate(player) || !requireRecord(player, entityUuid)) return;
        NpcRegistry.Record record = recordOf(entityUuid);
        player.tell("[Straja] Fișa NPC " + entityUuid + ": rol "
                + (record.role == null ? "—" : record.role)
                + " · nume " + (record.displayName == null ? "—" : record.displayName)
                + " · skin " + (record.skin == null ? "—" : record.skin));
    }

    @Override
    public boolean npcStillRegistered(String entityUuid) {
        return recordOf(entityUuid) != null;
    }

    private NpcRegistry.Record recordOf(String entityUuid) {
        if (entityUuid == null || entityUuid.isEmpty()) return null;
        return ctx.npcs().read().npcs.get(entityUuid);
    }

    private boolean requireRecord(PlayerGateway player, String entityUuid) {
        if (recordOf(entityUuid) != null) return true;
        player.tell("NPC-ul nu mai este înregistrat.");
        return false;
    }

    // ---------------------------------------------------------------- AT-003 Patrol Wand

    @Override
    public void patrolClick(PlayerGateway player, String dimension, int x, int y, int z) {
        if (!gate(player)) return;
        AdminToolStore store = ctx.adminTools().read();
        var holder = state(store, player);
        int slots = ctx.setup().read().checkpoints.size();
        for (int i = 0; i < holder.route.size(); i++) {
            if (holder.route.get(i).samePlace(dimension, x, y, z)) {
                holder.route.remove(i);
                ctx.adminTools().write(store);
                player.tell("Punct eliminat. Traseu: " + holder.route.size() + "/" + slots
                        + " puncte înregistrate.");
                return;
            }
        }
        if (holder.route.size() >= slots) {
            player.tell("Traseul este complet (" + slots + "/" + slots
                    + "). Sneak + click pe aer pentru finalizare.");
            return;
        }
        var point = new AdminToolStore.Waypoint();
        point.dimension = dimension;
        point.x = x;
        point.y = y;
        point.z = z;
        holder.route.add(point);
        ctx.adminTools().write(store);
        player.tell("Punct înregistrat la " + x + ", " + y + ", " + z
                + ". Traseu: " + holder.route.size() + "/" + slots + ".");
    }

    @Override
    public void patrolFinish(PlayerGateway player) {
        if (!gate(player)) return;
        AdminToolStore store = ctx.adminTools().read();
        var holder = state(store, player);
        SetupData setup = ctx.setup().read();
        int slots = setup.checkpoints.size();
        if (holder.route.size() != slots) {
            player.tell("Traseul cere exact " + slots + " puncte distincte — "
                    + holder.route.size() + "/" + slots + " înregistrate.");
            return;
        }
        for (var point : holder.route) {
            if (!player.dimension().equals(point.dimension)) {
                player.tell("Traseul traversează dimensiuni — finalizează din "
                        + point.dimension + " sau reînregistrează punctele.");
                return;
            }
        }
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura checkpoint-urile.");
            return;
        }
        for (int i = 0; i < holder.route.size(); i++) {
            var point = holder.route.get(i);
            guards.setCheckpointAt(player, setup.checkpoints.get(i).id,
                    point.dimension, point.x, point.y, point.z);
        }
        holder.route.clear();
        ctx.adminTools().write(store);
        player.tell("Traseul de patrulare a fost salvat (" + slots + " checkpoint-uri).");
    }

    @Override
    public void patrolStatus(PlayerGateway player) {
        if (!gate(player)) return;
        var holder = ctx.adminTools().read().holders.get(key(player));
        int recorded = holder == null ? 0 : holder.route.size();
        player.tell("Traseu în lucru: " + recorded + "/" + ctx.setup().read().checkpoints.size()
                + " puncte. Sneak + click pe aer pentru finalizare.");
    }

    // ---------------------------------------------------------------- AT-004 Survey Rod

    @Override
    public Menu surveyMenu(PlayerGateway player, String dimension, int x, int y, int z) {
        if (!gate(player)) return null;
        AdminToolStore store = ctx.adminTools().read();
        var holder = state(store, player);
        var target = new AdminToolStore.Waypoint();
        target.dimension = dimension;
        target.x = x;
        target.y = y;
        target.z = z;
        holder.surveyTarget = target;
        ctx.adminTools().write(store);
        var missing = SetupChecklist.missingLocations(ctx.setup().read());
        var actions = new ArrayList<MenuAction>();
        for (String locationKey : SetupData.LOCATION_KEYS) {
            actions.add(new MenuAction(
                    locationKey + (missing.contains(locationKey) ? " (lipsește)" : ""),
                    "tool-survey-stamp:" + locationKey));
        }
        if (!missing.isEmpty()) {
            actions.add(new MenuAction("Marchează toate cele lipsă aici", "tool-survey-all"));
        }
        return new Menu("[Straja] Jalon la " + x + ", " + y + ", " + z
                + " — alege locația de marcat:", List.copyOf(actions));
    }

    @Override
    public void surveyStamp(PlayerGateway player, String locationKey) {
        if (!gate(player)) return;
        var target = surveyTarget(player);
        if (target == null) return;
        guards.stampLocation(player, locationKey, target.dimension, target.x, target.y, target.z);
    }

    @Override
    public void surveyStampAllMissing(PlayerGateway player) {
        if (!gate(player)) return;
        var target = surveyTarget(player);
        if (target == null) return;
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate configura locațiile administrative.");
            return;
        }
        var missing = SetupChecklist.missingLocations(ctx.setup().read());
        if (missing.isEmpty()) {
            player.tell("Toate locațiile administrative sunt deja configurate.");
            return;
        }
        for (String locationKey : missing) {
            guards.stampLocation(player, locationKey, target.dimension, target.x, target.y, target.z);
        }
        player.tell("Au fost marcate " + missing.size() + " locații lipsă la "
                + target.x + ", " + target.y + ", " + target.z + ".");
    }

    @Override
    public boolean surveyPending(PlayerGateway player) {
        var holder = ctx.adminTools().read().holders.get(key(player));
        return holder != null && holder.surveyTarget != null;
    }

    private AdminToolStore.Waypoint surveyTarget(PlayerGateway player) {
        var holder = ctx.adminTools().read().holders.get(key(player));
        var target = holder == null ? null : holder.surveyTarget;
        if (target == null) {
            player.tell("Nicio poziție de măsurare — click un bloc cu jalonul mai întâi.");
            return null;
        }
        if (!player.dimension().equals(target.dimension)) {
            player.tell("Poziția măsurată este în altă dimensiune (" + target.dimension + ").");
            return null;
        }
        return target;
    }

    // ---------------------------------------------------------------- AT-005 Boundary Marker

    @Override
    public boolean cellClick(PlayerGateway player, String dimension, int x, int y, int z) {
        if (!gate(player)) return false;
        AdminToolStore store = ctx.adminTools().read();
        var holder = state(store, player);
        if (holder.cellCornerA == null || holder.cellCornerB != null) {
            var corner = new AdminToolStore.Waypoint();
            corner.dimension = dimension;
            corner.x = x;
            corner.y = y;
            corner.z = z;
            holder.cellCornerA = corner;
            holder.cellCornerB = null;
            ctx.adminTools().write(store);
            player.tell("Primul colț al celulei la " + x + ", " + y + ", " + z
                    + ". Click pe colțul opus pentru a continua.");
            return false;
        }
        if (!dimension.equals(holder.cellCornerA.dimension)) {
            holder.cellCornerA = null;
            ctx.adminTools().write(store);
            player.tell("Celula nu poate traversa dimensiuni — selecția a fost resetată.");
            return false;
        }
        var corner = new AdminToolStore.Waypoint();
        corner.dimension = dimension;
        corner.x = x;
        corner.y = y;
        corner.z = z;
        holder.cellCornerB = corner;
        ctx.adminTools().write(store);
        player.tell("Celulă delimitată: (" + holder.cellCornerA.x + ", " + holder.cellCornerA.y
                + ", " + holder.cellCornerA.z + ") ↔ (" + x + ", " + y + ", " + z + ").");
        return true;
    }

    @Override
    public void cellConfirm(PlayerGateway player) {
        if (!gate(player)) return;
        AdminToolStore store = ctx.adminTools().read();
        var holder = store.holders.get(key(player));
        if (holder == null || holder.cellCornerA == null || holder.cellCornerB == null) {
            player.tell("Selectează întâi cele două colțuri ale celulei cu marcajul.");
            return;
        }
        var a = holder.cellCornerA;
        var b = holder.cellCornerB;
        if (!player.dimension().equals(a.dimension)) {
            player.tell("Celula selectată este în altă dimensiune (" + a.dimension + ").");
            return;
        }
        holder.cellCornerA = null;
        holder.cellCornerB = null;
        ctx.adminTools().write(store);
        prison.createCell(player, null, a.dimension, a.x, a.y, a.z, b.x, b.y, b.z);
    }

    @Override
    public boolean cellSelectionReady(PlayerGateway player) {
        var holder = ctx.adminTools().read().holders.get(key(player));
        return holder != null && holder.cellCornerA != null && holder.cellCornerB != null;
    }

    // ---------------------------------------------------------------- AT-006 NPC Cloner

    @Override
    public void cloneCapture(PlayerGateway player, String entityUuid) {
        if (!gate(player)) return;
        NpcRegistry.Record record = recordOf(entityUuid);
        if (record == null) {
            player.tell("Clonatorul capturează doar un NPC Straja înregistrat.");
            return;
        }
        AdminToolStore store = ctx.adminTools().read();
        var template = new AdminToolStore.CloneTemplate();
        template.role = record.role;
        template.name = record.displayName;
        template.skin = record.skin;
        state(store, player).cloneTemplate = template;
        ctx.adminTools().write(store);
        player.tell("Șablon capturat: " + (template.name != null ? template.name : entityUuid)
                + " (rol: " + (template.role != null ? template.role : "—") + ").");
    }

    @Override
    public CloneTemplate cloneSpawnAt(PlayerGateway player, String dimension, int x, int y, int z) {
        if (!gate(player)) return null;
        var holder = ctx.adminTools().read().holders.get(key(player));
        var template = holder == null ? null : holder.cloneTemplate;
        if (template == null || template.role == null) {
            player.tell("Niciun șablon capturat — click un NPC Straja mai întâi.");
            return null;
        }
        if (dimension != null && !dimension.equals(player.dimension())) {
            player.tell("Poziția de clonare este în altă dimensiune (" + dimension + ").");
            return null;
        }
        return new CloneTemplate(template.role, template.name, template.skin);
    }

    @Override
    public void registerClone(PlayerGateway player, String entityUuid) {
        if (!gate(player)) return;
        var holder = ctx.adminTools().read().holders.get(key(player));
        var template = holder == null ? null : holder.cloneTemplate;
        if (template == null || template.role == null) {
            player.tell("Niciun șablon capturat — copia nu a fost înregistrată.");
            return;
        }
        npcs.register(entityUuid, template.role);
        if (template.name != null) npcs.setName(entityUuid, template.name);
        if (template.skin != null) npcs.setSkin(entityUuid, template.skin);
        player.tell("Copie înregistrată: " + entityUuid
                + " (rol: " + template.role + ").");
    }

    @Override
    public void cloneClear(PlayerGateway player) {
        if (!gate(player)) return;
        AdminToolStore store = ctx.adminTools().read();
        var holder = store.holders.get(key(player));
        if (holder == null || holder.cloneTemplate == null) {
            player.tell("Niciun șablon de șters.");
            return;
        }
        holder.cloneTemplate = null;
        ctx.adminTools().write(store);
        player.tell("Șablonul de clonare a fost șters.");
    }
}
