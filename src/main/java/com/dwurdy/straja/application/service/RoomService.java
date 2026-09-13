package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.RoomRoleplayUseCase;
import com.dwurdy.straja.application.port.out.PlayerGateway;
import com.dwurdy.straja.application.port.out.WorldGateway;
import com.dwurdy.straja.domain.model.Room;
import com.dwurdy.straja.domain.model.RoomStore;
import com.dwurdy.straja.domain.model.StrajaPolicies;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guard housing: bounded room discovery, ownership, waitlist and protection.
 * Port of the reference room system (flood-fill interior + single-door rule).
 */
public class RoomService implements RoomRoleplayUseCase {
    private final StrajaContext ctx;
    private final PlayerService players;
    private final AuditService audit;
    private final WorldGateway world;

    public RoomService(StrajaContext ctx, PlayerService players, AuditService audit, WorldGateway world) {
        this.ctx = ctx;
        this.players = players;
        this.audit = audit;
        this.world = world;
    }

    private StrajaPolicies p() {
        return ctx.policies();
    }

    private long now() {
        return ctx.clock().nowMillis();
    }

    // ---------------------------------------------------------------- query

    public Room roomAt(String dimension, double x, double y, double z) {
        for (Room room : ctx.rooms().read().rooms) {
            if (room.dimension.equals(dimension) && room.contains(x, y, z)) return room;
        }
        return null;
    }

    public Room roomAtSign(String dimension, int x, int y, int z) {
        for (Room room : ctx.rooms().read().rooms) {
            if (room.isSign(dimension, x, y, z)) return room;
        }
        return null;
    }

    public RoomStore.Assignment ownerOf(Room room) {
        return ctx.rooms().read().assignments.get(room.id);
    }

    public boolean isOwner(PlayerGateway player, RoomStore.Assignment owner) {
        if (owner == null) return false;
        return PlayerService.identityMatches(player, owner.playerUuid, owner.player);
    }

    public Room assignedRoom(PlayerGateway player) {
        RoomStore data = ctx.rooms().read();
        for (Room room : ordered(data.rooms)) {
            if (isOwner(player, data.assignments.get(room.id))) return room;
        }
        return null;
    }

    private static List<Room> ordered(List<Room> rooms) {
        List<Room> copy = new ArrayList<>(rooms);
        copy.sort(Comparator.comparingInt(r -> r.order != 0 ? r.order : orderOf(r.id)));
        return copy;
    }

