package com.baritoneserver.pathing.precompute;

import com.baritoneserver.pathing.movement.MovementHelper;
import com.baritoneserver.pathing.world.ServerBlockStateInterface;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Delegates block checks to MovementHelper.
 * In Baritone this caches Ternary results; we keep it simple for now.
 */
public class PrecomputedData {

    public boolean canWalkThrough(ServerBlockStateInterface bsi, int x, int y, int z, BlockState state) {
        return MovementHelper.canWalkThrough(bsi, x, y, z, state);
    }

    public boolean canWalkOn(ServerBlockStateInterface bsi, int x, int y, int z, BlockState state) {
        return MovementHelper.canWalkOn(bsi, x, y, z, state);
    }

    public boolean fullyPassable(ServerBlockStateInterface bsi, int x, int y, int z, BlockState state) {
        Ternary t = MovementHelper.fullyPassableBlockState(state);
        if (t == Ternary.YES) return true;
        if (t == Ternary.NO) return false;
        return MovementHelper.fullyPassablePosition(bsi, x, y, z, state);
    }
}
