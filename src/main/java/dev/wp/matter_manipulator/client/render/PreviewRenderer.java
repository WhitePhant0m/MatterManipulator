package dev.wp.matter_manipulator.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.wp.matter_manipulator.common.building.BlockAnalyzer;
import dev.wp.matter_manipulator.common.building.Location;
import dev.wp.matter_manipulator.common.building.PendingBlock;
import dev.wp.matter_manipulator.common.items.manipulator.MMConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Quaternionf;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * Renders semi-transparent ghost-block previews at the paste destination for
 * COPYING and MOVING modes, matching the 1.7.10 visual behavior.
 * Blocks are rendered using BlockRenderDispatcher with a custom VertexConsumer
 * that reduces alpha to ~35 %, making the preview translucent like the original.
 * The blocklist is cached and rebuilt only when the source region (coordA/B) changes.
 */
@OnlyIn(Dist.CLIENT)
public class PreviewRenderer {

    /**
     * Maximum number of blocks shown as ghost previews (performance guard).
     */
    private static final int MAX_BLOCKS = 1024;
    /**
     * Alpha multiplier applied to every vertex color (0 = invisible, 1 = opaque).
     */
    private static final float GHOST_ALPHA = 0.35f;

    // Source-region cache
    @Nullable
    private static Location lastA, lastB;
    private static List<PendingBlock> blockCache = List.of();

    // Dedicated BufferBuilder so we never interfere with Tesselator or the main buffer source
    private static final BufferBuilder GHOST_BUILDER = new BufferBuilder(2 * 1024 * 1024);

    // ── Public API ────────────────────────────────────────────────────────────────

    /**
     * Render paste-preview ghost blocks.
     * Call this after the line-render batch for the frame has been flushed.
     * The PoseStack must already be translated by {@code -cameraPos}.
     */
    public static void renderPastePreview(PoseStack ps, Minecraft mc, MMConfig cfg) {
        if (cfg.coordA == null || cfg.coordB == null || cfg.coordC == null) return;

        var level = mc.level;
        if (level == null || !cfg.coordA.dimension.equals(level.dimension())) return;

        refreshCache(cfg, level);
        if (blockCache.isEmpty()) return;

        // Safety: if something left GHOST_BUILDER in a building state, discard that data
        if (GHOST_BUILDER.building()) GHOST_BUILDER.end().release();

        var ghostSource = MultiBufferSource.immediate(GHOST_BUILDER);
        var ghostConsumer = new GhostConsumer(ghostSource.getBuffer(RenderType.translucent()), GHOST_ALPHA);
        var brd = mc.getBlockRenderer();
        var pasteOrigin = cfg.coordC.pos;

        for (var pb : blockCache) {
            BlockPos rel = pb.relPos;
            Vector3i transformedRel = cfg.transform.apply(new Vector3i(rel.getX(), rel.getY(), rel.getZ()));
            var worldPos = pasteOrigin.offset(transformedRel.x(), transformedRel.y(), transformedRel.z());
            
            BlockState state = pb.spec.state;

            // TODO: better BlockState transformation mapping
            // For now, we apply basic Y-rotation and X/Z mirrors if they match the transform
            // We can calculate rotationY from the transform's forward direction
            int rotationY = switch (cfg.transform.forward) {
                case EAST -> 1;
                case SOUTH -> 2;
                case WEST -> 3;
                default -> 0;
            };
            
            Rotation rotation = switch (rotationY) {
                case 1 -> Rotation.CLOCKWISE_90;
                case 2 -> Rotation.CLOCKWISE_180;
                case 3 -> Rotation.COUNTERCLOCKWISE_90;
                default -> Rotation.NONE;
            };

            if (rotation != Rotation.NONE) state = state.rotate(level, worldPos, rotation);
            if (cfg.transform.flipX) state = state.mirror(Mirror.LEFT_RIGHT);
            if (cfg.transform.flipZ) state = state.mirror(Mirror.FRONT_BACK);

            ps.pushPose();
            ps.translate(worldPos.getX(), worldPos.getY(), worldPos.getZ());
            var beModelData = ModelData.EMPTY;
            if (state.hasBlockEntity()) {
                var be = level.getBlockEntity(worldPos);
                if (be != null) beModelData = be.getModelData();
            }
            brd.renderBatched(state, worldPos, level, ps, ghostConsumer, false, level.getRandom(), beModelData, RenderType.solid());
            ps.popPose();
        }

        ghostSource.endBatch();
    }

    /**
     * Invalidate the cached blocklist so the next frame forces a re-analysis.
     */
    public static void invalidate() {
        lastA = null;
        lastB = null;
        blockCache = List.of();
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private static void refreshCache(MMConfig cfg, ClientLevel level) {
        if (Objects.equals(cfg.coordA, lastA) && Objects.equals(cfg.coordB, lastB)) return;
        lastA = cfg.coordA;
        lastB = cfg.coordB;
        var raw = BlockAnalyzer.analyzeRegion(level, cfg.coordA.pos, cfg.coordB.pos);
        blockCache = raw.size() > MAX_BLOCKS ? raw.subList(0, MAX_BLOCKS) : raw;
    }

    // ── Ghost VertexConsumer ──────────────────────────────────────────────────────

    /**
     * Forwards all vertex data to {@code inner}, but replaces the alpha component
     * of every color call with a fixed value, giving the block a translucent look.
     * Default putBulkData on the interface delegates to these overrides, so quad-based
     * rendering (ModelBlockRenderer) is correctly intercepted.
     */
    private static final class GhostConsumer implements VertexConsumer {

        private final VertexConsumer inner;
        private final int a255; // fixed alpha in [0, 255]

        GhostConsumer(VertexConsumer inner, float alphaMult) {
            this.inner = inner;
            this.a255 = Math.max(1, Math.min(255, (int) (255f * alphaMult)));
        }

        // ── Abstract interface methods (all must be implemented) ──────────────────

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            inner.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            inner.color(r, g, b, a255);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            inner.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            inner.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            inner.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            inner.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            inner.endVertex();
        }

        // ── Default-but-important overrides ──────────────────────────────────────

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            inner.defaultColor(r, g, b, a255);
        }

        @Override
        public void unsetDefaultColor() {
            inner.unsetDefaultColor();
        }
    }
}
