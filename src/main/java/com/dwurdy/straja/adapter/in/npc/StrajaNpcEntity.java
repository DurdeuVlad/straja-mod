package com.dwurdy.straja.adapter.in.npc;

import com.dwurdy.straja.StrajaMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Native Straja NPC. Server-authoritative: the role is an explicit persistent
 * field, never derived from the display name or position. All interactions are
 * routed through {@link NpcInteractionService} which applies the same
 * permission checks as the command layer and presents role-specific clickable
 * chat actions when a player interacts.
 */
public class StrajaNpcEntity extends PathfinderMob {
    public static final String TYPE_NAME = "straja_npc";

    private static final EntityDataAccessor<String> ROLE_ID =
            SynchedEntityData.defineId(StrajaNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> SKIN =
            SynchedEntityData.defineId(StrajaNpcEntity.class, EntityDataSerializers.STRING);

    public StrajaNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoAi(true);
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ROLE_ID, "");
        builder.define(SKIN, "default");
    }

    public String getRoleId() {
        return this.entityData.get(ROLE_ID);
    }

    public void setRoleId(String roleId) {
        this.entityData.set(ROLE_ID, roleId == null ? "" : roleId);
    }

    public String getSkin() {
        return this.entityData.get(SKIN);
    }

    public void setSkin(String skin) {
        this.entityData.set(SKIN, skin == null ? "default" : skin);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("StrajaRole", getRoleId());
        tag.putString("StrajaSkin", getSkin());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setRoleId(tag.getString("StrajaRole"));
        setSkin(tag.getString("StrajaSkin"));
        setNoAi(true);
        setPersistenceRequired();
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        // The jailer role has its own damage rules handled by the civic layer;
        // generic NPCs are protected unless the service allows the damage.
        if (NpcInteractionService.jailerMayTakeDamage(this, source)) return false;
        return super.isInvulnerableTo(source);
    }

    @Override
    public boolean isInvulnerable() {
        // The jailer must be damageable for the assault lifecycle (damage/death
        // events → FineService); every other NPC role stays immune.
        return !NpcRoles.JAILER.equals(getRoleId());
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level() instanceof ServerLevel serverLevel
                && NpcInteractionService.onNpcHurt(this, source, amount)) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (level() instanceof ServerLevel serverLevel) {
            NpcInteractionService.interact(this, player, serverLevel);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ---- registration ----

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, StrajaMod.MOD_ID);

    public static final Supplier<EntityType<StrajaNpcEntity>> NPC = ENTITY_TYPES.register(
            TYPE_NAME,
            () -> EntityType.Builder.of(StrajaNpcEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.95f)
                    .build(TYPE_NAME));

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
        bus.addListener(net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent.class,
                event -> event.put(NPC.get(), StrajaNpcEntity.createAttributes().build()));
    }
}
