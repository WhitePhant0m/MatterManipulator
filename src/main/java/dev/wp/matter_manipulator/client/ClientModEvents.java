package dev.wp.matter_manipulator.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.wp.matter_manipulator.MMMod;
import dev.wp.matter_manipulator.client.render.BoxRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = MMMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientModEvents {

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(
            new ShaderInstance(event.getResourceProvider(),
                MMMod.id("fancybox"),
                DefaultVertexFormat.POSITION_COLOR_TEX),
            BoxRenderer::setFancyBoxShader);
    }
}
