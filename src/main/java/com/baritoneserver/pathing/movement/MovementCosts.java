package com.baritoneserver.pathing.movement;

import com.baritoneserver.api.pathing.movement.ActionCosts;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

import static com.baritoneserver.api.pathing.movement.ActionCosts.*;

/**
 * Simplified movement cost calculations for server-side mob pathfinding.
 * These replace the individual Movement*.cost() methods from Baritone.
 * Checks are simplified: mobs can't break/place blocks.
 */
public final class MovementCosts {

    private MovementCosts() {}

    // ==================== Block Checks ====================

    public static boolean canWalkThrough(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.get(x, y, z);
        return canWalkThrough(state);
    }

    public static boolean canWalkThrough(BlockState state) {
        if (state.isAir()) return true;
        if (state.getFluidState().is(Fluids.WATER)) return true;
        if (state.getFluidState().is(Fluids.FLOWING_WATER)) return true;
        // Allow walking through non-solid blocks like grass, flowers, etc.
        if (!state.isSolid()) return true;
        return false;
    }

    public static boolean canWalkOn(CalculationContext ctx, int x, int y, int z) {
        BlockState state = ctx.get(x, y, z);
        return canWalkOn(state);
    }

    public static boolean canWalkOn(BlockState state) {
        if (state.isAir()) return false;
        if (state.isSolid()) {
            // Can't walk on fences/walls (too tall)
            if (state.getBlock() instanceof FenceBlock) return false;
            if (state.getBlock() instanceof FenceGateBlock) return false;
            if (state.getBlock() instanceof WallBlock) return false;
            return true;
        }
        // Can walk on water surface if it's source water
        return false;
    }

    public static boolean isWater(BlockState state) {
        return state.getFluidState().is(Fluids.WATER) || state.getFluidState().is(Fluids.FLOWING_WATER);
    }

    public static boolean isLava(BlockState state) {
        return state.getFluidState().is(Fluids.LAVA) || state.getFluidState().is(Fluids.FLOWING_LAVA);
    }

    public static boolean avoidWalkingInto(BlockState state) {
        if (isLava(state)) return true;
        if (state.is(Blocks.CACTUS)) return true;
        if (state.is(Blocks.FIRE)) return true;
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return true;
        return false;
    }

    // ==================== Movement Costs ====================

    /** Walk horizontally one block (TRAVERSE) */
    public static double traverse(CalculationContext ctx, int x, int y, int z, int destX, int destZ) {
        // Check: two blocks above destination must be passable (head + body clearance)
        if (!canWalkThrough(ctx, destX, y, destZ)) return COST_INF;
        if (!canWalkThrough(ctx, destX, y + 1, destZ)) return COST_INF;

        // Check: block below destination must be walkable
        if (!canWalkOn(ctx, destX, y - 1, destZ)) return COST_INF;

        // Check for dangerous blocks
        BlockState destFeet = ctx.get(destX, y, destZ);
        if (avoidWalkingInto(destFeet)) return COST_INF;

        // Water cost
        if (isWater(destFeet)) return WALK_ONE_IN_WATER_COST;

        return WALK_ONE_BLOCK_COST;
    }

    /** Walk up one block (ASCEND) */
    public static double ascend(CalculationContext ctx, int x, int y, int z, int destX, int destZ) {
        int destY = y + 1;

        // Check: need 3 blocks of clearance at destination (feet, body, head above jump)
        if (!canWalkThrough(ctx, destX, destY, destZ)) return COST_INF;
        if (!canWalkThrough(ctx, destX, destY + 1, destZ)) return COST_INF;
        // Need clearance above current position for jump
        if (!canWalkThrough(ctx, x, y + 2, z)) return COST_INF;

        // Block at destination level must be walkable
        if (!canWalkOn(ctx, destX, destY - 1, destZ)) return COST_INF;

        return WALK_ONE_BLOCK_COST + JUMP_ONE_BLOCK_COST;
    }

