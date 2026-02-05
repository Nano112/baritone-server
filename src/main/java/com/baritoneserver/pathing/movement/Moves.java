package com.baritoneserver.pathing.movement;

import net.minecraft.core.Direction;

/**
 * All 18 possible movement directions, ported from Baritone.
 * Each movement delegates to MovementCosts for cost calculation.
 */
public enum Moves {

    DOWNWARD(0, -1, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.downward(ctx, x, y, z);
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.pillar(ctx, x, y, z);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.traverse(ctx, x, y, z, x, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.traverse(ctx, x, y, z, x, z + 1);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.traverse(ctx, x, y, z, x + 1, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.traverse(ctx, x, y, z, x - 1, z);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.ascend(ctx, x, y, z, x, z - 1);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.ascend(ctx, x, y, z, x, z + 1);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.ascend(ctx, x, y, z, x + 1, z);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public double cost(CalculationContext ctx, int x, int y, int z) {
            return MovementCosts.ascend(ctx, x, y, z, x - 1, z);
        }
    },

    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.descend(ctx, x, y, z, x + 1, z, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.descend(ctx, x, y, z, x - 1, z, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.descend(ctx, x, y, z, x, z - 1, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.descend(ctx, x, y, z, x, z + 1, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.diagonal(ctx, x, y, z, x + 1, z - 1, result);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.diagonal(ctx, x, y, z, x - 1, z - 1, result);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.diagonal(ctx, x, y, z, x + 1, z + 1, result);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.diagonal(ctx, x, y, z, x - 1, z + 1, result);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.parkour(ctx, x, y, z, Direction.NORTH, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.parkour(ctx, x, y, z, Direction.SOUTH, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.parkour(ctx, x, y, z, Direction.EAST, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
            MovementCosts.parkour(ctx, x, y, z, Direction.WEST, result);
        }
    };

    public final boolean dynamicXZ;
    public final boolean dynamicY;
    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    public void apply(CalculationContext ctx, int x, int y, int z, MutableMoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("Movements with dynamic offset must override `apply`");
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(ctx, x, y, z);
    }

    public double cost(CalculationContext ctx, int x, int y, int z) {
        throw new UnsupportedOperationException("Movements must override `cost` or `apply`");
    }
}
