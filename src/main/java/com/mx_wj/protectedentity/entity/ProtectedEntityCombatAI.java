package com.mx_wj.protectedentity.entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

final class ProtectedEntityCombatAI {
    private static final int CHASE_TIMEOUT = 160;
    private static final double TARGET_RANGE = 35.0D;
    private static final MethodHandle ACTUALLY_HURT = findActuallyHurt();
    private final ProtectedEntity mob;
    private final TerrainRoutePlanner terrainPlanner = new TerrainRoutePlanner();
    private UUID avoidedTarget;
    private long avoidUntil;
    private long lastProgressTime;
    private long nextProgressCheck;
    private double lastChaseDistance;
    private long nextPathCheck;
    private boolean vanillaCanReach;
    private int attackCooldown;
    private int terrainCooldown;
    private int fireballCooldown;
    private int pearlCooldown;
    private int reboundCooldown;
    private int reboundTicks;
    private boolean rebounding;

    ProtectedEntityCombatAI(ProtectedEntity mob) {
        this.mob = mob;
        this.mob.getNavigation().setCanFloat(true);
    }

    void tick() {
        if (this.attackCooldown > 0) this.attackCooldown--;
        if (this.terrainCooldown > 0) this.terrainCooldown--;
        if (this.fireballCooldown > 0) this.fireballCooldown--;
        if (this.pearlCooldown > 0) this.pearlCooldown--;
        if (this.reboundCooldown > 0) this.reboundCooldown--;
        this.handleVoidBounce();
        this.walkOnWater();

        LivingEntity target = this.mob.getTarget();
        if (target != null && (!this.isValidTarget(target)
                || this.mob.distanceToSqr(target) > TARGET_RANGE * TARGET_RANGE * 2.0D)) {
            this.mob.setTarget(null);
            target = null;
        }
        if (target == null) {
            target = this.findTarget(null);
            if (target == null) return;
            this.mob.setTarget(target);
            this.startChase(target);
        }

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        double distance = this.mob.distanceToSqr(target);
        if (this.canStrike(target)) {
            this.mob.getNavigation().stop();
            this.lastProgressTime = this.mob.level().getGameTime();
            this.lastChaseDistance = this.distanceToTarget(target);
            this.nextProgressCheck = this.lastProgressTime + 10;
            if (this.attackCooldown == 0) {
                this.mob.swing(InteractionHand.MAIN_HAND);
                damage(target, this.mob.damageSources().mobAttack(this.mob), 0.10F);
                this.attackCooldown = 20;
            }
            return;
        }

        long now = this.mob.level().getGameTime();
        double currentDistance = this.distanceToTarget(target);
        if (now >= this.nextProgressCheck) {
            if (currentDistance < this.lastChaseDistance - 0.05D) {
                this.lastProgressTime = now;
            }
            this.lastChaseDistance = currentDistance;
            this.nextProgressCheck = now + 10;
        }
        if (now - this.lastProgressTime >= CHASE_TIMEOUT) {
            LivingEntity alternative = this.findTarget(target.getUUID());
            if (alternative != null) {
                this.avoidedTarget = target.getUUID();
                this.avoidUntil = now + CHASE_TIMEOUT;
                target = alternative;
                this.mob.setTarget(target);
                this.mob.getNavigation().stop();
            }
            this.startChase(target);
            distance = this.mob.distanceToSqr(target);
        }
        if (now >= this.nextPathCheck) {
            Path path = this.mob.getNavigation().createPath(target, 1);
            this.vanillaCanReach = path != null && path.canReach();
            if (path != null) {
                this.mob.getNavigation().moveTo(path, 1.5D);
            } else {
                this.mob.getNavigation().stop();
            }
            this.nextPathCheck = now + 10;
        }
        if (!this.vanillaCanReach && now - this.lastProgressTime >= 25
                && this.terrainCooldown == 0
                && ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)) {
            TerrainRoutePlanner.Step step = this.terrainPlanner.nextStep(this.mob, target);
            if (step != null) {
                this.followTerrainStep(step);
            }
            this.terrainCooldown = 5;
        }
        this.maybeThrowPearl(target, distance);
        this.maybeFireball(target, distance);
    }

    private void startChase(LivingEntity target) {
        this.lastProgressTime = this.mob.level().getGameTime();
        this.nextProgressCheck = this.lastProgressTime + 10;
        this.lastChaseDistance = this.distanceToTarget(target);
        this.nextPathCheck = this.lastProgressTime;
        this.vanillaCanReach = false;
    }

    private double distanceToTarget(LivingEntity target) {
        return this.mob.distanceTo(target);
    }

    private boolean canStrike(LivingEntity target) {
        return this.mob.getBoundingBox().inflate(2.0D, 1.0D, 2.0D)
                .intersects(target.getBoundingBox()) && this.mob.hasLineOfSight(target);
    }

    private boolean isValidTarget(LivingEntity target) {
        return target != this.mob && !(target instanceof ProtectedEntity) && target.isAlive()
                && target.level() == this.mob.level()
                && !(target instanceof Player player && (player.isCreative() || player.isSpectator()));
    }

    private LivingEntity findTarget(UUID excludedTarget) {
        long now = this.mob.level().getGameTime();
        AABB range = this.mob.getBoundingBox().inflate(TARGET_RANGE);
        LivingEntity preferred = this.mob.level().getEntitiesOfClass(LivingEntity.class, range,
                candidate -> this.isValidTarget(candidate)
                        && (excludedTarget == null || !candidate.getUUID().equals(excludedTarget))
                        && (now >= this.avoidUntil || !candidate.getUUID().equals(this.avoidedTarget)))
                .stream().min(Comparator.comparingDouble(this.mob::distanceToSqr)).orElse(null);
        if (preferred != null || excludedTarget != null) return preferred;
        return this.mob.level().getEntitiesOfClass(LivingEntity.class, range, this::isValidTarget)
                .stream().min(Comparator.comparingDouble(this.mob::distanceToSqr)).orElse(null);
    }

    private void followTerrainStep(TerrainRoutePlanner.Step step) {
        Level level = this.mob.level();
        BlockPos edit = step.editPosition();
        switch (step.action()) {
            case WALK -> this.mob.getNavigation().moveTo(step.destination().getX() + 0.5D,
                    step.destination().getY(), step.destination().getZ() + 0.5D, 1.5D);
            case DRAIN -> this.drainFluid(edit);
            case BREAK -> this.breakObstacle(edit);
            case PLACE -> {
                if (level.isLoaded(edit) && level.getBlockState(edit).canBeReplaced()
                        && level.getFluidState(edit).isEmpty()) {
                    level.setBlock(edit, Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
        }
        this.nextPathCheck = level.getGameTime()
                + (step.action() == TerrainRoutePlanner.Action.WALK ? 10 : 1);
    }

    private void drainFluid(BlockPos pos) {
        Level level = this.mob.level();
        if (!level.isLoaded(pos) || level.getFluidState(pos).isEmpty()) return;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BucketPickup pickup) {
            if (!pickup.pickupBlock(level, pos, state).isEmpty()) {
                pickup.getPickupSound(state).ifPresent(sound ->
                        level.playSound(null, pos, sound, SoundSource.BLOCKS, 1.0F, 1.0F));
            } else if (!level.getFluidState(pos).isSource()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        } else if (!level.getFluidState(pos).isSource()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void breakObstacle(BlockPos pos) {
        Level level = this.mob.level();
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.blocksMotion() && state.getDestroySpeed(level, pos) >= 0.0F
                && !state.hasBlockEntity()) {
            level.destroyBlock(pos, true, this.mob);
        }
    }

    private void walkOnWater() {
        BlockPos surface = BlockPos.containing(this.mob.getX(), this.mob.getY() - 0.1D, this.mob.getZ());
        if (this.mob.level().getFluidState(surface).is(FluidTags.WATER)
                && this.mob.getY() >= surface.getY() + 0.85D
                && this.mob.getDeltaMovement().y < 0.0D) {
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setPos(this.mob.getX(), surface.getY() + 1.0D, this.mob.getZ());
            this.mob.setDeltaMovement(movement.x, 0.0D, movement.z);
            this.mob.setOnGround(true);
            this.mob.resetFallDistance();
        }
    }

    private void handleVoidBounce() {
        if (!this.rebounding && this.reboundCooldown == 0
                && this.mob.getY() < this.mob.level().getMinBuildHeight()
                && this.mob.getDeltaMovement().y < -0.1D) {
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(movement.x, 1.45D, movement.z);
            this.mob.resetFallDistance();
            this.rebounding = true;
            this.reboundTicks = 0;
            this.reboundCooldown = 100;
        }
        if (!this.rebounding) return;
        if (this.mob.getDeltaMovement().y <= 0.0D) {
            this.rebounding = false;
            if (ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)
                    && this.mob.getRandom().nextBoolean()) {
                this.makePlatform();
            }
            return;
        }
        if (this.reboundTicks++ < 8) {
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(movement.x, movement.y + 0.04D, movement.z);
        }
        if (ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)) {
            BlockPos head = BlockPos.containing(this.mob.getX(),
                    this.mob.getY() + this.mob.getBbHeight() + 0.2D, this.mob.getZ());
            this.breakObstacle(head);
            this.breakObstacle(head.above());
        }
    }

    private void makePlatform() {
        Level level = this.mob.level();
        BlockPos center = this.mob.blockPosition().below();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos pos = center.offset(offsetX, 0, offsetZ);
                if (level.isLoaded(pos) && level.getBlockState(pos).canBeReplaced()
                        && level.getFluidState(pos).isEmpty()) {
                    level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
            }
        }
    }

    private void maybeThrowPearl(LivingEntity target, double distance) {
        if (this.pearlCooldown > 0 || distance < 64.0D || this.mob.getRandom().nextInt(90) != 0) return;
        ThrownEnderpearl pearl = new ThrownEnderpearl(this.mob.level(), this.mob);
        double landingX = target.getX() + this.mob.getRandom().nextDouble() * 4.0D - 2.0D;
        double landingZ = target.getZ() + this.mob.getRandom().nextDouble() * 4.0D - 2.0D;
        pearl.setPos(this.mob.getX(), this.mob.getEyeY() - 0.15D, this.mob.getZ());
        pearl.shoot(landingX - pearl.getX(), target.getY() + 0.5D - pearl.getY(),
                landingZ - pearl.getZ(), 1.7F, 0.0F);
        this.mob.level().addFreshEntity(pearl);
        this.pearlCooldown = 120;
    }

    private void maybeFireball(LivingEntity target, double distance) {
        if (this.fireballCooldown > 0 || distance < 25.0D || this.mob.getRandom().nextInt(65) != 0) return;
        TrackingFireball fireball = new TrackingFireball(this.mob.level(), this.mob, target);
        this.mob.level().addFreshEntity(fireball);
        this.fireballCooldown = 80;
    }

    static void damage(LivingEntity target, DamageSource source, float fraction) {
        if (target.isAlive() && Float.isFinite(target.getMaxHealth())) {
            float healthBefore = target.getHealth();
            try {
                ACTUALLY_HURT.invoke(target, source, target.getMaxHealth() * fraction);
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Throwable failure) {
                throw new IllegalStateException("Could not invoke LivingEntity.actuallyHurt", failure);
            }
            if (target.getHealth() < healthBefore && target.level() instanceof ServerLevel serverLevel) {
                serverLevel.broadcastDamageEvent(target, source);
            }
            if (target.isDeadOrDying()) {
                target.die(source);
            }
        }
    }

    private static MethodHandle findActuallyHurt() {
        try {
            return MethodHandles.lookup().unreflect(ObfuscationReflectionHelper.findMethod(
                    LivingEntity.class, "m_6475_", DamageSource.class, float.class));
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access LivingEntity.actuallyHurt", exception);
        }
    }
}
