package dev.wp.matter_manipulator.common.networking;

import dev.wp.matter_manipulator.common.building.*;
import dev.wp.matter_manipulator.common.building.shapes.ShapeLine;
import dev.wp.matter_manipulator.common.building.shapes.ShapeBlock;
import dev.wp.matter_manipulator.common.items.manipulator.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Client requests the server to start a build based on the current MMConfig.
 */
public class PacketStartBuild {

    public PacketStartBuild() {}

    public static void encode(PacketStartBuild pkt, FriendlyByteBuf buf) {}

    public static PacketStartBuild decode(FriendlyByteBuf buf) {
        return new PacketStartBuild();
    }

    public static void handle(PacketStartBuild pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) execute(player);
        });
        ctx.get().setPacketHandled(true);
    }

    public static void execute(ServerPlayer player) {
        var held = player.getMainHandItem();
        if (!(held.getItem() instanceof ItemMatterManipulator item)) return;

        var state = MMState.of(held);
        var cfg = state.getConfig();
        var tier = item.getTier();

        if (cfg.placeMode == PlaceMode.GEOMETRY   && !tier.hasCapability(MMCapability.ALLOW_GEOMETRY))   return;
        if (cfg.placeMode == PlaceMode.COPYING    && !tier.hasCapability(MMCapability.ALLOW_COPYING))    return;
        if (cfg.placeMode == PlaceMode.MOVING     && !tier.hasCapability(MMCapability.ALLOW_MOVING))     return;
        if (cfg.placeMode == PlaceMode.EXCHANGING && !tier.hasCapability(MMCapability.ALLOW_EXCHANGING)) return;
        if (cfg.placeMode == PlaceMode.CABLES     && !tier.hasCapability(MMCapability.ALLOW_CABLES))     return;

        switch (cfg.placeMode) {
            case GEOMETRY  -> startGeometry(player, tier, state, cfg);
            case COPYING   -> startCopyOrMove(player, tier, state, cfg, false);
            case MOVING    -> startCopyOrMove(player, tier, state, cfg, true);
            case EXCHANGING -> startExchanging(player, tier, state, cfg);
            case CABLES    -> startCables(player, tier, state, cfg);
        }
    }

    // ── GEOMETRY ─────────────────────────────────────────────────────────────────

    private static void startGeometry(ServerPlayer player, MMTier tier, MMState state, MMConfig cfg) {
        if (cfg.coordA == null || cfg.coordB == null) {
            player.sendSystemMessage(Component.literal("Set area first (right-click to set corner A, then corner B)."));
            return;
        }
        var level = player.serverLevel();
        if (!cfg.coordA.dimension.equals(level.dimension())) {
            player.sendSystemMessage(Component.literal("Coords must be in the current dimension."));
            return;
        }

        List<ShapeBlock> shapeBlocks = switch (cfg.shape) {
            case LINE     -> ShapeLine.generate(cfg.coordA.pos, cfg.coordB.pos);
            case CUBE     -> dev.wp.matter_manipulator.common.building.shapes.ShapeCube.generate(cfg.coordA.pos, cfg.coordB.pos);
            case SPHERE   -> dev.wp.matter_manipulator.common.building.shapes.ShapeSphere.generate(cfg.coordA.pos, cfg.coordB.pos);
            case CYLINDER -> dev.wp.matter_manipulator.common.building.shapes.ShapeCylinder.generate(cfg.coordA.pos, cfg.coordB.pos, 3);
        };

        var rng = level.getRandom();
        var blocks = new ArrayList<PendingBlock>();
        for (var sb : shapeBlocks) {
            var palette = switch (sb.slot()) {
                case CORNER -> cfg.corners;
                case EDGE   -> cfg.edges;
                case FACE   -> cfg.faces;
                case VOLUME -> cfg.volumes;
            };
            var picked = palette.pick(rng);
            if (picked == null) continue;
            blocks.add(new PendingBlock(sb.pos(), picked));
        }

        if (blocks.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "No palette blocks set. Use the radial menu to pick blocks for CORNER/EDGE/FACE/VOLUME slots."));
            return;
        }
        PendingBuild.start(player, tier, blocks, BlockPos.ZERO, null, null);
    }

    // ── COPYING / MOVING ─────────────────────────────────────────────────────────

    private static void startCopyOrMove(ServerPlayer player, MMTier tier, MMState state, MMConfig cfg, boolean moving) {
        if (cfg.coordA == null || cfg.coordB == null) {
            player.sendSystemMessage(Component.literal(
                moving ? "Mark cut corners A and B first." : "Mark copy corners A and B first."));
            return;
        }
        if (cfg.coordC == null) {
            player.sendSystemMessage(Component.literal("Mark paste target first."));
            return;
        }
        var level = player.serverLevel();
        if (!cfg.coordA.dimension.equals(level.dimension())) {
            player.sendSystemMessage(Component.literal("Coords must be in the current dimension."));
            return;
        }

        var rawBlocks = BlockAnalyzer.analyzeRegion(level, cfg.coordA.pos, cfg.coordB.pos);
        if (rawBlocks.isEmpty()) {
            player.sendSystemMessage(Component.literal("No blocks found in the selected region."));
            return;
        }

        var blocks = new ArrayList<PendingBlock>();
        for (var pb : rawBlocks) {
            Vector3i transformedRel = cfg.transform.apply(new Vector3i(pb.relPos.getX(), pb.relPos.getY(), pb.relPos.getZ()));
            
            BlockState blockState = pb.spec.state;

            // TODO: better BlockState transformation mapping
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

            if (rotation != Rotation.NONE) blockState = blockState.rotate(level, BlockPos.ZERO, rotation);
            if (cfg.transform.flipX) blockState = blockState.mirror(Mirror.LEFT_RIGHT);
            if (cfg.transform.flipZ) blockState = blockState.mirror(Mirror.FRONT_BACK);

            blocks.add(new PendingBlock(new BlockPos(transformedRel.x(), transformedRel.y(), transformedRel.z()), new BlockSpec(blockState, pb.spec.tileData)));
        }

        BlockPos sourceMin = null, sourceMax = null;
        if (moving) {
            var a = cfg.coordA.pos;
            var b = cfg.coordB.pos;
            sourceMin = new BlockPos(
                Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            sourceMax = new BlockPos(
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        PendingBuild.start(player, tier, blocks, cfg.coordC.pos, sourceMin, sourceMax);
    }

    // ── EXCHANGING ───────────────────────────────────────────────────────────────

    private static void startExchanging(ServerPlayer player, MMTier tier, MMState state, MMConfig cfg) {
        if (cfg.coordA == null || cfg.coordB == null) {
            player.sendSystemMessage(Component.literal("Set area first."));
            return;
        }
        if (cfg.replaceWhitelist == null || cfg.replaceWhitelist.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "Set blocks to replace first (Replace Whitelist → Add Block or Set Block)."));
            return;
        }
        if (cfg.replaceWith == null || cfg.replaceWith.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "Set the replacement block first (Set Replace Target)."));
            return;
        }

        var level = player.serverLevel();
        if (!cfg.coordA.dimension.equals(level.dimension())) {
            player.sendSystemMessage(Component.literal("Coords must be in the current dimension."));
            return;
        }

        var rng = level.getRandom();
        var blocks = new ArrayList<PendingBlock>();

        var a = cfg.coordA.pos;
        var b = cfg.coordB.pos;
        int minX = Math.min(a.getX(), b.getX()), maxX = Math.max(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY()), maxY = Math.max(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ()), maxZ = Math.max(a.getZ(), b.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    var pos = new BlockPos(x, y, z);
                    var existing = BlockSpec.fromWorld(level, pos);
                    if (existing.isAir()) continue;
                    if (!cfg.replaceWhitelist.containsBlock(existing)) continue;
                    var replacement = cfg.replaceWith.pick(rng);
                    if (replacement == null) continue;
                    // Use absolute positions with ZERO anchor
                    blocks.add(new PendingBlock(pos, replacement));
                }
            }
        }

        if (blocks.isEmpty()) {
            player.sendSystemMessage(Component.literal("No matching blocks found in the selected region."));
            return;
        }

        PendingBuild.start(player, tier, blocks, BlockPos.ZERO, null, null);
    }

    // ── CABLES ───────────────────────────────────────────────────────────────────

    private static void startCables(ServerPlayer player, MMTier tier, MMState state, MMConfig cfg) {
        if (cfg.coordA == null || cfg.coordB == null) {
            player.sendSystemMessage(Component.literal("Set cable start and end first."));
            return;
        }
        if (cfg.replaceWith == null || cfg.replaceWith.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                "Pick a cable block first (Pick Cable → right-click an existing cable)."));
            return;
        }

        var level = player.serverLevel();
        if (!cfg.coordA.dimension.equals(level.dimension())) {
            player.sendSystemMessage(Component.literal("Coords must be in the current dimension."));
            return;
        }

        var rng = level.getRandom();
        var a = cfg.coordA.pos;
        var b = cfg.coordB.pos;

        // Pin to dominant axis (matches 1.7.10 pinToAxes behaviour)
        int dx = Math.abs(b.getX() - a.getX());
        int dy = Math.abs(b.getY() - a.getY());
        int dz = Math.abs(b.getZ() - a.getZ());
        BlockPos pinnedB;
        if (dx >= dy && dx >= dz)      pinnedB = new BlockPos(b.getX(), a.getY(), a.getZ());
        else if (dy >= dx && dy >= dz) pinnedB = new BlockPos(a.getX(), b.getY(), a.getZ());
        else                           pinnedB = new BlockPos(a.getX(), a.getY(), b.getZ());

        var line = ShapeLine.generate(a, pinnedB);
        var blocks = new ArrayList<PendingBlock>();
        for (var sb : line) {
            var picked = cfg.replaceWith.pick(rng);
            if (picked == null) continue;
            // Absolute positions with ZERO anchor
            blocks.add(new PendingBlock(sb.pos(), picked));
        }

        if (blocks.isEmpty()) {
            player.sendSystemMessage(Component.literal("No positions to fill."));
            return;
        }

        PendingBuild.start(player, tier, blocks, BlockPos.ZERO, null, null);
    }
}
