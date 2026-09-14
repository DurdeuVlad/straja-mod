package com.dwurdy.straja.application.service;

import com.dwurdy.straja.application.StrajaContext;
import com.dwurdy.straja.application.port.in.NpcRegistryUseCase;
import com.dwurdy.straja.domain.model.NpcRegistry;
import com.dwurdy.straja.domain.model.Result;
import java.util.List;
import java.util.Set;

/**
 * NPC registry administration: role assignment, naming, skin and removal are
 * persisted here; the entity adapter only reflects them onto live entities.
 * Authorization lives at the command boundary — every caller is behind
 * {@code /straja npc *}, which requires permission level 2 (op/console).
 * The {@link NpcRegistryUseCase} view exposes only the read/adopt operations
 * the entity lifecycle events need.
 */
public class NpcAdminService implements NpcRegistryUseCase {
    public static final String RECEPTIONIST = "receptionist";
    public static final String SECRETARY = "secretary";
    public static final String JAILER = "jailer";
    public static final String ARCHIVIST = "archivist";
    public static final String TRAINER = "trainer";
    public static final String RECRUITER = "recruiter";
    public static final Set<String> KNOWN_ROLES =
            Set.of(RECEPTIONIST, SECRETARY, JAILER, ARCHIVIST, TRAINER, RECRUITER);
    /** Deterministic order for guided setup (checklist display + batch spawn). */
    public static final List<String> ROLE_ORDER =
            List.of(RECEPTIONIST, RECRUITER, SECRETARY, TRAINER, JAILER, ARCHIVIST);

    private final StrajaContext ctx;

    public NpcAdminService(StrajaContext ctx) {
        this.ctx = ctx;
    }

    public Result register(String entityUuid, String role) {
        if (!KNOWN_ROLES.contains(role)) return Result.fail("unknown_role");
        NpcRegistry registry = ctx.npcs().read();
        NpcRegistry.Record record = new NpcRegistry.Record();
        record.entityUuid = entityUuid;
        record.role = role;
        record.createdAt = ctx.clock().nowMillis();
        registry.npcs.put(entityUuid, record);
        ctx.npcs().write(registry);
        return Result.pass();
    }

    public Result assignRole(String entityUuid, String role) {
        if (!KNOWN_ROLES.contains(role)) return Result.fail("unknown_role");
        NpcRegistry registry = ctx.npcs().read();
        NpcRegistry.Record record = registry.npcs.get(entityUuid);
        if (record == null) {
            record = new NpcRegistry.Record();
            record.entityUuid = entityUuid;
            record.createdAt = ctx.clock().nowMillis();
            registry.npcs.put(entityUuid, record);
        }
        record.role = role;
        ctx.npcs().write(registry);
        return Result.pass();
    }

    public Result setName(String entityUuid, String name) {
        NpcRegistry registry = ctx.npcs().read();
        NpcRegistry.Record record = registry.npcs.get(entityUuid);
        if (record == null) return Result.fail("unknown_npc");
        record.displayName = name;
        ctx.npcs().write(registry);
        return Result.pass();
    }

    public Result setSkin(String entityUuid, String skin) {
        NpcRegistry registry = ctx.npcs().read();
        NpcRegistry.Record record = registry.npcs.get(entityUuid);
        if (record == null) return Result.fail("unknown_npc");
        record.skin = skin;
        ctx.npcs().write(registry);
        return Result.pass();
    }

    public Result remove(String entityUuid) {
        NpcRegistry registry = ctx.npcs().read();
        if (registry.npcs.remove(entityUuid) == null) return Result.fail("unknown_npc");
        ctx.npcs().write(registry);
        return Result.pass();
    }

    public List<NpcRegistry.Record> list() {
        return List.copyOf(ctx.npcs().read().npcs.values());
    }

    @Override
    public Registration registration(String entityUuid) {
        NpcRegistry.Record record = ctx.npcs().read().npcs.get(entityUuid);
        if (record == null) return null;
        return new Registration(record.role, record.skin, record.displayName);
    }

    @Override
    public void adopt(String entityUuid, String roleId) {
        if (ctx.npcs().read().npcs.containsKey(entityUuid)) return;
        register(entityUuid, roleId);
    }
}
