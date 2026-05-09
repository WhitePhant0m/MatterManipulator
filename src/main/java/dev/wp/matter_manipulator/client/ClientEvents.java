package dev.wp.matter_manipulator.client;

import dev.wp.matter_manipulator.MMMod;
import dev.wp.matter_manipulator.client.render.MMRenderer;
import dev.wp.matter_manipulator.common.config.MMModConfig;
import dev.wp.matter_manipulator.common.items.manipulator.ItemMatterManipulator;
import dev.wp.matter_manipulator.common.items.manipulator.MMState;
import dev.wp.matter_manipulator.common.items.manipulator.PendingAction;
import dev.wp.matter_manipulator.common.items.manipulator.PlaceMode;
import dev.wp.matter_manipulator.common.networking.*;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MMMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        MMRenderer.onRender(event);
    }

    @SubscribeEvent
    public static void onRenderHUD(RenderGuiOverlayEvent.Post event) {
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        String text = MMRenderer.hudText;
        if (text == null || text.isEmpty()) return;

        var mc = Minecraft.getInstance();
        var font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int tw = font.width(text);
        event.getGuiGraphics().drawString(font, text, (w - tw) / 2, h - 50, 0xFFFFFF, true);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        var held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof ItemMatterManipulator)) return;

        if (MMKeyMappings.LOCK_POSITIONS.consumeClick()) {
            MMNetwork.CHANNEL.sendToServer(new PacketToggleLock());
            return;
        }

        if (MMKeyMappings.CUT.consumeClick()) {
            MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.MOVING));
            if (MMModConfig.PASTE_AUTO_CLEAR.get()) {
                MMNetwork.CHANNEL.sendToServer(new PacketClearCoords());
                if (MMModConfig.RESET_TRANSFORM.get()) {
                    MMNetwork.CHANNEL.sendToServer(new PacketResetTransform());
                }
            }
            MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.MARK_CUT_A, null));
            return;
        }

        if (MMKeyMappings.COPY.consumeClick()) {
            MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.COPYING));
            if (MMModConfig.PASTE_AUTO_CLEAR.get()) {
                MMNetwork.CHANNEL.sendToServer(new PacketClearCoords());
                if (MMModConfig.RESET_TRANSFORM.get()) {
                    MMNetwork.CHANNEL.sendToServer(new PacketResetTransform());
                }
            }
            MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.MARK_COPY_A, null));
            return;
        }

        if (MMKeyMappings.PASTE.consumeClick()) {
            var state = MMState.of(held);
            var mode = state.getConfig().placeMode;
            if (mode != PlaceMode.COPYING && mode != PlaceMode.MOVING) {
                MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.COPYING));
            }
            MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.MARK_PASTE_A, null));
            return;
        }

        if (MMKeyMappings.RESET.consumeClick()) {
            MMNetwork.CHANNEL.sendToServer(new PacketClearCoords());
            if (MMModConfig.RESET_TRANSFORM.get()) {
                MMNetwork.CHANNEL.sendToServer(new PacketResetTransform());
            }
            return;
        }
    }
}
