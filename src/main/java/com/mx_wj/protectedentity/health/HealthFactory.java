package com.mx_wj.protectedentity.health;

import java.security.CodeSource;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HealthFactory {
    private static final String MOD_PACKAGE = "com.mx_wj.protectedentity.";
    private static final StackWalker CALLER_WALKER = StackWalker.getInstance(Set.of(
            StackWalker.Option.RETAIN_CLASS_REFERENCE, StackWalker.Option.SHOW_HIDDEN_FRAMES));
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
        if (isAuthorizedCaller() && internalId != null && !internalId.isEmpty()) {
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
        if (isAuthorizedCaller()) {
            healthMap(clientSide).remove(internalId);
        }
    }

    /** Checks the immediate caller of the guarded method invoking this helper. */
    public static boolean isAuthorizedCaller() {
        // getCallerClass() ALWAYS hides hidden classes, even with SHOW_HIDDEN_FRAMES.
        // Skip this helper and the guarded method, retaining the real entity callback.
        // Do not scan deeper: a mod frame further up must not authorize an external writer.
        return CALLER_WALKER.walk(frames -> frames.skip(2).findFirst()
                .map(frame -> isModClass(frame.getDeclaringClass())).orElse(false));
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
