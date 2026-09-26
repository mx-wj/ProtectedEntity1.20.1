package com.mx_wj.protectedentity.entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

final class ProtectedEntityCombatAI {
    private static final int CHASE_TIMEOUT = 240;
    private static final int ATTACK_INTERVAL = 10;
    private static final double MELEE_REACH = 2.0D;
    private static final int RAGE_PURSUIT_TIME = 200;
    private static final double RAGE_MIN_DISTANCE = 4.0D;
    private static final double RAGE_MELEE_REACH = 2.5D;
    private static final double RAGE_TERRAIN_SPEED_MULTIPLIER = 2.0D;
    private static final double RAGE_HIGH_JUMP_SPEED = 0.95D;
    private static final double RAGE_HIGH_JUMP_HEIGHT = 3.5D;
    private static final int RAGE_EGG_COUNT = 10;
    private static final int RAGE_EGG_INTERVAL = 40;
    private static final int RAGE_EYE_COOLDOWN = 60;
    private static final int RAGE_EYE_CHANCE = 12;
    private static final double SPRINT_DISTANCE = 20.0D;
    private static final int TERRAIN_RETRY_INTERVAL = 2;
    private static final int MAX_TERRAIN_EDITS_PER_TICK = 18;
    private static final double TERRAIN_MOVE_SPEED = 3.0D;
    private static final double TERRAIN_ASCENT_SPEED = 0.6D;
    private static final double TERRAIN_HIGH_JUMP_SPEED = 0.7D;
    private static final double TERRAIN_HIGH_JUMP_HEIGHT = 2.5D;
    private static final int HIGH_JUMP_MIN_TARGET_HEIGHT = 10;
    private static final int RESCUE_RADIUS = 6;
    private static final int RESCUE_VERTICAL_RANGE = 4;
    private static final int MAX_RESCUE_SEARCH_NODES = 512;
    private static final int MAX_RESCUE_BRIDGE_LENGTH = 10;
    private static final int MAX_RUNWAY_LOOKAHEAD = 6;
    private static final float LOW_HEALTH_THRESHOLD = 40.0F;
    private static final double TARGET_RANGE = 35.0D;
    private static final MethodHandle ACTUALLY_HURT = findActuallyHurt();
    private final ProtectedEntity mob;
    private final TerrainRoutePlanner terrainPlanner = new TerrainRoutePlanner();
    private UUID avoidedTarget;
    private UUID rageTarget;
    private long rageStartedAt;
    private boolean enraged;
    private long avoidUntil;
    private long lastProgressTime;
    private long nextProgressCheck;
    private double lastChaseDistance;
    private TerrainRoutePlanner.Step terrainStep;
    private long terrainStepStarted;
    private boolean terrainHighJump;
    private boolean terrainDescentReady;
    private BlockPos rescueLanding;
    private long rescueStarted;
    private int attackCooldown;
    private int terrainCooldown;
    private int terrainEditsThisTick;
    private int eggCooldown;
    private int eyeCooldown;
    private int reboundCooldown;
    private int reboundTicks;
    private boolean rebounding;
    private boolean lookingAtBlockThisTick;

    ProtectedEntityCombatAI(ProtectedEntity mob) {
        this.mob = mob;
        this.mob.getNavigation().setCanFloat(true);
    }

