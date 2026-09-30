package com.mx_wj.protectedentity.entity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
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
    private static final double VOID_BOUNCE_SPEED = 3.0D;
    private static final int VOID_CLEARANCE_HEIGHT = 16;
    private static final double VOID_MAX_ROOF_SPEED = 12.0D;
    private static final float ACTION_YAW_TOLERANCE = 12.0F;
    private static final float ACTION_PITCH_TOLERANCE = 12.0F;
    private static final double MELEE_REACH = 2.5D;
    private static final int RAGE_PURSUIT_TIME = 200;
    private static final double RAGE_MIN_DISTANCE = 4.0D;
    private static final int RAGE_MOVEMENT_CHECK_INTERVAL = 10;
    private static final double RAGE_MOVING_TARGET_MIN_SPEED = 0.15D;
    private static final double RAGE_DISTANCE_TOLERANCE = 0.05D;
    private static final double RAGE_MELEE_REACH = 3.0D;
    private static final double RAGE_TERRAIN_SPEED_MULTIPLIER = 2.0D;
    private static final double RAGE_HIGH_JUMP_SPEED = 0.95D;
    private static final double RAGE_HIGH_JUMP_HEIGHT = 3.5D;
    private static final int RAGE_EGG_COUNT = 10;
    private static final int RAGE_EGG_INTERVAL = 40;
    private static final int RAGE_EYE_COOLDOWN = 60;
    private static final int RAGE_EYE_CHANCE = 12;
    private static final double SPRINT_DISTANCE = 20.0D;
    private static final double TERRAIN_MOVE_SPEED = 3.0D;
    private static final double TERRAIN_ASCENT_SPEED = 0.6D;
    private static final double TERRAIN_HIGH_JUMP_SPEED = 0.7D;
    private static final double TERRAIN_HIGH_JUMP_HEIGHT = 2.5D;
    private static final int HIGH_JUMP_MIN_TARGET_HEIGHT = 2;
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
    private long rageMovementCheckedAt;
    private Vec3 lastRageTargetPosition;
    private double lastRageDistance;
    private boolean rageTargetEscaping;
    private boolean enraged;
    private long avoidUntil;
    private long lastProgressTime;
    private long nextProgressCheck;
    private double lastChaseDistance;
    private TerrainRoutePlanner.Step terrainStep;
    private long terrainStepProgressAt;
    private double terrainStepBestDistance;
    private boolean terrainHighJump;
    private boolean terrainDescentReady;
    private BlockPos rescueLanding;
    private long rescueStarted;
    private int attackCooldown;
    private int terrainEditsThisTick;
    private int eggCooldown;
    private int eyeCooldown;
    private boolean rebounding;
    private boolean breakingRoof;
    private int roofExitY;
    private double roofAcceleration;
    private double roofSpeedLimit;
    private boolean lookingAtBlockThisTick;

    ProtectedEntityCombatAI(ProtectedEntity mob) {
        this.mob = mob;
        this.mob.getNavigation().setCanFloat(true);
    }

    void tick() {
        this.terrainEditsThisTick = 0;
        this.lookingAtBlockThisTick = false;
        if (this.attackCooldown > 0) this.attackCooldown--;
        if (this.eggCooldown > 0) this.eggCooldown--;
        if (this.eyeCooldown > 0) this.eyeCooldown--;
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
                if (this.handleVoidBounce(false)) return;
                this.walkOnWater();
                return;
            }
            this.mob.setTarget(target);
            this.startChase(target);
        }

        this.updateRage(target);
        if (this.handleVoidBounce(true)) return;
        this.walkOnWater();
        if (this.handleFallRescue()) {
            this.setChaseSprint(false);
            return;
        }

        double distance = this.mob.distanceToSqr(target);
        if (this.mob.onGround() && this.canStrike(target)) {
            this.setChaseSprint(false);
            this.resetTerrainStep(false);
            this.mob.getNavigation().stop();
            this.lookAtTarget(target);
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
        if (this.terrainStep != null || this.lookingAtBlockThisTick || this.terrainEditsThisTick > 0) return;
        this.lookAtTarget(target);
        if (!this.isFacing(target.getEyePosition())) return;
        this.maybeThrowEggs(target, distance);
        this.maybeThrowEye(target);
    }

    void attackAfterTeleport(LivingEntity target) {
        if (!this.isValidTarget(target) || target != this.mob.getTarget()) return;
        this.resetTerrainStep(false);
        this.rescueLanding = null;
        this.rebounding = false;
        this.breakingRoof = false;
        this.mob.getNavigation().stop();
        // The normal tick waits for stable footing and alignment before attacking.
    }

    private void performMeleeAttack(LivingEntity target) {
        if (this.attackCooldown > 0 || this.terrainStep != null || this.rescueLanding != null
                || this.rebounding || this.lookingAtBlockThisTick
                || this.terrainEditsThisTick > 0 || !this.mob.onGround()
                || !this.canStrike(target) || !this.isFacing(target.getEyePosition())) return;
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
        this.rageMovementCheckedAt = this.rageStartedAt;
        this.lastRageTargetPosition = target == null ? null : target.position();
        this.lastRageDistance = target == null ? 0.0D : this.distanceToTarget(target);
        this.rageTargetEscaping = false;
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
        boolean escaping = this.isTargetEscaping(target, now);
        if (escaping || this.hitboxDistanceSquared(target) <= RAGE_MIN_DISTANCE * RAGE_MIN_DISTANCE) {
            this.rageStartedAt = now;
        } else if (now - this.rageStartedAt >= RAGE_PURSUIT_TIME) {
            this.enraged = true;
            this.eggCooldown = 0;
        }
    }

    private boolean isTargetEscaping(LivingEntity target, long now) {
        long elapsed = now - this.rageMovementCheckedAt;
        if (elapsed >= RAGE_MOVEMENT_CHECK_INTERVAL) {
            // Server-side player velocity does not reliably describe walking; sample actual positions.
            Vec3 displacement = target.position().subtract(this.lastRageTargetPosition);
            double minimumTravel = RAGE_MOVING_TARGET_MIN_SPEED * elapsed;
            double distance = this.distanceToTarget(target);
            this.rageTargetEscaping = displacement.horizontalDistanceSqr() >= minimumTravel * minimumTravel
                    && distance >= this.lastRageDistance - RAGE_DISTANCE_TOLERANCE;
            this.lastRageTargetPosition = target.position();
            this.lastRageDistance = distance;
            this.rageMovementCheckedAt = now;
        }
        return this.rageTargetEscaping;
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
        Vec3 eyes = this.mob.getEyePosition();
        if (this.mob.level().clip(new ClipContext(eyes, target.getEyePosition(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.mob)).getType() == HitResult.Type.MISS) {
            return true;
        }
        AABB targetBox = target.getBoundingBox();
        Vec3 hitPoint = new Vec3(Math.max(targetBox.minX, Math.min(eyes.x, targetBox.maxX)),
                targetBox.getCenter().y,
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
        boolean canModifyTerrain = ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob);
        if (this.terrainStep != null) {
            double remaining = this.terrainStepDistance();
            if (remaining < this.terrainStepBestDistance - 0.05D) {
                this.terrainStepBestDistance = remaining;
                this.terrainStepProgressAt = now;
                this.lastProgressTime = now;
            }
            if (this.rebounding || now - this.terrainStepProgressAt > 60
                    || this.horizontalDistanceTo(this.terrainStep.from()) > 6.25D
                    || this.mob.getY() < Math.min(this.terrainStep.from().getY(),
                            this.terrainStep.destination().getY()) - 0.75D
                    || !canModifyTerrain && !this.terrainStep.edits().isEmpty()) {
                this.resetTerrainStep(false);
            } else {
                this.followTerrainStep(target);
                if (this.terrainEditsThisTick > 0) {
                    this.terrainStepProgressAt = now;
                    this.lastProgressTime = now;
                }
                if (this.terrainStep != null) {
                    this.prepareRunway(target, canModifyTerrain);
                    return;
                }
            }
        }
        if (this.rebounding || !this.mob.onGround()) return;
        // Finish the active waypoint before switching between terrain and direct pursuit.
        if (this.terrainPlanner.canWalkDirectlyTo(this.mob, target)) {
            this.terrainPlanner.clearRoute();
            this.terrainMoveControl().moveAlongTerrain(target.getX(), this.mob.getY(), target.getZ(),
                    this.mob.isSprinting() ? TERRAIN_MOVE_SPEED : ProtectedEntity.WALK_SPEED_MODIFIER);
            return;
        }
        this.terrainStep = this.terrainPlanner.nextStep(this.mob, target, canModifyTerrain, this.enraged);
        if (this.terrainStep != null) {
            this.terrainStepProgressAt = now;
            this.terrainStepBestDistance = this.terrainStepDistance();
            this.followTerrainStep(target);
            this.prepareRunway(target, canModifyTerrain);
        } else {
            this.holdTerrainPosition();
        }
    }

    private double terrainStepDistance() {
        return Math.sqrt(this.horizontalDistanceTo(this.terrainStep.destination()))
                + Math.abs(this.mob.getY()
                        - TerrainRoutePlanner.standingY(this.mob.level(), this.terrainStep.destination()));
    }

    private ProtectedMoveControl terrainMoveControl() {
        return (ProtectedMoveControl)this.mob.getMoveControl();
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
            BlockPos pos = edit.position();
            if (!TerrainRoutePlanner.canEdit(level, pos)) {
                this.resetTerrainStep(false);
                return;
            }
            BlockState state = level.getBlockState(pos);
            switch (edit.action()) {
                case BREAK -> {
                    if (state.getCollisionShape(level, pos).isEmpty()) continue;
                    if (!TerrainRoutePlanner.canBreak(level, pos, state)) {
                        this.resetTerrainStep(false);
                        return;
                    }
                    this.holdTerrainPosition();
                    if (!this.lookAtBlock(pos)) return;
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
                    if (!this.lookAtBlock(pos)) return;
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
                    if (!this.lookAtBlock(pos)) {
                        this.holdTerrainPosition();
                        return;
                    }
                    AABB blockBox = new AABB(pos);
                    // Only a block intersecting our body requires jumping out of its way.
                    // Forward/diagonal supports can be placed together before the climb.
                    if (this.mob.getBoundingBox().inflate(0.0625D).intersects(blockBox)) {
                        this.moveToTerrainPosition(step.from(), true);
                        if (this.horizontalDistanceTo(step.from()) > 0.0225D) return;
                        if (this.mob.onGround() && step.destination().getY() > step.from().getY()) {
                            boolean highJumpNeeded = this.enraged
                                    || TerrainRoutePlanner.targetGroundPosition(target).getY() - step.from().getY()
                                    >= HIGH_JUMP_MIN_TARGET_HEIGHT;
                            this.terrainHighJump = highJumpNeeded && this.hasHighJumpClearance(step.from());
                            if (this.enraged && !this.terrainHighJump) {
                                this.resetTerrainStep(false);
                                return;
                            }
                            this.boostTerrainClimb(this.enraged
                                    ? RAGE_HIGH_JUMP_SPEED : this.terrainHighJump
                                    ? TERRAIN_HIGH_JUMP_SPEED : TERRAIN_ASCENT_SPEED);
                        }
                        return;
                    }
                    this.holdTerrainPosition();
                    if (!level.getEntitiesOfClass(LivingEntity.class, blockBox, LivingEntity::isAlive).isEmpty()) return;
                    if (!level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3)) {
                        this.resetTerrainStep(false);
                        return;
                    }
                    this.terrainEditsThisTick++;
                }
            }
        }
        if (step.isPillar() && !this.mob.onGround()) {
            this.lookAtBlock(step.destination());
            step = this.extendPillarDuringJump(step, target);
            this.terrainStep = step;
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
        double destinationY = TerrainRoutePlanner.standingY(level, destination);
        if (this.mob.onGround() && reachedHorizontally
                && Math.abs(this.mob.getY() - destinationY) < 0.25D) {
            this.resetTerrainStep(true);
            return;
        }
        double halfWidth = this.mob.getBbWidth() / 2.0D;
        AABB destinationBox = new AABB(destination.getX() + 0.5D - halfWidth,
                destinationY + 0.01D, destination.getZ() + 0.5D - halfWidth,
                destination.getX() + 0.5D + halfWidth, destinationY + this.mob.getBbHeight(),
                destination.getZ() + 0.5D + halfWidth);
        if (level.getBlockCollisions(this.mob, destinationBox).iterator().hasNext()) {
            this.resetTerrainStep(false);
            return;
        }
        if (destination.getY() <= step.from().getY()
                && !this.mob.onGround() && this.horizontalDistanceTo(destination) < 0.09D
                && this.mob.getY() >= destinationY) {
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(movement.x, Math.min(movement.y, -0.2D), movement.z);
            this.mob.hasImpulse = true;
        }
        this.moveToTerrainPosition(destination, step.isPillar());
    }

    private TerrainRoutePlanner.Step extendPillarDuringJump(TerrainRoutePlanner.Step step, LivingEntity target) {
        Level level = this.mob.level();
        if (!ForgeEventFactory.getMobGriefingEvent(level, this.mob)) return step;
        int targetY = TerrainRoutePlanner.targetGroundPosition(target).getY();
        BlockPos destination = step.destination();
        // Extend the same pillar while ascending; do not wait for a landing between blocks.
        while (destination.getY() < targetY
                && this.mob.getBoundingBox().minY > destination.getY() + 1.0625D) {
            if (!this.canPlaceScaffold(destination) || !this.lookAtBlock(destination)) break;
            if (!level.setBlock(destination, Blocks.COBBLESTONE.defaultBlockState(), 3)) break;
            this.terrainEditsThisTick++;
            destination = destination.above();
        }
        return destination.equals(step.destination()) ? step
                // Original clearance edits are complete; replaying them could break the new pillar.
                : new TerrainRoutePlanner.Step(step.from(), destination, List.of());
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
                BlockPos pos = horizontalPlane ? center.offset(offsetAcross, 0, offsetUp)
                        : directionX != 0 ? new BlockPos(center.getX(), bottom + offsetUp + 1, center.getZ() + offsetAcross)
                        : new BlockPos(center.getX() + offsetAcross, bottom + offsetUp + 1, center.getZ());
                if (pos.equals(center) || pos.equals(step.from().below()) || pos.equals(step.destination().below())
                        || !TerrainRoutePlanner.canEdit(level, pos)) continue;
                BlockState state = level.getBlockState(pos);
                if (state.getCollisionShape(level, pos).isEmpty()
                        || !TerrainRoutePlanner.canBreak(level, pos, state)
                        || !this.isFacing(Vec3.atCenterOf(pos))) continue;
                if (level.destroyBlock(pos, true, this.mob)) this.terrainEditsThisTick++;
            }
        }
    }

    private void prepareRunway(LivingEntity target, boolean canModifyTerrain) {
        if (!canModifyTerrain || this.terrainStep == null
                || !this.mob.onGround()
                || this.lookingAtBlockThisTick) return;
        Level level = this.mob.level();
        BlockPos destination = this.terrainStep.destination();
        if (!TerrainRoutePlanner.supports(level, destination.below(), level.getBlockState(destination.below()))
                || !level.getBlockState(destination).getCollisionShape(level, destination).isEmpty()
                || !level.getBlockState(destination.above()).getCollisionShape(level, destination.above()).isEmpty()) return;
        double speed = Math.sqrt(this.mob.getDeltaMovement().horizontalDistanceSqr());
        int length = this.enraged ? Math.min(16, Math.max(12, 8 + (int)Math.ceil(speed * 8.0D)))
                : Math.min(MAX_RUNWAY_LOOKAHEAD, Math.max(3, 2 + (int)Math.ceil(speed * 6.0D)));
        for (BlockPos support : this.terrainPlanner.previewRunway(this.mob, target, this.terrainStep, length)) {
            if (TerrainRoutePlanner.supports(level, support, level.getBlockState(support))) continue;
            if (!this.canPlaceScaffold(support)) return;
            // Optional lookahead must never steal the current step's steering or stop it.
            if (!this.isFacing(Vec3.atCenterOf(support)) || !this.lookAtBlock(support)) return;
            if (!level.setBlock(support, Blocks.COBBLESTONE.defaultBlockState(), 3)) return;
            this.terrainEditsThisTick++;
        }
    }

    private boolean lookAtBlock(BlockPos pos) {
        this.lookingAtBlockThisTick = true;
        Vec3 point = Vec3.atCenterOf(pos);
        ((ProtectedLookControl)this.mob.getLookControl()).lookAtAction(point);
        return this.isFacing(point);
    }

    private void lookAtTarget(LivingEntity target) {
        if (!this.lookingAtBlockThisTick) {
            ((ProtectedLookControl)this.mob.getLookControl()).lookAtAction(target.getEyePosition());
        }
    }

    private boolean isFacing(Vec3 point) {
        Vec3 offset = point.subtract(this.mob.getEyePosition());
        double horizontal = offset.horizontalDistance();
        float yaw = (float)(Mth.atan2(offset.z, offset.x) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float)(-Mth.atan2(offset.y, horizontal) * 180.0D / Math.PI);
        return (horizontal < 1.0E-5D
                    || Math.abs(Mth.wrapDegrees(yaw - this.mob.getYHeadRot())) <= ACTION_YAW_TOLERANCE)
                && Math.abs(Mth.wrapDegrees(pitch - this.mob.getXRot())) <= ACTION_PITCH_TOLERANCE;
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
        double destinationY = TerrainRoutePlanner.standingY(this.mob.level(), destination);
        if (!this.lookingAtBlockThisTick && horizontalDistance >= 0.01D) {
            ((ProtectedLookControl)this.mob.getLookControl()).lookAtAction(new Vec3(
                    destination.getX() + 0.5D, this.mob.getEyeY(), destination.getZ() + 0.5D));
        }
        if (horizontalDistance < 0.01D) {
            this.holdTerrainPosition();
        } else {
            double speed = this.mob.isSprinting() ? TERRAIN_MOVE_SPEED : ProtectedEntity.WALK_SPEED_MODIFIER;
            if (this.enraged) speed *= RAGE_TERRAIN_SPEED_MULTIPLIER;
            this.terrainMoveControl().moveAlongTerrain(destination.getX() + 0.5D,
                    vertical ? this.mob.getY() : destinationY, destination.getZ() + 0.5D,
                    Math.min(speed, Math.sqrt(horizontalDistance) * 2.0D));
        }
        if (!vertical && this.mob.onGround() && destinationY > this.mob.getY() + this.mob.getStepHeight()
                && horizontalDistance < 2.0D
                && this.terrainMoveControl().isAligned(destination.getX() + 0.5D, destination.getZ() + 0.5D)) {
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
                    || destination.getY() > this.terrainStep.from().getY() && this.mob.getY() >= destinationY)
                && horizontalDistance >= 0.09D) {
            double speed = Math.min(this.enraged ? 0.5D : 0.35D, Math.sqrt(horizontalDistance) * 0.7D);
            Vec3 forward = new Vec3(destination.getX() + 0.5D - this.mob.getX(), 0.0D,
                    destination.getZ() + 0.5D - this.mob.getZ()).normalize().scale(speed);
            Vec3 movement = this.mob.getDeltaMovement();
            // Do not cut an ascent short as soon as the feet reach the ledge's height.
            this.mob.setDeltaMovement(forward.x,
                    destination.getY() < this.terrainStep.from().getY() ? Math.min(movement.y, 0.08D) : movement.y,
                    forward.z);
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
        this.terrainMoveControl().stopTerrainMovement();
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
    }

    private void updateChaseSprint(double distanceSquared) {
        this.setChaseSprint(!this.rebounding && distanceSquared > SPRINT_DISTANCE * SPRINT_DISTANCE);
    }

    private void setChaseSprint(boolean sprinting) {
        if (this.mob.isSprinting() != sprinting) this.mob.setSprinting(sprinting);
    }

    private boolean handleFallRescue() {
        Level level = this.mob.level();
        if (this.rebounding || this.mob.isInWater()
                || !ForgeEventFactory.getMobGriefingEvent(level, this.mob)) {
            this.rescueLanding = null;
            return false;
        }
        Vec3 movement = this.mob.getDeltaMovement();
        Vec3 nextPosition = this.mob.position().add(movement);
        // onGround describes this tick, and may still be true just before walking off an edge.
        if (this.hasSupportAtNextPosition(nextPosition)) {
            this.rescueLanding = null;
            return false;
        }
        if (this.rescueLanding != null) {
            if (level.getGameTime() - this.rescueStarted <= 20
                    && TerrainRoutePlanner.canEdit(level, this.rescueLanding)
                    && TerrainRoutePlanner.supports(level, this.rescueLanding, level.getBlockState(this.rescueLanding))
                    && nextPosition.y >= this.rescueLanding.getY() + 1.0D) {
                this.steerToRescueLanding();
                return true;
            }
            this.rescueLanding = null;
        }
        if (this.isPlannedTerrainFlight(nextPosition)) return false;
        BlockPos landing = this.predictRescueLanding(movement, nextPosition, nextPosition.x, nextPosition.z);
        for (int depth = 0; depth < 3; depth++) {
            BlockPos below = landing.below(depth);
            if (TerrainRoutePlanner.canEdit(level, below)
                    && TerrainRoutePlanner.supports(level, below, level.getBlockState(below))) return false;
        }
        if (!this.extendRescueBridge(landing)) {
            if (this.lookingAtBlockThisTick || this.terrainEditsThisTick > 0) return true;
            BlockPos fallback = this.predictRescueLanding(movement, nextPosition, this.mob.getX(), this.mob.getZ());
            if (fallback.equals(landing)) return false;
            if (!this.extendRescueBridge(fallback)) {
                return this.lookingAtBlockThisTick || this.terrainEditsThisTick > 0;
            }
            landing = fallback;
        }
        this.resetTerrainStep(false);
        this.rescueLanding = landing;
        this.rescueStarted = level.getGameTime();
        this.mob.getNavigation().stop();
        this.mob.resetFallDistance();
        this.steerToRescueLanding();
        return true;
    }

    private boolean hasSupportAtNextPosition(Vec3 nextPosition) {
        Vec3 offset = nextPosition.subtract(this.mob.position());
        AABB nextBox = this.mob.getBoundingBox().move(offset);
        // Include surfaces crossed during the next fall and normal path/slab step-downs.
        double highestSurface = this.mob.getY() + (this.mob.onGround() ? this.mob.getStepHeight() : 0.001D);
        AABB feet = new AABB(nextBox.minX + 0.001D, Math.min(nextPosition.y, this.mob.getY()) - 0.6D,
                nextBox.minZ + 0.001D, nextBox.maxX - 0.001D, highestSurface + 0.001D,
                nextBox.maxZ - 0.001D);
        for (var shape : this.mob.level().getBlockCollisions(this.mob, feet)) {
            if (shape.max(Direction.Axis.Y) <= highestSurface) return true;
        }
        return false;
    }

    private BlockPos predictRescueLanding(Vec3 movement, Vec3 nextPosition, double landingX, double landingZ) {
        BlockPos horizontal = BlockPos.containing(landingX, nextPosition.y, landingZ);
        double offsetX = horizontal.getX() + 0.5D - nextPosition.x;
        double offsetZ = horizontal.getZ() + 0.5D - nextPosition.z;
        int steeringTicks = Math.min(8, 2 + (int)Math.ceil(Math.max(Math.abs(offsetX), Math.abs(offsetZ)) / 0.35D));
        // First account for the full next displacement; braking only applies after rescue takes over.
        double landingY = nextPosition.y;
        double fallingSpeed = Math.max(-0.6D, Math.min(0.0D, movement.y));
        for (int tick = 0; tick < steeringTicks; tick++) {
            landingY += fallingSpeed;
            fallingSpeed = Math.max(-0.6D, (fallingSpeed - 0.08D) * 0.98D);
        }
        return BlockPos.containing(landingX, landingY, landingZ).below();
    }

    private boolean isPlannedTerrainFlight(Vec3 nextPosition) {
        if (this.terrainStep == null) return false;
        BlockPos from = this.terrainStep.from();
        BlockPos destination = this.terrainStep.destination();
        Level level = this.mob.level();
        if (nextPosition.x < Math.min(from.getX(), destination.getX()) + 0.05D
                || nextPosition.x > Math.max(from.getX(), destination.getX()) + 0.95D
                || nextPosition.z < Math.min(from.getZ(), destination.getZ()) + 0.05D
                || nextPosition.z > Math.max(from.getZ(), destination.getZ()) + 0.95D
                || nextPosition.y < Math.min(TerrainRoutePlanner.standingY(level, from),
                        TerrainRoutePlanner.standingY(level, destination)) - 0.15D) return false;
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
            if (!this.canPlaceScaffold(pos)
                    || flightClearance.intersects(new AABB(pos))) return false;
            if (!this.lookAtBlock(pos)) return false;
            if (!level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3)) return false;
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
        this.terrainMoveControl().stopTerrainMovement();
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
        if (!TerrainRoutePlanner.canEdit(level, pos)) return;
        BlockState state = level.getBlockState(pos);
        if (state.blocksMotion() && TerrainRoutePlanner.canBreak(level, pos, state)) {
            if (!this.lookAtBlock(pos)) return;
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

    private boolean handleVoidBounce(boolean hasTarget) {
        int minHeight = this.mob.level().getMinBuildHeight();
        if (!this.rebounding && this.mob.getY() <= minHeight) {
            this.resetTerrainStep(false);
            this.rescueLanding = null;
            this.mob.getNavigation().stop();
            this.terrainMoveControl().stopTerrainMovement();
            this.setChaseSprint(false);
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(movement.x, VOID_BOUNCE_SPEED, movement.z);
            this.mob.hasImpulse = true;
            this.mob.resetFallDistance();
            this.rebounding = true;
        }
        if (!this.rebounding) return false;

        if (!this.breakingRoof
                && this.mob.getY() <= minHeight
                && this.mob.getY() + this.mob.getDeltaMovement().y > minHeight) {
            int thickness = this.measureRoofThickness();
            this.roofAcceleration = Math.min(4.0D, 0.4D + thickness * 0.25D);
            this.roofSpeedLimit = Math.min(VOID_MAX_ROOF_SPEED, VOID_BOUNCE_SPEED + thickness * 0.6D);
            this.breakingRoof = true;
        }
        if (this.breakingRoof) {
            // The whole clearance is opened before the next physics step, with no edit-per-tick limit.
            if (ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)) this.clearRoofColumn();
            ((ProtectedLookControl)this.mob.getLookControl()).lookAtAction(
                    new Vec3(this.mob.getX(), this.mob.getEyeY() + 8.0D, this.mob.getZ()));
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setXxa(0.0F);
            this.mob.setZza(0.0F);
            this.mob.setDeltaMovement(0.0D,
                    Math.min(this.roofSpeedLimit, Math.max(VOID_BOUNCE_SPEED, movement.y + this.roofAcceleration)),
                    0.0D);
            this.mob.hasImpulse = true;
            this.mob.resetFallDistance();
            if (this.mob.getY() >= this.roofExitY + 0.5D) this.breakingRoof = false;
            return true;
        }

        if (ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)) {
            BlockPos head = BlockPos.containing(this.mob.getX(),
                    this.mob.getY() + this.mob.getBbHeight() + 0.2D, this.mob.getZ());
            this.breakObstacle(head);
            this.breakObstacle(head.above());
        }
        if (this.mob.getDeltaMovement().y <= 0.0D
                && this.mob.getY() < minHeight + VOID_CLEARANCE_HEIGHT) {
            Vec3 movement = this.mob.getDeltaMovement();
            this.mob.setDeltaMovement(movement.x, VOID_BOUNCE_SPEED, movement.z);
            this.mob.hasImpulse = true;
            this.mob.resetFallDistance();
        }
        if (this.mob.getDeltaMovement().y <= 0.0D) {
            this.rebounding = false;
            this.breakingRoof = false;
            if (hasTarget && ForgeEventFactory.getMobGriefingEvent(this.mob.level(), this.mob)
                    && this.mob.getRandom().nextBoolean()) {
                return this.makePlatform();
            }
            return false;
        }
        return true;
    }

    private int measureRoofThickness() {
        Level level = this.mob.level();
        int minHeight = level.getMinBuildHeight();
        int firstSolid = Integer.MIN_VALUE;
        int clearLayers = 0;
        int clearance = (int)Math.ceil(this.mob.getBbHeight());
        for (int y = minHeight; y < level.getMaxBuildHeight(); y++) {
            if (this.roofLayerBlocksMovement(y)) {
                if (firstSolid == Integer.MIN_VALUE) firstSolid = y;
                clearLayers = 0;
            } else if (firstSolid != Integer.MIN_VALUE && ++clearLayers >= clearance) {
                this.roofExitY = y - clearLayers + 1;
                return this.roofExitY - firstSolid;
            }
            if (firstSolid == Integer.MIN_VALUE && y - minHeight >= VOID_CLEARANCE_HEIGHT) break;
        }
        this.roofExitY = firstSolid == Integer.MIN_VALUE ? minHeight : level.getMaxBuildHeight();
        return firstSolid == Integer.MIN_VALUE ? 0 : this.roofExitY - firstSolid;
    }

    private boolean roofLayerBlocksMovement(int y) {
        Level level = this.mob.level();
        RoofFootprint footprint = this.roofFootprint();
        for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                BlockPos pos = new BlockPos(x, y, z);
                if (level.isLoaded(pos)
                        && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return true;
            }
        }
        return false;
    }

    private void clearRoofColumn() {
        Level level = this.mob.level();
        RoofFootprint footprint = this.roofFootprint();
        int endY = Math.min(level.getMaxBuildHeight() - 1,
                this.roofExitY + (int)Math.ceil(this.mob.getBbHeight()));
        for (int y = Math.max(level.getMinBuildHeight(), Mth.floor(this.mob.getY())); y <= endY; y++) {
            for (int x = footprint.minX(); x <= footprint.maxX(); x++) {
                for (int z = footprint.minZ(); z <= footprint.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!TerrainRoutePlanner.canEdit(level, pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (!state.getCollisionShape(level, pos).isEmpty()
                            && TerrainRoutePlanner.canBreak(level, pos, state)) {
                        level.destroyBlock(pos, false, this.mob);
                    }
                }
            }
        }
    }

    private RoofFootprint roofFootprint() {
        AABB box = this.mob.getBoundingBox();
        int minX = Mth.floor(box.minX);
        int minZ = Mth.floor(box.minZ);
        // The mob is narrower than a block; extend a one-cell footprint to a 2x2 shaft.
        int maxX = Math.max(minX + 1, Mth.floor(box.maxX - 1.0E-7D));
        int maxZ = Math.max(minZ + 1, Mth.floor(box.maxZ - 1.0E-7D));
        return new RoofFootprint(minX, maxX, minZ, maxZ);
    }

    private record RoofFootprint(int minX, int maxX, int minZ, int maxZ) {}

    private boolean makePlatform() {
        Level level = this.mob.level();
        BlockPos center = this.mob.blockPosition().below();
        boolean placed = false;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos pos = center.offset(offsetX, 0, offsetZ);
                if (!this.canPlaceScaffold(pos)) continue;
                if (level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3)) {
                    this.terrainEditsThisTick++;
                    placed = true;
                }
            }
        }
        return placed;
    }

    private void maybeThrowEye(LivingEntity target) {
        if (this.lookingAtBlockThisTick || this.terrainStep != null || !this.isFacing(target.getEyePosition())) return;
        if (!this.enraged || this.eyeCooldown > 0
                || this.mob.getRandom().nextInt(RAGE_EYE_CHANCE) != 0) return;
        if (this.mob.level().addFreshEntity(new TargetedEnderEye(this.mob.level(), this.mob, target))) {
            this.eyeCooldown = RAGE_EYE_COOLDOWN;
        }
    }

    private void maybeThrowEggs(LivingEntity target, double distance) {
        if (this.lookingAtBlockThisTick || this.terrainStep != null || !this.isFacing(target.getEyePosition())) return;
        if (this.eggCooldown > 0) return;
        boolean rageVolley = this.enraged && !(target instanceof Player);
        if (!rageVolley && (distance < 25.0D || this.mob.getRandom().nextInt(65) != 0)) return;
        int count = rageVolley ? RAGE_EGG_COUNT : 1;
        for (int index = 0; index < count; index++) {
            this.mob.level().addFreshEntity(new TrackingEgg(this.mob.level(), this.mob, target, index, count));
        }
        this.eggCooldown = rageVolley ? RAGE_EGG_INTERVAL : 80;
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
