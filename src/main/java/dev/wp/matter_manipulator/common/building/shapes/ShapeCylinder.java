package dev.wp.matter_manipulator.common.building.shapes;

import dev.wp.matter_manipulator.common.items.manipulator.SlotType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class ShapeCylinder {

    /**
     * Cylinder whose axis runs from a to b. The radius is derived from the
     * perpendicular offset provided by coordC (or defaults to 3 if absent).
     * For simplicity: axis = primary direction A→B, radius applied in the
     * two perpendicular axes.
     */
    public static List<ShapeBlock> generate(BlockPos a, BlockPos b, int radius) {
        var result = new ArrayList<ShapeBlock>();

        int dx = b.getX() - a.getX();
        int dy = b.getY() - a.getY();
        int dz = b.getZ() - a.getZ();

        // Determine primary axis
        if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz)) {
            // Y axis cylinder
            generateAlongAxis(result, a, b, Direction.Axis.Y, radius);
        } else if (Math.abs(dx) >= Math.abs(dz)) {
            generateAlongAxis(result, a, b, Direction.Axis.X, radius);
        } else {
            generateAlongAxis(result, a, b, Direction.Axis.Z, radius);
        }

        return result;
    }

    private static void generateAlongAxis(List<ShapeBlock> result, BlockPos a, BlockPos b,
                                          Direction.Axis axis, int radius) {
        int r2 = radius * radius;
        int shellR2 = (radius - 1) * (radius - 1);

        int minAxis = axisCoord(a, axis), maxAxis = axisCoord(b, axis);
        if (minAxis > maxAxis) { int tmp = minAxis; minAxis = maxAxis; maxAxis = tmp; }
        int cx = a.getX(), cy = a.getY(), cz = a.getZ();

        for (int main = minAxis; main <= maxAxis; main++) {
            boolean cap = (main == minAxis || main == maxAxis);

            for (int u = -radius; u <= radius; u++) {
                for (int v = -radius; v <= radius; v++) {
                    int dist2 = u * u + v * v;
                    if (dist2 > r2) continue;

                    boolean shell = dist2 >= shellR2 || cap;
                    SlotType slot = cap ? SlotType.FACE : (shell ? SlotType.EDGE : SlotType.VOLUME);

                    BlockPos pos = switch (axis) {
                        case X -> new BlockPos(main, cy + u, cz + v);
                        case Y -> new BlockPos(cx + u, main, cz + v);
                        case Z -> new BlockPos(cx + u, cy + v, main);
                    };
                    result.add(new ShapeBlock(pos, slot));
                }
            }
        }
    }

    private static int axisCoord(BlockPos pos, Direction.Axis axis) {
        return switch (axis) {
            case X -> pos.getX();
            case Y -> pos.getY();
            case Z -> pos.getZ();
        };
    }
}
