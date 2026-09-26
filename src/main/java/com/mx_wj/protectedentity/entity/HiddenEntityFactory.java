package com.mx_wj.protectedentity.entity;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

public final class HiddenEntityFactory {
    private static final MethodHandle CONSTRUCTOR = loadConstructor();

    private HiddenEntityFactory() {
    }

    public static ProtectedEntity create(EntityType<ProtectedEntity> type, Level level) {
        try {
            return (ProtectedEntity) CONSTRUCTOR.invokeExact(type, level);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not create protected entity", failure);
        }
    }

    private static MethodHandle loadConstructor() {
        try (InputStream stream = HiddenEntityFactory.class.getResourceAsStream("HiddenProtectedEntity.class")) {
            if (stream == null) {
                throw new IllegalStateException("Missing hidden protected entity bytecode");
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(
                    stream.readAllBytes(), true, MethodHandles.Lookup.ClassOption.STRONG);
            Class<? extends ProtectedEntity> entityClass =
                    lookup.lookupClass().asSubclass(ProtectedEntity.class);
            return lookup.findConstructor(entityClass,
                            MethodType.methodType(void.class, EntityType.class, Level.class))
                    .asType(MethodType.methodType(ProtectedEntity.class, EntityType.class, Level.class));
        } catch (IOException | IllegalAccessException | NoSuchMethodException failure) {
            throw new IllegalStateException("Could not define hidden protected entity", failure);
        }
    }
}
