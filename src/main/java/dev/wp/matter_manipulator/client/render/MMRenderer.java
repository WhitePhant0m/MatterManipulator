package dev.wp.matter_manipulator.client.render;

import com.mojang.blaze3d.vertex.*;
import dev.wp.matter_manipulator.common.building.Location;
import dev.wp.matter_manipulator.common.building.shapes.*;
import dev.wp.matter_manipulator.common.items.manipulator.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Vector3i;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Renders translucent bounding boxes, ruler lines, and cable line previews
 * for the currently held matter manipulator. Matches 1.7.10 visual style.
 */
public class MMRenderer {

    /** Dimension string displayed above the hotbar (e.g. "10x5x8"). Empty when nothing to show. */
    public static String hudText = "";

    // Teal (selection): 0.15, 0.6, 0.75
    private static final float TR = 0.15f, TG = 0.6f, TB = 0.75f, TA = 0.75f;
    // Orange (paste): 0.75, 0.5, 0.15
    private static final float OR = 0.75f, OG = 0.5f, OB = 0.15f;
    private static final int RULER_LENGTH = 128;

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var mc = Minecraft.getInstance();
        var player = mc.player;
        if (player == null) return;

        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof ItemMatterManipulator)) {
            held = player.getOffhandItem();
            if (!(held.getItem() instanceof ItemMatterManipulator)) {
                hudText = "";
                return;
            }
        }

        var state = MMState.of(held);
        var cfg = getEffectiveConfig(state.getConfig(), mc);
        var camPos = mc.gameRenderer.getMainCamera().getPosition();
        var ps = event.getPoseStack();

        ps.pushPose();
        ps.translate(-camPos.x, -camPos.y, -camPos.z);

        try {
            switch (cfg.placeMode) {
                case GEOMETRY, EXCHANGING -> renderAreaMode(ps, mc, cfg);
                case CABLES               -> renderCableMode(ps, mc, cfg);
                case COPYING, MOVING      -> renderCopyMode(ps, mc, cfg);
            }
        } finally {
            ps.popPose();
        }

        if (state.getConfig().locked && hudText != null) {
            if (hudText.isEmpty()) hudText = "LOCKED";
            else hudText += " (LOCKED)";
        }
    }

    // ── GEOMETRY / EXCHANGING ─────────────────────────────────────────────────────

    private static void renderAreaMode(PoseStack ps, Minecraft mc, MMConfig cfg) {
        var bufSource = mc.renderBuffers().bufferSource();
        var linesBuf  = bufSource.getBuffer(RenderType.lines());

        if (cfg.coordA != null && cfg.coordB != null) {
            BlockPos a = cfg.coordA.pos, b = cfg.coordB.pos;

            if (cfg.placeMode == PlaceMode.GEOMETRY && cfg.shape != ShapeType.CUBE) {
                List<ShapeBlock> blocks = switch (cfg.shape) {
                    case LINE     -> ShapeLine.generate(a, b);
                    case SPHERE   -> ShapeSphere.generate(a, b);
                    case CYLINDER -> ShapeCylinder.generate(a, b, 3);
                    default -> Collections.emptyList();
                };

                for (var sb : blocks) {
                    var p = sb.pos();
                    LevelRenderer.renderLineBox(ps, linesBuf,
                        p.getX(), p.getY(), p.getZ(),
                        p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                        TR, TG, TB, 0.7f);
                }

                BoxRenderer.drawRulers(ps, linesBuf, a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);
                BoxRenderer.drawRulers(ps, linesBuf, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);

                hudText = blocks.size() + " blocks";
                bufSource.endBatch(RenderType.lines());
            } else {
                double x0 = Math.min(a.getX(), b.getX());
                double y0 = Math.min(a.getY(), b.getY());
                double z0 = Math.min(a.getZ(), b.getZ());
                double x1 = Math.max(a.getX(), b.getX()) + 1;
                double y1 = Math.max(a.getY(), b.getY()) + 1;
                double z1 = Math.max(a.getZ(), b.getZ()) + 1;

                BoxRenderer.drawFilledBox(ps, x0, y0, z0, x1, y1, z1, TR, TG, TB, 0.3f);
                LevelRenderer.renderLineBox(ps, linesBuf, x0, y0, z0, x1, y1, z1, TR, TG, TB, TA);
                BoxRenderer.drawRulers(ps, linesBuf, a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);
                BoxRenderer.drawRulers(ps, linesBuf, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);

                hudText = dims(a, b);
                bufSource.endBatch(RenderType.lines());
            }
        } else if (cfg.coordA != null) {
            BlockPos a = cfg.coordA.pos;
            BoxRenderer.drawRulers(ps, linesBuf, a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);
            hudText = "";
            bufSource.endBatch(RenderType.lines());
        } else {
            hudText = "";
        }
    }

    // ── CABLES ───────────────────────────────────────────────────────────────────

    private static void renderCableMode(PoseStack ps, Minecraft mc, MMConfig cfg) {
        var bufSource = mc.renderBuffers().bufferSource();
        var linesBuf  = bufSource.getBuffer(RenderType.lines());

        if (cfg.coordA != null && cfg.coordB != null) {
            BlockPos a = cfg.coordA.pos;
            BlockPos b = pinToAxis(a, cfg.coordB.pos);

            BoxRenderer.drawRulers(ps, linesBuf, a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);
            BoxRenderer.drawRulers(ps, linesBuf, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);

            for (var sb : ShapeLine.generate(a, b)) {
                var p = sb.pos();
                LevelRenderer.renderLineBox(ps, linesBuf,
                    p.getX(), p.getY(), p.getZ(),
                    p.getX() + 1, p.getY() + 1, p.getZ() + 1,
                    TR, TG, TB, 0.7f);
            }

            int len = Math.max(Math.max(
                Math.abs(a.getX() - b.getX()),
                Math.abs(a.getY() - b.getY())),
                Math.abs(a.getZ() - b.getZ())) + 1;
            hudText = len + " blocks";

            bufSource.endBatch(RenderType.lines());
        } else if (cfg.coordA != null) {
            BlockPos a = cfg.coordA.pos;
            BoxRenderer.drawRulers(ps, linesBuf, a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);
            hudText = "";
            bufSource.endBatch(RenderType.lines());
        } else {
            hudText = "";
        }
    }

    // ── COPYING / MOVING ─────────────────────────────────────────────────────────

    private static void renderCopyMode(PoseStack ps, Minecraft mc, MMConfig cfg) {
        // Ghost-block preview at paste destination (matches 1.7.10 behaviour)
        PreviewRenderer.renderPastePreview(ps, mc, cfg);

        var bufSource = mc.renderBuffers().bufferSource();
        var linesBuf  = bufSource.getBuffer(RenderType.lines());

        if (cfg.coordA != null) {
            BoxRenderer.drawRulers(ps, linesBuf, cfg.coordA.pos.getX() + 0.5, cfg.coordA.pos.getY() + 0.5, cfg.coordA.pos.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);
        }
        if (cfg.coordB != null) {
            BoxRenderer.drawRulers(ps, linesBuf, cfg.coordB.pos.getX() + 0.5, cfg.coordB.pos.getY() + 0.5, cfg.coordB.pos.getZ() + 0.5, TR, TG, TB, TA, RULER_LENGTH);
        }
        if (cfg.coordC != null) {
            BoxRenderer.drawRulers(ps, linesBuf, cfg.coordC.pos.getX() + 0.5, cfg.coordC.pos.getY() + 0.5, cfg.coordC.pos.getZ() + 0.5, OR, OG, OB, TA, RULER_LENGTH);
        }

        if (cfg.coordA != null && cfg.coordB != null) {
            BlockPos a = cfg.coordA.pos, b = cfg.coordB.pos;
            double x0 = Math.min(a.getX(), b.getX());
            double y0 = Math.min(a.getY(), b.getY());
            double z0 = Math.min(a.getZ(), b.getZ());
            double x1 = Math.max(a.getX(), b.getX()) + 1;
            double y1 = Math.max(a.getY(), b.getY()) + 1;
            double z1 = Math.max(a.getZ(), b.getZ()) + 1;

            // Source box (teal)
            BoxRenderer.drawFilledBox(ps, x0, y0, z0, x1, y1, z1, TR, TG, TB, 0.3f);
            LevelRenderer.renderLineBox(ps, linesBuf, x0, y0, z0, x1, y1, z1, TR, TG, TB, TA);
            hudText = dims(a, b);

            // Paste box (orange) — transformed size, placed at coordC
            if (cfg.coordC != null) {
                BlockPos c = cfg.coordC.pos;
                Vector3i ta = cfg.transform.apply(new Vector3i(0, 0, 0));
                Vector3i tb = cfg.transform.apply(new Vector3i(b.getX() - a.getX(), b.getY() - a.getY(), b.getZ() - a.getZ()));

                double px0 = c.getX() + Math.min(ta.x(), tb.x());
                double py0 = c.getY() + Math.min(ta.y(), tb.y());
                double pz0 = c.getZ() + Math.min(ta.z(), tb.z());
                double px1 = c.getX() + Math.max(ta.x(), tb.x()) + 1;
                double py1 = c.getY() + Math.max(ta.y(), tb.y()) + 1;
                double pz1 = c.getZ() + Math.max(ta.z(), tb.z()) + 1;

                BoxRenderer.drawFilledBox(ps, px0, py0, pz0, px1, py1, pz1, OR, OG, OB, 0.3f);
                LevelRenderer.renderLineBox(ps, linesBuf, px0, py0, pz0, px1, py1, pz1, OR, OG, OB, TA);
            }
        } else {
            hudText = "";
        }

        bufSource.endBatch(RenderType.lines());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static MMConfig getEffectiveConfig(MMConfig cfg, Minecraft mc) {
        if (cfg.locked || cfg.action == null) return cfg;

        BlockPos look = getLookingAt(mc, cfg.action);
        if (look == null) return cfg;

        var level = mc.level;
        if (level == null) return cfg;
        Location loc = new Location(level.dimension(), look);

        MMConfig eff = cfg.copy();
        switch (cfg.action) {
            case MOVING_COORDS -> eff.coordB = loc;
            case MARK_COPY_A, MARK_CUT_A -> {
                eff.coordA = loc;
                eff.coordB = null;
            }
            case MARK_COPY_B, MARK_CUT_B -> eff.coordB = loc;
            case MARK_PASTE_A -> eff.coordC = loc;
            default -> {}
        }
        return eff;
    }

    /** Pins {@code b} to the dominant axis relative to {@code a} (matches 1.7.10 pinToAxes). */
    private static BlockPos pinToAxis(BlockPos a, BlockPos b) {
        int dx = Math.abs(b.getX() - a.getX());
        int dy = Math.abs(b.getY() - a.getY());
        int dz = Math.abs(b.getZ() - a.getZ());
        if (dx >= dy && dx >= dz) return new BlockPos(b.getX(), a.getY(), a.getZ());
        if (dy >= dx && dy >= dz) return new BlockPos(a.getX(), b.getY(), a.getZ());
        return new BlockPos(a.getX(), a.getY(), b.getZ());
    }

    @Nullable
    private static BlockPos getLookingAt(Minecraft mc, @Nullable PendingAction action) {
        var hit = mc.hitResult;
        var player = mc.player;
        if (player == null) return null;

        if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
            var bhr = (BlockHitResult) hit;
            if (player.isShiftKeyDown() || (action != null && action.isDirectBlockPick())) {
                return bhr.getBlockPos();
            }
            return bhr.getBlockPos().relative(bhr.getDirection());
        }

        // Not looking at a block (air/entity)
        if (action != null && action.isDirectBlockPick()) return null;

        return player.blockPosition();
    }

    private static String dims(BlockPos a, BlockPos b) {
        return (Math.abs(a.getX() - b.getX()) + 1) + "x"
             + (Math.abs(a.getY() - b.getY()) + 1) + "x"
             + (Math.abs(a.getZ() - b.getZ()) + 1);
    }
}
