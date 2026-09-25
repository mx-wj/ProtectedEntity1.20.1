package com.mx_wj.protectedentity.client;

import com.mx_wj.protectedentity.entity.ProtectedEntity;
import net.minecraft.client.Minecraft;

public final class ClientHealthSync {
    private ClientHealthSync() {
    }

    public static void apply(int entityId, String internalId, float health) {
        var level = Minecraft.getInstance().level;
        if (level != null && level.getEntity(entityId) instanceof ProtectedEntity entity) {
            entity.applyClientHealthSync(internalId, health);
        }
    }
}
