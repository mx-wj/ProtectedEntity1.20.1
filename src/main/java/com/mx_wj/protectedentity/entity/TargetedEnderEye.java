package com.mx_wj.protectedentity.entity;

import com.mx_wj.protectedentity.ProtectedEntityMod;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

public class TargetedEnderEye extends ThrowableItemProjectile {
    private static final int MAX_AGE = 80;
    private static final double SPEED = 1.1D;
    private UUID lockedTarget;
    private int age;

    public TargetedEnderEye(EntityType<? extends TargetedEnderEye> type, Level level) {
        super(type, level);
    }

    public TargetedEnderEye(Level level, ProtectedEntity owner, LivingEntity target) {
        this(ProtectedEntityMod.TARGETED_ENDER_EYE.get(), level);
        this.setOwner(owner);
        this.setPos(owner.getX(), owner.getEyeY() - 0.15D, owner.getZ());
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
            if (this.level() instanceof ServerLevel serverLevel) {
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
        return Items.ENDER_EYE;
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide || !result.getEntity().getUUID().equals(this.lockedTarget)
                || !(result.getEntity() instanceof LivingEntity target)
                || !(this.getOwner() instanceof ProtectedEntity owner) || !target.isAlive()) return;
        Vec3 towardOwner = owner.position().subtract(target.position()).multiply(1.0D, 0.0D, 1.0D);
        if (towardOwner.lengthSqr() < 0.01D) {
            towardOwner = target.getLookAngle().multiply(-1.0D, 0.0D, -1.0D);
        }
        double firstAngle = Math.atan2(towardOwner.z, towardOwner.x);
        for (double radius : new double[]{1.4D, 2.0D, 2.5D}) {
            for (int direction = 0; direction < 8; direction++) {
                double angle = firstAngle + direction * Math.PI / 4.0D;
                double x = target.getX() + Math.cos(angle) * radius;
                double z = target.getZ() + Math.sin(angle) * radius;
                for (int height = 0; height <= 1; height++) {
                    if (owner.randomTeleport(x, target.getY() + height, z, true)) {
                        owner.setDeltaMovement(Vec3.ZERO);
                        owner.resetFallDistance();
                        owner.level().playSound(null, owner.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                                SoundSource.HOSTILE, 1.0F, 1.0F);
                        owner.attackAfterTeleport(target);
                        return;
                    }
                }
            }
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
