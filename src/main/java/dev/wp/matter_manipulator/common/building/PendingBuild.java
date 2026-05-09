package dev.wp.matter_manipulator.common.building;

import dev.wp.matter_manipulator.BlockTags;
import dev.wp.matter_manipulator.MMMod;
import dev.wp.matter_manipulator.common.config.MMModConfig;
import dev.wp.matter_manipulator.common.items.manipulator.MMState;
import dev.wp.matter_manipulator.common.items.manipulator.MMTier;
import dev.wp.matter_manipulator.common.items.manipulator.PlaceMode;
import dev.wp.matter_manipulator.common.items.manipulator.RemoveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.Tags;

import javax.annotation.Nullable;
import java.util.*;

public class PendingBuild {

    private static final Map<UUID, PendingBuild> ACTIVE = new HashMap<>();

    private final UUID playerId;
    private final MMTier tier;
    private final Deque<PendingBlock> queue;
    private final int totalBlocks;

    /** Non-null in MOVING mode: source region cleared after build completes. */
    @Nullable private final BlockPos sourceMin;
    @Nullable private final BlockPos sourceMax;

    private PendingBuild(UUID playerId, MMTier tier, List<PendingBlock> blocks,
                         @Nullable BlockPos sourceMin, @Nullable BlockPos sourceMax) {
        this.playerId = playerId;
        this.tier = tier;
        this.queue = new ArrayDeque<>(blocks);
        this.totalBlocks = blocks.size();
        this.sourceMin = sourceMin;
        this.sourceMax = sourceMax;
    }

    /**
     * Start (or replace) a build for a player.
     *
     * @param blocks     PendingBlocks whose {@code relPos} is relative to {@code anchor}
     *                   (or absolute world position when anchor = {@link BlockPos#ZERO}).
     * @param anchor     Added to each block's relPos to produce the world position.
     * @param sourceMin  Optional min corner to clear when build finishes (MOVING mode).
     * @param sourceMax  Optional max corner to clear when build finishes (MOVING mode).
     */
    public static void start(Player player, MMTier tier, List<PendingBlock> blocks,
                              BlockPos anchor, @Nullable BlockPos sourceMin, @Nullable BlockPos sourceMax) {
        if (blocks.isEmpty()) return;
        var translated = new ArrayList<PendingBlock>(blocks.size());
        for (var pb : blocks) {
            translated.add(new PendingBlock(anchor.offset(pb.relPos), pb.spec, pb.buildOrder));
        }
        translated.sort(Comparator.comparingInt(pb -> pb.buildOrder));
        ACTIVE.put(player.getUUID(), new PendingBuild(player.getUUID(), tier, translated, sourceMin, sourceMax));
        player.sendSystemMessage(Component.literal("Building " + blocks.size() + " blocks..."));
    }

    public static void cancel(UUID playerId) {
        ACTIVE.remove(playerId);
    }

