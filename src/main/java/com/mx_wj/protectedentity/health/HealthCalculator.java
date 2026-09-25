package com.mx_wj.protectedentity.health;

public final class HealthCalculator {
    public static final float MAX_HEALTH = 2000.0F;
    private static final float DAMAGE_MULTIPLIER = 0.05F;
    private static final float MAX_DAMAGE_PER_HIT = 10.0F;
    private HealthCalculator() {
    }

    public static HealthValue toValue(float health, String internalId, int randomA, int randomB, int checkValue) {
        return HealthValueLookup.fromFloat(health, internalId, randomA, randomB, checkValue);
    }

    static int checkValue(float health, String internalId, int randomA, int randomB) {
        int check = Integer.rotateLeft(Float.floatToRawIntBits(health) ^ randomA, 7);
        for (int i = 0; i < internalId.length(); i++) {
            check = Integer.rotateLeft(check ^ internalId.charAt(i), 5) * 31 + 0x9E3779B9;
        }
        return Integer.rotateLeft(check ^ randomB, 13) + randomA * 17;
    }

    public static float toFloat(HealthValue value) {
        int code = Math.round((value.amount() - 4096.375F) * 32.0F);
        int tenths = Math.floorMod((code - 49237) * 17382, 65521);
        return tenths / 10.0F;
    }

    public static float reducedDamage(float damage) {
        if (!Float.isFinite(damage) || damage <= 0.0F) {
            return 0.0F;
        }
        return Math.min(damage * DAMAGE_MULTIPLIER, MAX_DAMAGE_PER_HIT);
    }

    public static float limitDamage(float healthBeforeHit, float calculatedHealth) {
        if (!Float.isFinite(calculatedHealth)) {
            return healthBeforeHit;
        }
        return Math.max(calculatedHealth, healthBeforeHit - MAX_DAMAGE_PER_HIT);
    }

    public static float progress(float health, float maxHealth) {
        return maxHealth <= 0.0F ? 0.0F : Math.max(0.0F, Math.min(health / maxHealth, 1.0F));
    }

    public static float progress(HealthValue value, float maxHealth) {
        return value == null ? 0.0F : progress(toFloat(value), maxHealth);
    }
}
