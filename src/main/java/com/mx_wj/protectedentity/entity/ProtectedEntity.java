package com.mx_wj.protectedentity.entity;

import com.mx_wj.protectedentity.health.HealthCalculator;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * Named API used by renderers and other mod systems. Runtime instances are defined
 * from HiddenProtectedEntity bytecode by HiddenEntityFactory.
 */
public abstract class ProtectedEntity extends PathfinderMob {
    protected ProtectedEntity(EntityType<? extends ProtectedEntity> type, Level level) {
        super(type, level);
    }

    public abstract String getInternalId();

    public abstract void applyClientHealthSync(String internalId, float health);

    public abstract void playAttackAnimation();

    public abstract void attackAfterTeleport(LivingEntity target);

    abstract void onAttackHit(LivingEntity target);

    public abstract float getVisibleAttackAnimation(float partialTicks);

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, HealthCalculator.MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, 0.23D)
                .add(Attributes.FOLLOW_RANGE, 35.0D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D);
    }
}
