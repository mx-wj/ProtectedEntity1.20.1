package com.mx_wj.protectedentity;

import com.mx_wj.protectedentity.entity.ProtectedEntity;
import com.mx_wj.protectedentity.entity.HiddenEntityFactory;
import com.mx_wj.protectedentity.entity.TrackingEgg;
import com.mx_wj.protectedentity.entity.TargetedEnderEye;
import com.mx_wj.protectedentity.network.ModNetwork;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
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
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, MODID);

    public static final RegistryObject<EntityType<ProtectedEntity>> PROTECTED_ENTITY =
            ENTITY_TYPES.register("protected_entity", () ->
                    EntityType.Builder.of(HiddenEntityFactory::create, MobCategory.CREATURE)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(8)
                            .build(MODID + ":protected_entity"));
    public static final RegistryObject<EntityType<TrackingEgg>> TRACKING_EGG =
            ENTITY_TYPES.register("tracking_egg", () ->
                    EntityType.Builder.<TrackingEgg>of(TrackingEgg::new, MobCategory.MISC)
                            .sized(0.3125F, 0.3125F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .build(MODID + ":tracking_egg"));
    public static final RegistryObject<EntityType<TargetedEnderEye>> TARGETED_ENDER_EYE =
            ENTITY_TYPES.register("targeted_ender_eye", () ->
                    EntityType.Builder.<TargetedEnderEye>of(TargetedEnderEye::new, MobCategory.MISC)
                            .sized(0.3125F, 0.3125F)
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .build(MODID + ":targeted_ender_eye"));
    public static final RegistryObject<ForgeSpawnEggItem> PROTECTED_ENTITY_SPAWN_EGG =
            ITEMS.register("protected_entity_spawn_egg", () ->
                    new ForgeSpawnEggItem(PROTECTED_ENTITY, 0xC1121F, 0x5C0010,
                            new Item.Properties()));

    public ProtectedEntityMod() {
        ModNetwork.register();
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITY_TYPES.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(this::registerAttributes);
        modBus.addListener(this::addCreativeItems);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(PROTECTED_ENTITY.get(), ProtectedEntity.createAttributes().build());
    }

    private void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(PROTECTED_ENTITY_SPAWN_EGG);
        }
    }
}
