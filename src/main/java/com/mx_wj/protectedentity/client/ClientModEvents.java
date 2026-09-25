package com.mx_wj.protectedentity.client;

import com.mx_wj.protectedentity.ProtectedEntityMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;

@Mod.EventBusSubscriber(modid = ProtectedEntityMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ProtectedEntityMod.PROTECTED_ENTITY.get(), ProtectedEntityRenderer::new);
        event.registerEntityRenderer(ProtectedEntityMod.TRACKING_FIREBALL.get(), context ->
                new ThrownItemRenderer<>(context, 0.75F, true));
    }
}
