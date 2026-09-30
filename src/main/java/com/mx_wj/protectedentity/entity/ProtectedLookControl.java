package com.mx_wj.protectedentity.entity;

import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.phys.Vec3;

final class ProtectedLookControl extends LookControl {
    static final float YAW_SPEED = 10.0F;
    private static final float PITCH_SPEED = 8.0F;
    private boolean actionLook;

    ProtectedLookControl(ProtectedEntity mob) {
        super(mob);
    }

    void lookAtAction(Vec3 point) {
        this.actionLook = true;
        super.setLookAt(point.x, point.y, point.z, YAW_SPEED, PITCH_SPEED);
    }

    @Override
    public void setLookAt(double x, double y, double z, float yawSpeed, float pitchSpeed) {
        // Idle look goals must not overwrite the current combat or building focus.
        if (this.mob.getTarget() != null || this.actionLook) return;
        super.setLookAt(x, y, z, Math.min(yawSpeed, YAW_SPEED), Math.min(pitchSpeed, PITCH_SPEED));
    }

    @Override
    public void tick() {
        boolean hadFocus = this.lookAtCooldown > 0;
        super.tick();
        if (!hadFocus) {
            this.mob.setXRot(this.rotateTowards(this.mob.getXRot(), 0.0F, PITCH_SPEED));
            this.actionLook = false;
        }
    }

    @Override
    protected boolean resetXRotOnTick() {
        return false;
    }

    @Override
    protected void clampHeadRotationToBody() {
        // The body follows gradually; clamping here would snap the head during a turn.
    }
}
