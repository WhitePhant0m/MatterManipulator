package dev.wp.matter_manipulator.common.items.manipulator;

import dev.wp.matter_manipulator.client.gui.RadialMenuBuilder;
import dev.wp.matter_manipulator.client.gui.RadialMenuScreen;
import dev.wp.matter_manipulator.common.building.Location;
import dev.wp.matter_manipulator.common.building.PendingBuild;
import dev.wp.matter_manipulator.common.config.MMModConfig;
import dev.wp.matter_manipulator.common.networking.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class ItemMatterManipulator extends Item {

    private final MMTier tier;

    public ItemMatterManipulator(MMTier tier) {
        super(new Item.Properties().stacksTo(1));
        this.tier = tier;
    }

    public MMTier getTier() {
        return tier;
    }

    // ── Interaction ──────────────────────────────────────────────────────────────

    @Override
    public @Nonnull InteractionResult useOn(UseOnContext ctx) {
        var player = ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;
        var level = ctx.getLevel();
        var stack = ctx.getItemInHand();
        var pos = ctx.getClickedPos();
        var face = ctx.getClickedFace();

        if (level.isClientSide) {
            var cfg = MMState.of(stack).getConfig();
            if (cfg.locked) return InteractionResult.SUCCESS;

            // Block-selection actions target the actual clicked block; area-corner selection uses the adjacent face
            var selectedPos = (cfg.action != null && cfg.action.isDirectBlockPick())
                    ? pos
                    : (player.isShiftKeyDown() ? pos : pos.relative(face));

            // In COPYING/MOVING mode, right-click sets Coord C (Paste) by default, NOT Coord A (Source).
            // Source coordinates (A/B) must be set via the Mark buttons (actions).
            int which = (cfg.placeMode == PlaceMode.COPYING || cfg.placeMode == PlaceMode.MOVING) ? 2 : 0;

            MMNetwork.CHANNEL.sendToServer(
                    new PacketSetCoord(which, new Location(level.dimension(), selectedPos)));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }


    /**
     * Right-click air → open the radial config menu, unless a block-selection action is active.
     */
    @Override
    public @Nonnull InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            var cfg = MMState.of(stack).getConfig();
            if (cfg.action == null) {
                openRadialMenu(stack);
            } else if (!cfg.locked) {
                int which = (cfg.placeMode == PlaceMode.COPYING || cfg.placeMode == PlaceMode.MOVING) ? 2 : 0;
                MMNetwork.CHANNEL.sendToServer(new PacketSetCoord(which, new Location(level.dimension(), player.blockPosition())));
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @OnlyIn(Dist.CLIENT)
    private void openRadialMenu(ItemStack stack) {
        var cfg = MMState.of(stack).getConfig();
        Minecraft.getInstance().setScreen(new RadialMenuScreen(buildRadialMenu(cfg)));
    }

    // spotless:off
    @OnlyIn(Dist.CLIENT)
    private RadialMenuBuilder buildRadialMenu(MMConfig cfg) {
        int allModes = MMCapability.ALLOW_GEOMETRY | MMCapability.ALLOW_COPYING
                | MMCapability.ALLOW_MOVING | MMCapability.ALLOW_EXCHANGING | MMCapability.ALLOW_CABLES;
        boolean hasMultiMode = Integer.bitCount(tier.capabilities & allModes) > 1;
        boolean hasRemove = tier.hasCapability(MMCapability.ALLOW_REMOVING);
        boolean hasCopy = tier.hasCapability(MMCapability.ALLOW_COPYING);
        boolean hasMove = tier.hasCapability(MMCapability.ALLOW_MOVING);
        boolean hasExch = tier.hasCapability(MMCapability.ALLOW_EXCHANGING);
        boolean hasCable = tier.hasCapability(MMCapability.ALLOW_CABLES);

        var b = new RadialMenuBuilder();

        // "Set Mode" branch — hidden for PROTO which only has GEOMETRY
        if (hasMultiMode) {
            b.branch().label("Set Mode")
                    .branch().label("Remove Mode").hidden(!hasRemove)
                    .option().label("None").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetRemoveMode(RemoveMode.NONE))).done()
                    .option().label("Replaceable").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetRemoveMode(RemoveMode.REPLACEABLE))).done()
                    .option().label("All Blocks").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetRemoveMode(RemoveMode.ALL))).done()
                    .done()
                    .option().label("Geometry").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.GEOMETRY))).done()
                    .option().label("Copying").hidden(!hasCopy).onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.COPYING))).done()
                    .option().label("Moving").hidden(!hasMove).onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.MOVING))).done()
                    .option().label("Exchanging").hidden(!hasExch).onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.EXCHANGING))).done()
                    .option().label("Cables").hidden(!hasCable).onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPlaceMode(PlaceMode.CABLES))).done()
                    .done();
        } else if (hasRemove) {
            b.branch().label("Remove Mode")
                    .option().label("None").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetRemoveMode(RemoveMode.NONE))).done()
                    .option().label("Replaceable").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetRemoveMode(RemoveMode.REPLACEABLE))).done()
                    .option().label("All Blocks").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetRemoveMode(RemoveMode.ALL))).done()
                    .done();
        }

        // Mode-specific options
        switch (cfg.placeMode) {
            case GEOMETRY -> addGeometryOptions(b);
            case COPYING -> addCopyingOptions(b);
            case MOVING -> addMovingOptions(b);
            case EXCHANGING -> addExchangingOptions(b);
            case CABLES -> addCableOptions(b);
        }

        if (cfg.placeMode == PlaceMode.COPYING || cfg.placeMode == PlaceMode.MOVING) {
            addTransformOptions(b);
        }

        // Universal actions
        b.option().label("Start Build").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketStartBuild())).done();
        b.option().label("Cancel Build").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketCancelBuild())).done();

        return b;
    }

    @OnlyIn(Dist.CLIENT)
    private void addGeometryOptions(RadialMenuBuilder b) {
        b.branch().label("Select Blocks")
                .option().label("Corners").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.GEOM_SELECTING_BLOCK, BlockSelectMode.CORNERS))).done()
                .option().label("Edges").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.GEOM_SELECTING_BLOCK, BlockSelectMode.EDGES))).done()
                .option().label("Faces").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.GEOM_SELECTING_BLOCK, BlockSelectMode.FACES))).done()
                .option().label("Volumes").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.GEOM_SELECTING_BLOCK, BlockSelectMode.VOLUMES))).done()
                .option().label("All").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.GEOM_SELECTING_BLOCK, BlockSelectMode.ALL))).done()
                .option().label("Clear Palette").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketClearBlocks())).done()
                .done();
        b.branch().label("Set Shape")
                .option().label("Line").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetShape(ShapeType.LINE))).done()
                .option().label("Cube").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetShape(ShapeType.CUBE))).done()
                .option().label("Sphere").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetShape(ShapeType.SPHERE))).done()
                .option().label("Cylinder").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetShape(ShapeType.CYLINDER))).done()
                .done();
    }

    @OnlyIn(Dist.CLIENT)
    private void addCopyingOptions(RadialMenuBuilder b) {
        b.option().label("Mark Copy").onClicked(() -> {
            if (MMModConfig.PASTE_AUTO_CLEAR.get()) {
                MMNetwork.CHANNEL.sendToServer(new PacketClearCoords());
                if (MMModConfig.RESET_TRANSFORM.get()) {
                    MMNetwork.CHANNEL.sendToServer(new PacketResetTransform());
                }
            }
            MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.MARK_COPY_A, null));
        }).done();
        b.option().label("Mark Paste").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.MARK_PASTE_A, null))).done();
    }

    @OnlyIn(Dist.CLIENT)
    private void addMovingOptions(RadialMenuBuilder b) {
        b.option().label("Mark Cut").onClicked(() -> {
            if (MMModConfig.PASTE_AUTO_CLEAR.get()) {
                MMNetwork.CHANNEL.sendToServer(new PacketClearCoords());
                if (MMModConfig.RESET_TRANSFORM.get()) {
                    MMNetwork.CHANNEL.sendToServer(new PacketResetTransform());
                }
            }
            MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.MARK_CUT_A, null));
        }).done();
        b.option().label("Mark Paste").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.MARK_PASTE_A, null))).done();
    }

    @OnlyIn(Dist.CLIENT)
    private void addExchangingOptions(RadialMenuBuilder b) {
        b.branch().label("Replace Whitelist")
                .option().label("Add Block").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.EXCH_ADD_REPLACE, null))).done()
                .option().label("Set Block").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.EXCH_SET_REPLACE, null))).done()
                .done();
        b.option().label("Set Replace Target").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.EXCH_SET_TARGET, null))).done();
    }

    @OnlyIn(Dist.CLIENT)
    private void addTransformOptions(RadialMenuBuilder b) {
        var sub = b.branch().label("Edit Transform");

        sub.option().label("Rotate X+").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketRotateTransform(Direction.EAST, true))).done();
        sub.option().label("Rotate X-").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketRotateTransform(Direction.EAST, false))).done();
        sub.option().label("Rotate Y+").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketRotateTransform(Direction.UP, true))).done();
        sub.option().label("Rotate Y-").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketRotateTransform(Direction.UP, false))).done();
        sub.option().label("Rotate Z+").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketRotateTransform(Direction.SOUTH, true))).done();
        sub.option().label("Rotate Z-").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketRotateTransform(Direction.SOUTH, false))).done();

        sub.option().label("Flip X").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketToggleFlip(MMTransform.FLIP_X))).done();
        sub.option().label("Flip Y").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketToggleFlip(MMTransform.FLIP_Y))).done();
        sub.option().label("Flip Z").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketToggleFlip(MMTransform.FLIP_Z))).done();

        sub.option().label("Reset Transform").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketResetTransform())).done();

        sub.done();
    }

    @OnlyIn(Dist.CLIENT)
    private void addCableOptions(RadialMenuBuilder b) {
        b.option().label("Pick Cable").onClicked(() -> MMNetwork.CHANNEL.sendToServer(new PacketSetPendingAction(PendingAction.PICK_CABLE, null))).done();
    }
    // spotless:on

    // ── Tick (building loop) ─────────────────────────────────────────────────────

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof Player player)) return;
        if (!PendingBuild.hasActiveBuild(player.getUUID())) return;

        var state = MMState.of(stack);
        PendingBuild.tick(player, stack, tier, state);
        state.saveIfDirty();
    }

    // ── Tooltip ──────────────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        var state = MMState.of(stack);
        long charge = state.getCharge();
        long max = tier.maxFE;
        tooltip.add(Component.literal(String.format("Energy: %,d / %,d FE", charge, max)));
        tooltip.add(Component.literal("Range: " + (tier.range < 0 ? "Unlimited" : tier.range + " blocks")));
        tooltip.add(Component.literal("Speed: " + tier.placeSpeed + " blocks/tick"));

        var cfg = state.getConfig();
        if (cfg.coordA != null) tooltip.add(Component.literal("A: " + cfg.coordA.pos.toShortString()));
        if (cfg.coordB != null) tooltip.add(Component.literal("B: " + cfg.coordB.pos.toShortString()));
        if (cfg.coordC != null) tooltip.add(Component.literal("C: " + cfg.coordC.pos.toShortString()));

        tooltip.add(Component.literal("Mode: " + cfg.placeMode + " | Shape: " + cfg.shape));
        if (cfg.locked) {
            tooltip.add(Component.literal("POSITIONS LOCKED").withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    // ── Forge Energy Capability ──────────────────────────────────────────────────

    @Override
    public ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new ICapabilityProvider() {
            private final LazyOptional<IEnergyStorage> energyCap =
                    LazyOptional.of(() -> new StackEnergyStorage(stack, tier));

            @Override
            public @Nonnull <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
                if (cap == ForgeCapabilities.ENERGY) return energyCap.cast();
                return LazyOptional.empty();
            }
        };
    }

    // ── Energy Storage backed by NBT ─────────────────────────────────────────────

    private static class StackEnergyStorage implements IEnergyStorage {

        private final ItemStack stack;
        private final MMTier tier;

        StackEnergyStorage(ItemStack stack, MMTier tier) {
            this.stack = stack;
            this.tier = tier;
        }

        private MMState state() {
            return MMState.of(stack);
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            var s = state();
            long current = s.getCharge();
            long space = tier.maxFE - current;
            long accepted = Math.min(space, maxReceive);
            if (!simulate) s.setCharge(current + accepted);
            return (int) Math.min(accepted, Integer.MAX_VALUE);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            var s = state();
            long current = s.getCharge();
            long extracted = Math.min(current, maxExtract);
            if (!simulate) s.setCharge(current - extracted);
            return (int) Math.min(extracted, Integer.MAX_VALUE);
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(state().getCharge(), Integer.MAX_VALUE);
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(tier.maxFE, Integer.MAX_VALUE);
        }

        @Override
        public boolean canExtract() {
            return true;
        }

        @Override
        public boolean canReceive() {
            return true;
        }
    }
}
