package dev.wp.matter_manipulator.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Utility for drawing translucent animated selection boxes and ruler lines.
 * The filled box uses a custom GLSL shader (fancybox) that reproduces the
 * diagonal sine-wave shimmer from the 1.7.10 original.
 */
@OnlyIn(Dist.CLIENT)
public class BoxRenderer {

    @Nullable
    private static ShaderInstance fancyBoxShader;

    /** Called from RegisterShadersEvent once the shader is compiled and linked. */
    public static void setFancyBoxShader(ShaderInstance shader) {
        fancyBoxShader = shader;
    }

    // ── Filled animated box ───────────────────────────────────────────────────────

    /**
     * Draws a translucent, animated filled AABB using the fancybox shader.
     * UV coordinates match the 1.7.10 continuous-unwrap pattern so the
     * diagonal stripe animation is visually identical to the original.
     *
     * Callers must have the PoseStack already translated by -camPos before calling.
     */
    // spotless:off
    public static void drawFilledBox(PoseStack ps,
                                     double x0, double y0, double z0,
                                     double x1, double y1, double z1,
                                     float r, float g, float b, float a) {
        if (fancyBoxShader == null) return;

        // Expand slightly — matches 1.7.10 expand(0.002) so fill sits just outside wireframe
        x0 -= 0.002; y0 -= 0.002; z0 -= 0.002;
        x1 += 0.002; y1 += 0.002; z1 += 0.002;

        float fx0 = (float) x0, fy0 = (float) y0, fz0 = (float) z0;
        float fx1 = (float) x1, fy1 = (float) y1, fz1 = (float) z1;
        float dX = fx1 - fx0, dY = fy1 - fy0, dZ = fz1 - fz0;
        Matrix4f mat = ps.last().pose();

        // Upload time (0‥2.5 s period, matching 1.7.10: millis%2500 / 1000)
        var timeUniform = fancyBoxShader.getUniform("time");
        if (timeUniform != null) timeUniform.set((System.currentTimeMillis() % 2500) / 1000f);

        RenderSystem.setShader(() -> fancyBoxShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);

        // Each face uses UV coords that form a continuous unwrap across the whole box,
        // reproducing the 1.7.10 CapturingTessellator UV pattern exactly.

        // ── bottom (y-)  UV = (localX, localZ) ───────────────────────────────────
        buf.vertex(mat, fx0, fy0, fz0).color(r,g,b,a).uv(0,   0  ).endVertex();
        buf.vertex(mat, fx1, fy0, fz0).color(r,g,b,a).uv(dX,  0  ).endVertex();
        buf.vertex(mat, fx1, fy0, fz1).color(r,g,b,a).uv(dX,  dZ ).endVertex();
        buf.vertex(mat, fx0, fy0, fz1).color(r,g,b,a).uv(0,   dZ ).endVertex();

        // ── top (y+)     UV = (dY + localX, localZ) ──────────────────────────────
        buf.vertex(mat, fx0, fy1, fz0).color(r,g,b,a).uv(dY,      0  ).endVertex();
        buf.vertex(mat, fx0, fy1, fz1).color(r,g,b,a).uv(dY,      dZ ).endVertex();
        buf.vertex(mat, fx1, fy1, fz1).color(r,g,b,a).uv(dY + dX, dZ ).endVertex();
        buf.vertex(mat, fx1, fy1, fz0).color(r,g,b,a).uv(dY + dX, 0  ).endVertex();

        // ── west (x-)    UV = (localY, localZ) ───────────────────────────────────
        buf.vertex(mat, fx0, fy0, fz0).color(r,g,b,a).uv(0,  0  ).endVertex();
        buf.vertex(mat, fx0, fy0, fz1).color(r,g,b,a).uv(0,  dZ ).endVertex();
        buf.vertex(mat, fx0, fy1, fz1).color(r,g,b,a).uv(dY, dZ ).endVertex();
        buf.vertex(mat, fx0, fy1, fz0).color(r,g,b,a).uv(dY, 0  ).endVertex();

        // ── east (x+)    UV = (dX + localY, localZ) ──────────────────────────────
        buf.vertex(mat, fx1, fy1, fz1).color(r,g,b,a).uv(dX + dY, dZ ).endVertex();
        buf.vertex(mat, fx1, fy0, fz1).color(r,g,b,a).uv(dX,      dZ ).endVertex();
        buf.vertex(mat, fx1, fy0, fz0).color(r,g,b,a).uv(dX,      0  ).endVertex();
        buf.vertex(mat, fx1, fy1, fz0).color(r,g,b,a).uv(dX + dY, 0  ).endVertex();

        // ── north (z-)   UV = (localX, localY) ───────────────────────────────────
        buf.vertex(mat, fx0, fy0, fz0).color(r,g,b,a).uv(0,  0  ).endVertex();
        buf.vertex(mat, fx1, fy0, fz0).color(r,g,b,a).uv(dX, 0  ).endVertex();
        buf.vertex(mat, fx1, fy1, fz0).color(r,g,b,a).uv(dX, dY ).endVertex();
        buf.vertex(mat, fx0, fy1, fz0).color(r,g,b,a).uv(0,  dY ).endVertex();

        // ── south (z+)   UV = (dZ + localX, localY) ──────────────────────────────
        buf.vertex(mat, fx0, fy0, fz1).color(r,g,b,a).uv(dZ,      0  ).endVertex();
        buf.vertex(mat, fx0, fy1, fz1).color(r,g,b,a).uv(dZ,      dY ).endVertex();
        buf.vertex(mat, fx1, fy1, fz1).color(r,g,b,a).uv(dZ + dX, dY ).endVertex();
        buf.vertex(mat, fx1, fy0, fz1).color(r,g,b,a).uv(dZ + dX, 0  ).endVertex();

        BufferUploader.drawWithShader(buf.end());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
    // spotless:on

    // ── Ruler lines ───────────────────────────────────────────────────────────────

    /**
     * Draws axis-aligned ruler lines extending from (cx, cy, cz) in all 6 directions.
     * {@code linesBuf} must come from a {@code RenderType.lines()} buffer.
     */
    public static void drawRulers(PoseStack ps, VertexConsumer linesBuf,
                                  double cx, double cy, double cz,
                                  float r, float g, float b, float a, int length) {
        float fx = (float) cx, fy = (float) cy, fz = (float) cz;
        var pose = ps.last();

        addLine(linesBuf, pose, fx, fy, fz, fx + length, fy, fz, r, g, b, a,  1,  0,  0);
        addLine(linesBuf, pose, fx, fy, fz, fx - length, fy, fz, r, g, b, a, -1,  0,  0);
        addLine(linesBuf, pose, fx, fy, fz, fx, fy + length, fz, r, g, b, a,  0,  1,  0);
        addLine(linesBuf, pose, fx, fy, fz, fx, fy - length, fz, r, g, b, a,  0, -1,  0);
        addLine(linesBuf, pose, fx, fy, fz, fx, fy, fz + length, r, g, b, a,  0,  0,  1);
        addLine(linesBuf, pose, fx, fy, fz, fx, fy, fz - length, r, g, b, a,  0,  0, -1);
    }

    private static void addLine(VertexConsumer buf, PoseStack.Pose pose,
                                float x1, float y1, float z1,
                                float x2, float y2, float z2,
                                float r, float g, float b, float a,
                                float nx, float ny, float nz) {
        buf.vertex(pose.pose(), x1, y1, z1).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
        buf.vertex(pose.pose(), x2, y2, z2).color(r, g, b, a).normal(pose.normal(), nx, ny, nz).endVertex();
    }
}