    void tick() {
        this.terrainEditsThisTick = 0;
        this.lookingAtBlockThisTick = false;
        if (this.attackCooldown > 0) this.attackCooldown--;
        if (this.terrainCooldown > 0) this.terrainCooldown--;
        if (this.eggCooldown > 0) this.eggCooldown--;
        if (this.eyeCooldown > 0) this.eyeCooldown--;
        if (this.reboundCooldown > 0) this.reboundCooldown--;
        LivingEntity target = this.mob.getTarget();
        if (target != null && (!this.isValidTarget(target)
                || this.mob.distanceToSqr(target) > TARGET_RANGE * TARGET_RANGE * 2.0D)) {
            this.mob.setTarget(null);
            this.resetTerrainStep(false);
            this.mob.getNavigation().stop();
            target = null;
        }
        if (target == null) {
            target = this.findTarget(null);
            if (target == null) {
                if (this.terrainStep != null || this.rescueLanding != null) {
                    this.resetTerrainStep(false);
                    this.mob.getNavigation().stop();
                }
                this.terrainPlanner.clearRoute();
                this.rescueLanding = null;
                this.updateRage(null);
                this.setChaseSprint(false);
                this.handleVoidBounce(false);
                this.walkOnWater();
                return;
            }
            this.mob.setTarget(target);
            this.startChase(target);
        }

        this.updateRage(target);
        this.handleVoidBounce(true);
        this.walkOnWater();
        if (this.handleFallRescue()) {
            this.setChaseSprint(false);
            if (!this.lookingAtBlockThisTick) {
                this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            }
            return;
        }

        if (!this.lookingAtBlockThisTick) {
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
        double distance = this.mob.distanceToSqr(target);
        this.maybeThrowEggs(target, distance);
        if (this.canStrike(target)) {
            this.setChaseSprint(false);
            if (this.terrainStep != null && !this.mob.onGround()
                    && this.terrainStep.destination().getY() != this.terrainStep.from().getY()) {
                this.navigateToTarget(target, this.mob.level().getGameTime());
            } else {
                this.resetTerrainStep(false);
                this.mob.getNavigation().stop();
            }
            this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
            this.lastProgressTime = this.mob.level().getGameTime();
            this.lastChaseDistance = this.distanceToTarget(target);
            this.nextProgressCheck = this.lastProgressTime + 10;
            if (this.attackCooldown == 0) this.performMeleeAttack(target);
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
        if (!this.enraged && now - this.lastProgressTime >= CHASE_TIMEOUT) {
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
        this.updateChaseSprint(distance);
        this.navigateToTarget(target, now);
        this.maybeThrowEye(target);
    }

    void attackAfterTeleport(LivingEntity target) {
        if (!this.isValidTarget(target) || target != this.mob.getTarget()) return;
        this.resetTerrainStep(false);
        this.terrainCooldown = 0;
        this.mob.getNavigation().stop();
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (this.canStrike(target)) this.performMeleeAttack(target);
    }

    private void performMeleeAttack(LivingEntity target) {
        this.mob.playAttackAnimation();
        this.mob.level().playSound(null, this.mob.blockPosition(),
                SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 1.0F,
                0.9F + this.mob.getRandom().nextFloat() * 0.2F);
        float fraction = target.getMaxHealth() <= LOW_HEALTH_THRESHOLD ? 0.35F : 0.20F;
        if (damage(target, this.mob.damageSources().mobAttack(this.mob), fraction)) {
            this.mob.onAttackHit(target);
        }
        target.knockback(0.4D, this.mob.getX() - target.getX(),
                this.mob.getZ() - target.getZ());
        this.attackCooldown = ATTACK_INTERVAL;
    }

    private void startChase(LivingEntity target) {
        if (!target.getUUID().equals(this.rageTarget)) this.resetRage(target);
        this.setChaseSprint(false);
        this.rescueLanding = null;
        this.resetTerrainStep(false);
        this.terrainCooldown = 0;
        this.mob.getNavigation().stop();
        this.lastProgressTime = this.mob.level().getGameTime();
        this.nextProgressCheck = this.lastProgressTime + 10;
        this.lastChaseDistance = this.distanceToTarget(target);
    }

    void onAttackedBy(DamageSource source) {
        if (source.getEntity() instanceof LivingEntity attacker
                && attacker != this.mob.getTarget() && this.isValidTarget(attacker)) {
            this.mob.setTarget(attacker);
            this.startChase(attacker);
            this.mob.getLookControl().setLookAt(attacker, 30.0F, 30.0F);
        }
    }

    private double distanceToTarget(LivingEntity target) {
        return this.mob.distanceTo(target);
    }

    void onAttackHit(LivingEntity target) {
        if (target == this.mob.getTarget()) this.resetRage(target);
    }

    private void resetRage(LivingEntity target) {
        this.rageTarget = target == null ? null : target.getUUID();
        this.rageStartedAt = this.mob.level().getGameTime();
        this.enraged = false;
    }

    private void updateRage(LivingEntity target) {
        if (target == null || !this.isValidTarget(target)
                || this.mob.distanceToSqr(target) > TARGET_RANGE * TARGET_RANGE * 2.0D) {
            if (this.rageTarget != null) this.resetRage(null);
            return;
        }
        if (!target.getUUID().equals(this.rageTarget)) this.resetRage(target);
        if (this.enraged) return;
        long now = this.mob.level().getGameTime();
        if (this.hitboxDistanceSquared(target) <= RAGE_MIN_DISTANCE * RAGE_MIN_DISTANCE) {
            this.rageStartedAt = now;
        } else if (now - this.rageStartedAt >= RAGE_PURSUIT_TIME) {
            this.enraged = true;
            this.terrainCooldown = 0;
            this.eggCooldown = 0;
        }
    }

    private int terrainEditLimit() {
        return this.enraged ? MAX_TERRAIN_EDITS_PER_TICK * 3 : MAX_TERRAIN_EDITS_PER_TICK;
    }

    private int terrainRetryInterval() {
        return this.enraged ? 1 : TERRAIN_RETRY_INTERVAL;
    }

    private double hitboxDistanceSquared(LivingEntity target) {
        AABB attackerBox = this.mob.getBoundingBox();
        AABB targetBox = target.getBoundingBox();
        double gapX = Math.max(0.0D, Math.max(targetBox.minX - attackerBox.maxX,
                attackerBox.minX - targetBox.maxX));
        double gapY = Math.max(0.0D, Math.max(targetBox.minY - attackerBox.maxY,
                attackerBox.minY - targetBox.maxY));
        double gapZ = Math.max(0.0D, Math.max(targetBox.minZ - attackerBox.maxZ,
                attackerBox.minZ - targetBox.maxZ));
        return gapX * gapX + gapY * gapY + gapZ * gapZ;
    }

    private boolean canStrike(LivingEntity target) {
        double reach = this.enraged ? RAGE_MELEE_REACH : MELEE_REACH;
        if (this.hitboxDistanceSquared(target) > reach * reach) return false;
        AABB targetBox = target.getBoundingBox();
        Vec3 eyes = this.mob.getEyePosition();
        Vec3 hitPoint = new Vec3(Math.max(targetBox.minX, Math.min(eyes.x, targetBox.maxX)),
                Math.max(targetBox.minY, Math.min(eyes.y, targetBox.maxY)),
                Math.max(targetBox.minZ, Math.min(eyes.z, targetBox.maxZ)));
        return this.mob.level().clip(new ClipContext(eyes, hitPoint,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob)).getType() == HitResult.Type.MISS;
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

    private void navigateToTarget(LivingEntity target, long now) {
        this.mob.getNavigation().stop();
        if (!this.rebounding && this.mob.onGround() && this.terrainPlanner.canWalkDirectlyTo(this.mob, target)) {
            if (this.terrainStep != null) this.holdTerrainPosition();
            this.terrainPlanner.clearRoute();
            this.resetTerrainStep(true);
            this.mob.getMoveControl().setWantedPosition(target.getX(), this.mob.getY(), target.getZ(),
                    this.mob.isSprinting() ? TERRAIN_MOVE_SPEED : 1.0D);
            return;
        }
        boolean canModifyTerrain = ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob);
        if (this.terrainStep != null) {
            if (this.rebounding || now - this.terrainStepStarted > 60
                    || this.horizontalDistanceTo(this.terrainStep.from()) > 6.25D
                    || this.mob.getY() < Math.min(this.terrainStep.from().getY(),
                            this.terrainStep.destination().getY()) - 0.75D
                    || !canModifyTerrain && !this.terrainStep.edits().isEmpty()) {
                this.resetTerrainStep(false);
            } else {
                this.followTerrainStep(target);
                if (this.terrainStep != null) {
                    this.prepareRunway(target, canModifyTerrain);
                    return;
                }
            }
        }
        if (this.rebounding || !this.mob.onGround() || this.terrainCooldown > 0) return;
        this.terrainStep = this.terrainPlanner.nextStep(this.mob, target, canModifyTerrain, this.enraged);
        this.terrainCooldown = this.terrainStep == null ? this.terrainRetryInterval() : 0;
        if (this.terrainStep != null) {
            this.terrainStepStarted = now;
            this.followTerrainStep(target);
            this.prepareRunway(target, canModifyTerrain);
        } else {
            this.holdTerrainPosition();
        }
    }

    private void followTerrainStep(LivingEntity target) {
        Level level = this.mob.level();
        TerrainRoutePlanner.Step step = this.terrainStep;
        this.mob.getNavigation().stop();
        if (step.destination().getY() < step.from().getY() && !this.terrainDescentReady) {
            if (this.mob.onGround() && this.horizontalDistanceTo(step.from()) > 0.0225D) {
                this.moveToTerrainPosition(step.from(), true);
                return;
            }
            this.terrainDescentReady = true;
        }
        for (TerrainRoutePlanner.Edit edit : step.edits()) {
            if (this.terrainEditsThisTick >= this.terrainEditLimit()) return;
            BlockPos pos = edit.position();
            if (!TerrainRoutePlanner.canEdit(level, pos)) {
                this.resetTerrainStep(false);
                return;
            }
            BlockState state = level.getBlockState(pos);
            switch (edit.action()) {
                case BREAK -> {
                    if (state.getCollisionShape(level, pos).isEmpty()) continue;
                    if (state.getDestroySpeed(level, pos) < 0.0F || state.hasBlockEntity()) {
                        this.resetTerrainStep(false);
                        return;
                    }
                    this.holdTerrainPosition();
                    if (!level.destroyBlock(pos, true, this.mob)) {
                        this.resetTerrainStep(false);
                        return;
                    }
                    this.terrainEditsThisTick++;
                    this.widenTerrainOpening(step, pos);
                }
                case DRAIN -> {
                    if (state.getFluidState().isEmpty()) continue;
                    this.holdTerrainPosition();
                    this.drainFluid(pos);
                    if (!level.getFluidState(pos).isEmpty()) return;
                    this.terrainEditsThisTick++;
                }
                case PLACE -> {
                    if (TerrainRoutePlanner.supports(level, pos, state)) continue;
                    if (!state.canBeReplaced() || state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
                        this.resetTerrainStep(false);
                        return;
                    }
                    this.lookAtBlock(pos);
                    if (step.destination().getY() > step.from().getY()) {
                        this.moveToTerrainPosition(step.from(), true);
                        if (this.horizontalDistanceTo(step.from()) > 0.0225D) return;
                        if (this.mob.onGround()) {
                            boolean highJumpNeeded = this.enraged
                                    || TerrainRoutePlanner.targetGroundPosition(target).getY() - step.from().getY()
                                    >= HIGH_JUMP_MIN_TARGET_HEIGHT;
                            this.terrainHighJump = highJumpNeeded && this.hasHighJumpClearance(step.from());
                            if (this.enraged && !this.terrainHighJump) {
                                this.resetTerrainStep(false);
                                return;
                            }
                        }
                        double requiredHeight = this.enraged ? RAGE_HIGH_JUMP_HEIGHT
                                : this.terrainHighJump ? TERRAIN_HIGH_JUMP_HEIGHT : 1.1D;
                        if (this.mob.getY() < pos.getY() + requiredHeight || this.mob.getDeltaMovement().y > 0.0D) {
                            if (this.mob.onGround()) this.boostTerrainClimb(this.enraged
                                    ? RAGE_HIGH_JUMP_SPEED : this.terrainHighJump
                                    ? TERRAIN_HIGH_JUMP_SPEED : TERRAIN_ASCENT_SPEED);
                            return;
                        }
                    } else {
                        this.holdTerrainPosition();
                    }
                    AABB blockBox = new AABB(pos);
                    if (this.mob.getBoundingBox().inflate(0.0625D).intersects(blockBox)) {
                        this.moveToTerrainPosition(step.from(), true);
                        return;
                    }
                    if (!level.getEntitiesOfClass(LivingEntity.class, blockBox, LivingEntity::isAlive).isEmpty()) return;
                    if (!level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3)) {
                        this.resetTerrainStep(false);
                        return;
                    }
                    this.terrainEditsThisTick++;
                }
            }
        }
        BlockPos destination = step.destination();
        BlockPos support = destination.below();
        if (!TerrainRoutePlanner.canEdit(level, support)
                || !TerrainRoutePlanner.supports(level, support, level.getBlockState(support))) {
            this.holdTerrainPosition();
            this.resetTerrainStep(false);
            return;
        }
        boolean reachedHorizontally = this.horizontalDistanceTo(destination) < 0.09D;
        if (this.mob.onGround() && reachedHorizontally
                && Math.abs(this.mob.getY() - destination.getY()) < 0.25D) {
            this.resetTerrainStep(true);
            return;
        }
        double halfWidth = this.mob.getBbWidth() / 2.0D;
        AABB destinationBox = new AABB(destination.getX() + 0.5D - halfWidth,
                destination.getY() + 0.01D, destination.getZ() + 0.5D - halfWidth,
                destination.getX() + 0.5D + halfWidth, destination.getY() + this.mob.getBbHeight(),
                destination.getZ() + 0.5D + halfWidth);
        if (level.getBlockCollisions(this.mob, destinationBox).iterator().hasNext()) {
            this.resetTerrainStep(false);
            return;
        }
        if (destination.getY() <= step.from().getY()
                && !this.mob.onGround() && this.horizontalDistanceTo(destination) < 0.09D
                && this.mob.getY() >= destination.getY()) {
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(movement.x, Math.min(movement.y, -0.2D), movement.z);
            this.mob.hasImpulse = true;
        }
        this.moveToTerrainPosition(destination, step.isPillar());
    }

    private double horizontalDistanceTo(BlockPos pos) {
        double offsetX = pos.getX() + 0.5D - this.mob.getX();
        double offsetZ = pos.getZ() + 0.5D - this.mob.getZ();
        return offsetX * offsetX + offsetZ * offsetZ;
    }

    private void widenTerrainOpening(TerrainRoutePlanner.Step step, BlockPos center) {
        Level level = this.mob.level();
        int directionX = step.destination().getX() - step.from().getX();
        int directionZ = step.destination().getZ() - step.from().getZ();
        boolean horizontalPlane = directionX == 0 && directionZ == 0
                || center.equals(step.from().above(2));
        int bottom = Math.max(step.destination().getY(), center.getY() - 1);
        for (int offsetAcross = -1; offsetAcross <= 1; offsetAcross++) {
            for (int offsetUp = -1; offsetUp <= 1; offsetUp++) {
                if (this.terrainEditsThisTick >= this.terrainEditLimit()) return;
                BlockPos pos = horizontalPlane ? center.offset(offsetAcross, 0, offsetUp)
                        : directionX != 0 ? new BlockPos(center.getX(), bottom + offsetUp + 1, center.getZ() + offsetAcross)
                        : new BlockPos(center.getX() + offsetAcross, bottom + offsetUp + 1, center.getZ());
                if (pos.equals(center) || pos.equals(step.from().below()) || pos.equals(step.destination().below())
                        || !TerrainRoutePlanner.canEdit(level, pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.getCollisionShape(level, pos).isEmpty() || state.hasBlockEntity()
                        || state.getDestroySpeed(level, pos) < 0.0F) continue;
                if (level.destroyBlock(pos, true, this.mob)) this.terrainEditsThisTick++;
            }
        }
    }

    private void prepareRunway(LivingEntity target, boolean canModifyTerrain) {
        if (!canModifyTerrain || this.terrainStep == null
                || this.terrainEditsThisTick >= this.terrainEditLimit()) return;
        Level level = this.mob.level();
        BlockPos destination = this.terrainStep.destination();
        if (!TerrainRoutePlanner.supports(level, destination.below(), level.getBlockState(destination.below()))
                || !level.getBlockState(destination).getCollisionShape(level, destination).isEmpty()
                || !level.getBlockState(destination.above()).getCollisionShape(level, destination.above()).isEmpty()) return;
        double speed = Math.sqrt(this.mob.getDeltaMovement().horizontalDistanceSqr());
        int length = this.enraged ? Math.min(16, Math.max(12, 8 + (int)Math.ceil(speed * 8.0D)))
                : Math.min(MAX_RUNWAY_LOOKAHEAD, Math.max(3, 2 + (int)Math.ceil(speed * 6.0D)));
        for (BlockPos support : this.terrainPlanner.previewRunway(this.mob, target, this.terrainStep, length)) {
            if (this.terrainEditsThisTick >= this.terrainEditLimit()) return;
            if (TerrainRoutePlanner.supports(level, support, level.getBlockState(support))) continue;
            if (!this.canPlaceScaffold(support)
                    || !level.setBlock(support, Blocks.COBBLESTONE.defaultBlockState(), 3)) return;
            this.lookAtBlock(support);
            this.terrainEditsThisTick++;
        }
    }

    private void lookAtBlock(BlockPos pos) {
        this.lookingAtBlockThisTick = true;
        this.mob.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D,
                pos.getZ() + 0.5D, 30.0F, 75.0F);
    }

    private boolean hasHighJumpClearance(BlockPos from) {
        double halfWidth = this.mob.getBbWidth() / 2.0D;
        AABB jumpPath = new AABB(from.getX() + 0.5D - halfWidth, from.getY(),
                from.getZ() + 0.5D - halfWidth, from.getX() + 0.5D + halfWidth,
                from.getY() + (this.enraged ? RAGE_HIGH_JUMP_HEIGHT : TERRAIN_HIGH_JUMP_HEIGHT)
                        + this.mob.getBbHeight(),
                from.getZ() + 0.5D + halfWidth);
        return !this.mob.level().getBlockCollisions(this.mob, jumpPath).iterator().hasNext();
    }

    private void moveToTerrainPosition(BlockPos destination, boolean vertical) {
        double horizontalDistance = this.horizontalDistanceTo(destination);
        if (horizontalDistance < 0.01D) {
            this.holdTerrainPosition();
        } else {
            double speed = this.mob.isSprinting() ? TERRAIN_MOVE_SPEED : 1.0D;
            if (this.enraged) speed *= RAGE_TERRAIN_SPEED_MULTIPLIER;
            this.mob.getMoveControl().setWantedPosition(destination.getX() + 0.5D,
                    vertical ? this.mob.getY() : destination.getY(), destination.getZ() + 0.5D,
                    Math.min(speed, Math.sqrt(horizontalDistance) * 2.0D));
        }
        if (!vertical && this.mob.onGround() && destination.getY() > this.mob.getY() + 0.5D
                && horizontalDistance < 2.0D) {
            if (this.enraged) {
                if (this.terrainStep == null || !this.hasHighJumpClearance(this.terrainStep.from())) {
                    this.resetTerrainStep(false);
                    return;
                }
                this.boostTerrainClimb(RAGE_HIGH_JUMP_SPEED);
            } else {
                this.boostTerrainClimb(TERRAIN_ASCENT_SPEED);
            }
        }
        if (!vertical && !this.mob.onGround() && this.terrainStep != null
                && (destination.getY() < this.terrainStep.from().getY()
                    || destination.getY() > this.terrainStep.from().getY() && this.mob.getY() >= destination.getY())
                && horizontalDistance >= 0.09D) {
            double speed = Math.min(this.enraged ? 0.5D : 0.35D, Math.sqrt(horizontalDistance) * 0.7D);
            Vec3 forward = new Vec3(destination.getX() + 0.5D - this.mob.getX(), 0.0D,
                    destination.getZ() + 0.5D - this.mob.getZ()).normalize().scale(speed);
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(forward.x, Math.min(movement.y, 0.08D), forward.z);
            this.mob.hasImpulse = true;
        }
    }

    private void boostTerrainClimb(double speed) {
        Vec3 movement = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(movement.x, speed, movement.z);
        this.mob.setOnGround(false);
        this.mob.hasImpulse = true;
    }

    private void holdTerrainPosition() {
        this.mob.getMoveControl().setWantedPosition(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0D);
        this.mob.setZza(0.0F);
        Vec3 movement = this.mob.getDeltaMovement();
        this.mob.setDeltaMovement(movement.x * 0.25D, movement.y, movement.z * 0.25D);
    }

    private void resetTerrainStep(boolean completed) {
        this.terrainStep = null;
        this.terrainHighJump = false;
        this.terrainDescentReady = false;
        if (!completed) {
            this.terrainPlanner.clearRoute();
            this.holdTerrainPosition();
        }
        this.terrainCooldown = completed ? 0 : this.terrainRetryInterval();
    }

    private void updateChaseSprint(double distanceSquared) {
        this.setChaseSprint(!this.rebounding && distanceSquared > SPRINT_DISTANCE * SPRINT_DISTANCE);
    }

    private void setChaseSprint(boolean sprinting) {
        if (this.mob.isSprinting() != sprinting) this.mob.setSprinting(sprinting);
    }

    private boolean handleFallRescue() {
        Level level = this.mob.level();
        if (this.mob.onGround() || this.rebounding || this.mob.isInWater()
                || !ForgeEventFactory.getMobGriefingEvent(level, this.mob)) {
            this.rescueLanding = null;
            return false;
        }
        if (this.rescueLanding != null) {
            if (level.getGameTime() - this.rescueStarted <= 20
                    && TerrainRoutePlanner.canEdit(level, this.rescueLanding)
                    && TerrainRoutePlanner.supports(level, this.rescueLanding, level.getBlockState(this.rescueLanding))
                    && this.mob.getY() >= this.rescueLanding.getY() + 0.9D) {
                this.steerToRescueLanding();
                return true;
            }
            this.rescueLanding = null;
        }
        Vec3 movement = this.mob.getDeltaMovement();
        if (this.isPlannedTerrainFlight(this.mob.getX() + movement.x, this.mob.getZ() + movement.z)) return false;
        double landingX = this.mob.getX() + Math.max(-1.5D, Math.min(1.5D, movement.x * 2.0D));
        double landingZ = this.mob.getZ() + Math.max(-1.5D, Math.min(1.5D, movement.z * 2.0D));
        BlockPos landing = this.predictRescueLanding(movement, landingX, landingZ);
        for (int depth = 0; depth < 3; depth++) {
            BlockPos below = landing.below(depth);
            if (TerrainRoutePlanner.canEdit(level, below)
                    && TerrainRoutePlanner.supports(level, below, level.getBlockState(below))) return false;
        }
        if (!this.extendRescueBridge(landing)) {
            BlockPos fallback = this.predictRescueLanding(movement, this.mob.getX(), this.mob.getZ());
            if (fallback.equals(landing) || !this.extendRescueBridge(fallback)) return false;
            landing = fallback;
        }
        this.resetTerrainStep(false);
        this.terrainCooldown = 0;
        this.rescueLanding = landing;
        this.rescueStarted = level.getGameTime();
        this.mob.getNavigation().stop();
        this.mob.resetFallDistance();
        this.steerToRescueLanding();
        return true;
    }

    private BlockPos predictRescueLanding(Vec3 movement, double landingX, double landingZ) {
        BlockPos horizontal = BlockPos.containing(landingX, this.mob.getY(), landingZ);
        double offsetX = horizontal.getX() + 0.5D - this.mob.getX();
        double offsetZ = horizontal.getZ() + 0.5D - this.mob.getZ();
        int steeringTicks = Math.min(8, 2 + (int)Math.ceil(Math.max(Math.abs(offsetX), Math.abs(offsetZ)) / 0.35D));
        double landingY = this.mob.getY();
        double fallingSpeed = Math.max(-0.6D, Math.min(0.0D, movement.y));
        for (int tick = 0; tick < steeringTicks; tick++) {
            landingY += fallingSpeed;
            fallingSpeed = Math.max(-0.6D, (fallingSpeed - 0.08D) * 0.98D);
        }
        return BlockPos.containing(landingX, landingY, landingZ).below();
    }

    private boolean isPlannedTerrainFlight(double nextX, double nextZ) {
        if (this.terrainStep == null) return false;
        BlockPos from = this.terrainStep.from();
        BlockPos destination = this.terrainStep.destination();
        if (nextX < Math.min(from.getX(), destination.getX()) + 0.05D
                || nextX > Math.max(from.getX(), destination.getX()) + 0.95D
                || nextZ < Math.min(from.getZ(), destination.getZ()) + 0.05D
                || nextZ > Math.max(from.getZ(), destination.getZ()) + 0.95D
                || this.mob.getY() < Math.min(from.getY(), destination.getY()) - 0.15D) return false;
        Level level = this.mob.level();
        return TerrainRoutePlanner.supports(level, destination.below(), level.getBlockState(destination.below()))
                || destination.getY() > from.getY() && this.horizontalDistanceTo(from) < 0.25D
                && TerrainRoutePlanner.supports(level, from.below(), level.getBlockState(from.below()));
    }

    private boolean extendRescueBridge(BlockPos landing) {
        Level level = this.mob.level();
        double halfWidth = this.mob.getBbWidth() / 2.0D;
        AABB landingClearance = new AABB(landing.getX() + 0.5D - halfWidth, landing.getY() + 1.0D,
                landing.getZ() + 0.5D - halfWidth, landing.getX() + 0.5D + halfWidth,
                landing.getY() + 1.0D + this.mob.getBbHeight(), landing.getZ() + 0.5D + halfWidth);
        if (level.getBlockCollisions(this.mob, landingClearance).iterator().hasNext()) return false;
        Vec3 landingFeet = new Vec3(landing.getX() + 0.5D, landing.getY() + 1.0D, landing.getZ() + 0.5D);
        AABB flightClearance = this.mob.getBoundingBox().expandTowards(landingFeet.subtract(this.mob.position()))
                .deflate(0.001D);
        if (level.getBlockCollisions(this.mob, flightClearance).iterator().hasNext()) return false;
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> towardLanding = new HashMap<>();
        Map<BlockPos, Integer> lengths = new HashMap<>();
        open.add(landing);
        towardLanding.put(landing, null);
        lengths.put(landing, 0);
        BlockPos anchor = null;
        int searched = 0;
        while (!open.isEmpty() && searched++ < MAX_RESCUE_SEARCH_NODES) {
            BlockPos current = open.removeFirst();
            if (!TerrainRoutePlanner.canEdit(level, current)) continue;
            BlockState state = level.getBlockState(current);
            if (state.isFaceSturdy(level, current, Direction.UP)) {
                anchor = current;
                break;
            }
            if (!this.canPlaceScaffold(current) || flightClearance.intersects(new AABB(current))) continue;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (TerrainRoutePlanner.canEdit(level, neighbor)
                        && level.getBlockState(neighbor).isFaceSturdy(level, neighbor, direction.getOpposite())) {
                    anchor = current;
                    break;
                }
            }
            if (anchor != null) break;
            int length = lengths.get(current);
            if (length >= MAX_RESCUE_BRIDGE_LENGTH) continue;
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (Math.abs(neighbor.getX() - landing.getX()) + Math.abs(neighbor.getZ() - landing.getZ()) <= RESCUE_RADIUS
                        && Math.abs(neighbor.getY() - landing.getY()) <= RESCUE_VERTICAL_RANGE
                        && !towardLanding.containsKey(neighbor)) {
                    towardLanding.put(neighbor, current);
                    lengths.put(neighbor, length + 1);
                    open.addLast(neighbor);
                }
            }
        }
        if (anchor == null) return false;
        for (BlockPos pos = anchor; pos != null; pos = towardLanding.get(pos)) {
            if (level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) continue;
            if (this.terrainEditsThisTick >= this.terrainEditLimit() || !this.canPlaceScaffold(pos)
                    || flightClearance.intersects(new AABB(pos))
                    || !level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3)) return false;
            this.lookAtBlock(pos);
            this.terrainEditsThisTick++;
        }
        return TerrainRoutePlanner.supports(level, landing, level.getBlockState(landing));
    }

    private boolean canPlaceScaffold(BlockPos pos) {
        Level level = this.mob.level();
        if (!TerrainRoutePlanner.canEdit(level, pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.canBeReplaced() && !state.hasBlockEntity() && state.getFluidState().isEmpty()
                && !this.mob.getBoundingBox().intersects(new AABB(pos))
                && level.getEntitiesOfClass(LivingEntity.class, new AABB(pos), LivingEntity::isAlive).isEmpty();
    }

    private void steerToRescueLanding() {
        this.mob.getMoveControl().setWantedPosition(this.mob.getX(), this.mob.getY(), this.mob.getZ(), 0.0D);
        this.mob.setZza(0.0F);
        Vec3 movement = this.mob.getDeltaMovement();
        double offsetX = this.rescueLanding.getX() + 0.5D - this.mob.getX();
        double offsetZ = this.rescueLanding.getZ() + 0.5D - this.mob.getZ();
        this.mob.setDeltaMovement(Math.max(-0.35D, Math.min(0.35D, offsetX * 0.3D)),
                Math.max(-0.6D, Math.min(0.0D, movement.y)),
                Math.max(-0.35D, Math.min(0.35D, offsetZ * 0.3D)));
        this.mob.hasImpulse = true;
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

    private void handleVoidBounce(boolean hasTarget) {
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
            if (hasTarget && ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)
                    && this.mob.getRandom().nextBoolean()) {
                this.makePlatform();
            }
            return;
        }
        if (this.reboundTicks++ < 8) {
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(movement.x, movement.y + 0.04D, movement.z);
        }
        if (hasTarget && ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)) {
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
                        && level.getFluidState(pos).isEmpty()
                        && level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3)) {
                    this.lookAtBlock(pos);
                }
            }
        }
    }

    private void maybeThrowEye(LivingEntity target) {
        if (!this.enraged || this.eyeCooldown > 0
                || this.mob.getRandom().nextInt(RAGE_EYE_CHANCE) != 0) return;
        if (this.mob.level().addFreshEntity(new TargetedEnderEye(this.mob.level(), this.mob, target))) {
            this.eyeCooldown = RAGE_EYE_COOLDOWN;
        }
    }

    private void maybeThrowEggs(LivingEntity target, double distance) {
        if (this.eggCooldown > 0) return;
        if (!this.enraged && (distance < 25.0D || this.mob.getRandom().nextInt(65) != 0)) return;
        int count = this.enraged ? RAGE_EGG_COUNT : 1;
        for (int index = 0; index < count; index++) {
            this.mob.level().addFreshEntity(new TrackingEgg(this.mob.level(), this.mob, target, index, count));
        }
        this.eggCooldown = this.enraged ? RAGE_EGG_INTERVAL : 80;
    }

    static boolean damage(LivingEntity target, DamageSource source, float fraction) {
        if (target.isAlive() && Float.isFinite(target.getMaxHealth())) {
            float healthBefore = target.getHealth();
            float absorptionBefore = target.getAbsorptionAmount();
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
            boolean damaged = target.getHealth() < healthBefore
                    || target.getAbsorptionAmount() < absorptionBefore;
            if (target.isDeadOrDying()) {
                target.die(source);
            }
            return damaged;
        }
        return false;
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
