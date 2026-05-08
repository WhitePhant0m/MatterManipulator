package dev.wp.matter_manipulator.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.wp.matter_manipulator.client.gui.RadialMenu.RadialMenuOption;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import static java.lang.Math.PI;

/**
 * Full-screen radial menu. Built from a {@link RadialMenuBuilder}, it draws arc wedges for each
 * option. Branch options replace the active option list in-place; leaf options close the screen.
 */
@OnlyIn(Dist.CLIENT)
public class RadialMenuScreen extends Screen {

    private static final double TAU = PI * 2;

    /** Holds the current set of options; branch clicks swap its contents via onClick. */
    private final RadialMenu currentMenu;

    public RadialMenuScreen(RadialMenuBuilder builder) {
        super(Component.empty());
        this.currentMenu = builder.build();
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        layoutOptions();

        int w = this.width, h = this.height;
        int dim = Math.min(w, h);
        double cx = w / 2.0, cy = h / 2.0;
        float inner = currentMenu.innerRadius, outer = currentMenu.outerRadius;

        double nx = (mouseX - cx) / (dim / 2.0);
        double ny = (mouseY - cy) / (dim / 2.0);
        double mouseR = Math.sqrt(nx * nx + ny * ny);
        double mouseTheta = modTau(Math.atan2(ny, nx));

        Matrix4f matrix = graphics.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buf = tesselator.getBuilder();

        for (RadialMenuOption opt : currentMenu.options) {
            if (opt.isHidden) continue;
            boolean hovered = mouseR >= inner && mouseR <= outer
                && isAngleBetween(mouseTheta, opt.startTheta, opt.endTheta);

            float gr = hovered ? 0.45f : 0.12f;
            float a  = 0.88f;

            buf.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
            double step = PI / 32;
            for (int i = 0; ; i++) {
                double t  = opt.startTheta + i * step;
                double tc = Math.min(Math.max(t, opt.startTheta), opt.endTheta);
                float ox = (float) (Math.cos(tc) * outer * dim / 2.0 + cx);
                float oy = (float) (Math.sin(tc) * outer * dim / 2.0 + cy);
                float ix = (float) (Math.cos(tc) * inner * dim / 2.0 + cx);
                float iy = (float) (Math.sin(tc) * inner * dim / 2.0 + cy);
                buf.vertex(matrix, ox, oy, 0).color(gr, gr, gr, a).endVertex();
                buf.vertex(matrix, ix, iy, 0).color(gr, gr, gr, a).endVertex();
                if (t > opt.endTheta) break;
            }
            BufferUploader.drawWithShader(buf.end());
        }

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        for (RadialMenuOption opt : currentMenu.options) {
            if (opt.isHidden) continue;
            double midTheta = (opt.startTheta + opt.endTheta) / 2;
            double midR     = (inner + outer) / 2.0;
            int lx = (int) (Math.cos(midTheta) * midR * dim / 2.0 + cx);
            int ly = (int) (Math.sin(midTheta) * midR * dim / 2.0 + cy);

            String label = opt.label.get();
            var lines = font.split(Component.literal(label), 60);
            int totalH = lines.size() * font.lineHeight;
            int startY = ly - totalH / 2;
            for (var line : lines) {
                graphics.drawString(font, line, lx - font.width(line) / 2, startY, 0xFFCCCCCC, false);
                startY += font.lineHeight;
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int w = this.width, h = this.height;
        int dim = Math.min(w, h);
        double cx = w / 2.0, cy = h / 2.0;
        float inner = currentMenu.innerRadius, outer = currentMenu.outerRadius;

        double nx = (mouseX - cx) / (dim / 2.0);
        double ny = (mouseY - cy) / (dim / 2.0);
        double mouseR = Math.sqrt(nx * nx + ny * ny);
        double mouseTheta = modTau(Math.atan2(ny, nx));

        for (RadialMenuOption opt : currentMenu.options) {
            if (opt.isHidden) continue;
            if (mouseR >= inner && mouseR <= outer
                    && isAngleBetween(mouseTheta, opt.startTheta, opt.endTheta)) {
                if (opt.onClick != null) opt.onClick.onClick(currentMenu, opt, button);
                if (!opt.keepOpen) this.onClose();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void layoutOptions() {
        double weightSum = 0;
        for (RadialMenuOption opt : currentMenu.options) {
            opt.isHidden = opt.hidden.getAsBoolean();
            if (!opt.isHidden) weightSum += opt.weight;
        }

        double angle = 0;
        for (RadialMenuOption opt : currentMenu.options) {
            if (opt.isHidden) { opt.startTheta = 0; opt.endTheta = 0; continue; }
            double slice = opt.weight / weightSum * TAU;
            opt.startTheta = angle;
            angle += slice;
            opt.endTheta = angle;
        }

        // Rotate so the first visible option is centred at the top (−π/2)
        RadialMenuOption first = null;
        for (RadialMenuOption opt : currentMenu.options) if (!opt.isHidden) { first = opt; break; }
        if (first != null) {
            double offset = (first.endTheta - first.startTheta) / 2 + PI / 2;
            for (RadialMenuOption opt : currentMenu.options) {
                if (!opt.isHidden) { opt.startTheta -= offset; opt.endTheta -= offset; }
            }
        }
    }

    private static double modTau(double a) { return ((a % TAU) + TAU) % TAU; }

    private static boolean isAngleBetween(double target, double a1, double a2) {
        if (a2 < a1) return false;
        while (a1 < 0)   { a1 += TAU; a2 += TAU; }
        while (a1 > TAU) { a1 -= TAU; a2 -= TAU; }
        return modTau(target - a1) < a2 - a1;
    }
}
