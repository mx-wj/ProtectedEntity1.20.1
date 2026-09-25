package com.mx_wj.protectedentity.client;

import com.mx_wj.protectedentity.entity.ProtectedEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class ProtectedEntityRenderer extends HumanoidMobRenderer<ProtectedEntity, PlayerModel<ProtectedEntity>> {
    private static final ResourceLocation STEVE_SKIN =
            new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    public ProtectedEntityRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(ProtectedEntity entity) {
        return STEVE_SKIN;
    }
}
