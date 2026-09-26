package com.mx_wj.protectedentity.entity;

import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

final class IdleStrollGoal extends WaterAvoidingRandomStrollGoal {
    private final ProtectedEntity mob;

    IdleStrollGoal(ProtectedEntity mob) {
        super(mob, 1.0D);
        this.mob = mob;
    }

    @Override
    public boolean canUse() {
        return this.mob.getTarget() == null && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.mob.getTarget() == null && super.canContinueToUse();
    }
}
