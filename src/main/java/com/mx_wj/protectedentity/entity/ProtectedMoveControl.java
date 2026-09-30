package com.mx_wj.protectedentity.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.phys.Vec3;

/** Keeps terrain steering independent of vanilla's automatic jump state. */
final class ProtectedMoveControl extends MoveControl {
    private static final float MOVE_YAW_TOLERANCE = 15.0F;
    private boolean terrainMovement;

    ProtectedMoveControl(ProtectedEntity mob) {
        super(mob);
    }

    @Override
    public void setWantedPosition(double x, double y, double z, double speed) {
        this.terrainMovement = false;
        super.setWantedPosition(x, y, z, speed);
    }

    void moveAlongTerrain(double x, double y, double z, double speed) {
        super.setWantedPosition(x, y, z, speed);
        // A new terrain command must also cancel a previous vanilla JUMPING operation.
        this.operation = Operation.MOVE_TO;
        this.terrainMovement = true;
    }

    void stopTerrainMovement() {
        this.operation = Operation.WAIT;
        this.terrainMovement = false;
        this.mob.setSpeed(0.0F);
        this.mob.setXxa(0.0F);
        this.mob.setJumping(false);
    }

    boolean isAligned(double x, double z) {
        double dx = x - this.mob.getX();
        double dz = z - this.mob.getZ();
        if (dx * dx + dz * dz < 0.01D) return true;
        float yaw = (float)(Mth.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        return Math.abs(Mth.wrapDegrees(yaw - this.mob.getYRot())) <= MOVE_YAW_TOLERANCE;
    }

    @Override
    public void tick() {
        if (!this.terrainMovement) {
            super.tick();
            return;
        }
        this.terrainMovement = false;
        this.operation = Operation.WAIT;
        this.mob.setXxa(0.0F);
        this.mob.setJumping(false);
        double dx = this.wantedX - this.mob.getX();
        double dz = this.wantedZ - this.mob.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.1D) {
            this.mob.setSpeed(0.0F);
            this.brakeHorizontally(0.0D);
            return;
        }
        float yaw = (float)(Mth.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        this.mob.setYRot(this.rotlerp(this.mob.getYRot(), yaw, ProtectedLookControl.YAW_SPEED));
        if (!this.isAligned(this.wantedX, this.wantedZ)) {
            this.mob.setSpeed(0.0F);
            this.brakeHorizontally(0.0D);
            return;
        }
        // Brake before a nearby waypoint; turning slowly while coasting causes circles.
        this.brakeHorizontally(distance * 0.5D);
        this.mob.setSpeed((float)(Math.min(this.speedModifier, distance * 2.0D)
                * this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED)));
        // Terrain AI alone decides whether this step requires a jump.
    }

    private void brakeHorizontally(double maximumSpeed) {
        Vec3 movement = this.mob.getDeltaMovement();
        double speed = movement.horizontalDistance();
        if (speed > maximumSpeed) {
            double scale = maximumSpeed / speed;
            this.mob.setDeltaMovement(movement.x * scale, movement.y, movement.z * scale);
        }
    }

    @Override
    protected float rotlerp(float from, float to, float maximumChange) {
        return super.rotlerp(from, to, Math.min(maximumChange, ProtectedLookControl.YAW_SPEED));
    }
}
