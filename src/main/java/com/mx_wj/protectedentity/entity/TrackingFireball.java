package com.mx_wj.protectedentity.entity;

import com.mx_wj.protectedentity.ProtectedEntityMod;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

public class TrackingFireball extends SmallFireball {
    private UUID lockedTarget;
    private int age;

    public TrackingFireball(EntityType<? extends TrackingFireball> type, Level level) {
        super(type, level);
    }

    public TrackingFireball(Level level, ProtectedEntity owner, LivingEntity target) {
        this(ProtectedEntityMod.TRACKING_FIREBALL.get(), level);
        this.setOwner(owner);
        this.setPos(owner.getX(), owner.getEyeY() - 0.15D, owner.getZ());
        this.lockedTarget = target.getUUID();
        this.steer(target);
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            if (this.age++ >= 100) {
                this.discard();
                return;
            }
            if (this.age <= 60 && this.level() instanceof ServerLevel serverLevel) {
                Entity target = this.lockedTarget == null ? null : serverLevel.getEntity(this.lockedTarget);
                if (target instanceof LivingEntity livingTarget && livingTarget.isAlive()) {
                    this.steer(livingTarget);
                }
            } else {
                this.xPower = 0.0D;
                this.yPower = 0.0D;
                this.zPower = 0.0D;
            }
        }
        super.tick();
    }

    private void steer(LivingEntity target) {
        Vec3 direction = target.getEyePosition().subtract(this.position()).normalize();
        this.setDeltaMovement(direction.scale(0.65D));
        this.xPower = direction.x * 0.04D;
        this.yPower = direction.y * 0.04D;
        this.zPower = direction.z * 0.04D;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        if (!this.level().isClientSide && result.getEntity() instanceof LivingEntity target
                && target != this.getOwner()) {
            ProtectedEntityCombatAI.damage(target,
                    this.damageSources().fireball(this, this.getOwner()), 0.05F);
        }
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult result) {
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.lockedTarget != null) tag.putUUID("LockedTarget", this.lockedTarget);
        tag.putInt("Age", this.age);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("LockedTarget")) this.lockedTarget = tag.getUUID("LockedTarget");
        this.age = tag.getInt("Age");
    }
}