    public static boolean hasActiveBuild(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    /** Called from ItemMatterManipulator.inventoryTick on the server. */
    public static void tick(Player player, ItemStack stack, MMTier tier, MMState state) {
        var build = ACTIVE.get(player.getUUID());
        if (build == null) return;
        build.doTick(player, stack, state);
    }

    private void doTick(Player player, ItemStack stack, MMState state) {
        if (!(player.level() instanceof ServerLevel level)) return;

        var inv = new MMInventory(player);
        var cfg = state.getConfig();
        int placed = 0;
        int shuffleCount = 0;

        int speed = (tier == MMTier.MK3) ? MMModConfig.MK3_PLACE_SPEED.get() : tier.placeSpeed;

        while (placed < speed && !queue.isEmpty()) {
            var pb = queue.poll();

            // Skip out-of-world Y
            if (pb.relPos.getY() < level.getMinBuildHeight() || pb.relPos.getY() >= level.getMaxBuildHeight()) {
                continue;
            }

            // Defer unloaded chunks
            if (!level.isLoaded(pb.relPos)) {
                queue.addLast(pb);
                if (++shuffleCount > queue.size()) break;
                continue;
            }

            // Range check
            if (tier.range > 0) {
                double distSq = player.blockPosition().distSqr(pb.relPos);
                if (distSq > (long) tier.range * tier.range) continue;
            }

            var existingState = level.getBlockState(pb.relPos);

            // Skip if block is already correct (no tile data to restore)
            if (existingState.equals(pb.spec.state) && pb.spec.tileData == null) {
                placed++;
                continue;
            }

            // Check whether we're allowed to replace the existing block
            if (!existingState.isAir()) {
                if (!canReplace(level, pb.relPos, existingState, cfg.removeMode, cfg.placeMode)) {
                    queue.addLast(pb);
                    if (++shuffleCount > queue.size()) break;
                    continue;
                }
            }

            // Dry-run placement check (matches 1.7.10 ProxiedWorld)
            var simulated = new SimulatedLevelReader(level, pb.relPos);
            if (!pb.spec.state.canSurvive(simulated, pb.relPos)) {
                // Dependency not met (e.g. torch neighbor), shuffle to back
                queue.addLast(pb);
                if (++shuffleCount > queue.size()) break;
                continue;
            }

            // Energy cost
            double hardness = Math.max(existingState.getDestroySpeed(level, pb.relPos), 0);
            double dist = player.position().distanceTo(Vec3.atCenterOf(pb.relPos));
            boolean hasTile = level.getBlockEntity(pb.relPos) != null;
            long feCost = computeFECost(hardness, dist, hasTile);

            if (!state.hasCharge(feCost)) {
                queue.addFirst(pb);
                player.sendSystemMessage(Component.literal("Not enough energy to continue building."));
                ACTIVE.remove(playerId);
                return;
            }

            // Item check
            var required = pb.spec.toStack();
            if (!required.isEmpty() && !inv.canConsume(required)) {
                queue.addLast(pb);
                if (++shuffleCount > queue.size()) {
                    MMMod.LOGGER.debug("Skipping {} — no items available", pb.relPos);
                    break;
                }
                continue;
            }

            // Remove existing block first
            if (!existingState.isAir()) {
                if (!dropBlacklisted(existingState)) {
                    var drops = Block.getDrops(existingState, level, pb.relPos, level.getBlockEntity(pb.relPos), player, stack);
                    for (var drop : drops) {
                        inv.give(drop);
                    }
                }
                level.setBlockAndUpdate(pb.relPos, Blocks.AIR.defaultBlockState());
            }

            // Place the new block
            var sound = pb.spec.state.getSoundType(level, pb.relPos, player);
            level.setBlockAndUpdate(pb.relPos, pb.spec.state);

            // Restore tile entity data if present
            if (pb.spec.tileData != null) {
                BlockEntity be = level.getBlockEntity(pb.relPos);
                if (be != null) {
                    var tileTag = pb.spec.tileData.copy();
                    tileTag.putInt("x", pb.relPos.getX());
                    tileTag.putInt("y", pb.relPos.getY());
                    tileTag.putInt("z", pb.relPos.getZ());
                    be.load(tileTag);
                    be.setChanged();
                }
            }

            state.consumeCharge(feCost);
            inv.consume(required);

            level.playSound(null, pb.relPos, sound.getPlaceSound(), SoundSource.BLOCKS,
                sound.getVolume(), sound.getPitch());

            placed++;
            shuffleCount = 0;
        }

        if (queue.isEmpty()) {
            // MOVING mode: remove source blocks after all destination blocks are placed
            if (sourceMin != null && sourceMax != null) {
                clearSourceRegion(level);
            }
            ACTIVE.remove(playerId);
            player.sendSystemMessage(Component.literal("Build complete! (" + totalBlocks + " blocks)"));
        }
    }

    /**
     * Returns true if the existing block may be overwritten given the current remove mode.
     * Bedrock-like blocks with hardness &lt; 0 are never allowed.
     */
    private static boolean canReplace(ServerLevel level, BlockPos pos, BlockState existing,
                                      RemoveMode removeMode, PlaceMode placeMode) {
        // Bedrock etc. are never replaceable
        if (existing.getDestroySpeed(level, pos) < 0) return false;

        // EXCHANGING: blocks were pre-filtered to the whitelist, always replace
        if (placeMode == PlaceMode.EXCHANGING) return true;

        return switch (removeMode) {
            case NONE        -> false;
            case REPLACEABLE -> existing.canBeReplaced();
            case ALL         -> true;
        };
    }

    /** MOVING mode: remove every non-air, non-bedrock block in the source region. */
    private void clearSourceRegion(ServerLevel level) {
        if (sourceMin == null || sourceMax == null) return;
        for (int x = sourceMin.getX(); x <= sourceMax.getX(); x++) {
            for (int y = sourceMin.getY(); y <= sourceMax.getY(); y++) {
                for (int z = sourceMin.getZ(); z <= sourceMax.getZ(); z++) {
                    var pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    if (!state.isAir() && state.getDestroySpeed(level, pos) >= 0) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    /** FE = 128 × √(hardness+1) × dist^1.25 × (hasTile ? 16 : 1), minimum 1. */
    public static long computeFECost(double hardness, double dist, boolean hasTile) {
        double base = 128.0 * Math.sqrt(Math.max(hardness, 0) + 1.0);
        double distMult = dist > 1.0 ? Math.pow(dist, 1.25) : 1.0;
        double tileMult = hasTile ? 16.0 : 1.0;
        return Math.max(1L, (long) (base * distMult * tileMult));
    }

    private static boolean dropBlacklisted(BlockState state) {
        return state.is(BlockTags.DROP_BLACKLISTED);
    }
}
