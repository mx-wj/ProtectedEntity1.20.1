package com.mx_wj.protectedentity;

import com.mx_wj.protectedentity.entity.ProtectedEntity;
import com.mx_wj.protectedentity.entity.TrackingFireball;
import com.mx_wj.protectedentity.network.ModNetwork;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(ProtectedEntityMod.MODID)
public class ProtectedEntityMod {
    public static final String MODID = "protectedentity";

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MODID);

    public static final RegistryObject<EntityType<ProtectedEntity>> PROTECTED_ENTITY =
            ENTITY_TYPES.register("protected_entity", () ->
                    EntityType.Builder.of(ProtectedEntity::new, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(8)
                            .build(MODID + ":protected_entity"));
    public static final RegistryObject<EntityType<TrackingFireball>> TRACKING_FIREBALL =
            ENTITY_TYPES.register("tracking_fireball", () ->
                    EntityType.Builder.<TrackingFireball>of(TrackingFireball::new, MobCategory.MISC)
                            .sized(0.3125F, 0.3125F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .build(MODID + ":tracking_fireball"));

    public ProtectedEntityMod() {
        ModNetwork.register();
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITY_TYPES.register(modBus);
        modBus.addListener(this::registerAttributes);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(PROTECTED_ENTITY.get(), ProtectedEntity.createAttributes().build());
    }
}
