/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.baritoneserver.api.pathing.goals;

import net.minecraft.core.BlockPos;

import java.util.Arrays;

public class GoalRunAway implements Goal {

    private final BlockPos[] from;
    private final double distanceSq;
    private final Integer maintainY;

    public GoalRunAway(double distance, BlockPos... from) {
        this(distance, null, from);
    }

    public GoalRunAway(double distance, Integer maintainY, BlockPos... from) {
        this.from = from;
        this.distanceSq = distance * distance;
        this.maintainY = maintainY;
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (maintainY != null && y < maintainY - 2) {
            return false;
        }
        for (BlockPos pos : from) {
            int diffX = x - pos.getX();
            int diffZ = z - pos.getZ();
            double distSq = diffX * diffX + diffZ * diffZ;
            if (distSq < distanceSq) {
                return false;
            }
        }
        return true;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double min = Double.MAX_VALUE;
        for (BlockPos pos : from) {
            double diffX = x - pos.getX();
            double diffZ = z - pos.getZ();
            double distSq = diffX * diffX + diffZ * diffZ;
            min = Math.min(min, distSq);
        }
        min = Math.sqrt(min);
        if (min < Math.sqrt(distanceSq)) {
            return (Math.sqrt(distanceSq) - min) * GoalXZ.COST_HEURISTIC;
        }
        double yPenalty = 0;
        if (maintainY != null) {
            yPenalty = GoalYLevel.calculate(maintainY, y);
        }
        return -min + yPenalty;
    }

    @Override
    public String toString() {
        return String.format("GoalRunAway{from=%s, dist=%.1f}", Arrays.toString(from), Math.sqrt(distanceSq));
    }
}
