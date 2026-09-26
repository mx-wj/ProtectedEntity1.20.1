package com.mx_wj.protectedentity.entity;

import com.mx_wj.protectedentity.ProtectedEntityMod;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class TrackingEgg extends ThrowableItemProjectile {
    private static final int TRACKING_TICKS = 80;
    private static final int MAX_AGE = 100;
    private static final double SPEED = 0.7D;
    private UUID lockedTarget;
    private int age;

    public TrackingEgg(EntityType<? extends TrackingEgg> type, Level level) {
        super(type, level);
    }

    public TrackingEgg(Level level, ProtectedEntity owner, LivingEntity target, int index, int count) {
        this(ProtectedEntityMod.TRACKING_EGG.get(), level);
        this.setOwner(owner);
        double angle = 2.0D * Math.PI * index / count;
        this.setPos(owner.getX() + Math.cos(angle) * 0.35D,
                owner.getEyeY() - 0.15D + Math.sin(angle * 2.0D) * 0.12D,
                owner.getZ() + Math.sin(angle) * 0.35D);
        this.lockedTarget = target.getUUID();
        this.steer(target);
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            if (this.age++ >= MAX_AGE) {
                this.discard();
                return;
            }
            if (this.age <= TRACKING_TICKS && this.level() instanceof ServerLevel serverLevel) {
                Entity target = this.lockedTarget == null ? null : serverLevel.getEntity(this.lockedTarget);
                if (target instanceof LivingEntity livingTarget && livingTarget.isAlive()) {
                    this.steer(livingTarget);
                }
            }
        }
        super.tick();
    }

    private void steer(LivingEntity target) {
        Vec3 direction = target.getBoundingBox().getCenter().subtract(this.position()).normalize();
        this.setDeltaMovement(direction.scale(SPEED));
        this.hasImpulse = true;
    }

    @Override
    protected float getGravity() {
        return 0.0F;
    }

    @Override
    protected Item getDefaultItem() {
        return Items.EGG;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!this.level().isClientSide && result.getEntity() instanceof LivingEntity target
                && target != this.getOwner()) {
            ProtectedEntityCombatAI.damage(target,
                    this.damageSources().thrown(this, this.getOwner()), 0.15F);
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!this.level().isClientSide) this.discard();
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