    private static int orderOf(String id) {
        int i = id.length() - 1;
        while (i >= 0 && Character.isDigit(id.charAt(i))) i--;
        try {
            return Integer.parseInt(id.substring(i + 1));
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- discovery

    public void markerSelect(PlayerGateway player, String dimension, int x, int y, int z) {
        if (!players.isCommissioner(player)) {
            player.tell("Sigiliul de inspecție poate fi folosit doar de Comisaru'.");
            return;
        }
        RoomStore data = ctx.rooms().read();
        var selection = new RoomStore.MarkerSelection();
        selection.dimension = dimension;
        selection.x = x;
        selection.y = y;
        selection.z = z;
        selection.selectedAt = now();
        data.selections.put(player.uuid().toString(), selection);
        ctx.rooms().write(data);
        player.tell("Bloc selectat pentru cameră la " + x + ", " + y + ", " + z
                + ". Confirmă înregistrarea camerei la Comisaru'.");
    }

    public Room discover(PlayerGateway player, String requestedId) {
        if (!players.isCommissioner(player)) {
            player.tell("Doar Comisaru' poate crea camere.");
            return null;
        }
        RoomStore data = ctx.rooms().read();
        var selection = data.selections.get(player.uuid().toString());
        int seedX, seedY, seedZ;
        String dimension;
        if (selection != null) {
            seedX = selection.x; seedY = selection.y; seedZ = selection.z;
            dimension = selection.dimension;
        } else {
            seedX = (int) Math.floor(player.x());
            seedY = (int) Math.floor(player.y());
            seedZ = (int) Math.floor(player.z());
            dimension = player.dimension();
        }
        if (!dimension.equals(player.dimension()) && selection != null) {
            player.tell("Selecția camerei este într-o altă dimensiune decât cea curentă.");
            return null;
        }
        var geometry = discoverGeometry(dimension, seedX, seedY, seedZ);
        if (!geometry.ok) {
            player.tell(switch (geometry.code) {
                case "SELECTION_NOT_INTERIOR" -> "Blocul selectat nu pare să fie în interiorul unei camere.";
                case "INTERIOR_TOO_SMALL" -> "Interiorul trebuie să aibă cel puțin "
                        + p().roomMinInteriorX + "×" + p().roomMinInteriorY + "×" + p().roomMinInteriorZ + " blocuri.";
                case "TOO_LARGE" -> "Camera depășește limita sigură de " + p().roomMaxDimension + "×"
                        + p().roomMaxDimension + "×" + p().roomMaxDimension + " / " + p().roomMaxBlocks + " blocuri.";
                case "OPEN_OR_TOO_LARGE" -> "Camera este deschisă sau depășește limita de scanare "
                        + p().roomMaxDimension + "×" + p().roomMaxDimension + "×" + p().roomMaxDimension + ".";
                case "OPEN_WALL" -> "Pereții camerei nu sunt închiși complet.";
                case "SINGLE_TWO_BLOCK_DOOR_REQUIRED" -> "Camera trebuie să aibă exact o ușă standard de două blocuri.";
                case "UNREADABLE_BLOCK" -> "Un bloc din zona scanată nu poate fi citit; nu s-a creat camera.";
                default -> "Camera nu a putut fi validată (" + geometry.code + ").";
            });
            return null;
        }
        String id = requestedId == null || requestedId.isBlank() ? nextId(data) : requestedId.toLowerCase();
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            player.tell("ID invalid. Folosește doar litere mici, cifre, _ sau - (maximum 32).");
            return null;
        }
        Room previous = null;
        for (Room room : data.rooms) if (room.id.equalsIgnoreCase(id)) previous = room;
        Room room = new Room();
        room.id = id;
        room.order = previous != null && previous.order != 0 ? previous.order : orderOf(id);
        room.dimension = dimension;
        room.minX = geometry.minX; room.minY = geometry.minY; room.minZ = geometry.minZ;
        room.maxX = geometry.maxX; room.maxY = geometry.maxY; room.maxZ = geometry.maxZ;
        room.hasDoor = geometry.hasDoor;
        if (geometry.hasDoor) {
            room.doorX = geometry.doorX; room.doorY = geometry.doorY; room.doorZ = geometry.doorZ;
        }
        room.createdAt = now();
        data.rooms.removeIf(item -> item.id.equalsIgnoreCase(id));
        data.rooms.add(room);
        refreshSign(room, data);
        trimAndSave(data);
        audit.record("room_create", player.name(), player.uuid().toString(), id, null, "SUCCESS", "bounds " + (room.maxX - room.minX + 1) + "x" + (room.maxY - room.minY + 1) + "x" + (room.maxZ - room.minZ + 1));
        player.tell("Camera " + id + " a fost descoperită și înregistrată. Semnul se actualizează automat după atribuiri.");
        processWaitlist();
        return room;
    }

    private String nextId(RoomStore data) {
        int number = 1;
        while (true) {
            String id = "camera_" + number;
            boolean taken = false;
            for (Room room : data.rooms) if (room.id.equalsIgnoreCase(id)) { taken = true; break; }
            if (!taken) return id;
            number++;
        }
    }

    private record Geometry(boolean ok, String code,
                            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                            boolean hasDoor, int doorX, int doorY, int doorZ) {
        static Geometry fail(String code) { return new Geometry(false, code, 0, 0, 0, 0, 0, 0, false, 0, 0, 0); }
    }

    /** Bounded flood fill of traversable interior, then boundary validation. */
    private Geometry discoverGeometry(String dimension, int sx, int sy, int sz) {
        int limit = p().roomMaxDimension;
        int maxBlocks = p().roomMaxBlocks;
        Set<Long> visited = new LinkedHashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{sx, sy, sz});
        boolean overflow = false;
        while (!queue.isEmpty()) {
            int[] point = queue.poll();
            long key = pack(point[0], point[1], point[2]);
            if (visited.contains(key)) continue;
            if (Math.abs(point[0] - sx) > limit || Math.abs(point[1] - sy) > limit || Math.abs(point[2] - sz) > limit) {
                overflow = true;
                continue;
            }
            var block = world.blockAt(dimension, point[0], point[1], point[2]);
            if (block == null) return Geometry.fail("UNREADABLE_BLOCK");
            if (!block.traversable()) {
                if (visited.isEmpty()) return Geometry.fail("SELECTION_NOT_INTERIOR");
                continue;
            }
            visited.add(key);
            if (visited.size() > maxBlocks) return Geometry.fail("TOO_LARGE");
            queue.add(new int[]{point[0] + 1, point[1], point[2]});
            queue.add(new int[]{point[0] - 1, point[1], point[2]});
            queue.add(new int[]{point[0], point[1] + 1, point[2]});
            queue.add(new int[]{point[0], point[1] - 1, point[2]});
            queue.add(new int[]{point[0], point[1], point[2] + 1});
            queue.add(new int[]{point[0], point[1], point[2] - 1});
        }
        if (overflow) return Geometry.fail("OPEN_OR_TOO_LARGE");
        if (visited.isEmpty()) return Geometry.fail("SELECTION_NOT_INTERIOR");
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : visited) {
            int x = (int) (key >> 40), y = (int) (key >> 20) & 0xFFFFF, z = (int) (key & 0xFFFFF);
            x = (x & 0x800000) != 0 ? x | ~0xFFFFFF : x;
            y = (y & 0x80000) != 0 ? y | ~0xFFFFF : y;
            z = (z & 0x80000) != 0 ? z | ~0xFFFFF : z;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        int bMinX = minX - 1, bMinY = minY - 1, bMinZ = minZ - 1;
        int bMaxX = maxX + 1, bMaxY = maxY + 1, bMaxZ = maxZ + 1;
        int dimX = bMaxX - bMinX + 1, dimY = bMaxY - bMinY + 1, dimZ = bMaxZ - bMinZ + 1;
        if (dimX > limit || dimY > limit || dimZ > limit || dimX * dimY * dimZ > maxBlocks) return Geometry.fail("TOO_LARGE");
        if (dimX - 2 < p().roomMinInteriorX || dimY - 2 < p().roomMinInteriorY || dimZ - 2 < p().roomMinInteriorZ) {
            return Geometry.fail("INTERIOR_TOO_SMALL");
        }
        List<int[]> doorCells = new ArrayList<>();
        int scanned = 0;
        for (int x = bMinX; x <= bMaxX; x++) {
            for (int y = bMinY; y <= bMaxY; y++) {
                for (int z = bMinZ; z <= bMaxZ; z++) {
                    boolean boundary = x == bMinX || x == bMaxX || y == bMinY || y == bMaxY || z == bMinZ || z == bMaxZ;
                    if (!boundary) continue;
                    if (++scanned > maxBlocks) return Geometry.fail("TOO_LARGE");
                    var block = world.blockAt(dimension, x, y, z);
                    if (block == null) return Geometry.fail("UNREADABLE_BLOCK");
                    if (block.solid()) continue;
                    if (block.door()) {
                        doorCells.add(new int[]{x, y, z});
                        continue;
                    }
                    return Geometry.fail("OPEN_WALL");
                }
            }
        }
        if (p().roomRequireSingleDoor) {
            Map<String, List<Integer>> columns = new HashMap<>();
            for (int[] cell : doorCells) {
                columns.computeIfAbsent(cell[0] + "," + cell[2], k -> new ArrayList<>()).add(cell[1]);
            }
            List<Integer> ys = columns.size() == 1 ? columns.values().iterator().next().stream().sorted().toList() : List.of();
            if (doorCells.size() != 2 || columns.size() != 1 || ys.get(1) != ys.get(0) + 1) {
                return Geometry.fail("SINGLE_TWO_BLOCK_DOOR_REQUIRED");
            }
            int[] door = doorCells.stream().min(Comparator.comparingInt(c -> c[1])).get();
            return new Geometry(true, "OK", bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ, true, door[0], door[1], door[2]);
        }
        if (!doorCells.isEmpty()) {
            int[] door = doorCells.get(0);
            return new Geometry(true, "OK", bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ, true, door[0], door[1], door[2]);
        }
        return new Geometry(true, "OK", bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ, false, 0, 0, 0);
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0xFFFFFF) << 40) | (((long) y & 0xFFFFF) << 20) | ((long) z & 0xFFFFF);
    }

    // ---------------------------------------------------------------- signs

    private void refreshSign(Room room, RoomStore data) {
        if (!room.hasDoor) {
            room.signStatus = "NO_DOOR_SIGN_POSITION";
            return;
        }
        String facing;
        int x, y, z;
        if (room.doorX == room.minX) { x = room.minX - 1; y = room.doorY + 2; z = room.doorZ; facing = "east"; }
        else if (room.doorX == room.maxX) { x = room.maxX + 1; y = room.doorY + 2; z = room.doorZ; facing = "west"; }
        else if (room.doorZ == room.minZ) { x = room.doorX; y = room.doorY + 2; z = room.minZ - 1; facing = "south"; }
        else if (room.doorZ == room.maxZ) { x = room.doorX; y = room.doorY + 2; z = room.maxZ + 1; facing = "north"; }
        else { room.signStatus = "NO_DOOR_SIGN_POSITION"; return; }
        var owner = data.assignments.get(room.id);
        int order = room.order != 0 ? room.order : orderOf(room.id);
        room.signDimension = room.dimension;
        room.signX = x; room.signY = y; room.signZ = z; room.signFacing = facing;
        boolean placed = world.setRoomSign(room.dimension, x, y, z, facing,
                "Camera #" + order,
                owner != null ? "OCUPATĂ" : "NEOCUPATĂ",
                owner != null ? owner.player : "—");
        room.signStatus = placed ? "UPDATED" : "FAILED";
    }

    // ---------------------------------------------------------------- assignment

    public boolean eligible(PlayerGateway player) {
        if (player == null || players.isCommissioner(player)) return false;
        var state = players.state(player);
        return state.rank >= 1 && !state.resigned && !state.fired && !state.suspended && !state.resignationPending;
    }

    private Room firstFree(RoomStore data) {
        for (Room room : ordered(data.rooms)) {
            if (data.assignments.get(room.id) == null) return room;
        }
        return null;
    }

    private void assign(RoomStore data, PlayerGateway player, Room room) {
        data.assignments.values().removeIf(owner -> isOwner(player, owner));
        var assignment = new RoomStore.Assignment();
        assignment.player = player.name();
        assignment.playerUuid = player.uuid().toString();
        assignment.assignedAt = now();
        data.assignments.put(room.id, assignment);
    }

    private void sendLetter(PlayerGateway target, String subject, String body) {
        var result = ctx.delivery().sendLetter(null, target.name(), subject, body);
        switch (result.mode()) {
            case DELIVERED, PENDING_MAILBOX -> target.tell("Ai primit o scrisoare Envelope: " + subject + ".");
            case FAILED -> target.tell("Scrisoarea „" + subject + "” nu a putut fi pusă în inventar. Eliberează un slot și anunță Comisaru'.");
            case CHAT_FALLBACK -> target.tell("Scrisoare Straja (" + subject + "): " + body);
            default -> { }
        }
    }

    public String assignAutomatically(PlayerGateway player) {
        RoomStore data = ctx.rooms().read();
        if (data.rooms.isEmpty()) return "NO_ROOMS_CONFIGURED";
        if (!eligible(player)) {
            if (data.waitlist.removeIf(e -> player.uuid().toString().equals(e.playerUuid))) ctx.rooms().write(data);
            return "NOT_ELIGIBLE";
        }
        if (assignedRoom(player) != null) {
            if (data.waitlist.removeIf(e -> player.uuid().toString().equals(e.playerUuid))) ctx.rooms().write(data);
            return "ALREADY_ASSIGNED";
        }
        Room room = firstFree(data);
        if (room == null) {
            var entry = data.waitlist.stream().filter(e -> player.uuid().toString().equals(e.playerUuid)).findFirst().orElse(null);
            boolean changed = false;
            if (entry == null) {
                entry = new RoomStore.WaitlistEntry();
                entry.player = player.name();
                entry.playerUuid = player.uuid().toString();
                entry.rank = players.state(player).rank;
                entry.queuedAt = now();
                data.waitlist.add(entry);
                changed = true;
            } else if (entry.rank != players.state(player).rank || !entry.player.equals(player.name())) {
                entry.player = player.name();
                entry.rank = players.state(player).rank;
                changed = true;
            }
            int position = data.waitlist.indexOf(entry) + 1;
            boolean notify = entry.notifiedPosition != position;
            if (notify) {
                entry.notifiedPosition = position;
                changed = true;
            }
            if (changed) ctx.rooms().write(data);
            if (notify) sendLetter(player, "Lista de așteptare pentru camere",
                    "Nu există camere libere. Ești pe poziția " + position + ". Vei primi automat o cameră și o scrisoare când se eliberează.");
            return "WAITING:" + position;
        }
        assign(data, player, room);
        data.waitlist.removeIf(e -> player.uuid().toString().equals(e.playerUuid));
        refreshSign(room, data);
        trimAndSave(data);
        sendLetter(player, "Cameră atribuită: " + room.id,
                "Camera ta din cazarma Străjii este " + room.id + ". Atribuirea este în ordinea camerelor și rămâne protejată cât timp ești în Strajă.");
        return "ASSIGNED:" + room.id;
    }

    /** FIFO: an offline first entry keeps the free room reserved. */
    public void processWaitlist() {
        RoomStore data = ctx.rooms().read();
        long retentionMs = ctx.policies().roomWaitlistRetentionDays * 24L * 60 * 60 * 1000;
        boolean changed = retentionMs > 0 && data.waitlist.removeIf(
                e -> now() - e.queuedAt >= retentionMs);
        List<RoomStore.WaitlistEntry> queue = new ArrayList<>(data.waitlist);
        queue.sort(Comparator.comparingLong(e -> e.queuedAt));
        for (var entry : queue) {
            if (firstFree(data) == null) break;
            PlayerGateway target = ctx.server().findPlayer(entry.playerUuid);
            if (target == null) break;
            if (!eligible(target) || assignedRoom(target) != null) {
                changed |= data.waitlist.remove(entry);
                continue;
            }
            Room room = firstFree(data);
            if (room == null) break;
            assign(data, target, room);
            data.waitlist.remove(entry);
            changed = true;
            refreshSign(room, data);
            sendLetter(target, "Cameră atribuită: " + room.id,
                    "Camera ta din cazarma Străjii este " + room.id + ". Camera a fost atribuită automat după eliberarea unui loc.");
        }
        if (changed) ctx.rooms().write(data);
    }

    public boolean releaseFor(PlayerGateway player) {
        RoomStore data = ctx.rooms().read();
        boolean released = data.assignments.values().removeIf(owner -> isOwner(player, owner));
        released |= data.waitlist.removeIf(e -> PlayerService.identityMatches(player, e.playerUuid, e.player));
        if (released) {
            for (Room room : data.rooms) refreshSign(room, data);
            ctx.rooms().write(data);
            processWaitlist();
        }
        return released;
    }

    @Override
    public boolean canRelease(PlayerGateway player) {
        RoomStore data = ctx.rooms().read();
        for (Room room : data.rooms) {
            if (isOwner(player, data.assignments.get(room.id))) return true;
        }
        return data.waitlist.stream().anyMatch(
                e -> PlayerService.identityMatches(player, e.playerUuid, e.player));
    }

    public void status(PlayerGateway player) {
        RoomStore data = ctx.rooms().read();
        Room mine = assignedRoom(player);
        if (mine != null) player.tell("Camera ta: " + mine.id + ".");
        int position = -1;
        for (int i = 0; i < data.waitlist.size(); i++) {
            if (player.uuid().toString().equals(data.waitlist.get(i).playerUuid)) position = i + 1;
        }
        if (position > 0) player.tell("Ești pe poziția " + position + " în lista de așteptare.");
        if (mine == null && position < 0) player.tell("Nu ai o cameră atribuită.");
        player.tell("Camere libere: " + data.rooms.stream().filter(r -> data.assignments.get(r.id) == null).count()
                + "/" + data.rooms.size() + ". Așteptare: " + data.waitlist.size() + ".");
    }

    public void list(PlayerGateway player) {
        RoomStore data = ctx.rooms().read();
        for (Room room : ordered(data.rooms)) {
            var owner = data.assignments.get(room.id);
            player.tell(room.id + " " + (owner != null ? "OCUPATĂ " + owner.player : "NEOCUPATĂ"));
        }
        if (data.rooms.isEmpty()) player.tell("Nu există camere configurate.");
    }

    private void trimAndSave(RoomStore data) {
        List<Room> sorted = ordered(data.rooms);
        data.rooms = new ArrayList<>(sorted.subList(0, Math.min(sorted.size(), Math.max(1, p().roomRetentionLimit))));
        ctx.rooms().write(data);
    }

    /** Room protection: non-owners may not break/place inside an occupied room. */
    public boolean protectBlock(PlayerGateway player, String dimension, int x, int y, int z) {
        for (Room room : ctx.rooms().read().rooms) {
            if (!room.dimension.equals(dimension)
                    || (!room.contains(x, y, z) && !room.isSign(dimension, x, y, z))) continue;
            var owner = ownerOf(room);
            if (owner == null || isOwner(player, owner) || players.isCommissioner(player)) continue;
            player.tell("Camera " + room.id + " este atribuită lui " + owner.player + ".");
            return true;
        }
        return false;
    }

    /** Read-only form used by explosion hooks, which have no player actor. */
    public boolean isProtectedBlock(String dimension, int x, int y, int z) {
        var data = ctx.rooms().read();
        for (Room room : data.rooms) {
            if (!room.dimension.equals(dimension)
                    || (!room.contains(x, y, z) && !room.isSign(dimension, x, y, z))) continue;
            if (data.assignments.get(room.id) != null) return true;
        }
        return false;
    }
}
