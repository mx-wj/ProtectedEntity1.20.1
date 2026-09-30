package com.mx_wj.protectedentity.entity;

import com.mx_wj.protectedentity.health.HealthCalculator;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.level.Level;

/**
 * Named API used by renderers and other mod systems. Runtime instances are defined
 * from HiddenProtectedEntity bytecode by HiddenEntityFactory.
 */
public abstract class ProtectedEntity extends PathfinderMob {
    private static final double BASE_MOVEMENT_SPEED = 0.23D;
    // Mob.setSpeed also scales forward input, so matching a player's 0.1F requires its square root.
    static final double WALK_SPEED_MODIFIER = Math.sqrt(0.1F) / BASE_MOVEMENT_SPEED;

    protected ProtectedEntity(EntityType<? extends ProtectedEntity> type, Level level) {
        super(type, level);
        this.lookControl = new ProtectedLookControl(this);
        this.moveControl = new ProtectedMoveControl(this);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                double dx = ProtectedEntity.this.getX() - ProtectedEntity.this.xo;
                double dz = ProtectedEntity.this.getZ() - ProtectedEntity.this.zo;
                float desiredYaw = dx * dx + dz * dz > 2.5E-7D
                        ? ProtectedEntity.this.getYRot() : ProtectedEntity.this.getYHeadRot();
                ProtectedEntity.this.yBodyRot = Mth.approachDegrees(ProtectedEntity.this.yBodyRot,
                        desiredYaw, ProtectedLookControl.YAW_SPEED);
            }
        };
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
                .add(Attributes.MOVEMENT_SPEED, BASE_MOVEMENT_SPEED)
                .add(Attributes.FOLLOW_RANGE, 35.0D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D);
    }
}
