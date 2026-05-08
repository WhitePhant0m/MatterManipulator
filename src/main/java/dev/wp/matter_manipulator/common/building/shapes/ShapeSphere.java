package dev.wp.matter_manipulator.common.building.shapes;

import dev.wp.matter_manipulator.common.items.manipulator.SlotType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class ShapeSphere {

    /**
     * Ellipsoid shell where center=a and radius=distance(a,b) per axis.
     * Only the outer shell (1 block thick) is included.
     */
    public static List<ShapeBlock> generate(BlockPos center, BlockPos radiusPoint) {
        var result = new ArrayList<ShapeBlock>();

        double rx = Math.abs(radiusPoint.getX() - center.getX()) + 0.5;
        double ry = Math.abs(radiusPoint.getY() - center.getY()) + 0.5;
        double rz = Math.abs(radiusPoint.getZ() - center.getZ()) + 0.5;

        if (rx < 0.5) rx = 0.5;
        if (ry < 0.5) ry = 0.5;
        if (rz < 0.5) rz = 0.5;

        int minX = (int) Math.floor(center.getX() - rx);
        int maxX = (int) Math.ceil(center.getX() + rx);
        int minY = (int) Math.floor(center.getY() - ry);
        int maxY = (int) Math.ceil(center.getY() + ry);
        int minZ = (int) Math.floor(center.getZ() - rz);
        int maxZ = (int) Math.ceil(center.getZ() + rz);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = (x - center.getX()) / rx;
                    double dy = (y - center.getY()) / ry;
                    double dz = (z - center.getZ()) / rz;
                    double dist = dx * dx + dy * dy + dz * dz;

                    // Only the surface shell: between (r-1)^2 and r^2 when normalized
                    double innerRx = (rx - 1) / rx, innerRy = (ry - 1) / ry, innerRz = (rz - 1) / rz;
                    double innerDx = (x - center.getX()) / Math.max(rx - 1, 0.001);
                    double innerDy = (y - center.getY()) / Math.max(ry - 1, 0.001);
                    double innerDz = (z - center.getZ()) / Math.max(rz - 1, 0.001);
                    double innerDist = innerDx * innerDx + innerDy * innerDy + innerDz * innerDz;

                    if (dist <= 1.0 && (rx <= 1 || ry <= 1 || rz <= 1 || innerDist >= 1.0)) {
                        result.add(new ShapeBlock(new BlockPos(x, y, z), SlotType.FACE));
                    }
                }
            }
        }

        return result;
    }
}
