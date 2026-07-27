package dev.groundfog.client;

import java.io.IOException;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import dev.groundfog.GroundFogMod;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

@EventBusSubscriber(modid = GroundFogMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FogShaders {

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        try {
            event.registerShader(
                    new ShaderInstance(event.getResourceProvider(),
                            ResourceLocation.fromNamespaceAndPath(GroundFogMod.MODID, "volumetric_fog"),
                            DefaultVertexFormat.POSITION),
                    shader -> VolumetricFogRenderer.shader = shader);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load volumetric fog shader", e);
        }
    }

    private FogShaders() {}
}