    /** Walk down one block or fall (DESCEND). Writes to result for dynamic y. */
    public static void descend(CalculationContext ctx, int x, int y, int z, int destX, int destZ, MutableMoveResult result) {
        // Check: horizontal clearance at dest
        if (!canWalkThrough(ctx, destX, y, destZ)) {
            result.cost = COST_INF;
            return;
        }
        if (!canWalkThrough(ctx, destX, y + 1, destZ)) {
            result.cost = COST_INF;
            return;
        }

        // Look downward from y-1 to find the landing
        BlockState below = ctx.get(destX, y - 1, destZ);

        if (canWalkOn(below)) {
            // Simple step down
            if (!canWalkThrough(ctx, destX, y - 1, destZ) || canWalkOn(below)) {
                result.x = destX;
                result.y = y - 1;
                result.z = destZ;
                result.cost = WALK_ONE_BLOCK_COST + Math.max(FALL_N_BLOCKS_COST[1] - FALL_N_BLOCKS_COST[0], 0);
                return;
            }
        }

        // Check for fall
        if (!canWalkThrough(ctx, destX, y - 1, destZ)) {
            // Can't go through, solid ground at y-1
            result.cost = COST_INF;
            return;
        }

        // Fall: scan downward
        for (int fallDist = 2; fallDist <= ctx.maxFallHeightNoWater; fallDist++) {
            int landY = y - fallDist;
            if (landY < ctx.worldBottom()) {
                result.cost = COST_INF;
                return;
            }
            BlockState landing = ctx.get(destX, landY, destZ);
            if (canWalkOn(landing)) {
                // Check clearance at landing
                boolean clear = true;
                for (int fy = landY + 1; fy < y; fy++) {
                    if (!canWalkThrough(ctx, destX, fy, destZ)) {
                        clear = false;
                        break;
                    }
                }
                if (clear) {
                    result.x = destX;
                    result.y = landY + 1;
                    result.z = destZ;
                    result.cost = WALK_OFF_BLOCK_COST + FALL_N_BLOCKS_COST[fallDist] + CENTER_AFTER_FALL_COST;
                    return;
                }
            }
            if (!canWalkThrough(ctx, destX, landY, destZ)) {
                break; // Hit solid, no landing
            }
        }

        result.cost = COST_INF;
    }

    /** Move straight down one block (DOWNWARD) */
    public static double downward(CalculationContext ctx, int x, int y, int z) {
        if (!ctx.allowDownward) return COST_INF;

        BlockState below = ctx.get(x, y - 1, z);
        if (!canWalkThrough(below)) return COST_INF;
        if (!canWalkOn(ctx, x, y - 2, z)) return COST_INF;
        if (avoidWalkingInto(below)) return COST_INF;

        return FALL_N_BLOCKS_COST[1];
    }

    /** Move straight up one block by jumping (PILLAR) */
    public static double pillar(CalculationContext ctx, int x, int y, int z) {
        // Mobs can't pillar (requires block placement)
        return COST_INF;
    }

    /** Walk diagonally. Writes to result for dynamic y. */
    public static void diagonal(CalculationContext ctx, int x, int y, int z, int destX, int destZ, MutableMoveResult result) {
        // Diagonal needs clearance in both axis-aligned directions
        int dx = destX - x; // +1 or -1
        int dz = destZ - z; // +1 or -1

        // Check body clearance at destination
        if (!canWalkThrough(ctx, destX, y, destZ)) { result.cost = COST_INF; return; }
        if (!canWalkThrough(ctx, destX, y + 1, destZ)) { result.cost = COST_INF; return; }

        // Check clearance in both cardinal intermediary positions (to avoid cutting corners)
        if (!canWalkThrough(ctx, x + dx, y, z) || !canWalkThrough(ctx, x + dx, y + 1, z)) {
            if (!canWalkThrough(ctx, x, y, z + dz) || !canWalkThrough(ctx, x, y + 1, z + dz)) {
                result.cost = COST_INF;
                return;
            }
        }

        // Check floor at destination
        if (canWalkOn(ctx, destX, y - 1, destZ)) {
            // Flat diagonal
            BlockState destFeet = ctx.get(destX, y, destZ);
            if (avoidWalkingInto(destFeet)) { result.cost = COST_INF; return; }
            result.x = destX;
            result.y = y;
            result.z = destZ;
            result.cost = WALK_ONE_BLOCK_COST * Math.sqrt(2);
            return;
        }

        // Diagonal descend
        if (ctx.allowDiagonalDescend && canWalkThrough(ctx, destX, y - 1, destZ) && canWalkOn(ctx, destX, y - 2, destZ)) {
            result.x = destX;
            result.y = y - 1;
            result.z = destZ;
            result.cost = WALK_ONE_BLOCK_COST * Math.sqrt(2) + FALL_N_BLOCKS_COST[1];
            return;
        }

        // Diagonal ascend
        if (ctx.allowDiagonalAscend
                && canWalkOn(ctx, destX, y, destZ)
                && canWalkThrough(ctx, destX, y + 1, destZ)
                && canWalkThrough(ctx, destX, y + 2, destZ)
                && canWalkThrough(ctx, x, y + 2, z)) {
            result.x = destX;
            result.y = y + 1;
            result.z = destZ;
            result.cost = WALK_ONE_BLOCK_COST * Math.sqrt(2) + JUMP_ONE_BLOCK_COST;
            return;
        }

        result.cost = COST_INF;
    }

