package com.dwurdy.straja.adapter.out.minecraft;

import com.dwurdy.straja.StrajaMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Native hit feedback for the service whip; permission remains in CustodyService. */
public final class WhipItem extends Item {
    public static final float MAX_DAMAGE = 1.0F;
    public static final double KNOCKBACK_STRENGTH = 1.75D;
    public static final double ATTACK_SPEED_BONUS = 2.0D;
    private static final ResourceLocation ATTACK_SPEED_ID =
            ResourceLocation.fromNamespaceAndPath(StrajaMod.MOD_ID, "whip_attack_speed");

    public WhipItem(Properties properties) {
        super(properties);
    }

    public static Properties properties() {
        return new Properties().stacksTo(1).rarity(Rarity.UNCOMMON)
                .attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_SPEED,
                                new AttributeModifier(ATTACK_SPEED_ID, ATTACK_SPEED_BONUS,
                                        AttributeModifier.Operation.ADD_VALUE),
                                EquipmentSlotGroup.MAINHAND)
                        .build());
    }

    /** Applies the whip's high-impact, low-damage hit feedback on the server. */
    public static void animateHit(net.minecraft.server.level.ServerPlayer attacker,
                                  LivingEntity target) {
        if (attacker.level().isClientSide()) return;

        applyKnockback(attacker, target);
        attacker.swing(InteractionHand.MAIN_HAND, true);
        attacker.level().playSound(null, target.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG,
                SoundSource.PLAYERS, 0.8F, 0.85F);
    }

    private static void applyKnockback(LivingEntity attacker, LivingEntity target) {
        double x = target.getX() - attacker.getX();
        double z = target.getZ() - attacker.getZ();
        if (x * x + z * z < 1.0E-6D) {
            float radians = attacker.getYRot() * ((float) Math.PI / 180F);
            x = -Math.sin(radians);
            z = Math.cos(radians);
        }
        target.knockback(KNOCKBACK_STRENGTH, x, z);
    }

    @Override
    public boolean hurtEnemy(ItemStack stack, LivingEntity target, LivingEntity attacker) {
        // Player targets are handled by StrajaEvents so converted lethal hits
        // get the same response and the effect is never applied twice. Mobs do
        // not enter that custody path, but still receive the whip's knockback.
        if (!target.level().isClientSide()
                && !(target instanceof net.minecraft.server.level.ServerPlayer)) {
            applyKnockback(attacker, target);
        }
        return true;
    }
}
