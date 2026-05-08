package dev.wp.matter_manipulator.common.items.manipulator;

import dev.wp.matter_manipulator.common.building.Location;
import dev.wp.matter_manipulator.common.data.WeightedSpecList;
import net.minecraft.nbt.CompoundTag;

public class MMConfig {

    public PlaceMode placeMode = PlaceMode.GEOMETRY;
    public RemoveMode removeMode = RemoveMode.NONE;
    public ShapeType shape = ShapeType.CUBE;

    public Location coordA = null;
    public Location coordB = null;
    public Location coordC = null;

    public WeightedSpecList corners = new WeightedSpecList();
    public WeightedSpecList edges = new WeightedSpecList();
    public WeightedSpecList faces = new WeightedSpecList();
    public WeightedSpecList volumes = new WeightedSpecList();

    public WeightedSpecList replaceWhitelist = new WeightedSpecList();
    public WeightedSpecList replaceWith = new WeightedSpecList();

    public int[] arraySpan = new int[]{1, 1, 1};
    public MMTransform transform = new MMTransform();

    /** Captured blocks for COPYING/MOVING modes, stored as relative-position NBT list. */
    public CompoundTag capturedRegion = null;
    /** Origin of the captured region (coordA at time of capture). */
    public Location captureOrigin = null;

    /** Pending one-shot action that the next right-click block will fulfil (server-side). */
    public PendingAction action = null;
    /** Which slot type is being targeted when blockSelectMode action is active. */
    public BlockSelectMode blockSelectMode = BlockSelectMode.ALL;

    public boolean locked = false;

    public MMConfig() {}

    public MMConfig copy() {
        MMConfig next = new MMConfig();
        next.placeMode = this.placeMode;
        next.removeMode = this.removeMode;
        next.shape = this.shape;
        next.coordA = this.coordA;
        next.coordB = this.coordB;
        next.coordC = this.coordC;
        next.corners = this.corners;
        next.edges = this.edges;
        next.faces = this.faces;
        next.volumes = this.volumes;
        next.replaceWhitelist = this.replaceWhitelist;
        next.replaceWith = this.replaceWith;
        next.arraySpan = this.arraySpan.clone();
        next.transform = this.transform.copy();
        next.capturedRegion = this.capturedRegion != null ? this.capturedRegion.copy() : null;
        next.captureOrigin = this.captureOrigin;
        next.action = this.action;
        next.blockSelectMode = this.blockSelectMode;
        next.locked = this.locked;
        return next;
    }

    public void clearCoords() {
        coordA = null;
        coordB = null;
        coordC = null;
        capturedRegion = null;
        captureOrigin = null;
    }

    public boolean hasRegion() {
        return coordA != null && coordB != null;
    }

    public boolean hasPasteTarget() {
        return coordC != null;
    }

    public void rotate(net.minecraft.core.Direction axis, int amount) {
        transform.rotate(axis, amount);
    }
}