    /**
     * Parkour jump. Faithful port of Baritone's MovementParkour.cost().
     * Handles both flat gap jumps (2-4 blocks) and parkour ascend (gap + 1 up).
     * Writes to result for dynamic xz and y.
     */
    public static void parkour(CalculationContext ctx, int x, int y, int z, Direction dir, MutableMoveResult result) {
        if (!ctx.allowParkour) {
            result.cost = COST_INF;
            return;
        }

        int dx = dir.getStepX();
        int dz = dir.getStepZ();

        // Adjacent block at feet level must be passable (there IS a gap)
        if (!canWalkThrough(ctx, x + dx, y, z + dz)) {
            result.cost = COST_INF;
            return;
        }
        // Must not be able to walk on adjacent (otherwise it's a traverse, not parkour)
        BlockState adjBelow = ctx.get(x + dx, y - 1, z + dz);
        if (canWalkOn(adjBelow)) {
            result.cost = COST_INF;
            return;
        }
        // Avoid walking into dangerous adjacent blocks (unless water)
        if (avoidWalkingInto(adjBelow) && !isWater(adjBelow)) {
            result.cost = COST_INF;
            return;
        }
        // Adjacent head+body clearance
        if (!canWalkThrough(ctx, x + dx, y + 1, z + dz)) { result.cost = COST_INF; return; }
        if (!canWalkThrough(ctx, x + dx, y + 2, z + dz)) { result.cost = COST_INF; return; }
        // Jump clearance above current position
        if (!canWalkThrough(ctx, x, y + 2, z)) { result.cost = COST_INF; return; }

        // Can't parkour off certain blocks (vines, ladders) or from liquid
        BlockState standingOn = ctx.get(x, y - 1, z);
        if (isLava(standingOn)) { result.cost = COST_INF; return; }
        BlockState feetBlock = ctx.get(x, y, z);
        if (isWater(feetBlock)) { result.cost = COST_INF; return; }

        int maxJump = ctx.canSprint ? 4 : 3;

        for (int dist = 2; dist <= maxJump; dist++) {
            int landX = x + dx * dist;
            int landZ = z + dz * dist;

            // Check body+head clearance at landing position
            if (!canWalkThrough(ctx, landX, y + 1, landZ)) break;
            if (!canWalkThrough(ctx, landX, y + 2, landZ)) break;

            // Check the block at feet level of the landing
            BlockState destFeet = ctx.get(landX, y, landZ);
            if (!canWalkThrough(destFeet)) {
                // Block at feet level is solid — PARKOUR ASCEND candidate
                // Land ON TOP of this block at y+1
                if (dist <= 3 && ctx.allowParkourAscend && ctx.canSprint
                        && canWalkOn(destFeet)
                        && checkOvershootSafety(ctx, landX + dx, y + 1, landZ + dz)) {
                    result.x = landX;
                    result.y = y + 1;
                    result.z = landZ;
                    result.cost = dist * SPRINT_ONE_BLOCK_COST + ctx.jumpPenalty;
                    return;
                }
                break; // solid block at feet, can't check further distances
            }

            // Feet level is passable — check for flat landing below
            BlockState landingOn = ctx.get(landX, y - 1, landZ);
            if (canWalkOn(landingOn)) {
                if (checkOvershootSafety(ctx, landX + dx, y, landZ + dz)) {
                    result.x = landX;
                    result.y = y;
                    result.z = landZ;
                    result.cost = costFromJumpDistance(dist);
                    return;
                }
                break; // found landing but overshoot unsafe
            }

            // No landing here — check y+3 clearance for jump arc to continue to next distance
            if (!canWalkThrough(ctx, landX, y + 3, landZ)) break;
        }

        result.cost = COST_INF;
    }

    /** Baritone's parkour cost varies by distance: 2-3 blocks use walk cost, 4 uses sprint cost */
    private static double costFromJumpDistance(int dist) {
        return switch (dist) {
            case 2 -> WALK_ONE_BLOCK_COST * 2;
            case 3 -> WALK_ONE_BLOCK_COST * 3;
            case 4 -> SPRINT_ONE_BLOCK_COST * 4;
            default -> COST_INF;
        };
    }

    /** Check that the block beyond the landing isn't dangerous (lava, cactus, fire) */
    private static boolean checkOvershootSafety(CalculationContext ctx, int x, int y, int z) {
        BlockState beyond = ctx.get(x, y, z);
        return !avoidWalkingInto(beyond);
    }
}
