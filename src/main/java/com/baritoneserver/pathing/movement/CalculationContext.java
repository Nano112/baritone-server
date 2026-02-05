package com.baritoneserver.pathing.movement;

import com.baritoneserver.api.IEntityContext;
import com.baritoneserver.api.pathing.movement.ActionCosts;
import com.baritoneserver.pathing.precompute.PrecomputedData;
import com.baritoneserver.pathing.world.ServerBlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import static com.baritoneserver.api.pathing.movement.ActionCosts.COST_INF;

/**
 * Server-side calculation context for mob pathfinding.
 * Replaces Baritone's CalculationContext which depends on client-side player data.
 */
public class CalculationContext {

    public final IEntityContext entityContext;
    public final ServerLevel world;
    public final ServerBlockStateInterface bsi;
    public final PrecomputedData precomputedData;

    // Mob capabilities
    public final double movementSpeed;
    public final boolean canSprint;
    public final boolean canSwim;

    // Configuration — mobs can't break/place blocks initially
    public final boolean allowBreak = false;
    public final boolean allowPlace = false;
    public final boolean hasThrowaway = false;
    public final boolean hasWaterBucket = false;
    public final boolean allowParkour;
    public final boolean allowParkourPlace = false;
    public final boolean allowParkourAscend;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    public final boolean allowJumpAtBuildLimit = false;
    public final boolean assumeWalkOnWater = false;
    public final boolean allowWalkOnMagmaBlocks = false;
    public final boolean allowFallIntoLava = false;
    public final int frostWalker = 0;

    public final int minFallHeight = 3;
    public int maxFallHeightNoWater = 4;
    public final int maxFallHeightBucket = 0;

    public final double waterWalkSpeed;
    public final double breakBlockAdditionalCost = 0;
    public final double walkOnWaterOnePenalty = 0;
    public double jumpPenalty = 2.0;
    public double backtrackCostFavoringCoefficient = 0.5;

    public CalculationContext(IEntityContext entityContext) {
        this.entityContext = entityContext;
        this.world = entityContext.world();
        this.bsi = new ServerBlockStateInterface(world);
        this.precomputedData = new PrecomputedData();

        Mob mob = entityContext.entity();

        this.movementSpeed = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
        this.canSprint = true;
        this.canSwim = true;

        this.allowParkour = true;
        this.allowParkourAscend = true;
        this.allowDiagonalDescend = true;
        this.allowDiagonalAscend = true;
        this.allowDownward = true;

        this.waterWalkSpeed = ActionCosts.WALK_ONE_IN_WATER_COST;
    }

    public BlockState get(int x, int y, int z) {
        return bsi.get0(x, y, z);
    }

    public boolean isLoaded(int x, int z) {
        return bsi.isLoaded(x, z);
    }

    public BlockState get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        return COST_INF;
    }

    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        return COST_INF;
    }

    public double placeBucketCost() {
        return COST_INF;
    }

    public boolean isPossiblyProtected(int x, int y, int z) {
        return false;
    }

    public int worldTop() {
        return world.getMinY() + world.getHeight();
    }

    public int worldBottom() {
        return world.getMinY();
    }
}
