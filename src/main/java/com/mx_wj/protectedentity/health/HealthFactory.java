package com.mx_wj.protectedentity.health;

import java.security.CodeSource;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class HealthFactory {
    private static final String MOD_PACKAGE = "com.mx_wj.protectedentity.";
    private static final StackWalker CALLER_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, HealthValue> SERVER_HEALTH = new ConcurrentHashMap<>();
    private static final Map<String, HealthValue> CLIENT_HEALTH = new ConcurrentHashMap<>();

    private HealthFactory() {
    }

    public static float getHealth(String internalId, boolean clientSide) {
        HealthValue value = getHealthValue(internalId, clientSide);
        return value == null ? 0.0F : HealthCalculator.toFloat(value);
    }

    public static HealthValue getHealthValue(String internalId, boolean clientSide) {
        return internalId == null || internalId.isEmpty() ? null : healthMap(clientSide).get(internalId);
    }

    public static void setHealth(String internalId, float health, boolean clientSide) {
        if (isModClass(CALLER_WALKER.getCallerClass()) && internalId != null && !internalId.isEmpty()) {
            int randomA = RANDOM.nextInt();
            int randomB = RANDOM.nextInt();
            int checkValue = HealthCalculator.checkValue(health, internalId, randomA, randomB);
            HealthValue value = HealthCalculator.toValue(health, internalId, randomA, randomB, checkValue);
            if (value != null) {
                healthMap(clientSide).put(internalId, value);
            }
        }
    }

    public static void removeHealth(String internalId, boolean clientSide) {
        if (isModClass(CALLER_WALKER.getCallerClass())) {
            healthMap(clientSide).remove(internalId);
        }
    }

    public static boolean isModClass(Class<?> caller) {
        if (!caller.getName().startsWith(MOD_PACKAGE)) {
            return false;
        }
        CodeSource ownSource = HealthFactory.class.getProtectionDomain().getCodeSource();
        CodeSource callerSource = caller.getProtectionDomain().getCodeSource();
        return ownSource != null && callerSource != null
                && ownSource.getLocation().equals(callerSource.getLocation());
    }

    private static Map<String, HealthValue> healthMap(boolean clientSide) {
        return clientSide ? CLIENT_HEALTH : SERVER_HEALTH;
    }
}
