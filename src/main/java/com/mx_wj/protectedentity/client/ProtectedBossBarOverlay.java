package com.mx_wj.protectedentity.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mx_wj.protectedentity.ProtectedEntityMod;
import com.mx_wj.protectedentity.entity.ProtectedEntity;
import com.mx_wj.protectedentity.health.HealthCalculator;
import com.mx_wj.protectedentity.health.HealthFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ProtectedEntityMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ProtectedBossBarOverlay {
    private static final ResourceLocation FRAME = new ResourceLocation(
            ProtectedEntityMod.MODID, "textures/gui/protected_boss_frame.png");
    private static final ResourceLocation FILL = new ResourceLocation(
            ProtectedEntityMod.MODID, "textures/gui/protected_boss_fill.png");

    private static final int TEXTURE_WIDTH = 2079;
    private static final int TEXTURE_HEIGHT = 756;
    private static final int BAR_WIDTH = 256;
    private static final int BAR_HEIGHT = 32;
    private static final int FILL_WIDTH = 208;
    private static final int BAR_Y_OFFSET = -4;
    private static final int NAME_Y_OFFSET = -7;
    private static final int BAR_INCREMENT = 36;

    private ProtectedBossBarOverlay() {
    }

    @SubscribeEvent
    public static void renderBossBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
        if (!(event.getBossEvent().getName().getContents() instanceof TranslatableContents contents)
                || !contents.getKey().equals("bossbar.protectedentity")) {
            return;
        }

        Object[] args = contents.getArgs();
        if (args.length < 2) {
            return;
        }

        int entityId;
        try {
            entityId = Integer.parseInt(args[0].toString());
        } catch (NumberFormatException exception) {
            return;
        }

        event.setCanceled(true);
        event.setIncrement(BAR_INCREMENT);

        GuiGraphics graphics = event.getGuiGraphics();
        int x = (graphics.guiWidth() - BAR_WIDTH) / 2;
        int y = event.getY();
        int barY = y + BAR_Y_OFFSET;
        var level = Minecraft.getInstance().level;
        float progress = event.getBossEvent().getProgress();
        if (level != null && level.getEntity(entityId) instanceof ProtectedEntity entity) {
            progress = HealthCalculator.progress(
                    HealthFactory.getHealthValue(entity.getInternalId(), true), entity.getMaxHealth());
        }
        progress = Mth.clamp(progress, 0.0F, 1.0F);
        int fillWidth = Math.round(FILL_WIDTH * progress);

        RenderSystem.enableBlend();
        graphics.blit(FRAME, x, barY, BAR_WIDTH, BAR_HEIGHT,
                0.0F, 245.0F, TEXTURE_WIDTH, 255, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        if (fillWidth > 0) {
            graphics.blit(FILL, x + 24, barY + 12, fillWidth, 9,
                    44.0F, 323.0F, Math.round(1992 * progress), 103,
                    TEXTURE_WIDTH, TEXTURE_HEIGHT);
        }
        RenderSystem.disableBlend();

        var font = Minecraft.getInstance().font;
        var name = event.getBossEvent().getName();
        graphics.drawString(font, name, (graphics.guiWidth() - font.width(name)) / 2,
                y + NAME_Y_OFFSET, 0xFFFFFF);
    }
}
