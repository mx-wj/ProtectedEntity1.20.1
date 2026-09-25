package com.mx_wj.protectedentity.network;

import com.mx_wj.protectedentity.ProtectedEntityMod;
import com.mx_wj.protectedentity.client.ClientHealthSync;
import com.mx_wj.protectedentity.entity.ProtectedEntity;
import com.mx_wj.protectedentity.health.HealthCalculator;
import com.mx_wj.protectedentity.health.HealthFactory;
import com.mx_wj.protectedentity.health.HealthValue;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class ModNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ProtectedEntityMod.MODID, "health_sync"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, HealthSync.class, HealthSync::encode, HealthSync::decode,
                HealthSync::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendToPlayer(ServerPlayer player, ProtectedEntity entity) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), HealthSync.of(entity));
    }

    public static void sendToTracking(ProtectedEntity entity) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), HealthSync.of(entity));
    }

    private record HealthSync(int entityId, String internalId, float health) {
        private static HealthSync of(ProtectedEntity entity) {
            String internalId = entity.getInternalId();
            HealthValue value = HealthFactory.getHealthValue(internalId, false);
            float health = value == null ? 0.0F : HealthCalculator.toFloat(value);
            return new HealthSync(entity.getId(), internalId, health);
        }

        private static void encode(HealthSync message, FriendlyByteBuf buffer) {
            buffer.writeVarInt(message.entityId);
            buffer.writeUtf(message.internalId);
            buffer.writeFloat(message.health);
        }

        private static HealthSync decode(FriendlyByteBuf buffer) {
            return new HealthSync(buffer.readVarInt(), buffer.readUtf(), buffer.readFloat());
        }

        private static void handle(HealthSync message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientHealthSync.apply(message.entityId, message.internalId, message.health)));
            context.setPacketHandled(true);
        }
    }
}
