package dev.wp.matter_manipulator.common.networking;

import dev.wp.matter_manipulator.common.building.BlockSpec;
import dev.wp.matter_manipulator.common.building.Location;
import dev.wp.matter_manipulator.common.data.WeightedSpecList;
import dev.wp.matter_manipulator.common.items.manipulator.MMConfig;
import dev.wp.matter_manipulator.common.items.manipulator.MMState;
import dev.wp.matter_manipulator.common.items.manipulator.PendingAction;
import dev.wp.matter_manipulator.common.items.manipulator.PlaceMode;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketSetCoord {

    /**
     * 0=A, 1=B, 2=C
     */
    public final int which;
    public final Location loc;

    public PacketSetCoord(int which, Location loc) {
        this.which = which;
        this.loc = loc;
    }

    public static void encode(PacketSetCoord pkt, FriendlyByteBuf buf) {
        buf.writeByte(pkt.which);
        buf.writeResourceLocation(pkt.loc.dimension.location());
        buf.writeBlockPos(pkt.loc.pos);
    }

    public static PacketSetCoord decode(FriendlyByteBuf buf) {
        int which = buf.readByte();
        var dimKey = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
        var pos = buf.readBlockPos();
        return new PacketSetCoord(which, new Location(dimKey, pos));
    }

    public static void handle(PacketSetCoord pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            var held = player.getMainHandItem();
            var state = MMState.ofHeld(held);
            if (state == null) return;

            var cfg = state.mutate();

            if (cfg.locked) {
                player.sendSystemMessage(Component.literal("Matter Manipulator positions are LOCKED. Toggle to change."));
                return;
            }

            if (cfg.action != null) {
                handlePendingAction(player, held, state, cfg, pkt.loc);
            } else if (isAreaMode(cfg.placeMode)) {
                // First click in GEOMETRY/EXCHANGING/CABLES: anchor corner A, wait for corner B
                cfg.coordA = pkt.loc;
                cfg.coordB = null;
                cfg.action = PendingAction.MOVING_COORDS;
                player.sendSystemMessage(Component.literal(
                        "Corner A set at " + pkt.loc.pos.toShortString() + ". Right-click to set corner B."));
            } else {
                // Direct coord set for other modes (COPYING, MOVING)
                // In these modes, right-click defaults to setting coord C (Paste target) if no action is active.
                int effectiveWhich = pkt.which;
                if (effectiveWhich == 0 && (cfg.placeMode == PlaceMode.COPYING || cfg.placeMode == PlaceMode.MOVING)) {
                    effectiveWhich = 2;
                }

                setCoord(cfg, effectiveWhich, pkt.loc);
                String label = switch (effectiveWhich) {
                    case 0 -> "A";
                    case 1 -> "B";
                    default -> "C";
                };
                player.sendSystemMessage(Component.literal(
                        "Coord " + label + " set to " + pkt.loc.pos.toShortString()));
            }

            state.saveIfDirty();
        });
        ctx.get().setPacketHandled(true);
    }

    /**
     * True for modes where the user selects an area with two sequential right-clicks.
     */
    private static boolean isAreaMode(PlaceMode mode) {
        return mode == PlaceMode.GEOMETRY || mode == PlaceMode.EXCHANGING || mode == PlaceMode.CABLES;
    }

    private static void setCoord(MMConfig cfg, int which, Location loc) {
        switch (which) {
            case 0 -> cfg.coordA = loc;
            case 1 -> cfg.coordB = loc;
            case 2 -> cfg.coordC = loc;
        }
    }

    private static void handlePendingAction(ServerPlayer player, net.minecraft.world.item.ItemStack held,
                                            MMState state, MMConfig cfg, Location loc) {
        switch (cfg.action) {
            case MOVING_COORDS -> {
                // Second click: confirm corner B
                cfg.coordB = loc;
                cfg.action = null;
                player.sendSystemMessage(Component.literal(
                        "Corner B set at " + loc.pos.toShortString() + "."));
            }
            case GEOM_SELECTING_BLOCK -> {
                var level = player.serverLevel();
                BlockSpec block = BlockSpec.fromWorld(level, loc.pos);
                boolean add = player.isShiftKeyDown();
                String blockName = block.state.getBlock().getName().getString();
                switch (cfg.blockSelectMode) {
                    case CORNERS -> {
                        if (!add) cfg.corners = new WeightedSpecList();
                        cfg.corners.add(block, 1);
                    }
                    case EDGES -> {
                        if (!add) cfg.edges = new WeightedSpecList();
                        cfg.edges.add(block, 1);
                    }
                    case FACES -> {
                        if (!add) cfg.faces = new WeightedSpecList();
                        cfg.faces.add(block, 1);
                    }
                    case VOLUMES -> {
                        if (!add) cfg.volumes = new WeightedSpecList();
                        cfg.volumes.add(block, 1);
                    }
                    case ALL -> {
                        if (!add) {
                            cfg.corners = new WeightedSpecList();
                            cfg.edges = new WeightedSpecList();
                            cfg.faces = new WeightedSpecList();
                            cfg.volumes = new WeightedSpecList();
                        }
                        cfg.corners.add(block, 1);
                        cfg.edges.add(block, 1);
                        cfg.faces.add(block, 1);
                        cfg.volumes.add(block, 1);
                    }
                }
                String slot = cfg.blockSelectMode.name().toLowerCase();
                player.sendSystemMessage(Component.literal(
                        (add ? "Added " : "Set ") + slot + " block to " + blockName));
                cfg.action = null;
            }
            case MARK_COPY_A -> {
                cfg.coordA = loc;
                cfg.action = PendingAction.MARK_COPY_B;
                player.sendSystemMessage(Component.literal("Copy A set. Right-click to set copy B."));
            }
            case MARK_COPY_B -> {
                cfg.coordB = loc;
                cfg.action = null;
                player.sendSystemMessage(Component.literal("Copy B set."));
            }
            case MARK_CUT_A -> {
                cfg.coordA = loc;
                cfg.action = PendingAction.MARK_CUT_B;
                player.sendSystemMessage(Component.literal("Cut A set. Right-click to set cut B."));
            }
            case MARK_CUT_B -> {
                cfg.coordB = loc;
                cfg.action = null;
                player.sendSystemMessage(Component.literal("Cut B set."));
            }
            case MARK_PASTE -> {
                cfg.coordC = loc;
                cfg.action = null;
                player.sendSystemMessage(Component.literal("Paste target set."));
            }
            case EXCH_SET_TARGET -> {
                // Air is valid — "replace with air" means delete matching blocks
                var level = player.serverLevel();
                BlockSpec block = BlockSpec.fromWorld(level, loc.pos);
                cfg.replaceWith = new WeightedSpecList();
                cfg.replaceWith.add(block, 1);
                String name = block.isAir() ? "air (delete)" : block.state.getBlock().getName().getString();
                player.sendSystemMessage(Component.literal("Will replace with: " + name));
                cfg.action = null;
            }
            case EXCH_ADD_REPLACE -> {
                var level = player.serverLevel();
                BlockSpec block = BlockSpec.fromWorld(level, loc.pos);
                if (!block.isAir()) {
                    if (cfg.replaceWhitelist == null) cfg.replaceWhitelist = new WeightedSpecList();
                    cfg.replaceWhitelist.add(block, 1);
                    player.sendSystemMessage(Component.literal(
                            "Added to replace whitelist: " + block.state.getBlock().getName().getString()));
                }
                cfg.action = null;
            }
            case EXCH_SET_REPLACE -> {
                var level = player.serverLevel();
                BlockSpec block = BlockSpec.fromWorld(level, loc.pos);
                if (!block.isAir()) {
                    cfg.replaceWhitelist = new WeightedSpecList();
                    cfg.replaceWhitelist.add(block, 1);
                    player.sendSystemMessage(Component.literal(
                            "Replace whitelist set to: " + block.state.getBlock().getName().getString()));
                }
                cfg.action = null;
            }
            case PICK_CABLE -> {
                var level = player.serverLevel();
                BlockSpec block = BlockSpec.fromWorld(level, loc.pos);
                if (!block.isAir()) {
                    cfg.replaceWith = new WeightedSpecList();
                    cfg.replaceWith.add(block, 1);
                    player.sendSystemMessage(Component.literal(
                            "Cable block set to: " + block.state.getBlock().getName().getString()));
                } else {
                    player.sendSystemMessage(Component.literal("No block there."));
                }
                cfg.action = null;
            }
        }
    }
}
